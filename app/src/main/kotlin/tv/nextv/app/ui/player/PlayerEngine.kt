package tv.nextv.app.ui.player

import android.content.res.Resources
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.player.AdVolumeControl
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.ReceivedRate
import tv.nextv.app.player.LiveRecording
import tv.nextv.core.model.StreamFormat
import tv.nextv.app.player.TimeshiftBuffer
import tv.nextv.app.player.VideoDecoders
import tv.nextv.app.player.codecName

/**
 * Lecteur ExoPlayer de l'écran du lecteur : un seul pour tous les flux (chaînes, qualités, repli HLS,
 * serveurs de secours, différé), recréé seulement quand le réglage du son direct ou du décodeur
 * préféré change. Événements de lecture, pistes audio et sous-titres, infos techniques (format,
 * décodeurs, débit, images perdues).
 */
@OptIn(UnstableApi::class)
internal class PlayerEngine(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val resources: Resources,
    private val events: Events,
) {
    /** Ce que le lecteur signale à l'écran du lecteur. */
    interface Events {
        fun onBuffering()
        fun onReady()
        fun onEnded()
        fun onError(error: PlaybackException)
    }

    /** Le lecteur, gardé d'un flux à l'autre ; réglages avec lesquels il a été créé (son direct, décodeurs). */
    private var exoPlayer: ExoPlayer? = null
    private var exoPlayerConfig: PlayerConfig? = null

    /** Réglages avec lesquels le lecteur a été créé : changés, il est recréé. */
    private data class PlayerConfig(
        val passthrough: Boolean,
        val preferredDecoders: Map<String, String>,
        val excludedDecoders: Set<String>,
        val softwareAudio: Boolean,
    )

    /** Son décodé par l'appli d'abord (après une erreur de décodage du son sur le flux en cours). */
    private var softwareAudio = false

    /** Format du son illisible trouvé dans les pistes du flux (message « aucun son »). */
    private var unsupportedAudio: String? = null

    /** Décodeurs vidéo qui ont échoué sur le film ou la chaîne en cours : le lecteur est recréé sans eux. */
    private var excludedDecoders: Set<String> = emptySet()

    /** Son coupé faute d'en savoir lire une piste : signalé (« son illisible ») tant que dure ce flux. */
    private var audioGivenUp = false

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)

    /** Lecteur qui lit un flux, `null` sans lecture (flux illisible, chaîne introuvable…). */
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    /** Lecteur en cours, `null` sans lecture. */
    val current: ExoPlayer? get() = mutablePlayer.value

    /** Numéro du flux en cours : change à chaque flux ouvert (la vitesse x2 est à redemander). */
    var generation = 0
        private set

    /** Chaîne demandée ([startZapTimer]) : heure, pour journaliser le délai jusqu'à la première image. */
    private var zapStartedAt = 0L

    /** Option « baisser le son des pubs », lue par le traitement du son de chaque lecteur. */
    val adVolume = AdVolumeControl()

    private val bandwidthMeter: DefaultBandwidthMeter = container.playerFactory.newBandwidthMeter()
    private var preferredDecoders: Map<String, String> = emptyMap()

    /** Langues choisies en dernier (audio ; sous-titres, `null` : désactivés), proposées en premier. */
    private var audioLanguage: String? = null
    private var subtitleLanguage: String? = null

    /** Son Dolby / DTS envoyé tel quel à l'ampli (sinon décodé par l'appareil). */
    private var audioPassthrough = false

    // Images perdues déjà prises en compte, pour les compteurs du décodeur en cours (nouveaux à chaque flux).
    private var countedCounters: DecoderCounters? = null
    private var countedDroppedFrames = 0

    init {
        scope.launch { container.settings.preferredDecoders.collect { preferredDecoders = it } }
        scope.launch { container.settings.audioLanguage.collect { audioLanguage = it } }
        scope.launch { container.settings.audioPassthrough.collect { audioPassthrough = it } }
        scope.launch { container.settings.subtitleLanguage.collect { subtitleLanguage = it } }
        scope.launch {
            container.settings.lowerAdVolume.collect {
                adVolume.enabled = it
                state.update { s -> s.copy(lowerAdVolume = it) }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> events.onBuffering()
                Player.STATE_READY -> events.onReady()
                Player.STATE_ENDED -> events.onEnded()
            }
            state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) = events.onError(error)

        override fun onTracksChanged(tracks: Tracks) {
            unsupportedAudio = null
            val problem = audioProblemOf(tracks)
            state.update { it.copy(tracks = describeTracks(tracks), audioProblem = problem, unsupportedAudio = unsupportedAudio ?: it.unsupportedAudio.takeIf { problem != null }) }
        }

        override fun onRenderedFirstFrame() {
            val started = zapStartedAt
            if (started == 0L) return
            zapStartedAt = 0L
            container.logger.debug(TAG, "zapping → première image : ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    /** Débit reçu du flux, mesuré à part : ExoPlayer n'en donne pas pour un flux continu (MPEG-TS). */
    private val receivedRate = ReceivedRate()
    private val received = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork) return
            receivedRate.add(bytesTransferred, SystemClock.elapsedRealtime())?.let { mbps -> state.update { it.copy(receivedMbps = mbps) } }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            state.update { it.copy(bandwidthMbps = bitrateEstimate / 1_000_000.0) }
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            updateInfo {
                it.copy(
                    videoMimeType = format.sampleMimeType,
                    width = format.width.takeIf { w -> w > 0 },
                    height = format.height.takeIf { h -> h > 0 },
                    frameRate = format.frameRate.takeIf { f -> f > 0f },
                )
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            updateInfo { it.copy(videoDecoder = decoderName) }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            updateInfo { it.copy(audioMimeType = format.sampleMimeType, audioChannels = format.channelCount.takeIf { c -> c > 0 }) }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            updateInfo { it.copy(audioDecoder = decoderName) }
        }
    }

    /**
     * Lit le flux de [request], avec sa propre source réseau.
     *
     * @param forceHls traiter le flux comme du HLS (seconde tentative pour une URL sans extension).
     * @param startPositionMs position de départ d'une reprise (0 : un direct démarre au plus près du direct).
     */
    fun play(request: PlaybackRequest, forceHls: Boolean, startPositionMs: Long, record: Boolean = false) {
        // Direct continu (MPEG-TS…) : les dernières secondes sont gardées en mémoire pour revenir en arrière.
        val recording = if (record && !forceHls && request.stream.format in RECORDABLE_FORMATS) LiveRecording(LIVE_TAIL_BYTES) else null
        liveRecording = recording
        open(container.playerFactory.mediaSource(request, forceHls, received, recording), startPositionMs)
    }

    /** Direct en cours gardé pour revenir en arrière (`null` : pas un direct continu). */
    var liveRecording: LiveRecording? = null
        private set

    /**
     * Relit le direct gardé ([recording], déjà passé sur le disque) à partir de [position] (octets du flux).
     * [play] faux : prêt, en pause (mise en pause du direct).
     */
    fun playLiveRecording(recording: LiveRecording, position: Long, play: Boolean) {
        val disk = recording.disk ?: return
        open(container.playerFactory.timeshiftSource(disk, (position - recording.diskBase).coerceAtLeast(0)), startPositionMs = 0)
        if (!play) exoPlayer?.playWhenReady = false
    }

    /** Lit l'enregistrement du différé [buffer]. */
    fun playTimeshift(buffer: TimeshiftBuffer) {
        open(container.playerFactory.timeshiftSource(buffer), startPositionMs = 0)
    }

    private fun open(source: MediaSource, startPositionMs: Long) {
        // Ancien flux arrêté avant d'ouvrir le nouveau : beaucoup d'abonnements n'acceptent qu'une connexion.
        exoPlayer?.stop()
        val player = reusablePlayer()
        // État propre au flux précédent : erreur, infos, son coupé (avance rapide), pistes choisies, vitesse.
        generation++
        adVolume.newStream()
        receivedRate.reset()
        state.update { it.copy(error = null, info = PlaybackInfo(), audioProblem = null, unsupportedAudio = null, receivedMbps = null) }
        audioGivenUp = false
        player.setPlaybackSpeed(1f)
        player.resetTrackSelection()
        // Position de départ seulement pour ce flux (reprise) ; sinon position par défaut.
        if (startPositionMs > 0) player.setMediaSource(source, startPositionMs) else player.setMediaSource(source)
        player.playWhenReady = true
        player.prepare()
        mutablePlayer.value = player
    }

    /** Le lecteur actuel, ou un nouveau si le réglage du son direct ou du décodeur préféré a changé. */
    private fun reusablePlayer(): ExoPlayer {
        val config = PlayerConfig(audioPassthrough, preferredDecoders, excludedDecoders, softwareAudio)
        exoPlayer?.takeIf { exoPlayerConfig == config }?.let { return it }
        release()
        return container.playerFactory.create(
            bandwidthMeter, preferredDecoders, adVolume, passthrough = audioPassthrough, excludedDecoders = excludedDecoders, softwareAudio = softwareAudio,
        ).apply {
            addListener(listener)
            addAnalyticsListener(analytics)
            exoPlayer = this
            exoPlayerConfig = config
        }
    }

    /** Arrête la lecture et ferme la connexion ; le lecteur est gardé pour le flux suivant. */
    fun stop() {
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutablePlayer.value = null
    }

    /** Libère le lecteur (écran du lecteur fermé). */
    fun release() {
        exoPlayer?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        exoPlayer = null
        exoPlayerConfig = null
        mutablePlayer.value = null
    }

    /** Chaîne demandée : le délai jusqu'à sa première image sera journalisé. */
    fun startZapTimer() {
        zapStartedAt = SystemClock.elapsedRealtime()
    }

    /** Pas d'image attendue (question « reprendre / recommencer ») : pas de délai à journaliser. */
    fun cancelZapTimer() {
        zapStartedAt = 0L
    }

    /**
     * Images perdues depuis le dernier passage, lues sur les compteurs du décodeur (le lecteur ne les
     * signale lui-même que par lots de 50, trop tard pour réagir vite).
     */
    fun newDroppedFrames(): Int {
        val counters = mutablePlayer.value?.videoDecoderCounters ?: return 0
        if (counters !== countedCounters) {
            countedCounters = counters
            countedDroppedFrames = 0
        }
        counters.ensureUpdated()
        val total = counters.droppedBufferCount
        val delta = total - countedDroppedFrames
        countedDroppedFrames = total
        if (delta > 0) updateInfo { it.copy(droppedFrames = total) }
        return delta.coerceAtLeast(0)
    }

    /** Met à jour les infos de lecture ; le type de décodeur (matériel ou logiciel) est déduit dès que possible. */
    private fun updateInfo(transform: (PlaybackInfo) -> PlaybackInfo) {
        state.update { state ->
            val info = transform(state.info)
            val decoder = info.videoDecoder
            val resolved = if (decoder != null && info.hardwareDecoder == null) {
                info.copy(hardwareDecoder = VideoDecoders.isHardware(info.videoMimeType, decoder))
            } else {
                info
            }
            state.copy(info = resolved)
        }
    }

    /**
     * Pistes du nouveau flux : choix faits dans le précédent oubliés, son rétabli ; langue audio et
     * sous-titres choisis en dernier, essayés en premier.
     */
    private fun ExoPlayer.resetTrackSelection() {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setPreferredAudioLanguage(audioLanguage)
            .setPreferredTextLanguage(subtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage == null)
            .build()
    }

    /**
     * Image sans son : aucune piste audio dans le flux, ou aucune que l'appareil sache lire. Les
     * pistes ne sont connues qu'une fois l'image arrivée (vidéo présente).
     */
    private fun audioProblemOf(tracks: Tracks): AudioProblem? {
        if (audioGivenUp) return AudioProblem.UNSUPPORTED
        if (tracks.isEmpty || !tracks.containsType(C.TRACK_TYPE_VIDEO)) return null
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return when {
            audio.isEmpty() -> AudioProblem.NO_TRACK
            audio.none { group -> (0 until group.length).any(group::isTrackSupported) } -> {
                val formats = audio.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).sampleMimeType } }
                container.logger.warn(TAG, "Son illisible par l'appareil : ${formats.distinct()}")
                unsupportedAudio = formats.firstNotNullOfOrNull { codecName(it) }
                AudioProblem.UNSUPPORTED
            }
            else -> null
        }
    }

    private fun describeTracks(tracks: Tracks): PlayerTracks {
        val audio = mutableListOf<TrackChoice>()
        val subtitles = mutableListOf<TrackChoice>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val target = when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio
                C.TRACK_TYPE_TEXT -> subtitles
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                target += TrackChoice(groupIndex, trackIndex, trackLabel(format, language, target.size + 1), language, group.isTrackSelected(trackIndex))
            }
        }
        return PlayerTracks(audio, subtitles)
    }

    /** « Français · 5.1 », « Anglais », « Piste 2 ». */
    private fun trackLabel(format: Format, language: String?, position: Int): String {
        val languageName = language?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).takeIf { it.isNotBlank() && it != code } ?: code
        }?.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        val name = format.label?.takeIf { it.isNotBlank() && !it.equals(languageName, ignoreCase = true) }
        val channels = when (format.channelCount) {
            1 -> "mono"
            2 -> "stéréo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        val title = listOfNotNull(languageName, name).joinToString(" · ").ifEmpty { resources.getString(R.string.player_track, position) }
        // Son : son format (« MP2 », « Dolby Digital (AC3) ») aide à comprendre une piste muette.
        val codec = format.sampleMimeType?.takeIf { MimeTypes.isAudio(it) }?.let { codecName(it) }
        return listOfNotNull(title, channels, codec).joinToString(" · ")
    }

    fun selectAudio(choice: TrackChoice) {
        val player = mutablePlayer.value ?: return
        val group = player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            .build()
        audioLanguage = choice.language ?: audioLanguage
        choice.language?.let { scope.launch { container.settings.setAudioLanguage(it) } }
    }

    /** [choice] `null` : sous-titres désactivés. */
    fun selectSubtitles(choice: TrackChoice?) {
        val player = mutablePlayer.value ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        if (choice == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
        subtitleLanguage = choice?.language
        scope.launch { container.settings.setSubtitleLanguage(choice?.language) }
    }

    // --- Reprise après un échec de décodage ----------------------------------------------------------

    /**
     * Décodeur vidéo suivant pour [mimeType], une fois [failed] écarté : `null` s'il n'y en a pas d'autre.
     * Il est alors écarté pour le film ou la chaîne en cours (le lecteur sera recréé sans lui).
     */
    fun excludeDecoder(mimeType: String?, failed: String?): String? {
        val decoders = mimeType?.let { VideoDecoders.list(it) }.orEmpty().map { it.name }.filter { it !in excludedDecoders }
        // Décodeur en cause inconnu : celui que le lecteur prend en premier.
        val culprit = failed ?: preferredDecoders[mimeType]?.takeIf { it in decoders } ?: decoders.firstOrNull() ?: return null
        val next = decoders.firstOrNull { it != culprit } ?: return null
        excludedDecoders = excludedDecoders + culprit
        return next
    }

    /** Nouveau film ou nouvelle chaîne : tous les décodeurs sont de nouveau essayés. */
    fun resetExcludedDecoders() {
        excludedDecoders = emptySet()
        softwareAudio = false
    }

    /**
     * Erreur de décodage du son : le lecteur sera recréé avec le son décodé par l'appli d'abord (MPEG audio,
     * FFmpeg), pour le film ou la chaîne en cours. @return faux si c'est déjà le cas.
     */
    fun preferSoftwareAudio(): Boolean {
        if (softwareAudio) return false
        softwareAudio = true
        return true
    }

    /**
     * La piste son [failed] ne peut pas être lue : une autre piste son lisible est choisie ; s'il n'y en a
     * pas, le son est coupé (l'image continue, « son illisible » est signalé). @return vrai si une autre
     * piste est choisie.
     */
    fun skipAudioTrack(failed: Format?): Boolean {
        val player = exoPlayer ?: return false
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        for (group in groups) {
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val same = failed != null && (format.id == failed.id || format.sampleMimeType == failed.sampleMimeType)
                if (!same && group.isTrackSupported(index)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                    return true
                }
            }
        }
        audioGivenUp = true
        player.setAudioDisabled(true)
        state.update { it.copy(audioProblem = AudioProblem.UNSUPPORTED, unsupportedAudio = codecName(failed?.sampleMimeType) ?: it.unsupportedAudio) }
        return false
    }

    /** Sous-titres illisibles : coupés pour ce flux. */
    fun disableSubtitles() {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    private companion object {
        const val TAG = "Player"
    }
}

/** Coupe ou rétablit le son du lecteur (avance rapide quand la sortie audio ne suit pas la vitesse). */
internal fun ExoPlayer.setAudioDisabled(disabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled).build()
}

/** Flux du direct qu'on peut garder pour revenir en arrière (continus ; le HLS se recule dans sa fenêtre). */
private val RECORDABLE_FORMATS = setOf(StreamFormat.PROGRESSIVE, StreamFormat.UNKNOWN)

/** Dernières secondes du direct gardées en mémoire : 32 Mo (une trentaine de secondes en HD). */
private const val LIVE_TAIL_BYTES = 32 * 1024 * 1024
