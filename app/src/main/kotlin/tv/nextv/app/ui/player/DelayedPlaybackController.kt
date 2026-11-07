package tv.nextv.app.ui.player

import android.content.Context
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.TimeshiftBuffer
import tv.nextv.app.player.TimeshiftRecorder
import tv.nextv.app.player.toStream
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.Mirrors
import tv.nextv.core.source.XtreamAccount

/**
 * Lecture décalée du direct : pause et différé (le direct enregistré pendant la pause), replay d'un
 * programme passé et « Depuis le début » d'un programme en cours (enregistrements du serveur), sauts
 * de ← → et avance ou retour rapides.
 *
 * @param handle arguments de l'écran : programme du replay à lire, oublié en le quittant.
 */
@OptIn(UnstableApi::class)
internal class DelayedPlaybackController(
    private val container: AppContainer,
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val session: PlaybackSession,
    private val engine: PlayerEngine,
    private val handle: SavedStateHandle,
    private val host: Host,
) {
    /** Ce que la lecture décalée demande à l'écran du lecteur. */
    interface Host {
        /** Ouvre le flux de [PlaybackSession.request]. */
        fun start(forceHls: Boolean)

        /** Relit la chaîne en cours, en direct. */
        fun reload()

        /** Programme en cours et suivant de la chaîne affichée. */
        suspend fun refreshProgrammes()

        /** Playlist de la chaîne en cours (menu des chaînes). */
        fun showPlaylist(sourceId: Long)
    }

    private val resources = context.resources

    /**
     * Replay d'un programme (début, fin) ; `null` : lecture normale. Oublié au changement de chaîne
     * et au retour au direct.
     */
    var replay: Pair<Long, Long>? = handle.get<Long>(PlayerViewModel.ARG_REPLAY_START)?.takeIf { it > 0 }
        ?.let { start -> handle.get<Long>(PlayerViewModel.ARG_REPLAY_STOP)?.takeIf { it > start }?.let { start to it } }
        private set

    /**
     * « Depuis le début » d'un programme en cours : lu depuis l'enregistrement du serveur, mais comme
     * le direct (bandeau, menu des chaînes, zapping), avec le retard affiché et → pour le rattraper.
     */
    var restarting = false
        private set

    /** Heure correspondant au début du flux de l'enregistrement en cours de lecture. */
    private var segmentStart = 0L

    /** Adresse de l'enregistrement à partir d'une heure donnée (jusqu'à la fin du programme). */
    private var replayUrlFrom: ((Long) -> String?)? = null
    private var pendingJumpSeconds = 0
    private var jumpJob: Job? = null

    /** Replay lu en HLS : on s'y déplace à la seconde près (sinon, l'enregistrement est relu à la minute). */
    private var replayHls = false

    /** Avance (1) ou retour (−1) rapide en cours, tant que la touche est maintenue. */
    private var fastDirection = 0
    private var fastJob: Job? = null
    /** Dernier signe de la touche maintenue : sans nouvelles (relâchement perdu), l'avance rapide s'arrête. */
    private var fastHeartbeat = 0L
    /** Flux auquel la vitesse x2 a été demandée (un saut dans un enregistrement continu en ouvre un autre, à vitesse normale). */
    private var fastGeneration = -1
    /** Son coupé pendant l'avance rapide : sortie audio qui ne suit pas la vitesse (son transmis tel quel à l'ampli). */
    private var fastAudioOff = false
    /** Retour rapide par déplacements dans le flux : position visée, et lecture à reprendre ensuite. */
    private var rewindInPlace = false
    private var rewindTarget = 0L
    private var resumeAfterRewind = false

    // Différé du direct.
    private var timeshift: TimeshiftBuffer? = null
    private var recorder: TimeshiftRecorder? = null

    /** Lecture depuis l'enregistrement du différé. */
    var playingTimeshift = false
        private set

    /** Le direct est enregistré pour le différé (pause, puis lecture décalée). */
    val recording: Boolean get() = timeshift != null

    /** Programme passé revu (pas « Depuis le début ») : le programme du direct n'est pas affiché. */
    val replayingPast: Boolean get() = replay != null && !restarting

    /** Autre chaîne, ou la même relue : fin de l'avance rapide et du différé ([otherChannel] : du replay aussi). */
    fun beforeLoad(otherChannel: Boolean) {
        // Autre chaîne : fin du replay (le programme revu appartenait à la chaîne quittée).
        if (otherChannel) leaveReplay()
        resetFastSeek()
        stopTimeshift()
    }

    /** La chaîne se lit normalement (sans replay). */
    fun playingLive() {
        restarting = false
    }

    /**
     * Programme lu depuis l'enregistrement du serveur Xtream : un programme passé avec les commandes
     * d'un film, ou le programme en cours depuis son début, présenté comme le direct en différé.
     */
    suspend fun playReplay(requested: ChannelEntity, primary: ChannelEntity, programme: Pair<Long, Long>) {
        val (start, stop) = programme
        val restart = stop > System.currentTimeMillis()
        restarting = restart
        // Version enregistrée par le serveur : toutes les qualités ne le sont pas forcément.
        val recorded = requested.takeIf { it.catchupDays > 0 }
            ?: container.channelDao.recordedVersion(primary.sourceId, primary.variantKey)
            ?: requested
        val source = container.sourceRepository.getSource(recorded.sourceId)
        val title = container.programmeDao.upcoming(primary.id, start, limit = 1).firstOrNull()?.takeIf { it.startMillis == start }?.title
        val channelName = primary.title.ifBlank { primary.name }
        val time = DateUtils.formatDateRange(
            context,
            start,
            stop,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
        if (restart) {
            container.settings.addRecentChannel(primary.sourceId, primary.itemKey)
            host.showPlaylist(primary.sourceId)
            state.update {
                it.copy(
                    channelName = channelName,
                    channelNumber = primary.channelNumber,
                    logoUrl = primary.logoUrl,
                    sourceName = source?.name.orEmpty(),
                    isLive = true,
                    replay = null,
                    // Retard sur le direct : il reste le même pendant la lecture, la pause l'augmente.
                    liveDelayMs = (System.currentTimeMillis() - start).coerceAtLeast(0),
                    paused = false,
                    pausedAtMillis = null,
                    now = null,
                    next = null,
                    match = null,
                    error = null,
                    quality = null,
                    qualityNotice = null,
                    buffering = true,
                    channelVersion = it.channelVersion + 1,
                )
            }
            host.refreshProgrammes()
        } else {
            state.update {
                it.copy(
                    channelName = channelName,
                    channelNumber = primary.channelNumber,
                    logoUrl = primary.logoUrl,
                    sourceName = source?.name.orEmpty(),
                    isLive = false,
                    replay = time,
                    now = PlayerProgramme(title ?: channelName, start, stop),
                    next = null,
                    match = null,
                    error = null,
                    quality = null,
                    qualityNotice = null,
                    buffering = true,
                    channelVersion = it.channelVersion + 1,
                )
            }
        }
        val account = (source?.location as? SourceLocation.Remote)?.url?.let(XtreamAccount::parse)
        val timeZone = source?.let { container.serverTimeZone(it) }
        val urlFrom: ((Long) -> String?)? = if (account != null && timeZone != null) {
            val url: (Long) -> String? = { from -> Catchup.xtreamUrl(recorded.streamUrl, account, from, stop, timeZone) }
            url
        } else {
            null
        }
        replayUrlFrom = urlFrom
        segmentStart = start
        // Programme passé : d'abord en HLS (on s'y déplace finement), sauf si ce serveur l'a déjà refusé.
        val hlsUrl = if (!restart && account != null && timeZone != null && recorded.sourceId !in hlsReplayRefused) {
            Catchup.xtreamUrl(recorded.streamUrl, account, start, stop, timeZone, extension = "m3u8")
        } else {
            null
        }
        replayHls = hlsUrl != null
        state.update {
            it.copy(seekable = urlFrom != null, watchedAt = start, pendingJumpSeconds = 0, jumpStepSeconds = if (replayHls) FINE_JUMP_SECONDS else MINUTE_SECONDS)
        }
        val url = hlsUrl ?: urlFrom?.invoke(start)
        if (source == null || url == null) {
            engine.stop()
            state.update { it.copy(buffering = false, error = resources.getString(R.string.player_replay_unavailable)) }
            return
        }
        session.playing = recorded
        session.startPositionMs = 0
        session.serverRoots = serverRootsOf(source)
        session.backupUrls = Mirrors.alternatives(url, session.serverRoots)
        session.request = PlaybackRequest(
            stream = recorded.toStream().copy(url = url, format = if (replayHls) StreamFormat.HLS else StreamFormat.PROGRESSIVE),
            userAgent = source.userAgent,
            credentials = null,
            credentialsOrigin = null,
        )
        session.triedHlsFallback = true
        host.start(forceHls = false)
    }

    /** L'enregistrement HLS n'est pas proposé par ce serveur : même programme en flux continu (pas d'une minute). */
    private fun fallBackToContinuousReplay(): Boolean {
        if (!replayHls) return false
        replayHls = false
        val source = session.playing?.sourceId ?: return false
        hlsReplayRefused += source
        val url = replayUrlFrom?.invoke(segmentStart) ?: return false
        val playback = session.request ?: return false
        container.logger.info(TAG, "Replay HLS refusé par le serveur : flux continu")
        session.request = playback.copy(stream = playback.stream.copy(url = url, format = StreamFormat.PROGRESSIVE))
        session.backupUrls = Mirrors.alternatives(url, session.serverRoots)
        state.update { it.copy(jumpStepSeconds = MINUTE_SECONDS) }
        host.start(forceHls = false)
        return true
    }

    /**
     * Erreur de lecture propre à la lecture décalée : enregistrement du différé interrompu, replay
     * HLS refusé, direct sorti de la fenêtre du serveur après une longue pause.
     *
     * @return vrai si elle est traitée.
     */
    fun handleError(error: PlaybackException, player: ExoPlayer): Boolean {
        if (playingTimeshift) {
            // Enregistrement du différé interrompu : retour au direct.
            container.logger.warn(TAG, "Différé interrompu (${error.errorCodeName})", error)
            backToLive()
            return true
        }
        if (fallBackToContinuousReplay()) return true
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Direct trop en retard sur la fenêtre disponible (pause trop longue) : on revient au direct.
            player.seekToDefaultPosition()
            player.prepare()
            state.update { it.copy(liveDelayMs = 0, paused = false, pausedAtMillis = null) }
            return true
        }
        return false
    }

    /** Heure à l'écran dans un replay, mise à jour au fil de la lecture. */
    fun updateWatchedAt() {
        if (replayUrlFrom != null && engine.current != null && pendingJumpSeconds == 0) {
            val at = watchedAt()
            if (abs(at - (state.value.watchedAt ?: 0)) >= 1_000) state.update { it.copy(watchedAt = at) }
        }
    }

    // --- Pause du direct et différé -------------------------------------------------------------

    /** Pause / lecture. En direct, la pause crée un différé ; le retard sur le direct est affiché. */
    fun togglePause() {
        val player = engine.current ?: return
        if (!state.value.isLive) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        if (state.value.paused) resumeLive(player) else pauseLive(player)
    }

    private fun pauseLive(player: ExoPlayer) {
        player.pause()
        val now = System.currentTimeMillis()
        val playback = session.request
        // Flux continu (MPEG-TS…) : le serveur le coupe si on cesse de le lire. Il est donc
        // enregistré pendant la pause, puis relu depuis l'enregistrement.
        // « Depuis le début » : l'enregistrement est celui du serveur, il suffit d'arrêter la lecture.
        if (!restarting && timeshift == null && playback != null && !session.playingAsHls && playback.stream.format in RECORDABLE_FORMATS) {
            // Le lecteur libère d'abord sa connexion (l'image reste affichée) : beaucoup de
            // fournisseurs n'acceptent qu'une connexion à la fois.
            player.stop()
            val buffer = TimeshiftBuffer(File(context.cacheDir, TIMESHIFT_FILE), timeshiftLimit())
            val call = container.playerFactory.liveCall(playback)
            timeshift = buffer
            recorder = TimeshiftRecorder(call, buffer).also { it.start() }
        }
        state.update { it.copy(paused = true, pausedAtMillis = now) }
    }

    private fun resumeLive(player: ExoPlayer) {
        val since = state.value.pausedAtMillis ?: System.currentTimeMillis()
        val delay = state.value.liveDelayMs + (System.currentTimeMillis() - since)
        val buffer = timeshift
        if (buffer != null && !playingTimeshift) {
            startTimeshiftPlayback(buffer)
        } else {
            player.play()
        }
        state.update { it.copy(paused = false, pausedAtMillis = null, liveDelayMs = delay) }
    }

    /** Lecture depuis l'enregistrement du différé. */
    private fun startTimeshiftPlayback(buffer: TimeshiftBuffer) {
        playingTimeshift = true
        engine.playTimeshift(buffer)
    }

    /** Retour au direct : fin du différé, de « Depuis le début » ou du replay. */
    fun backToLive() {
        if (restarting || replay != null) {
            leaveReplay()
            host.reload()
            return
        }
        val wasDelayed = state.value.liveDelayMs > 0 || state.value.paused
        val hadRecording = timeshift != null
        stopTimeshift()
        if (!wasDelayed && !hadRecording) return
        val player = engine.current
        if (!hadRecording && player != null) {
            // HLS : le lecteur rejoint le direct dans la fenêtre du serveur.
            player.seekToDefaultPosition()
            player.play()
        } else {
            host.start(forceHls = session.playingAsHls && session.request?.stream?.format != StreamFormat.HLS)
        }
    }

    /** Fin du replay : la chaîne se lit de nouveau en direct (aussi après une recréation de l'écran). */
    private fun leaveReplay() {
        if (replay == null && !restarting) return
        replay = null
        restarting = false
        replayUrlFrom = null
        jumpJob?.cancel()
        pendingJumpSeconds = 0
        resetFastSeek()
        handle[PlayerViewModel.ARG_REPLAY_START] = 0L
        handle[PlayerViewModel.ARG_REPLAY_STOP] = 0L
        replayHls = false
        state.update { it.copy(replay = null, seekable = false, watchedAt = null, pendingJumpSeconds = 0) }
    }

    /** Heure de ce qui est à l'écran dans l'enregistrement en cours. */
    private fun watchedAt(): Long = segmentStart + (engine.current?.currentPosition ?: 0)

    /**
     * Replay ou « Depuis le début » : recule ou avance de [steps] pas (passer la pub avant le
     * programme). Un pas vaut 30 s quand on peut se déplacer dans l'enregistrement (HLS), sinon une
     * minute : l'enregistrement est alors relu à partir de la nouvelle minute (le serveur ne permet
     * pas d'avancer dans le flux continu). Les appuis rapprochés s'additionnent.
     */
    fun jump(steps: Int) {
        if (replayUrlFrom == null) return
        pendingJumpSeconds += steps * state.value.jumpStepSeconds
        state.update { it.copy(pendingJumpSeconds = pendingJumpSeconds) }
        jumpJob?.cancel()
        jumpJob = scope.launch {
            delay(JUMP_DEBOUNCE_MILLIS)
            applyJump()
        }
    }

    private fun applyJump() {
        val urlFrom = replayUrlFrom ?: return
        val programme = replay ?: return
        val seconds = pendingJumpSeconds
        pendingJumpSeconds = 0
        state.update { it.copy(pendingJumpSeconds = 0) }
        if (seconds == 0) return
        val player = engine.current
        // HLS : déplacement dans l'enregistrement, à la seconde près.
        if (replayHls && player != null && player.isCurrentMediaItemSeekable) {
            val duration = player.duration.takeIf { it > 0 } ?: (programme.second - segmentStart)
            val position = (player.currentPosition + seconds * 1_000L).coerceIn(0, (duration - 5_000).coerceAtLeast(0))
            player.seekTo(position)
            state.update { it.copy(watchedAt = segmentStart + position) }
            return
        }
        val now = System.currentTimeMillis()
        val wanted = watchedAt() + seconds * 1_000L
        // « Depuis le début » : avancer jusqu'au direct, c'est le rejoindre.
        if (restarting && wanted >= now - MINUTE_MILLIS) {
            backToLive()
            return
        }
        val latest = if (restarting) now - MINUTE_MILLIS else programme.second - MINUTE_MILLIS
        val target = (wanted.coerceIn(programme.first - MAX_EARLY_MILLIS, latest) / MINUTE_MILLIS) * MINUTE_MILLIS
        val url = urlFrom(target) ?: return
        val playback = session.request ?: return
        segmentStart = target
        session.request = playback.copy(stream = playback.stream.copy(url = url))
        session.backupUrls = Mirrors.alternatives(url, session.serverRoots)
        state.update {
            it.copy(
                watchedAt = target,
                liveDelayMs = if (restarting) (now - target).coerceAtLeast(0) else it.liveDelayMs,
                paused = false,
                pausedAtMillis = null,
            )
        }
        host.start(forceHls = false)
    }

    // --- Avance et retour rapides (x2) ---------------------------------------------------------------

    /**
     * ← → maintenu : avance ([direction] 1) ou retour (−1) rapide x2, dans un replay, « Depuis le
     * début », un film ou un épisode. À rappeler tant que la touche reste enfoncée ; [stopFastSeek]
     * au relâchement. L'avance lit deux fois plus vite ; le retour recule de 2 s par seconde
     * (dans un enregistrement continu, le recul est appliqué au relâchement, à la minute).
     */
    fun fastSeek(direction: Int) {
        fastHeartbeat = SystemClock.uptimeMillis()
        if (direction == 0 || direction == fastDirection) return
        stopFastSeek()
        val player = engine.current ?: return
        if (state.value.isLive && replayUrlFrom == null) return
        // Appuis courts encore en attente : appliqués d'abord.
        if (pendingJumpSeconds != 0) {
            jumpJob?.cancel()
            applyJump()
        }
        if (direction < 0) {
            val current = engine.current ?: player
            rewindInPlace = seeksInPlace(current)
            if (!rewindInPlace && replayUrlFrom == null) return
            if (rewindInPlace) {
                resumeAfterRewind = current.playWhenReady
                rewindTarget = current.currentPosition
                current.pause()
            }
        } else if (state.value.paused) {
            togglePause()
        } else {
            engine.current?.play()
        }
        fastDirection = direction
        state.update { it.copy(fastSeek = direction) }
        fastJob = scope.launch {
            while (true) {
                if (SystemClock.uptimeMillis() - fastHeartbeat > FAST_HEARTBEAT_TIMEOUT_MILLIS) {
                    stopFastSeek()
                    return@launch
                }
                if (direction > 0) fastForwardTick() else rewindTick()
                delay(FAST_TICK_MILLIS)
            }
        }
    }

    /** Touche relâchée : lecture normale à l'endroit atteint. */
    fun stopFastSeek() {
        val direction = fastDirection
        if (direction == 0) return
        val audioOff = fastAudioOff
        val inPlace = rewindInPlace
        val resume = resumeAfterRewind
        resetFastSeek()
        val player = engine.current
        when {
            direction > 0 -> {
                player?.setPlaybackSpeed(1f)
                if (audioOff) player?.setAudioDisabled(false)
                if (restarting && player != null) {
                    val at = watchedAt()
                    state.update { it.copy(watchedAt = at, liveDelayMs = (System.currentTimeMillis() - at).coerceAtLeast(0)) }
                }
            }
            inPlace -> if (resume) player?.play()
            pendingJumpSeconds != 0 -> applyJump()
        }
    }

    private fun resetFastSeek() {
        fastJob?.cancel()
        fastJob = null
        fastDirection = 0
        fastGeneration = -1
        fastAudioOff = false
        rewindInPlace = false
        if (state.value.fastSeek != 0) state.update { it.copy(fastSeek = 0) }
    }

    private fun fastForwardTick() {
        val player = engine.current ?: return
        if (engine.generation != fastGeneration) {
            // Premier passage, ou nouveau flux après un saut : vitesse x2 (et son coupé s'il le fallait).
            fastGeneration = engine.generation
            if (fastAudioOff) player.setAudioDisabled(true)
            player.setPlaybackSpeed(FAST_SPEED)
        } else if (player.playbackParameters.speed != FAST_SPEED && !fastAudioOff) {
            // Son transmis tel quel à l'ampli (Dolby…) : il ne peut pas être accéléré, l'image seule passe en x2.
            fastAudioOff = true
            player.setAudioDisabled(true)
            player.setPlaybackSpeed(FAST_SPEED)
        }
        if (replayUrlFrom != null) {
            val now = System.currentTimeMillis()
            val at = watchedAt()
            // « Depuis le début » : le direct est rattrapé.
            if (restarting && at >= now - MINUTE_MILLIS) {
                resetFastSeek()
                backToLive()
                return
            }
            state.update {
                it.copy(watchedAt = at, liveDelayMs = if (restarting) (now - at).coerceAtLeast(0) else it.liveDelayMs)
            }
        }
    }

    private fun rewindTick() {
        if (rewindInPlace) {
            val player = engine.current ?: return
            rewindTarget = (rewindTarget - FAST_TICK_MILLIS * FAST_SPEED.toLong()).coerceAtLeast(0)
            player.seekTo(rewindTarget)
            if (replayUrlFrom != null) state.update { it.copy(watchedAt = segmentStart + rewindTarget) }
            return
        }
        // Enregistrement continu : le recul s'additionne, il est appliqué au relâchement.
        val programme = replay ?: return
        val step = (FAST_TICK_MILLIS * FAST_SPEED.toLong() / 1_000).toInt()
        if (watchedAt() + (pendingJumpSeconds - step) * 1_000L < programme.first - MAX_EARLY_MILLIS) return
        pendingJumpSeconds -= step
        state.update { it.copy(pendingJumpSeconds = pendingJumpSeconds) }
    }

    /** On peut se déplacer dans le flux lu (film, épisode, replay HLS) sans le rouvrir. */
    private fun seeksInPlace(player: ExoPlayer): Boolean =
        player.isCurrentMediaItemSeekable && (replayUrlFrom == null || replayHls)

    /** Arrête l'enregistrement du différé et oublie le retard sur le direct. */
    fun stopTimeshift() {
        recorder?.stop()
        recorder = null
        timeshift?.release()
        timeshift = null
        playingTimeshift = false
        state.update { it.copy(paused = false, pausedAtMillis = null, liveDelayMs = 0) }
    }

    private fun timeshiftLimit(): Long = minOf(TIMESHIFT_MAX_BYTES, context.cacheDir.usableSpace / 2)

    /** Fin du flux. @return vrai si c'était un enregistrement (différé, « Depuis le début ») : retour au direct. */
    fun onEnded(): Boolean {
        // « Depuis le début » : l'enregistrement a rattrapé le direct.
        if (playingTimeshift || restarting) {
            // Fin de l'enregistrement (limite atteinte ou flux arrêté) : retour au direct.
            backToLive()
            return true
        }
        return false
    }

    private companion object {
        const val TAG = "Player"
        const val TIMESHIFT_FILE = "timeshift.ts"
        const val TIMESHIFT_MAX_BYTES = 4L * 1024 * 1024 * 1024
        val RECORDABLE_FORMATS = setOf(StreamFormat.PROGRESSIVE, StreamFormat.UNKNOWN)
        const val MINUTE_MILLIS = 60_000L
        const val MINUTE_SECONDS = 60

        /** Pas de ← → dans un replay où l'on peut se déplacer (HLS). */
        const val FINE_JUMP_SECONDS = 30

        /** Serveurs (playlists) qui ne proposent pas le replay en HLS : pas de nouvel essai jusqu'au redémarrage. */
        val hlsReplayRefused: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet<Long>())

        /** Appuis rapprochés sur ← → : additionnés pendant ce délai. */
        const val JUMP_DEBOUNCE_MILLIS = 900L

        /** Avance et retour rapides : x2. */
        const val FAST_SPEED = 2f
        const val FAST_TICK_MILLIS = 500L

        /** Sans répétition de la touche maintenue pendant ce délai, l'avance rapide s'arrête. */
        const val FAST_HEARTBEAT_TIMEOUT_MILLIS = 1_500L

        /** Recul possible avant le début prévu (programme commencé en avance). */
        const val MAX_EARLY_MILLIS = 15 * MINUTE_MILLIS
    }
}
