package tv.nextv.app.ui.player

import android.app.ActivityManager
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
import tv.nextv.app.player.LiveLoadControl
import tv.nextv.app.player.PreviewHandoff
import tv.nextv.app.player.StreamTaps
import tv.nextv.app.player.applyTrackPreferences
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

        /**
         * Direct repris en pleine lecture (aperçu de l'accueil) : la suite n'est pas une coupure de la connexion
         * tant que la première image n'est pas arrivée sur ce nouvel écran ([onReady] suit alors).
         */
        fun onContinued()
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
        val film: Boolean,
    )

    /** Le flux en cours est un film ou un épisode (plus d'avance en mémoire qu'un direct). */
    private var film = false

    // Direct en cours, tel qu'il peut être rendu à l'aperçu de l'accueil (null : flux qui ne peut pas l'être).
    private var liveTaps: StreamTaps? = null
    private var liveRequest: PlaybackRequest? = null

    /** Mémoire tampon du lecteur en cours, s'il lit un direct (réglable pour l'aperçu). */
    private var liveBuffer: LiveLoadControl? = null

    /** Lecteur réglé comme celui de l'aperçu (son décodé, décodeurs par défaut) : il peut lui être rendu. */
    private var previewCompatible = false

    /** Aperçu de l'accueil montré dès l'ouverture de l'écran, pas encore repris ([play] le reprend ou le libère). */
    private var pendingHandoff: PreviewHandoff? = null

    /** Direct repris : la première image sur ce nouvel écran termine son démarrage. */
    private var awaitingFirstFrame = false

    /** Images perdues ignorées jusqu'à cette heure (changement d'écran d'un direct repris). */
    private var dropsIgnoredUntil = 0L

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

    /**
     * Option « baisser le son des pubs », lue par le traitement du son du lecteur. Celle du lecteur de l'aperçu quand
     * le plein écran le reprend (le traitement est déjà branché sur ce lecteur).
     */
    var adVolume = AdVolumeControl()
        private set

    /** Réglage « baisser le son des pubs ». */
    private var adVolumeEnabled = false

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
                adVolumeEnabled = it
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
            if (awaitingFirstFrame) {
                awaitingFirstFrame = false
                if (mutablePlayer.value?.playbackState == Player.STATE_READY) events.onReady()
            }
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
     * @param film film ou épisode, et non un direct.
     * @return [PreviewStart.TAKEN] si le flux continue sur le lecteur de l'aperçu de l'accueil (déjà en lecture), une
     * autre valeur s'il y avait un aperçu à reprendre mais qu'il a fallu rouvrir le flux, `null` sans aperçu à reprendre.
     */
    fun play(request: PlaybackRequest, forceHls: Boolean, startPositionMs: Long, record: Boolean = false, film: Boolean = false): PreviewStart? {
        this.film = film
        // Direct continu (MPEG-TS…) : les dernières secondes sont gardées en mémoire pour revenir en arrière.
        // Le direct précédent cède l'anneau avant que le nouveau n'y écrive.
        liveRecording?.retire()
        val recording = if (record && !forceHls && request.stream.format in RECORDABLE_FORMATS) LiveRecording(liveTail()) else null
        liveRecording = recording
        // Chaîne ouverte depuis son aperçu : il joue déjà ce flux, la lecture continue dessus.
        val handoff = pendingHandoff?.also { pendingHandoff = null } ?: container.previewHandoff.take()
        var previewStart: PreviewStart? = null
        if (handoff != null) {
            val sameStream = !film && !forceHls && startPositionMs == 0L && handoff.request.stream.url == request.stream.url
            if (sameStream && adopt(handoff, request, recording)) return PreviewStart.TAKEN
            container.logger.info(TAG, if (sameStream) "Aperçu en erreur : flux rouvert" else "Aperçu d'une autre version : flux rouvert")
            previewStart = if (sameStream) PreviewStart.NOT_PLAYING else PreviewStart.OTHER_VERSION
            // Autre flux (autre version de la chaîne, serveur de secours…) : sa connexion est fermée d'abord.
            if (mutablePlayer.value === handoff.player) mutablePlayer.value = null
            handoff.player.release()
        }
        if (film) {
            open(container.playerFactory.mediaSource(request, forceHls, received), startPositionMs)
        } else {
            // Direct : mesure du débit et mémoire du direct branchées de façon à pouvoir les retirer, si le flux
            // est rendu à l'aperçu de l'accueil au retour.
            val taps = StreamTaps().apply {
                listener = received
                this.recording = recording
            }
            open(container.playerFactory.mediaSource(request, forceHls, taps = taps), startPositionMs)
            liveTaps = taps
            liveRequest = request
        }
        return previewStart
    }

    /** Chaîne ouverte depuis l'aperçu de l'accueil : ce qu'il est advenu de son flux (infos de lecture). */
    fun notePreviewStart(start: PreviewStart) {
        updateInfo { it.copy(previewStart = start) }
    }

    /**
     * Chaîne [channelId] ouverte depuis son aperçu : le lecteur de l'aperçu est montré tout de suite, au son du
     * plein écran, sans attendre que la chaîne soit chargée ([play] le reprend alors, ou le libère).
     */
    fun showPreviewHandoff(channelId: Long) {
        val handoff = container.previewHandoff.takeFor(channelId, toPreview = false) ?: return
        val player = handoff.player
        if (player.playerError != null) {
            player.release()
            return
        }
        pendingHandoff = handoff
        handoff.setPreview(false)
        container.playerFactory.takeSound(player)
        player.volume = 1f
        mutablePlayer.value = player
    }

    /**
     * Retour à l'accueil sur une chaîne en direct : le lecteur, en marche, est confié à l'aperçu ([ids] : chaînes
     * qu'il montre), qui continue sur la même connexion. `null` si ce flux ne peut pas l'être (film, différé, réglages
     * du son ou des décodeurs que l'aperçu n'a pas) : il est alors fermé comme avant.
     */
    fun handBack(ids: Set<Long>, playingId: Long?): PreviewHandoff? {
        val player = exoPlayer ?: return null
        val taps = liveTaps ?: return null
        val request = liveRequest ?: return null
        val buffer = liveBuffer ?: return null
        if (film || !previewCompatible || mutablePlayer.value !== player) return null
        if (player.playerError != null || !player.playWhenReady) return null
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return null
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        // Mesure du débit et mémoire du direct de cet écran : retirées, la connexion continue sans elles.
        taps.listener = null
        taps.recording = null
        liveRecording?.retire()
        container.playerFactory.releaseSound(player)
        val info = state.value.info
        // Le traitement « baisser le son des pubs » reste branché sur ce lecteur : coupé dans l'aperçu ; cet écran en
        // prend un nouveau pour un éventuel lecteur suivant.
        val control = adVolume.also { it.enabled = false }
        adVolume = AdVolumeControl().also { it.enabled = adVolumeEnabled }
        val handoff = PreviewHandoff(player, request, taps, buffer, control, ids, toPreview = true, playingId, info.videoDecoder, info.audioDecoder)
        handoff.setPreview(true)
        exoPlayer = null
        exoPlayerConfig = null
        forgetLive()
        mutablePlayer.value = null
        container.logger.debug(TAG, "retour à l'accueil → direct confié à l'aperçu")
        return handoff
    }

    private fun forgetLive() {
        liveTaps = null
        liveRequest = null
        liveBuffer = null
        previewCompatible = false
        awaitingFirstFrame = false
    }

    /** Direct en cours gardé pour revenir en arrière (`null` : pas un direct continu). */
    var liveRecording: LiveRecording? = null
        private set

    /**
     * Mémoire des dernières secondes du direct, allouée au premier direct continu puis réutilisée à chaque
     * chaîne : un bloc neuf de 32 Mo à chaque zapping ralentissait le zapping et pouvait faire fermer
     * l'application faute de mémoire sur les petits appareils.
     */
    private var liveTail: ByteArray? = null

    private fun liveTail(): ByteArray = liveTail ?: ByteArray(liveTailBytes()).also { liveTail = it }

    /** Petit appareil (clé TV d'entrée de gamme) : moitié moins de secondes gardées. */
    private fun liveTailBytes(): Int {
        val manager = container.appContext.getSystemService(ActivityManager::class.java)
        val small = manager == null || manager.isLowRamDevice || manager.largeMemoryClass <= SMALL_HEAP_MB
        return if (small) LIVE_TAIL_BYTES / 2 else LIVE_TAIL_BYTES
    }

    /**
     * Relit le direct gardé ([recording], déjà passé sur le disque) à partir de [position] (octets du flux).
     * [play] faux : prêt, en pause (mise en pause du direct).
     */
    fun playLiveRecording(recording: LiveRecording, position: Long, play: Boolean) {
        val disk = recording.disk ?: return
        open(container.playerFactory.timeshiftSource(disk, (position - recording.diskBase).coerceAtLeast(0)), startPositionMs = 0)
        liveTaps = null
        liveRequest = null
        if (!play) exoPlayer?.playWhenReady = false
    }

    /** Lit l'enregistrement du différé [buffer]. */
    fun playTimeshift(buffer: TimeshiftBuffer) {
        open(container.playerFactory.timeshiftSource(buffer), startPositionMs = 0)
        liveTaps = null
        liveRequest = null
    }

    private fun open(source: MediaSource, startPositionMs: Long) {
        // Ancien flux arrêté avant d'ouvrir le nouveau : beaucoup d'abonnements n'acceptent qu'une connexion.
        exoPlayer?.stop()
        val player = reusablePlayer()
        awaitingFirstFrame = false
        liveTaps = null
        liveRequest = null
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

    /**
     * Reprend le lecteur de l'aperçu de l'accueil, qui lit déjà le flux demandé : pas de nouvelle connexion ni
     * de nouvelle attente d'image. Sa mémoire tampon passe à celle du plein écran. Il sert à ce flux seulement (créé
     * par l'aperçu, sans le traitement du son du plein écran) : le flux suivant aura un lecteur neuf. Faux s'il ne
     * joue plus (le flux est alors ouvert normalement).
     */
    private fun adopt(handoff: PreviewHandoff, request: PlaybackRequest, recording: LiveRecording?): Boolean {
        val player = handoff.player
        val playbackState = player.playbackState
        if (player.playerError != null || (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING)) return false
        // Lecteur précédent de cet écran : rendu. Celui de l'aperçu, peut-être déjà affiché, reste à l'écran.
        exoPlayer?.takeIf { it !== player }?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        generation++
        // « Baisser le son des pubs » : le traitement du son de ce lecteur, réglé comme en plein écran.
        adVolume = handoff.adVolume.also { it.enabled = adVolumeEnabled }
        adVolume.newStream()
        receivedRate.reset()
        state.update { it.copy(error = null, info = PlaybackInfo(), audioProblem = null, unsupportedAudio = null, receivedMbps = null) }
        audioGivenUp = false
        // Mesure du débit et mémoire du direct (retour en arrière) : branchées sur la connexion déjà ouverte.
        handoff.taps.listener = received
        handoff.taps.recording = recording
        // Mémoire tampon du plein écran (l'aperçu ne lisait que quelques secondes d'avance), reprises normales.
        handoff.setPreview(false)
        liveTaps = handoff.taps
        liveRequest = request
        liveBuffer = handoff.buffer
        previewCompatible = true
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        exoPlayer = player
        exoPlayerConfig = null
        // Plein écran : le son au niveau normal, demandé au système. Les pistes restent celles de l'aperçu, choisies
        // avec les langues du plein écran : les changer maintenant pourrait recharger le direct (coupure).
        container.playerFactory.takeSound(player)
        player.volume = 1f
        player.setPlaybackSpeed(1f)
        player.playWhenReady = true
        mutablePlayer.value = player
        // Images perdues pendant l'aperçu, et au changement d'écran : pas une raison de baisser la qualité.
        player.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            countedCounters = counters
            countedDroppedFrames = counters.droppedBufferCount
        }
        dropsIgnoredUntil = SystemClock.elapsedRealtime() + SETTLE_MILLIS
        // Ce que le lecteur a déjà annoncé pendant l'aperçu : pistes, formats, décodeurs, état.
        listener.onTracksChanged(player.currentTracks)
        updateInfo { info ->
            val video = player.videoFormat
            val audio = player.audioFormat
            info.copy(
                videoMimeType = video?.sampleMimeType,
                width = video?.width?.takeIf { it > 0 },
                height = video?.height?.takeIf { it > 0 },
                frameRate = video?.frameRate?.takeIf { it > 0f },
                videoDecoder = handoff.videoDecoder,
                audioMimeType = audio?.sampleMimeType,
                audioChannels = audio?.channelCount?.takeIf { it > 0 },
                audioDecoder = handoff.audioDecoder,
                previewStart = PreviewStart.TAKEN,
            )
        }
        listener.onPlaybackStateChanged(playbackState)
        // Démarrage sur ce nouvel écran : terminé à sa première image (pas une coupure avant elle).
        events.onContinued()
        awaitingFirstFrame = true
        zapStartedAt = 0L
        container.logger.debug(TAG, "zapping → aperçu repris, sans rouvrir le flux")
        return true
    }

    /** Le lecteur actuel, ou un nouveau si le réglage du son direct ou du décodeur préféré a changé. */
    private fun reusablePlayer(): ExoPlayer {
        val config = PlayerConfig(audioPassthrough, preferredDecoders, excludedDecoders, softwareAudio, film)
        exoPlayer?.takeIf { exoPlayerConfig == config }?.let { return it }
        release()
        // Direct : mémoire tampon réglable, pour le cas où il est rendu à l'aperçu de l'accueil.
        val buffer = if (film) null else container.playerFactory.liveLoadControl(preview = false)
        return container.playerFactory.create(
            bandwidthMeter, preferredDecoders, adVolume, passthrough = audioPassthrough, excludedDecoders = excludedDecoders, softwareAudio = softwareAudio,
            film = film, liveBuffer = buffer,
        ).apply {
            addListener(listener)
            addAnalyticsListener(analytics)
            exoPlayer = this
            exoPlayerConfig = config
            liveBuffer = buffer
            previewCompatible = !film && !audioPassthrough && !softwareAudio && excludedDecoders.isEmpty()
        }
    }

    /** Arrête la lecture et ferme la connexion ; le lecteur est gardé pour le flux suivant. */
    fun stop() {
        releasePendingHandoff()
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        liveTaps = null
        liveRequest = null
        awaitingFirstFrame = false
        mutablePlayer.value = null
    }

    /** Libère le lecteur (écran du lecteur fermé). */
    fun release() {
        releasePendingHandoff()
        exoPlayer?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        exoPlayer = null
        exoPlayerConfig = null
        forgetLive()
        mutablePlayer.value = null
    }

    /** Aperçu montré à l'ouverture mais pas repris (chaîne introuvable, écran quitté avant la lecture) : fermé. */
    private fun releasePendingHandoff() {
        val handoff = pendingHandoff ?: return
        pendingHandoff = null
        if (mutablePlayer.value === handoff.player) mutablePlayer.value = null
        handoff.player.release()
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
        if (SystemClock.elapsedRealtime() < dropsIgnoredUntil) return 0
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
    private fun ExoPlayer.resetTrackSelection() = applyTrackPreferences(audioLanguage, subtitleLanguage)

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

        /** Changement d'écran d'un direct repris : images perdues ignorées pendant ce temps. */
        const val SETTLE_MILLIS = 4_000L
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

/** Mémoire maximale de l'application (avec `largeHeap`) en dessous de laquelle l'appareil est traité comme petit. */
private const val SMALL_HEAP_MB = 256
