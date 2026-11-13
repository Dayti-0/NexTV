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
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.common.Timeline
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
     * @param preview aperçu de l'accueil : petite mémoire tampon, sans prendre le son au système.
     * @param passthrough son Dolby / DTS envoyé tel quel à l'ampli.
     * @param liveBuffer mémoire tampon d'un direct qui peut passer de l'aperçu au plein écran (et inversement).
     * @param layered direct qui peut passer de l'aperçu au plein écran : il dessine dans sa propre couche vidéo
     * ([VideoLayers], Android 10 et plus), que chaque écran place sous sa zone vidéo.
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
        /** Film ou épisode (et non un direct) : plus d'avance en mémoire, et moins de reprises hachées. */
        film: Boolean = false,
        liveBuffer: LiveLoadControl? = null,
        layered: Boolean = false,
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
            .setLoadControl(liveBuffer ?: if (preview) liveLoadControl(preview = true) else if (film) filmLoadControl() else loadControl())
        if (bandwidthMeter != null) builder.setBandwidthMeter(bandwidthMeter)
        // L'aperçu de l'accueil ne prend pas le son au système.
        return builder.build().also {
            if (preview) releaseSound(it) else takeSound(it)
            if (layered) VideoLayers.attach(it)
        }
    }

    /**
     * Lecture en plein écran : le son est demandé au système (une autre application qui joue se tait, et la
     * lecture s'interrompt le temps d'une annonce vocale ou d'un appel), et elle se met en pause quand la
     * sortie son disparaît (casque débranché). Aussi pour l'aperçu repris par le plein écran.
     */
    fun takeSound(player: ExoPlayer) {
        player.setAudioAttributes(MOVIE_AUDIO, /* handleAudioFocus = */ true)
        player.setHandleAudioBecomingNoisy(true)
    }

    /**
     * Aperçu de l'accueil : le son n'est pas demandé au système. Le type de son reste celui du plein écran : changé
     * au passage de l'un à l'autre, il viderait la sortie son (coupure, image qui marque un temps d'arrêt).
     */
    fun releaseSound(player: ExoPlayer) {
        player.setAudioAttributes(MOVIE_AUDIO, /* handleAudioFocus = */ false)
        player.setHandleAudioBecomingNoisy(false)
    }

    /**
     * Mémoire tampon d'un direct : celle du plein écran, ou en aperçu ([LiveLoadControl.preview]) quelques
     * secondes d'avance seulement. Elle change sans recréer le lecteur quand le direct passe d'un écran à l'autre.
     */
    fun liveLoadControl(preview: Boolean): LiveLoadControl = LiveLoadControl(loadControl(), preview)

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
        /**
         * Direct qui peut passer de l'aperçu au plein écran (et inversement) : la mesure du débit et la mémoire du
         * direct s'y branchent (à la place de [transferListener] et [recording]).
         */
        taps: StreamTaps? = null,
    ): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(httpClient(request))
            .setUserAgent(userAgent(request))
            .setDefaultRequestProperties(request.stream.headers - "User-Agent")
            .setTransferListener(taps ?: transferListener)
        val networkFactory: DataSource.Factory = when {
            taps != null -> DataSource.Factory { LiveRecordingDataSource(httpFactory.createDataSource()) { taps.recording } }
            recording != null -> DataSource.Factory { LiveRecordingDataSource(httpFactory.createDataSource()) { recording } }
            else -> httpFactory
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, networkFactory)
        return DefaultMediaSourceFactory(dataSourceFactory)
            .apply { if (taps != null) setLoadErrorHandlingPolicy(taps.retryPolicy) }
            .createMediaSource(mediaItem(request.stream, forceHls))
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

    /**
     * Film ou épisode : il se charge d'avance tant que la mémoire le permet (jusqu'à [FILM_MAX_BUFFER_MILLIS]), pour
     * passer les ralentissements du serveur sans s'arrêter. Après une coupure, il attend [FILM_RESUME_BUFFER_MILLIS]
     * d'avance avant de reprendre : sur une connexion trop lente, quelques pauses franches plutôt qu'une image qui
     * s'arrête et reprend toutes les cinq secondes. Le démarrage et les déplacements restent aussi rapides.
     */
    private fun filmLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, FILM_MAX_BUFFER_MILLIS, START_BUFFER_MILLIS, FILM_RESUME_BUFFER_MILLIS)
        .setTargetBufferBytes(filmBufferBytes(context))
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

/** Type de son des lectures (aperçu compris : le même des deux côtés, voir [PlayerFactory.releaseSound]). */
private val MOVIE_AUDIO: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

/**
 * Mémoire tampon d'un direct, celle du plein écran ([full]) ; en aperçu ([preview]), le flux n'est lu qu'à quelques
 * secondes d'avance ([PREVIEW_MAX_BUFFER_MILLIS], [PREVIEW_BUFFER_BYTES] au plus).
 *
 * En aperçu, la lecture du flux reprend dès qu'il repasse sous cette limite : l'ancienne mémoire de l'aperçu
 * s'arrêtait de lire à 8 s d'avance et ne reprenait qu'à 4 s. Certains serveurs coupent un client qui ne lit plus
 * pendant plusieurs secondes ; le flux repartait alors de ses premières secondes, en boucle. Gardée en plein écran
 * après OK sur l'aperçu, elle faisait aussi croire à une connexion lente (passage à une qualité inférieure).
 */
@OptIn(UnstableApi::class)
class LiveLoadControl internal constructor(private val full: DefaultLoadControl, preview: Boolean) : LoadControl {
    @Volatile
    var preview: Boolean = preview

    override fun onPrepared(playerId: PlayerId) = full.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        full.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = full.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = full.onReleased(playerId)

    override fun getAllocator(): Allocator = full.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = full.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = full.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        full.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val loading = full.shouldContinueLoading(parameters)
        if (!preview) return loading
        return loading && parameters.bufferedDurationUs < PREVIEW_MAX_BUFFER_MILLIS * 1000L && full.allocator.totalBytesAllocated < PREVIEW_BUFFER_BYTES
    }

    // Aperçu d'un flux très lourd (4K) : sa limite en octets peut tomber avant les secondes demandées pour démarrer.
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        full.shouldStartPlayback(parameters) || (preview && full.allocator.totalBytesAllocated >= PREVIEW_BUFFER_BYTES)
}

/**
 * Pistes d'un nouveau flux : choix faits dans le précédent oubliés, son rétabli ; langue audio et sous-titres
 * choisis en dernier, essayés en premier. Les mêmes pour l'aperçu et le plein écran : différents, le passage de l'un
 * à l'autre changerait les pistes en cours (le direct rechargé, une coupure).
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.applyTrackPreferences(audioLanguage: String?, subtitleLanguage: String?) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .clearOverrides()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setPreferredAudioLanguage(audioLanguage)
        .setPreferredTextLanguage(subtitleLanguage)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage == null)
        .build()
}

/**
 * Mémoire qu'un film peut occuper d'avance : un cinquième de celle que le système accorde à l'application, entre
 * [MAX_BUFFER_BYTES] (petites box) et [FILM_MAX_BUFFER_BYTES]. Au-delà, le système ferme l'application.
 */
internal fun filmBufferBytes(context: Context): Int {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    if (manager == null || manager.isLowRamDevice) return MAX_BUFFER_BYTES
    return filmBufferBytes(manager.largeMemoryClass)
}

/** @param largeMemoryClass mémoire accordée à l'application, en Mo. */
internal fun filmBufferBytes(largeMemoryClass: Int): Int =
    (largeMemoryClass.toLong() * 1024 * 1024 / 5).coerceIn(MAX_BUFFER_BYTES.toLong(), FILM_MAX_BUFFER_BYTES.toLong()).toInt()

private const val START_BUFFER_MILLIS = 1_500
private const val FILM_RESUME_BUFFER_MILLIS = 10_000
private const val FILM_MAX_BUFFER_MILLIS = 5 * 60_000
private const val FILM_MAX_BUFFER_BYTES = 96 * 1024 * 1024
private const val RESUME_BUFFER_MILLIS = 3_000
private const val MAX_BUFFER_BYTES = 48 * 1024 * 1024
private const val PREVIEW_MAX_BUFFER_MILLIS = 8_000
private const val PREVIEW_BUFFER_BYTES = 12 * 1024 * 1024
