package tv.nextv.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** @param preferredDecoders décodeur choisi par type de flux (réglages), essayé en premier. */
    fun create(
        request: PlaybackRequest,
        bandwidthMeter: DefaultBandwidthMeter? = null,
        preferredDecoders: Map<String, String> = emptyMap(),
    ): ExoPlayer {
        val baseClient = clients.client(RequestPurpose.STREAM)
        val origin = request.credentialsOrigin?.toHttpUrlOrNull()
        val client = if (request.credentials != null && origin != null) {
            baseClient.newBuilder()
                .authenticator(ScopedCredentialsAuthenticator(AuthScope.forUrl(origin, request.credentials)))
                .build()
        } else {
            baseClient
        }

        val headers = request.stream.headers
        val userAgent = headers["User-Agent"]
            ?: request.userAgent?.takeIf { it.isNotBlank() }
            ?: clients.networkSettings.defaultUserAgent
        val httpFactory = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers - "User-Agent")
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        // Repli automatique sur un autre décodeur si le premier ne démarre pas (box aux décodeurs capricieux).
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(VideoDecoders.selector(preferredDecoders))
        val builder = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        if (bandwidthMeter != null) builder.setBandwidthMeter(bandwidthMeter)
        return builder.build()
    }

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
