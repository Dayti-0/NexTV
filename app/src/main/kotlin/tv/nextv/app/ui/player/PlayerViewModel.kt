package tv.nextv.app.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.text.format.DateUtils
import java.io.File
import kotlin.math.abs
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.settings.AspectMode
import tv.nextv.app.player.AdVolumeControl
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.toStream
import tv.nextv.app.player.TimeshiftBuffer
import tv.nextv.app.player.TimeshiftRecorder
import tv.nextv.app.player.VideoDecoders
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.home.Category
import tv.nextv.core.football.Broadcast
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.FootballDataResult
import tv.nextv.core.football.FootballMatches
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.Mirrors
import tv.nextv.core.source.XtreamAccount
import tv.nextv.core.stream.QualityOption
import tv.nextv.core.stream.QualitySwitcher
import tv.nextv.core.stream.QualityTarget
import java.util.Locale

private const val WORKING_QUALITY_MILLIS = 60_000L

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    private val context: Context,
) : ViewModel() {

    private var channelId: Long = checkNotNull(handle.get<Long>(ARG_CHANNEL_ID))

    /**
     * Replay d'un programme (début, fin) ; `null` : lecture normale. Oublié au changement de chaîne
     * et au retour au direct.
     */
    private var replay: Pair<Long, Long>? = handle.get<Long>(ARG_REPLAY_START)?.takeIf { it > 0 }
        ?.let { start -> handle.get<Long>(ARG_REPLAY_STOP)?.takeIf { it > start }?.let { start to it } }

    /**
     * « Depuis le début » d'un programme en cours : lu depuis l'enregistrement du serveur, mais comme
     * le direct (bandeau, menu des chaînes, zapping), avec le retard affiché et → pour le rattraper.
     */
    private var restarting = false

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
    /** Lecteur auquel la vitesse x2 a été demandée (un saut dans un enregistrement continu en crée un autre). */
    private var fastPlayer: ExoPlayer? = null
    /** Son coupé pendant l'avance rapide : sortie audio qui ne suit pas la vitesse (son transmis tel quel à l'ampli). */
    private var fastAudioOff = false
    /** Retour rapide par déplacements dans le flux : position visée, et lecture à reprendre ensuite. */
    private var rewindInPlace = false
    private var rewindTarget = 0L
    private var resumeAfterRewind = false
    private val resources = context.resources

    /** Chaîne, version et flux en cours, partagés par les parties du lecteur. */
    private val session = PlaybackSession()

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()


    private val sourceId = MutableStateFlow<Long?>(null)

    // Menu des chaînes (« Retour ») : catégories, liste, programme de la chaîne sélectionnée.
    private val channelMenu = ChannelMenuController(container, viewModelScope, sourceId)
    val menu: StateFlow<ChannelMenuState> = channelMenu.menu
    val programmeSheet: StateFlow<ProgrammeSheetState?> = channelMenu.programmeSheet
    val menuProgramme: StateFlow<MenuProgramme?> = channelMenu.menuProgramme
    val menuGroups: StateFlow<List<GroupRow>> = channelMenu.groups
    val menuChannels: Flow<PagingData<MenuChannel>> = channelMenu.channels
    val menuFavorites: StateFlow<List<ChannelListRow>> = channelMenu.favorites

    /** Chaîne sélectionnée dans le menu : son programme s'affiche à côté. */
    fun focusMenuChannel(row: ChannelListRow) = channelMenu.focus(row)

    private var resumeOnStart = true
    private var multicastLock: WifiManager.MulticastLock? = null
    private var loadJob: Job? = null
    private var switcher: QualitySwitcher? = null
    private var autoQuality = true
    private var preferredDecoders: Map<String, String> = emptyMap()

    /** Langues choisies en dernier (audio ; sous-titres, `null` : désactivés), proposées en premier. */
    private var audioLanguage: String? = null
    private var subtitleLanguage: String? = null

    /** Son Dolby / DTS envoyé tel quel à l'ampli (sinon décodé par l'appareil). */
    private var audioPassthrough = false

    /** Versions 4K écartées (réglage « Masquer la 4K »). */
    private var hideUhd = false

    /** Option « baisser le son des pubs », lue par le traitement du son de chaque lecteur. */
    private val adVolume = AdVolumeControl()

    /** Format de l'image (réglage commun à toutes les lectures). */
    val aspectMode: StateFlow<AspectMode> = container.settings.aspectMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AspectMode.FIT)

    // Images perdues déjà prises en compte, pour le lecteur courant.
    private var countedPlayer: ExoPlayer? = null
    private var countedDroppedFrames = 0

    /** Qualité que l'appareil n'a pas su décoder (images perdues), retenue d'une chaîne à l'autre. */
    private var decoderCeilingRank: Int? = null

    // Différé du direct.
    private var timeshift: TimeshiftBuffer? = null
    private var recorder: TimeshiftRecorder? = null
    private var playingTimeshift = false

    /** Dernier serveur qui a répondu (playlist, racine) : les chaînes suivantes commencent par lui. */
    private var workingServer: Pair<Long, String>? = null

    // Reprise de lecture des films et épisodes.
    private var resumeMode: String = handle.get<String>(ARG_RESUME) ?: RESUME_ASK
    private var pendingResume: Pair<ChannelEntity, ProgressEntity>? = null
    private val bandwidthMeter: DefaultBandwidthMeter = container.playerFactory.newBandwidthMeter()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> switcher?.onBufferingStarted()
                Player.STATE_READY -> {
                    switcher?.onReady()
                    rememberServer()
                }
                Player.STATE_ENDED -> onEnded()
            }
            mutableState.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
            mutableState.update { it.copy(tracks = describeTracks(tracks), audioProblem = audioProblemOf(tracks)) }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            mutableState.update { it.copy(bandwidthMbps = bitrateEstimate / 1_000_000.0) }
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
     * Images perdues depuis le dernier passage, lues sur les compteurs du décodeur (le lecteur ne les
     * signale lui-même que par lots de 50, trop tard pour réagir vite).
     */
    private fun newDroppedFrames(): Int {
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
        mutableState.update { state ->
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

    init {
        viewModelScope.launch {
            container.settings.autoQuality.collect { autoQuality = it }
        }

        viewModelScope.launch {
            container.settings.preferredDecoders.collect { preferredDecoders = it }
        }
        viewModelScope.launch {
            container.settings.audioLanguage.collect { audioLanguage = it }
        }
        viewModelScope.launch {
            container.settings.audioPassthrough.collect { audioPassthrough = it }
        }
        viewModelScope.launch {
            container.settings.hideUhd.collect { hideUhd = it }
        }
        viewModelScope.launch {
            container.settings.subtitleLanguage.collect { subtitleLanguage = it }
        }
        viewModelScope.launch {
            container.settings.lowerAdVolume.collect {
                adVolume.enabled = it
                mutableState.update { state -> state.copy(lowerAdVolume = it) }
            }
        }
        load(channelId)
        viewModelScope.launch {
            while (true) {
                refreshProgrammes()
                delay(PROGRAMME_REFRESH_MILLIS)
            }
        }
        // Surveillance de la lecture (2 fois par seconde) : blocage, images perdues, retour à une meilleure qualité.
        viewModelScope.launch {
            while (true) {
                delay(QUALITY_CHECK_MILLIS)
                if (replayUrlFrom != null && mutablePlayer.value != null && pendingJumpSeconds == 0) {
                    val at = watchedAt()
                    if (abs(at - (state.value.watchedAt ?: 0)) >= 1_000) mutableState.update { it.copy(watchedAt = at) }
                }
                val adMuted = adVolume.enabled && adVolume.attenuating
                if (state.value.adMuted != adMuted) mutableState.update { it.copy(adMuted = adMuted) }
                val dropped = newDroppedFrames()
                // Pause ou différé : changer de version ramènerait au direct.
                if (timeshift != null || state.value.paused || state.value.liveDelayMs > 0) continue
                // L'appareil ne suit pas cette qualité (4K sur une petite box…) : version inférieure si elle existe.
                if (dropped > 0) {
                    val decision = switcher?.onDroppedFrames(dropped)
                    if (decision is QualitySwitcher.Decision.Switch) {
                        decoderCeilingRank = switcher?.decoderCeilingRank
                        switchQuality(decision)
                        continue
                    }
                }
                val estimate = state.value.bandwidthMbps?.let { (it * 1_000_000).toLong() }
                val decision = switcher?.evaluate(estimate)
                if (decision is QualitySwitcher.Decision.Switch) switchQuality(decision)
            }
        }
        // Position des films et épisodes, pour la reprise de lecture.
        viewModelScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_MILLIS)
                saveProgress()
            }
        }
    }

    private var workingQualityJob: Job? = null

    /** Version qui joue sans erreur une minute durant : la chaîne commencera par elle la prochaine fois. */
    private fun rememberWhenStable(variant: ChannelEntity) {
        workingQualityJob?.cancel()
        val quality = variant.quality
        if (switcher == null || quality == null || variant.kind != ContentKind.LIVE.name || variant.variantKey.isEmpty()) return
        workingQualityJob = viewModelScope.launch {
            delay(WORKING_QUALITY_MILLIS)
            if (session.playing?.id == variant.id && state.value.error == null) {
                container.settings.rememberWorkingQuality(variant.sourceId, variant.variantKey, quality)
            }
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
        choice.language?.let { viewModelScope.launch { container.settings.setAudioLanguage(it) } }
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
        viewModelScope.launch { container.settings.setSubtitleLanguage(choice?.language) }
    }

    fun setLowerAdVolume(enabled: Boolean) {
        viewModelScope.launch { container.settings.setLowerAdVolume(enabled) }
    }

    fun setAspectMode(mode: AspectMode) {
        viewModelScope.launch { container.settings.setAspectMode(mode) }
    }

    /** Chaîne regardée juste avant celle-ci (touche « chaîne précédente », ⏪). */
    fun previousChannel() {
        val current = session.listed ?: return
        if (current.kind != ContentKind.LIVE.name) return
        viewModelScope.launch {
            val key = container.settings.recentChannels(current.sourceId).first().firstOrNull { it != current.itemKey } ?: return@launch
            val id = container.channelDao.primaryIdByKey(current.sourceId, key) ?: return@launch
            load(id)
        }
    }

    /** Chaîne suivante (+1) ou précédente (-1) de la playlist, en boucle. */
    fun zap(direction: Int) {
        val current = session.listed ?: return
        if (current.kind != ContentKind.LIVE.name) return
        viewModelScope.launch {
            val dao = container.channelDao
            // Ordre de la liste « Toutes » : depuis la place de la chaîne en cours dans cette liste.
            val position = current.variantKey.takeIf { it.isNotEmpty() }?.let { dao.listedPosition(current.sourceId, it) } ?: current.position
            val target = if (direction > 0) {
                dao.nextChannelId(current.sourceId, position) ?: dao.nextChannelId(current.sourceId, -1)
            } else {
                dao.previousChannelId(current.sourceId, position) ?: dao.previousChannelId(current.sourceId, Int.MAX_VALUE)
            }
            if (target != null && target != current.id) load(target)
        }
    }

    fun openMenu() {
        val current = session.listed ?: return
        channelMenu.open(current)
    }

    fun closeMenu() = channelMenu.close()

    fun selectMenuCategory(category: Category) = channelMenu.selectCategory(category)

    /** Programme en cours (ou le prochain) d'une chaîne du menu, avec sa description. */
    fun showProgramme(row: ChannelListRow) = channelMenu.showProgramme(row)

    fun hideProgramme() = channelMenu.hideProgramme()

    fun playFromMenu(id: Long) {
        closeMenu()
        if (id != session.listed?.id) load(id)
    }

    private fun load(id: Long) {
        // Autre chaîne : fin du replay (le programme revu appartenait à la chaîne quittée).
        if (id != channelId) leaveReplay()
        resetFastSeek()
        stopTimeshift()
        channelId = id
        handle[ARG_CHANNEL_ID] = id
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadChannel(id) }
    }

    private suspend fun loadChannel(id: Long) {
        val dao = container.channelDao
        val requested = dao.get(id)
        if (requested == null) {
            releasePlayer()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_channel_missing)) }
            return
        }
        val isLive = requested.kind == ContentKind.LIVE.name
        // Pour l'affichage et le zapping : la meilleure version de la chaîne.
        val primary = if (isLive && requested.variantKey.isNotEmpty()) dao.primaryOf(requested.sourceId, requested.variantKey) ?: requested else requested
        session.channel = primary
        session.listed = requested
        val replayed = replay
        if (replayed != null && isLive) {
            // Programme encore en cours : « Depuis le début », avec le bandeau du direct.
            playReplay(requested, primary, replayed, restart = replayed.second > System.currentTimeMillis())
            return
        }
        restarting = false
        // Chaînes récentes de l'accueil.
        if (isLive) container.settings.addRecentChannel(primary.sourceId, primary.itemKey)
        sourceId.value = primary.sourceId
        val source = container.sourceRepository.getSource(primary.sourceId)

        val variants = if (isLive && primary.variantKey.isNotEmpty()) dao.variants(primary.sourceId, primary.variantKey) else emptyList()
        // 4K écartée quand l'écran ou le décodeur ne la suivent pas (image saccadée), ou masquée dans les réglages.
        val uhdAllowed = !hideUhd && container.playerFactory.supportsUhd
        val options = variants.filter { it.streamSupport != StreamSupport.UNSUPPORTED.name }
            .map { QualityOption(it.id, StreamQuality.entries.firstOrNull { q -> q.name == it.quality }) }
            .let { all -> if (uhdAllowed) all else all.filter { it.quality != StreamQuality.UHD }.ifEmpty { all } }
        // Chaîne ouverte sur sa meilleure version : départ par la qualité qui a fonctionné la dernière fois.
        val remembered = if (autoQuality && requested.isPrimary && options.size > 1) {
            container.settings.workingQuality(primary.sourceId, primary.variantKey)?.let { name -> StreamQuality.entries.firstOrNull { it.name == name } }
        } else {
            null
        }
        val rememberedStart = remembered?.let { quality ->
            val rank = StreamQuality.rankOf(quality)
            options.sortedByDescending { StreamQuality.rankOf(it.quality) }.firstOrNull { StreamQuality.rankOf(it.quality) <= rank }?.channelId
        }
        val preferredId = rememberedStart
            ?: if (options.any { it.channelId == requested.id }) requested.id else options.firstOrNull()?.channelId ?: requested.id
        // Qualité visée (réglages) : au plus HD, ou SD pour économiser la connexion.
        val qualityTarget = container.settings.qualityTarget.first()
        val startId = if (options.size > 1) qualityTarget.start(options, preferredId) else preferredId
        switcher = if (options.size > 1) {
            QualitySwitcher(
                options,
                startId,
                enabled = autoQuality,
                clock = System::currentTimeMillis,
                decoderCeilingRank = decoderCeilingRank,
                maxRank = qualityTarget.maxRank(options),
            )
        } else {
            null
        }

        mutableState.update {
            it.copy(
                channelName = primary.title.ifBlank { primary.name },
                channelNumber = primary.channelNumber,
                logoUrl = primary.logoUrl,
                sourceName = source?.name.orEmpty(),
                isLive = isLive,
                now = null,
                next = null,
                match = null,
                error = null,
                qualityNotice = null,
                buffering = true,
                channelVersion = it.channelVersion + 1,
            )
        }
        refreshProgrammes()
        if (!isLive) mutableState.update { it.copy(vod = vodInfo(requested), upNext = null) }
        if (isLive) session.startPositionMs = 0
        if (!isLive && handleResume(requested)) return
        val firstId = switcher?.current?.channelId ?: startId
        val variant = if (firstId == requested.id) requested else dao.get(firstId) ?: requested
        playVariant(variant, notice = null)
    }

    /**
     * Programme lu depuis l'enregistrement du serveur Xtream : un programme passé avec les commandes
     * d'un film, ou ([restart]) le programme en cours depuis son début, présenté comme le direct en
     * différé.
     */
    private suspend fun playReplay(requested: ChannelEntity, primary: ChannelEntity, programme: Pair<Long, Long>, restart: Boolean) {
        val (start, stop) = programme
        switcher = null
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
            sourceId.value = primary.sourceId
            mutableState.update {
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
            refreshProgrammes()
        } else {
            mutableState.update {
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
        mutableState.update {
            it.copy(seekable = urlFrom != null, watchedAt = start, pendingJumpSeconds = 0, jumpStepSeconds = if (replayHls) FINE_JUMP_SECONDS else MINUTE_SECONDS)
        }
        val url = hlsUrl ?: urlFrom?.invoke(start)
        if (source == null || url == null) {
            releasePlayer()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_replay_unavailable)) }
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
        start(forceHls = false)
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
        mutableState.update { it.copy(jumpStepSeconds = MINUTE_SECONDS) }
        start(forceHls = false)
        return true
    }

    /** Lance la lecture d'une version précise de la chaîne. */
    private suspend fun playVariant(variant: ChannelEntity, notice: String?) {
        stopTimeshift()
        session.playing = variant
        rememberWhenStable(variant)
        val stream = variant.toStream()
        mutableState.update {
            it.copy(
                notices = stream.issues.map { issue -> resources.describe(issue) },
                quality = StreamQuality.entries.firstOrNull { q -> q.name == variant.quality }?.label,
                qualityNotice = notice,
                error = null,
            )
        }
        if (stream.support == StreamSupport.UNSUPPORTED) {
            releasePlayer()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.stream_unsupported)) }
            return
        }
        val source = container.sourceRepository.getSource(variant.sourceId)
        session.serverRoots = source?.let(::serverRootsOf).orEmpty()
        val url = workingServer?.takeIf { it.first == variant.sourceId }
            ?.let { (_, root) -> stream.url.takeIf { Mirrors.alternatives(it, session.serverRoots).isNotEmpty() }?.let { Mirrors.rewrite(it, root) } }
            ?: stream.url
        session.backupUrls = Mirrors.alternatives(url, session.serverRoots)
        session.request = PlaybackRequest(
            stream = stream.copy(url = url),
            userAgent = source?.userAgent,
            credentials = container.sourceRepository.credentialsFor(variant.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        if (stream.format == StreamFormat.UDP) acquireMulticastLock() else releaseMulticastLock()
        session.triedHlsFallback = false
        start(forceHls = false)
    }

    private fun switchQuality(decision: QualitySwitcher.Decision.Switch) {
        val notice = resources.getString(
            when (decision.reason) {
                QualitySwitcher.Reason.UPGRADE -> R.string.player_quality_up
                QualitySwitcher.Reason.DECODER -> R.string.player_quality_decoder
                QualitySwitcher.Reason.ERROR, QualitySwitcher.Reason.BUFFERING -> R.string.player_quality_down
            },
            decision.option.quality?.label ?: resources.getString(R.string.player_quality_unknown),
        )
        container.logger.info(TAG, "Qualité ${decision.reason} → ${decision.option.quality}")
        viewModelScope.launch {
            val variant = container.channelDao.get(decision.option.channelId) ?: return@launch
            playVariant(variant, notice)
        }
    }

    private suspend fun refreshProgrammes() {
        val current = session.channel ?: return
        if (current.kind != ContentKind.LIVE.name || (replay != null && !restarting)) return
        // En différé : le programme à l'écran, pas celui du direct.
        val shown = state.value
        val now = System.currentTimeMillis() - shown.liveDelayMs - (shown.pausedAtMillis?.let { System.currentTimeMillis() - it } ?: 0)
        val upcoming = container.programmeDao.upcoming(current.id, now, limit = 2)
            .map { PlayerProgramme(it.title, it.startMillis, it.stopMillis) }
        val live = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
        mutableState.update { it.copy(now = live, next = upcoming.firstOrNull { p -> p != live }) }
        val match = if (live != null) matchOnAir(current.id, now) else null
        if (session.channel?.id == current.id) mutableState.update { it.copy(match = match) }
    }

    /**
     * Match que diffuse le programme en cours de la chaîne, avec son score : celui de football-data.org
     * (réponse partagée avec la section Foot), sinon l'affiche du guide (coupes d'Europe, titre sans
     * compétition) avec le score en direct.
     */
    private suspend fun matchOnAir(channelId: Long, now: Long): PlayerMatch? {
        val programme = container.programmeDao.upcoming(channelId, now, limit = 1).firstOrNull()?.takeIf { it.startMillis <= now } ?: return null
        val result = container.footballData.matches(now, now + FOOTBALL_WINDOW_MILLIS) as? FootballDataResult.Success
        val broadcast = Broadcast(Unit, programme.title, programme.subtitle, programme.category, programme.description, programme.startMillis, programme.stopMillis)
        val match = result?.let { BroadcastFinder.assign(it.matches, listOf(broadcast)).keys.firstOrNull() }
        if (match != null) {
            val live = match.status == MatchStatus.LIVE
            return PlayerMatch(
                home = match.home.displayName,
                away = match.away.displayName,
                homeCrest = match.home.crestUrl,
                awayCrest = match.away.crestUrl,
                score = if (match.homeScore != null && match.awayScore != null && (live || match.status == MatchStatus.FINISHED)) {
                    "${match.homeScore} – ${match.awayScore}"
                } else {
                    null
                },
                live = live,
                clock = match.clock.takeIf { live },
                halfTime = live && match.halfTime,
            )
        }
        val fixture = FootballMatches.fixtureOf(programme.title, programme.subtitle, programme.category, programme.description)
        val (home, away) = fixture?.let { it.home to it.away }
            ?: FootballMatches.teamsOf(programme.title, programme.subtitle, programme.category)
            ?: return null
        val score = container.liveScores.find(fixture?.competition, home, away, programme.startMillis, programme.stopMillis)
            ?.takeIf { it.inPlay || it.finished }
        val crest = result?.let { BroadcastFinder.crests(it.matches.flatMap { scheduled -> listOf(scheduled.home, scheduled.away) }) }
        return PlayerMatch(
            home = home,
            away = away,
            homeCrest = crest?.invoke(home),
            awayCrest = crest?.invoke(away),
            score = score?.let { if (it.homeScore != null && it.awayScore != null) "${it.homeScore} – ${it.awayScore}" else null },
            live = score?.inPlay == true,
            clock = score?.clock,
            halfTime = score?.halfTime == true,
        )
    }

    private fun start(forceHls: Boolean) {
        val playbackRequest = session.request ?: return
        releasePlayer()
        mutableState.update { it.copy(info = PlaybackInfo(), audioProblem = null) }
        session.playingAsHls = forceHls || playbackRequest.stream.format == StreamFormat.HLS
        val player = container.playerFactory.create(playbackRequest, bandwidthMeter, preferredDecoders, adVolume, passthrough = audioPassthrough).apply {
            applyTrackPreferences()
            addListener(listener)
            addAnalyticsListener(analytics)
            val item = container.playerFactory.mediaItem(playbackRequest.stream, forceHls)
            // Position de départ seulement pour une reprise : un direct démarre au plus près du direct.
            if (session.startPositionMs > 0) setMediaItem(item, session.startPositionMs) else setMediaItem(item)
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
        mutableState.update { it.copy(error = null, buffering = true) }
    }

    private fun handleError(error: PlaybackException) {
        val player = mutablePlayer.value ?: return
        val playbackRequest = session.request ?: return
        if (playingTimeshift) {
            // Enregistrement du différé interrompu : retour au direct.
            container.logger.warn(TAG, "Différé interrompu (${error.errorCodeName})", error)
            backToLive()
            return
        }
        if (fallBackToContinuousReplay()) return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Direct trop en retard sur la fenêtre disponible (pause trop longue) : on revient au direct.
            player.seekToDefaultPosition()
            player.prepare()
            mutableState.update { it.copy(liveDelayMs = 0, paused = false, pausedAtMillis = null) }
            return
        }
        val format = playbackRequest.stream.format
        if (!session.triedHlsFallback && format in HLS_FALLBACK_FORMATS && error.errorCode in HLS_FALLBACK_ERRORS) {
            // Beaucoup d'URL IPTV sans extension servent en réalité du HLS.
            session.triedHlsFallback = true
            container.logger.info(TAG, "Format non reconnu, nouvelle tentative en HLS")
            start(forceHls = true)
            return
        }
        if (error.errorCode in SERVER_ERRORS && session.backupUrls.isNotEmpty()) {
            // Serveur injoignable : même flux sur un serveur de secours de la playlist.
            container.logger.info(TAG, "Serveur injoignable (${error.errorCodeName}), essai d'un serveur de secours")
            session.request = playbackRequest.copy(stream = playbackRequest.stream.copy(url = session.backupUrls.first()))
            session.backupUrls = session.backupUrls.drop(1)
            mutableState.update { it.copy(qualityNotice = resources.getString(R.string.player_backup_server)) }
            start(forceHls = session.playingAsHls)
            return
        }
        container.logger.warn(TAG, "Lecture impossible (${error.errorCodeName}) : ${playbackRequest.stream.url}", error)
        // Autre version de la même chaîne, de qualité inférieure, si elle existe.
        val decision = switcher?.onError()
        if (decision is QualitySwitcher.Decision.Switch) {
            switchQuality(decision)
            return
        }
        val viaProxy = container.httpClients.usesProxy(RequestPurpose.STREAM) &&
            format != StreamFormat.RTSP && format != StreamFormat.UDP
        mutableState.update { it.copy(buffering = false, error = resources.describePlaybackError(error, viaProxy)) }
    }

    /** Serveur principal de la playlist puis ses serveurs de secours ; vide sans serveur de secours. */
    private fun serverRootsOf(source: PlaylistSource): List<String> {
        if (source.mirrors.isEmpty()) return emptyList()
        val main = (source.location as? SourceLocation.Remote)?.url?.let(Mirrors::normalize)
        return (listOfNotNull(main) + source.mirrors).distinct()
    }

    /** Le flux joue : son serveur sert de départ aux chaînes suivantes de la playlist. */
    private fun rememberServer() {
        val variant = session.playing ?: return
        if (session.serverRoots.isEmpty() || playingTimeshift) return
        val url = session.request?.stream?.url ?: return
        if (Mirrors.alternatives(url, session.serverRoots).isEmpty()) return
        Mirrors.normalize(url)?.let { workingServer = variant.sourceId to it }
    }

    fun retry() {
        session.triedHlsFallback = false
        val variant = session.playing
        if (session.request == null || variant == null) {
            load(channelId)
        } else {
            // Nouvel essai complet : toutes les versions redeviennent candidates.
            load(session.listed?.id ?: variant.id)
        }
    }

    fun onStart() {
        val player = mutablePlayer.value ?: return
        if (resumeOnStart) {
            // Flux en direct : on reprend au direct plutôt qu'à la position mise en pause.
            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
            player.play()
        }
    }

    fun onStop() {
        val player = mutablePlayer.value ?: return
        resumeOnStart = player.playWhenReady
        player.pause()
        viewModelScope.launch { saveProgress() }
    }

    // --- Pause du direct et différé -------------------------------------------------------------

    /** Pause / lecture. En direct, la pause crée un différé ; le retard sur le direct est affiché. */
    fun togglePause() {
        val player = mutablePlayer.value ?: return
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
        mutableState.update { it.copy(paused = true, pausedAtMillis = now) }
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
        mutableState.update { it.copy(paused = false, pausedAtMillis = null, liveDelayMs = delay) }
    }

    /** Lecture depuis l'enregistrement du différé. */
    private fun startTimeshiftPlayback(buffer: TimeshiftBuffer) {
        val playbackRequest = session.request ?: return
        releasePlayer()
        playingTimeshift = true
        val player = container.playerFactory.create(playbackRequest, bandwidthMeter, preferredDecoders, adVolume, passthrough = audioPassthrough).apply {
            applyTrackPreferences()
            addListener(listener)
            addAnalyticsListener(analytics)
            setMediaSource(container.playerFactory.timeshiftSource(buffer))
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
    }

    /** Retour au direct : fin du différé, de « Depuis le début » ou du replay. */
    fun backToLive() {
        if (restarting || replay != null) {
            leaveReplay()
            load(channelId)
            return
        }
        val wasDelayed = state.value.liveDelayMs > 0 || state.value.paused
        val hadRecording = timeshift != null
        stopTimeshift()
        if (!wasDelayed && !hadRecording) return
        val player = mutablePlayer.value
        if (!hadRecording && player != null) {
            // HLS : le lecteur rejoint le direct dans la fenêtre du serveur.
            player.seekToDefaultPosition()
            player.play()
        } else {
            start(forceHls = session.playingAsHls && session.request?.stream?.format != StreamFormat.HLS)
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
        handle[ARG_REPLAY_START] = 0L
        handle[ARG_REPLAY_STOP] = 0L
        replayHls = false
        mutableState.update { it.copy(replay = null, seekable = false, watchedAt = null, pendingJumpSeconds = 0) }
    }

    /** Heure de ce qui est à l'écran dans l'enregistrement en cours. */
    private fun watchedAt(): Long = segmentStart + (mutablePlayer.value?.currentPosition ?: 0)

    /**
     * Replay ou « Depuis le début » : recule ou avance de [steps] pas (passer la pub avant le
     * programme). Un pas vaut 30 s quand on peut se déplacer dans l'enregistrement (HLS), sinon une
     * minute : l'enregistrement est alors relu à partir de la nouvelle minute (le serveur ne permet
     * pas d'avancer dans le flux continu). Les appuis rapprochés s'additionnent.
     */
    fun jump(steps: Int) {
        if (replayUrlFrom == null) return
        pendingJumpSeconds += steps * state.value.jumpStepSeconds
        mutableState.update { it.copy(pendingJumpSeconds = pendingJumpSeconds) }
        jumpJob?.cancel()
        jumpJob = viewModelScope.launch {
            delay(JUMP_DEBOUNCE_MILLIS)
            applyJump()
        }
    }

    private fun applyJump() {
        val urlFrom = replayUrlFrom ?: return
        val programme = replay ?: return
        val seconds = pendingJumpSeconds
        pendingJumpSeconds = 0
        mutableState.update { it.copy(pendingJumpSeconds = 0) }
        if (seconds == 0) return
        val player = mutablePlayer.value
        // HLS : déplacement dans l'enregistrement, à la seconde près.
        if (replayHls && player != null && player.isCurrentMediaItemSeekable) {
            val duration = player.duration.takeIf { it > 0 } ?: (programme.second - segmentStart)
            val position = (player.currentPosition + seconds * 1_000L).coerceIn(0, (duration - 5_000).coerceAtLeast(0))
            player.seekTo(position)
            mutableState.update { it.copy(watchedAt = segmentStart + position) }
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
        mutableState.update {
            it.copy(
                watchedAt = target,
                liveDelayMs = if (restarting) (now - target).coerceAtLeast(0) else it.liveDelayMs,
                paused = false,
                pausedAtMillis = null,
            )
        }
        start(forceHls = false)
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
        val player = mutablePlayer.value ?: return
        if (state.value.isLive && replayUrlFrom == null) return
        // Appuis courts encore en attente : appliqués d'abord.
        if (pendingJumpSeconds != 0) {
            jumpJob?.cancel()
            applyJump()
        }
        if (direction < 0) {
            val current = mutablePlayer.value ?: player
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
            mutablePlayer.value?.play()
        }
        fastDirection = direction
        mutableState.update { it.copy(fastSeek = direction) }
        fastJob = viewModelScope.launch {
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
        val player = mutablePlayer.value
        when {
            direction > 0 -> {
                player?.setPlaybackSpeed(1f)
                if (audioOff) player?.setAudioDisabled(false)
                if (restarting && player != null) {
                    val at = watchedAt()
                    mutableState.update { it.copy(watchedAt = at, liveDelayMs = (System.currentTimeMillis() - at).coerceAtLeast(0)) }
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
        fastPlayer = null
        fastAudioOff = false
        rewindInPlace = false
        if (state.value.fastSeek != 0) mutableState.update { it.copy(fastSeek = 0) }
    }

    private fun fastForwardTick() {
        val player = mutablePlayer.value ?: return
        if (player !== fastPlayer) {
            // Premier passage, ou nouveau lecteur après un saut : vitesse x2 (et son coupé s'il le fallait).
            fastPlayer = player
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
            mutableState.update {
                it.copy(watchedAt = at, liveDelayMs = if (restarting) (now - at).coerceAtLeast(0) else it.liveDelayMs)
            }
        }
    }

    private fun rewindTick() {
        if (rewindInPlace) {
            val player = mutablePlayer.value ?: return
            rewindTarget = (rewindTarget - FAST_TICK_MILLIS * FAST_SPEED.toLong()).coerceAtLeast(0)
            player.seekTo(rewindTarget)
            if (replayUrlFrom != null) mutableState.update { it.copy(watchedAt = segmentStart + rewindTarget) }
            return
        }
        // Enregistrement continu : le recul s'additionne, il est appliqué au relâchement.
        val programme = replay ?: return
        val step = (FAST_TICK_MILLIS * FAST_SPEED.toLong() / 1_000).toInt()
        if (watchedAt() + (pendingJumpSeconds - step) * 1_000L < programme.first - MAX_EARLY_MILLIS) return
        pendingJumpSeconds -= step
        mutableState.update { it.copy(pendingJumpSeconds = pendingJumpSeconds) }
    }

    /** On peut se déplacer dans le flux lu (film, épisode, replay HLS) sans le rouvrir. */
    private fun seeksInPlace(player: ExoPlayer): Boolean =
        player.isCurrentMediaItemSeekable && (replayUrlFrom == null || replayHls)

    private fun ExoPlayer.setAudioDisabled(disabled: Boolean) {
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled).build()
    }

    private fun stopTimeshift() {
        recorder?.stop()
        recorder = null
        timeshift?.release()
        timeshift = null
        playingTimeshift = false
        mutableState.update { it.copy(paused = false, pausedAtMillis = null, liveDelayMs = 0) }
    }

    private fun timeshiftLimit(): Long = minOf(TIMESHIFT_MAX_BYTES, context.cacheDir.usableSpace / 2)

    private fun onEnded() {
        // « Depuis le début » : l'enregistrement a rattrapé le direct.
        if (playingTimeshift || restarting) {
            // Fin de l'enregistrement (limite atteinte ou flux arrêté) : retour au direct.
            backToLive()
            return
        }
        if (!state.value.isLive) {
            val player = mutablePlayer.value ?: return
            viewModelScope.launch { saveProgress(positionMs = player.duration, durationMs = player.duration) }
            // Épisode terminé : le suivant est proposé.
            state.value.vod?.next?.let { next -> mutableState.update { it.copy(upNext = next) } }
        }
    }

    // --- Reprise de lecture -----------------------------------------------------------------------

    /** @return vrai si la lecture attend le choix « reprendre / recommencer » (ou reprend un autre épisode). */
    private suspend fun handleResume(requested: ChannelEntity): Boolean {
        val mode = resumeMode
        resumeMode = RESUME_ASK
        session.startPositionMs = 0
        if (mode == RESUME_NO) return false
        val progress = container.progress.get(requested.sourceId, requested.itemKey) ?: return false
        val sameItem = progress.channelId == requested.id
        if (mode == RESUME_YES) {
            if (sameItem) {
                session.startPositionMs = progress.positionMs
                return false
            }
            resumeOther(progress)
            return true
        }
        if (sameItem && progress.positionMs <= 0) return false
        pendingResume = requested to progress
        mutableState.update {
            it.copy(
                buffering = false,
                resumePrompt = ResumePrompt(
                    title = progress.title,
                    resumeLabel = progress.episodeLabel,
                    positionMs = progress.positionMs,
                    startLabel = if (sameItem) null else episodeLabel(requested),
                ),
            )
        }
        return true
    }

    /** Choix fait dans la question « reprendre / recommencer ». */
    fun answerResume(resume: Boolean) {
        val (requested, progress) = pendingResume ?: return
        pendingResume = null
        mutableState.update { it.copy(resumePrompt = null, buffering = true) }
        viewModelScope.launch {
            when {
                !resume -> {
                    session.startPositionMs = 0
                    playVariant(requested, notice = null)
                }
                progress.channelId == requested.id -> {
                    session.startPositionMs = progress.positionMs
                    playVariant(requested, notice = null)
                }
                else -> resumeOther(progress)
            }
        }
    }

    /** Reprend un autre épisode (celui en cours dans la série). */
    private fun resumeOther(progress: ProgressEntity) {
        resumeMode = RESUME_YES
        load(progress.channelId)
    }

    /** Enregistre la position du film ou de l'épisode en cours. */
    private suspend fun saveProgress(positionMs: Long? = null, durationMs: Long? = null) {
        val item = session.playing ?: return
        if (item.kind == ContentKind.LIVE.name || state.value.resumePrompt != null) return
        val player = mutablePlayer.value
        val position = positionMs ?: player?.currentPosition ?: return
        val duration = (durationMs ?: player?.duration ?: 0).takeIf { it > 0 } ?: 0
        val entity = ProgressEntity(
            sourceId = item.sourceId,
            itemKey = item.itemKey,
            kind = item.kind,
            channelId = item.id,
            title = if (item.kind == ContentKind.SERIES.name) item.seriesName ?: item.title else item.title,
            episodeLabel = if (item.kind == ContentKind.SERIES.name) episodeLabel(item) else null,
            logoUrl = item.logoUrl,
            positionMs = position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
        )
        val finished = duration > 0 && (duration - position < ProgressRepository.END_MARGIN_MS || position >= duration * 95 / 100)
        if (finished && item.kind == ContentKind.SERIES.name) {
            // Épisode terminé : la série reprendra à l'épisode suivant.
            val episodes = container.channelDao.episodes(item.sourceId, item.itemKey)
            val next = episodes.getOrNull(episodes.indexOfFirst { it.id == item.id } + 1)
            if (next != null) {
                val nextEntity = container.channelDao.get(next.id)
                container.progress.saveNext(entity.copy(channelId = next.id, episodeLabel = nextEntity?.let(::episodeLabel)))
            } else {
                container.progress.clear(item.sourceId, item.itemKey)
            }
            return
        }
        container.progress.save(entity)
    }

    /** Titre et épisode d'un film ou d'un épisode, avec l'épisode suivant de la série. */
    private suspend fun vodInfo(item: ChannelEntity): VodInfo {
        if (item.kind != ContentKind.SERIES.name) {
            return VodInfo(item.title.ifBlank { item.name }, null, item.year, item.logoUrl, next = null)
        }
        val episodes = container.channelDao.episodes(item.sourceId, item.itemKey)
        val next = episodes.getOrNull(episodes.indexOfFirst { it.id == item.id } + 1)?.let { row ->
            val label = when {
                row.season != null && row.episode != null -> "S%02dE%02d".format(row.season, row.episode)
                row.episode != null -> "E%02d".format(row.episode)
                else -> null
            }
            NextEpisode(row.id, label, row.name, row.logoUrl)
        }
        val series = item.seriesName ?: item.title.ifBlank { item.name }
        val label = episodeLabel(item)
        val episodeTitle = item.name.takeIf { it.isNotBlank() && !it.equals(series, ignoreCase = true) }
        return VodInfo(series, listOfNotNull(label, episodeTitle).joinToString("  ·  ").ifEmpty { null }, item.year, item.logoUrl, next)
    }

    /** Film ou épisode : avance ou recul de [deltaMs] dans la vidéo. */
    fun seekBy(deltaMs: Long) {
        val player = mutablePlayer.value ?: return
        if (state.value.isLive) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Film ou épisode : position choisie sur la barre de progression. */
    fun seekTo(positionMs: Long) {
        val player = mutablePlayer.value ?: return
        if (state.value.isLive) return
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Épisode suivant de la série, depuis son début. */
    fun playNextEpisode() {
        val next = state.value.vod?.next ?: state.value.upNext ?: return
        mutableState.update { it.copy(upNext = null) }
        viewModelScope.launch { saveProgress() }
        resumeMode = RESUME_NO
        load(next.id)
    }

    fun dismissUpNext() {
        mutableState.update { it.copy(upNext = null) }
    }

    private fun episodeLabel(item: ChannelEntity): String? {
        val season = item.season
        val episode = item.episode
        return when {
            season != null && episode != null -> "S%02dE%02d".format(season, episode)
            episode != null -> "E%02d".format(episode)
            else -> null
        }
    }

    private fun releasePlayer() {
        mutablePlayer.value?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        mutablePlayer.value = null
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("nextv-udp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    override fun onCleared() {
        // Dernière position (le ViewModel disparaît : enregistrement dans la portée de l'application).
        val player = mutablePlayer.value
        val item = session.playing
        if (player != null && item != null && item.kind != ContentKind.LIVE.name && state.value.resumePrompt == null) {
            val position = player.currentPosition
            val duration = player.duration
            container.applicationScope.launch { saveProgress(position, duration) }
        }
        stopTimeshift()
        releasePlayer()
        releaseMulticastLock()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_RESUME = "resume"

        /** Replay : début et fin du programme passé (millisecondes), 0 pour une lecture normale. */
        const val ARG_REPLAY_START = "replayStart"
        const val ARG_REPLAY_STOP = "replayStop"

        /** Demander s'il faut reprendre un film ou un épisode commencé. */
        const val RESUME_ASK = "ask"

        /** Reprendre sans demander (catégorie « Reprendre la lecture », bouton « Reprendre »). */
        const val RESUME_YES = "yes"

        /** Depuis le début, sans demander. */
        const val RESUME_NO = "no"

        private const val PROGRESS_SAVE_MILLIS = 10_000L
        private const val TIMESHIFT_FILE = "timeshift.ts"
        private const val TIMESHIFT_MAX_BYTES = 4L * 1024 * 1024 * 1024
        private val RECORDABLE_FORMATS = setOf(StreamFormat.PROGRESSIVE, StreamFormat.UNKNOWN)
        private const val TAG = "Player"
        private const val PROGRAMME_REFRESH_MILLIS = 30_000L

        /** Même période que la section Foot : la réponse de football-data.org est partagée. */
        private const val FOOTBALL_WINDOW_MILLIS = 8 * 24 * 60 * 60_000L
        private const val QUALITY_CHECK_MILLIS = 500L
        private const val MINUTE_MILLIS = 60_000L
        private const val MINUTE_SECONDS = 60

        /** Pas de ← → dans un replay où l'on peut se déplacer (HLS). */
        private const val FINE_JUMP_SECONDS = 30

        /** Serveurs (playlists) qui ne proposent pas le replay en HLS : pas de nouvel essai jusqu'au redémarrage. */
        private val hlsReplayRefused = java.util.Collections.synchronizedSet(HashSet<Long>())

        /** Appuis rapprochés sur ← → : additionnés pendant ce délai. */
        private const val JUMP_DEBOUNCE_MILLIS = 900L

        /** Avance et retour rapides : x2. */
        private const val FAST_SPEED = 2f
        private const val FAST_TICK_MILLIS = 500L

        /** Sans répétition de la touche maintenue pendant ce délai, l'avance rapide s'arrête. */
        private const val FAST_HEARTBEAT_TIMEOUT_MILLIS = 1_500L

        /** Recul possible avant le début prévu (programme commencé en avance). */
        private const val MAX_EARLY_MILLIS = 15 * MINUTE_MILLIS
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        /** Erreurs qui peuvent venir du serveur (injoignable, en panne, bloqué) : un serveur de secours peut répondre. */
        private val SERVER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}
