package tv.nextv.app.player

import android.content.Context
import android.os.Handler
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
 * Crée les lecteurs Media3 et les sources de leurs flux.
 *
 * Un lecteur sert à plusieurs flux : chacun lui est donné par [mediaSource], avec sa propre source
 * réseau. Les requêtes HTTP(S) des flux — manifestes et segments HLS/DASH compris — passent par le
 * client OkHttp « flux » : proxy (si activé pour les flux), délais, redirections, User-Agent, en-têtes
 * et identifiants de la playlist du flux s'y appliquent. RTSP et UDP sont lus directement par Media3,
 * sans proxy.
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val clients: HttpClientProvider,
) {

    /** Mesure du débit réel des téléchargements des flux (voir [create]). */
    fun newBandwidthMeter(): DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

    /**
     * Lecteur sans flux : chaque flux lui est donné par `setMediaSource(mediaSource(…))`.
     *
     * @param preferredDecoders décodeur choisi par type de flux (réglages), essayé en premier.
     * @param adVolume option « baisser le son des pubs », appliquée au son décodé.
     * @param preview aperçu de l'accueil : petite mémoire tampon (il cède vite la place au plein écran).
     * @param passthrough son Dolby / DTS envoyé tel quel à l'ampli.
     */
    fun create(
        bandwidthMeter: DefaultBandwidthMeter? = null,
        preferredDecoders: Map<String, String> = emptyMap(),
        adVolume: AdVolumeControl? = null,
        preview: Boolean = false,
        passthrough: Boolean = false,
        /** Décodeurs vidéo qui ont échoué sur le flux en cours : écartés. */
        excludedDecoders: Set<String> = emptySet(),
        /**
         * Son décodé par l'appli d'abord (MPEG audio, puis FFmpeg), avant les décodeurs de l'appareil : après
         * une erreur de décodage du son sur le flux en cours.
         */
        softwareAudio: Boolean = false,
    ): ExoPlayer {
        // Repli automatique sur un autre décodeur si le premier ne démarre pas (box aux décodeurs capricieux).
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                (if (passthrough) DefaultAudioSink.Builder(context) else DefaultAudioSink.Builder().setAudioCapabilities(decodedAudio(context)))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(listOfNotNull<AudioProcessor>(adVolume?.let(::AdVolumeAudioProcessor)).toTypedArray())
                    .build()

            // MP2 (couches I et II du MPEG audio) : décodé dans l'appli, en dernier recours (ni l'appareil ni FFmpeg
            // ne le lisent) ; en premier après une erreur de décodage du son.
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                val mpeg = MpegAudioRenderer(eventHandler, eventListener, audioSink)
                if (softwareAudio) out.add(0, mpeg) else out.add(mpeg)
            }
        }
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(VideoDecoders.selector(preferredDecoders, excludedDecoders))
            // Son : décodeurs de l'appareil d'abord, FFmpeg pour les formats qu'il ne sait pas lire (l'inverse
            // après une erreur de décodage du son).
            .setExtensionRendererMode(if (softwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val builder = ExoPlayer.Builder(context, renderers)
            // Flux donné sans sa source (setMediaItem) : client « flux » quand même (proxy), sans identifiants.
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(clients.client(RequestPurpose.STREAM)))))
            .setLoadControl(if (preview) previewLoadControl() else loadControl())
        if (bandwidthMeter != null) builder.setBandwidthMeter(bandwidthMeter)
        if (!preview) {
            // Lecture en plein écran : le son est demandé au système (une autre application qui joue se tait,
            // et la lecture s'interrompt le temps d'une annonce vocale ou d'un appel), et elle se met en pause
            // quand la sortie son disparaît (casque débranché). L'aperçu de l'accueil, lui, ne prend pas le son.
            builder.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            builder.setHandleAudioBecomingNoisy(true)
        }
        return builder.build()
    }

    /** Formats de son compressés (Dolby, DTS) et leur type, pour savoir si l'appareil les décode. */
    private val encodedAudio = mapOf(
        C.ENCODING_AC3 to MimeTypes.AUDIO_AC3,
        C.ENCODING_E_AC3 to MimeTypes.AUDIO_E_AC3,
        C.ENCODING_E_AC3_JOC to MimeTypes.AUDIO_E_AC3_JOC,
        C.ENCODING_AC4 to MimeTypes.AUDIO_AC4,
        C.ENCODING_DTS to MimeTypes.AUDIO_DTS,
        C.ENCODING_DTS_HD to MimeTypes.AUDIO_DTS_HD,
        C.ENCODING_DOLBY_TRUEHD to MimeTypes.AUDIO_TRUEHD,
    )

    /**
     * Sortie du son décodé par l'appareil (ou par FFmpeg) : le Dolby (AC3, E-AC3) et le DTS ne sont
     * envoyés tels quels à l'écran que si aucun des deux ne sait les décoder. Envoyés à un écran ou
     * un moniteur qui annonce à tort savoir les lire (Fire TV), ils donneraient une image sans son.
     */
    private fun decodedAudio(context: Context): AudioCapabilities {
        val device = AudioCapabilities.getCapabilities(context)
        val encoded = encodedAudio.filter { (encoding, mimeType) -> device.supportsEncoding(encoding) && !hasDecoder(mimeType) }.keys
        return AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT) + encoded.toIntArray(), device.maxChannelCount)
    }

    /** L'appareil, ou à défaut FFmpeg, sait décoder ce format de son. */
    private fun hasDecoder(mimeType: String): Boolean = ffmpegDecodes(mimeType) || try {
        MediaCodecUtil.getDecoderInfos(mimeType, false, false).isNotEmpty()
    } catch (e: MediaCodecUtil.DecoderQueryException) {
        false
    }

    private fun ffmpegDecodes(mimeType: String): Boolean = try {
        FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType)
    } catch (e: UnsatisfiedLinkError) {
        // Bibliothèque native absente pour ce processeur : décodeurs de l'appareil seulement.
        false
    }

    /**
     * L'appareil affiche et décode la 4K : sinon (écran ou moniteur Full HD, petite box), une version
     * 4K serait réduite à l'affichage et saccadée, faute d'un décodeur assez rapide.
     */
    val supportsUhd: Boolean by lazy {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val screen = display?.supportedModes?.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        val decoder = listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264).any { mimeType ->
            try {
                MediaCodecUtil.getDecoderInfos(mimeType, false, false).any { info ->
                    info.hardwareAccelerated && info.capabilities?.videoCapabilities?.areSizeAndRateSupported(3840, 2160, 50.0) == true
                }
            } catch (e: MediaCodecUtil.DecoderQueryException) {
                false
            }
        }
        screen >= 3840 && decoder
    }

    /**
     * Source du flux de [request] : connexion propre à ce flux (client, User-Agent, en-têtes et
     * identifiants de sa playlist).
     *
     * @param forceHls traiter le flux comme du HLS (seconde tentative pour une URL sans extension).
     */
    fun mediaSource(
        request: PlaybackRequest,
        forceHls: Boolean = false,
        transferListener: TransferListener? = null,
        /** Direct continu : ce qui est reçu y est gardé, pour revenir en arrière. */
        recording: LiveRecording? = null,
    ): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(httpClient(request))
            .setUserAgent(userAgent(request))
            .setDefaultRequestProperties(request.stream.headers - "User-Agent")
            .setTransferListener(transferListener)
        val networkFactory: DataSource.Factory = if (recording != null) {
            DataSource.Factory { LiveRecordingDataSource(httpFactory.createDataSource(), recording) }
        } else {
            httpFactory
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, networkFactory)
        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem(request.stream, forceHls))
    }

    /** Requête du direct pour l'enregistrement du différé : mêmes client, en-têtes et identifiants que la lecture. */
    fun liveCall(request: PlaybackRequest): Call {
        val builder = Request.Builder().url(request.stream.url).header("User-Agent", userAgent(request))
        (request.stream.headers - "User-Agent").forEach { (name, value) -> builder.header(name, value) }
        return httpClient(request).newCall(builder.build())
    }

    /** Source du lecteur pour l'enregistrement du différé. */
    fun timeshiftSource(buffer: TimeshiftBuffer, startOffset: Long = 0): MediaSource =
        ProgressiveMediaSource.Factory(DataSource.Factory { TimeshiftDataSource(buffer, startOffset) })
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
        // Mémoire des petites box (Fire TV) : au plus ~48 Mo de flux d'avance, même en très haut débit
        // (sinon jusqu'à 130 Mo pour 50 s de 4K, de quoi faire fermer l'application faute de mémoire).
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .build()

    /** Aperçu : quelques secondes d'avance seulement. */
    private fun previewLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(PREVIEW_MIN_BUFFER_MILLIS, PREVIEW_MAX_BUFFER_MILLIS, START_BUFFER_MILLIS, RESUME_BUFFER_MILLIS)
        .setTargetBufferBytes(PREVIEW_BUFFER_BYTES)
        .build()

    private fun userAgent(request: PlaybackRequest): String =
        request.stream.headers["User-Agent"]
            ?: request.userAgent?.takeIf { it.isNotBlank() }
            ?: clients.networkSettings.defaultUserAgent

    private fun mediaItem(stream: StreamDescriptor, forceHls: Boolean = false): MediaItem {
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
private const val MAX_BUFFER_BYTES = 48 * 1024 * 1024
private const val PREVIEW_MIN_BUFFER_MILLIS = 4_000
private const val PREVIEW_MAX_BUFFER_MILLIS = 8_000
private const val PREVIEW_BUFFER_BYTES = 12 * 1024 * 1024
