package tv.nextv.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.net.AuthScope
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.ScopedCredentialsAuthenticator

/**
 * Paramètres de lecture d'une chaîne.
 *
 * @property credentialsOrigin URL de la playlist : ses identifiants ne sont présentés qu'à ce serveur.
 */
data class PlaybackRequest(
    val stream: StreamDescriptor,
    val userAgent: String?,
    val credentials: Credentials?,
    val credentialsOrigin: String?,
)

/**
 * Crée les lecteurs Media3.
 *
 * Les requêtes HTTP(S) des flux — manifestes et segments HLS/DASH compris — passent par le client
 * OkHttp « flux » : proxy (si activé pour les flux), délais, redirections et en-têtes de la playlist
 * s'y appliquent. RTSP et UDP sont lus directement par Media3, sans proxy.
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val clients: HttpClientProvider,
) {

    /** Mesure du débit réel des téléchargements du flux (voir [create]). */
    fun newBandwidthMeter(): DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

    /**
     * @param preferredDecoders décodeur choisi par type de flux (réglages), essayé en premier.
     * @param adVolume option « baisser le son des pubs », appliquée au son décodé.
     */
    fun create(
        request: PlaybackRequest,
        bandwidthMeter: DefaultBandwidthMeter? = null,
        preferredDecoders: Map<String, String> = emptyMap(),
        adVolume: AdVolumeControl? = null,
    ): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(httpClient(request))
            .setUserAgent(userAgent(request))
            .setDefaultRequestProperties(request.stream.headers - "User-Agent")
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        // Repli automatique sur un autre décodeur si le premier ne démarre pas (box aux décodeurs capricieux).
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(listOfNotNull<AudioProcessor>(adVolume?.let(::AdVolumeAudioProcessor)).toTypedArray())
                    .build()
        }
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(VideoDecoders.selector(preferredDecoders))
        val builder = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl())
        if (bandwidthMeter != null) builder.setBandwidthMeter(bandwidthMeter)
        return builder.build()
    }

    /** Requête du direct pour l'enregistrement du différé : mêmes client, en-têtes et identifiants que la lecture. */
    fun liveCall(request: PlaybackRequest): Call {
        val builder = Request.Builder().url(request.stream.url).header("User-Agent", userAgent(request))
        (request.stream.headers - "User-Agent").forEach { (name, value) -> builder.header(name, value) }
        return httpClient(request).newCall(builder.build())
    }

    /** Source du lecteur pour l'enregistrement du différé. */
    fun timeshiftSource(buffer: TimeshiftBuffer): MediaSource =
        ProgressiveMediaSource.Factory(DataSource.Factory { TimeshiftDataSource(buffer) })
            .createMediaSource(MediaItem.fromUri("timeshift://live"))

    private fun httpClient(request: PlaybackRequest): OkHttpClient {
        val baseClient = clients.client(RequestPurpose.STREAM)
        val origin = request.credentialsOrigin?.toHttpUrlOrNull()
        return if (request.credentials != null && origin != null) {
            baseClient.newBuilder()
                .authenticator(ScopedCredentialsAuthenticator(AuthScope.forUrl(origin, request.credentials)))
                .build()
        } else {
            baseClient
        }
    }

    /**
     * Démarrage et reprise plus rapides que par défaut (2,5 s et 5 s de mémoire tampon) : une chaîne,
     * ou la version de repli après un blocage, se lance dès 1,5 s de flux reçu.
     */
    private fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            START_BUFFER_MILLIS,
            RESUME_BUFFER_MILLIS,
        )
        .build()

    private fun userAgent(request: PlaybackRequest): String =
        request.stream.headers["User-Agent"]
            ?: request.userAgent?.takeIf { it.isNotBlank() }
            ?: clients.networkSettings.defaultUserAgent

    /** @param forceHls traiter le flux comme du HLS (seconde tentative pour une URL sans extension). */
    fun mediaItem(stream: StreamDescriptor, forceHls: Boolean = false): MediaItem {
        val mimeType = when {
            forceHls -> MimeTypes.APPLICATION_M3U8
            else -> when (stream.format) {
                StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
                StreamFormat.DASH -> MimeTypes.APPLICATION_MPD
                StreamFormat.SMOOTH_STREAMING -> MimeTypes.APPLICATION_SS
                StreamFormat.RTSP -> MimeTypes.APPLICATION_RTSP
                StreamFormat.PROGRESSIVE, StreamFormat.UDP, StreamFormat.UNKNOWN -> null
            }
        }
        return MediaItem.Builder()
            .setUri(stream.url)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()
    }
}

private const val START_BUFFER_MILLIS = 1_500
private const val RESUME_BUFFER_MILLIS = 3_000
