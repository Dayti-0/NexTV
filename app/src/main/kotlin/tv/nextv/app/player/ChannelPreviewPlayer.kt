package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.repository.SourceRepository

/** Flux de lecture d'une chaîne enregistrée. */
fun ChannelEntity.toStream() = StreamDescriptor(
    url = streamUrl,
    headers = HeaderCodec.decode(streamHeaders),
    format = StreamFormat.entries.firstOrNull { it.name == streamFormat } ?: StreamFormat.UNKNOWN,
    support = StreamSupport.entries.firstOrNull { it.name == streamSupport } ?: StreamSupport.SUPPORTED,
    issues = streamIssues.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }.toSet(),
)

/**
 * Aperçu vidéo de l'accueil : un seul lecteur, au son réduit (réglages), pour la chaîne sélectionnée.
 * À libérer avant de lancer une chaîne en plein écran : beaucoup d'abonnements n'autorisent
 * qu'une connexion à la fois.
 *
 * Le lecteur est créé au premier aperçu puis gardé d'une chaîne à l'autre ([stop] ferme le flux, pas le
 * lecteur) : en recréer un complet à chaque chaîne survolée retardait l'image. [release] le libère quand
 * l'accueil n'est plus affiché.
 *
 * Chaîne de l'aperçu ouverte en plein écran : [handOff] confie le lecteur en marche au lecteur plein écran,
 * qui continue sur la même connexion (pas de nouvelle ouverture du flux, l'image ne s'arrête pas). Au retour sur
 * l'accueil, [adopt] reprend de même le direct du plein écran.
 *
 * Le lecteur est réglé comme celui du plein écran (type de son, pistes) : le passage de l'un à l'autre ne change
 * que la mémoire tampon, le volume et la prise du son.
 */
@OptIn(UnstableApi::class)
class ChannelPreviewPlayer(
    private val factory: PlayerFactory,
    private val sources: SourceRepository,
    /** Réglages du lecteur plein écran : avec certains, il ne peut pas reprendre le lecteur de l'aperçu. */
    private val settings: SettingsRepository? = null,
) {
    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)

    /** Lecteur de l'aperçu, `null` sans aperçu (aucune chaîne, flux illisible ou en erreur). */
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    /** Le lecteur, gardé entre deux aperçus. */
    private var exoPlayer: ExoPlayer? = null

    private var channelId: Long? = null

    /** Flux de l'aperçu en cours, et de quoi y brancher le lecteur plein écran s'il le reprend. */
    private var request: PlaybackRequest? = null
    private var taps: StreamTaps? = null

    /** Mémoire tampon du lecteur (celle d'un aperçu tant qu'il est ici). */
    private var buffer: LiveLoadControl? = null

    /** Le plein écran peut reprendre ce lecteur (créé comme il l'aurait créé lui-même, au son près). */
    private var transferable = false

    // Décodeurs en service, pour les infos du lecteur plein écran s'il reprend l'aperçu.
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private val decoders = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
    }

    private val errors = object : Player.Listener {
        // Flux en erreur : l'aperçu s'efface (le logo reste affiché).
        override fun onPlayerError(error: PlaybackException) = stop()
    }

    /** Volume de l'aperçu (0 : sans le son), appliqué aussi à l'aperçu en cours. Fil principal. */
    var volume: Float = 0f
        set(value) {
            field = value
            exoPlayer?.volume = value
        }

    /** Montre [channel] (sans effet si c'est déjà la chaîne de l'aperçu). Fil principal. */
    suspend fun show(channel: ChannelEntity) {
        if (channel.id == channelId && mutablePlayer.value != null) return
        stop()
        val stream = channel.toStream()
        if (stream.support == StreamSupport.UNSUPPORTED || stream.format == StreamFormat.UDP) return
        val source = sources.getSource(channel.sourceId)
        val request = PlaybackRequest(
            stream = stream,
            userAgent = source?.userAgent,
            credentials = sources.credentialsFor(channel.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        // Son envoyé tel quel à l'ampli, décodeur imposé, « baisser le son des pubs » : le plein écran crée son
        // lecteur autrement, il rouvrira le flux.
        if (exoPlayer == null) {
            transferable = settings?.let { !it.audioPassthrough.first() && !it.lowerAdVolume.first() && it.preferredDecoders.first().isEmpty() } ?: false
        }
        // Langues du plein écran (dernières choisies) : les mêmes pistes, rien ne change s'il reprend l'aperçu.
        val audioLanguage = settings?.audioLanguage?.first()
        val subtitleLanguage = settings?.subtitleLanguage?.first()
        channelId = channel.id
        val liveBuffer = buffer ?: factory.liveLoadControl(preview = true).also { buffer = it }
        val player = exoPlayer ?: factory.create(preview = true, liveBuffer = liveBuffer).also {
            it.addListener(errors)
            it.addAnalyticsListener(decoders)
            exoPlayer = it
        }
        liveBuffer.preview = true
        val streamTaps = StreamTaps().apply { preview = true }
        this.request = request
        taps = streamTaps
        player.volume = volume
        player.applyTrackPreferences(audioLanguage, subtitleLanguage)
        player.setMediaSource(factory.mediaSource(request, taps = streamTaps))
        player.playWhenReady = true
        player.prepare()
        mutablePlayer.value = player
    }

    /**
     * Chaîne [id] ouverte en plein écran : si c'est celle de l'aperçu et qu'il joue, le lecteur est confié tel
     * quel (il n'appartient plus à l'aperçu) ; sinon `null`. Fil principal.
     */
    fun handOff(id: Long): PreviewHandoff? {
        val player = exoPlayer ?: return null
        val playing = request ?: return null
        val streamTaps = taps ?: return null
        val liveBuffer = buffer ?: return null
        if (!transferable || id != channelId || mutablePlayer.value == null) return null
        if (player.playerError != null || (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING)) return null
        player.removeListener(errors)
        player.removeAnalyticsListener(decoders)
        val handoff = PreviewHandoff(player, playing, streamTaps, liveBuffer, setOf(id), toPreview = false, videoDecoder, audioDecoder)
        exoPlayer = null
        buffer = null
        forgetStream()
        mutablePlayer.value = null
        return handoff
    }

    /**
     * Retour sur l'accueil : l'aperçu de la chaîne [id] continue sur le direct que jouait le plein écran (même
     * lecteur, même connexion), au volume de l'aperçu. Son propre lecteur, s'il en avait un, est rendu. Fil principal.
     */
    fun adopt(handoff: PreviewHandoff, id: Long) {
        exoPlayer?.let {
            it.removeListener(errors)
            it.removeAnalyticsListener(decoders)
            it.release()
        }
        val player = handoff.player
        handoff.setPreview(true)
        factory.releaseSound(player)
        player.addListener(errors)
        player.addAnalyticsListener(decoders)
        exoPlayer = player
        buffer = handoff.buffer
        // Le plein écran ne le confie que réglé comme il l'aurait été ici (son, décodeurs, mémoire du direct).
        transferable = true
        channelId = id
        request = handoff.request
        taps = handoff.taps
        videoDecoder = handoff.videoDecoder
        audioDecoder = handoff.audioDecoder
        player.volume = volume
        // Sa nouvelle vidéo ne doit pas le faire repartir au direct (nouvelle connexion).
        ContinuingPlayers.mark(player)
        mutablePlayer.value = player
    }

    private fun forgetStream() {
        channelId = null
        request = null
        taps = null
        videoDecoder = null
        audioDecoder = null
    }

    /** Arrête l'aperçu et ferme sa connexion ; le lecteur reste prêt pour la chaîne suivante. Fil principal. */
    fun stop() {
        forgetStream()
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutablePlayer.value = null
    }

    /** Accueil quitté (lecture en plein écran, autre écran) : le lecteur et ses décodeurs sont rendus. Fil principal. */
    fun release() {
        forgetStream()
        mutablePlayer.value = null
        exoPlayer?.let {
            it.removeListener(errors)
            it.removeAnalyticsListener(decoders)
            it.release()
        }
        exoPlayer = null
        buffer = null
    }
}
