package tv.nextv.app.ui.player

import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import tv.nextv.app.player.TimeshiftBuffer
import tv.nextv.app.player.VideoDecoders

/**
 * Lecteur ExoPlayer de l'écran du lecteur : création et libération, événements de lecture, pistes
 * audio et sous-titres, infos techniques (format, décodeurs, débit, images perdues).
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

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    /** Lecteur en cours, `null` sans lecture. */
    val current: ExoPlayer? get() = mutablePlayer.value

    /** Option « baisser le son des pubs », lue par le traitement du son de chaque lecteur. */
    val adVolume = AdVolumeControl()

    private val bandwidthMeter: DefaultBandwidthMeter = container.playerFactory.newBandwidthMeter()
    private var preferredDecoders: Map<String, String> = emptyMap()

    /** Langues choisies en dernier (audio ; sous-titres, `null` : désactivés), proposées en premier. */
    private var audioLanguage: String? = null
    private var subtitleLanguage: String? = null

    /** Son Dolby / DTS envoyé tel quel à l'ampli (sinon décodé par l'appareil). */
    private var audioPassthrough = false

    // Images perdues déjà prises en compte, pour le lecteur courant.
    private var countedPlayer: ExoPlayer? = null
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
            state.update { it.copy(tracks = describeTracks(tracks), audioProblem = audioProblemOf(tracks)) }
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
     * Lit le flux de [request] dans un nouveau lecteur.
     *
     * @param forceHls traiter le flux comme du HLS (seconde tentative pour une URL sans extension).
     * @param startPositionMs position de départ d'une reprise (0 : un direct démarre au plus près du direct).
     */
    fun play(request: PlaybackRequest, forceHls: Boolean, startPositionMs: Long) {
        release()
        state.update { it.copy(info = PlaybackInfo(), audioProblem = null) }
        val player = newPlayer(request).apply {
            val item = container.playerFactory.mediaItem(request.stream, forceHls)
            if (startPositionMs > 0) setMediaItem(item, startPositionMs) else setMediaItem(item)
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
    }

    /** Lit l'enregistrement du différé [buffer] dans un nouveau lecteur. */
    fun playTimeshift(request: PlaybackRequest, buffer: TimeshiftBuffer) {
        release()
        val player = newPlayer(request).apply {
            setMediaSource(container.playerFactory.timeshiftSource(buffer))
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
    }

    private fun newPlayer(request: PlaybackRequest): ExoPlayer =
        container.playerFactory.create(request, bandwidthMeter, preferredDecoders, adVolume, passthrough = audioPassthrough).apply {
            applyTrackPreferences()
            addListener(listener)
            addAnalyticsListener(analytics)
        }

    /** Arrête la lecture et libère le lecteur. */
    fun release() {
        mutablePlayer.value?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        mutablePlayer.value = null
    }

    /**
     * Images perdues depuis le dernier passage, lues sur les compteurs du décodeur (le lecteur ne les
     * signale lui-même que par lots de 50, trop tard pour réagir vite).
     */
    fun newDroppedFrames(): Int {
        val player = mutablePlayer.value
        if (player !== countedPlayer) {
            countedPlayer = player
            countedDroppedFrames = 0
        }
        val counters = player?.videoDecoderCounters ?: return 0
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

    /** Langue audio et sous-titres choisis en dernier, essayés en premier. */
    private fun ExoPlayer.applyTrackPreferences() {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
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
        if (tracks.isEmpty || !tracks.containsType(C.TRACK_TYPE_VIDEO)) return null
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return when {
            audio.isEmpty() -> AudioProblem.NO_TRACK
            audio.none { group -> (0 until group.length).any(group::isTrackSupported) } -> {
                val formats = audio.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).sampleMimeType } }
                container.logger.warn(TAG, "Son illisible par l'appareil : ${formats.distinct()}")
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
        return listOfNotNull(title, channels).joinToString(" · ")
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

    private companion object {
        const val TAG = "Player"
    }
}

/** Coupe ou rétablit le son du lecteur (avance rapide quand la sortie audio ne suit pas la vitesse). */
internal fun ExoPlayer.setAudioDisabled(disabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled).build()
}
