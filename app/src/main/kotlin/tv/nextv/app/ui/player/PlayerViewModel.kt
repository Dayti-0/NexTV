package tv.nextv.app.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import java.io.File
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.TimeshiftBuffer
import tv.nextv.app.player.TimeshiftRecorder
import tv.nextv.app.player.VideoDecoders
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.home.Category
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.stream.QualityOption
import tv.nextv.core.stream.QualitySwitcher

/** Programme affiché dans le bandeau du lecteur. */
data class PlayerProgramme(val title: String, val startMillis: Long, val stopMillis: Long)

data class PlayerUiState(
    val channelName: String = "",
    val channelNumber: String? = null,
    val logoUrl: String? = null,
    val sourceName: String = "",
    /** Chaîne en direct (bandeau, zapping, menu) ou film / épisode (commandes de lecture). */
    val isLive: Boolean = true,
    val buffering: Boolean = true,
    val error: String? = null,
    /** Limites connues du flux (pas de proxy pour RTSP/UDP…). */
    val notices: List<String> = emptyList(),
    val now: PlayerProgramme? = null,
    val next: PlayerProgramme? = null,
    /** Qualité de la version en cours de lecture (« FHD »…), si la playlist l'indique. */
    val quality: String? = null,
    /** Message après un changement automatique de qualité. */
    val qualityNotice: String? = null,
    /** Débit réellement mesuré sur les téléchargements du flux, en Mbit/s (absent avant la première mesure). */
    val bandwidthMbps: Double? = null,
    /** Incrémenté à chaque changement de chaîne : le bandeau se réaffiche. */
    val channelVersion: Int = 0,
    /** Détails techniques de la lecture en cours (panneau « Infos de lecture »). */
    val info: PlaybackInfo = PlaybackInfo(),
    /** Direct en pause. */
    val paused: Boolean = false,
    /** Début de la pause en cours (le retard sur le direct augmente pendant la pause). */
    val pausedAtMillis: Long? = null,
    /** Retard sur le direct accumulé par les pauses précédentes. */
    val liveDelayMs: Long = 0,
    /** Film ou épisode déjà commencé : choix entre reprendre et recommencer. */
    val resumePrompt: ResumePrompt? = null,
)

/**
 * @property resumeLabel épisode à reprendre (« S01E08 »), `null` pour un film.
 * @property startLabel épisode demandé, s'il diffère de celui à reprendre.
 */
data class ResumePrompt(
    val title: String,
    val resumeLabel: String?,
    val positionMs: Long,
    val startLabel: String?,
)

/** Ce que le lecteur décode réellement : formats, décodeurs, images perdues. */
data class PlaybackInfo(
    val videoMimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val videoDecoder: String? = null,
    /** Vrai : décodeur matériel ; faux : logiciel ; `null` : inconnu. */
    val hardwareDecoder: Boolean? = null,
    val audioMimeType: String? = null,
    val audioChannels: Int? = null,
    val audioDecoder: String? = null,
    /** Images perdues depuis le début de la lecture de cette version. */
    val droppedFrames: Int = 0,
)

/** Chaîne du menu, avec la catégorie dont elle provient : la liste affichée correspond-elle au choix ? */
data class MenuChannel(val category: Category, val row: ChannelListRow)

/** Menu latéral des chaînes, ouvert par « Retour » pendant la lecture. */
data class ChannelMenuState(
    val open: Boolean = false,
    val category: Category = Category.All,
    val currentChannelId: Long = 0,
    /** Chaîne en cours, quelle que soit la version affichée par la liste. */
    val currentItemKey: String? = null,
    /** Rang de la chaîne en cours dans la liste affichée (focus initial). */
    val initialIndex: Int = 0,
)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    private val context: Context,
) : ViewModel() {

    private var channelId: Long = checkNotNull(handle.get<Long>(ARG_CHANNEL_ID))
    /** Chaîne affichée (meilleure version), base du zapping. */
    private var channel: ChannelEntity? = null
    /** Ligne choisie dans les listes (une version précise : celle de sa catégorie). */
    private var listed: ChannelEntity? = null
    /** Version effectivement lue (peut différer de [channel] après un changement de qualité). */
    private var playing: ChannelEntity? = null
    private val resources = context.resources

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private val mutableMenu = MutableStateFlow(ChannelMenuState())
    val menu: StateFlow<ChannelMenuState> = mutableMenu.asStateFlow()

    private val sourceId = MutableStateFlow<Long?>(null)

    val menuGroups: StateFlow<List<GroupRow>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.channelDao.observeGroups(id, ContentKind.LIVE.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val menuChannels: Flow<PagingData<MenuChannel>> = combine(sourceId, mutableMenu) { id, menu -> id to menu.category }
        .distinctUntilChanged()
        .flatMapLatest { (id, category) ->
            if (id == null || category == Category.Favorites) {
                emptyFlow()
            } else {
                Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
                    container.channelDao.pageChannels(
                        sourceId = id,
                        filterGroup = if (category is Category.Group) 1 else 0,
                        group = (category as? Category.Group)?.title.orEmpty(),
                        query = "",
                        now = System.currentTimeMillis(),
                    )
                }.flow.map { data -> data.map { MenuChannel(category, it) } }
            }
        }
        .cachedIn(viewModelScope)

    val menuFavorites: StateFlow<List<ChannelListRow>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.channelDao.observeFavoriteChannels(id, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var request: PlaybackRequest? = null
    private var triedHlsFallback = false
    private var resumeOnStart = true
    private var multicastLock: WifiManager.MulticastLock? = null
    private var loadJob: Job? = null
    private var switcher: QualitySwitcher? = null
    private var autoQuality = true
    private var preferredDecoders: Map<String, String> = emptyMap()

    // Images perdues déjà prises en compte, pour le lecteur courant.
    private var countedPlayer: ExoPlayer? = null
    private var countedDroppedFrames = 0

    /** Qualité que l'appareil n'a pas su décoder (images perdues), retenue d'une chaîne à l'autre. */
    private var decoderCeilingRank: Int? = null

    // Différé du direct.
    private var timeshift: TimeshiftBuffer? = null
    private var recorder: TimeshiftRecorder? = null
    private var playingTimeshift = false
    private var playingAsHls = false

    // Reprise de lecture des films et épisodes.
    private var resumeMode: String = handle.get<String>(ARG_RESUME) ?: RESUME_ASK
    private var startPositionMs = 0L
    private var pendingResume: Pair<ChannelEntity, ProgressEntity>? = null
    private val bandwidthMeter: DefaultBandwidthMeter = container.playerFactory.newBandwidthMeter()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> switcher?.onBufferingStarted()
                Player.STATE_READY -> switcher?.onReady()
                Player.STATE_ENDED -> onEnded()
            }
            mutableState.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
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

    /** Chaîne suivante (+1) ou précédente (-1) de la playlist, en boucle. */
    fun zap(direction: Int) {
        val current = listed ?: return
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
        val current = listed ?: return
        viewModelScope.launch {
            val dao = container.channelDao
            val group = current.groupTitle.orEmpty()
            // Catégorie masquée (chaîne ouverte depuis les favoris) : menu ouvert sur les favoris.
            val (category, index) = if (container.hiddenGroupDao.isHidden(current.sourceId, ContentKind.LIVE.name, group)) {
                val favorite = menuFavorites.value.indexOfFirst { it.itemKey == current.itemKey }
                if (favorite >= 0) Category.Favorites to favorite else Category.All to dao.indexInList(current.sourceId, 0, "", current.position)
            } else {
                Category.Group(group) to dao.indexInList(current.sourceId, 1, group, current.position)
            }
            mutableMenu.value = ChannelMenuState(
                open = true,
                category = category,
                currentChannelId = current.id,
                currentItemKey = current.itemKey,
                initialIndex = index,
            )
        }
    }

    fun closeMenu() {
        mutableMenu.update { it.copy(open = false) }
    }

    fun selectMenuCategory(category: Category) {
        mutableMenu.update { it.copy(category = category, initialIndex = 0) }
    }

    fun playFromMenu(id: Long) {
        closeMenu()
        if (id != listed?.id) load(id)
    }

    private fun load(id: Long) {
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
        channel = primary
        listed = requested
        sourceId.value = primary.sourceId
        val source = container.sourceRepository.getSource(primary.sourceId)

        val variants = if (isLive && primary.variantKey.isNotEmpty()) dao.variants(primary.sourceId, primary.variantKey) else emptyList()
        val options = variants.filter { it.streamSupport != StreamSupport.UNSUPPORTED.name }
            .map { QualityOption(it.id, StreamQuality.entries.firstOrNull { q -> q.name == it.quality }) }
        val startId = if (options.any { it.channelId == requested.id }) requested.id else options.firstOrNull()?.channelId ?: requested.id
        switcher = if (options.size > 1) {
            QualitySwitcher(
                options,
                startId,
                enabled = autoQuality,
                clock = System::currentTimeMillis,
                decoderCeilingRank = decoderCeilingRank,
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
                error = null,
                qualityNotice = null,
                buffering = true,
                channelVersion = it.channelVersion + 1,
            )
        }
        refreshProgrammes()
        if (isLive) startPositionMs = 0
        if (!isLive && handleResume(requested)) return
        val firstId = switcher?.current?.channelId ?: startId
        val variant = if (firstId == requested.id) requested else dao.get(firstId) ?: requested
        playVariant(variant, notice = null)
    }

    /** Lance la lecture d'une version précise de la chaîne. */
    private suspend fun playVariant(variant: ChannelEntity, notice: String?) {
        stopTimeshift()
        playing = variant
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
        request = PlaybackRequest(
            stream = stream,
            userAgent = source?.userAgent,
            credentials = container.sourceRepository.credentialsFor(variant.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        if (stream.format == StreamFormat.UDP) acquireMulticastLock() else releaseMulticastLock()
        triedHlsFallback = false
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
        val current = channel ?: return
        if (current.kind != ContentKind.LIVE.name) return
        val now = System.currentTimeMillis()
        val upcoming = container.programmeDao.upcoming(current.id, now, limit = 2)
            .map { PlayerProgramme(it.title, it.startMillis, it.stopMillis) }
        val live = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
        mutableState.update { it.copy(now = live, next = upcoming.firstOrNull { p -> p != live }) }
    }

    private fun start(forceHls: Boolean) {
        val playbackRequest = request ?: return
        releasePlayer()
        mutableState.update { it.copy(info = PlaybackInfo()) }
        playingAsHls = forceHls || playbackRequest.stream.format == StreamFormat.HLS
        val player = container.playerFactory.create(playbackRequest, bandwidthMeter, preferredDecoders).apply {
            addListener(listener)
            addAnalyticsListener(analytics)
            val item = container.playerFactory.mediaItem(playbackRequest.stream, forceHls)
            // Position de départ seulement pour une reprise : un direct démarre au plus près du direct.
            if (startPositionMs > 0) setMediaItem(item, startPositionMs) else setMediaItem(item)
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
        mutableState.update { it.copy(error = null, buffering = true) }
    }

    private fun handleError(error: PlaybackException) {
        val player = mutablePlayer.value ?: return
        val playbackRequest = request ?: return
        if (playingTimeshift) {
            // Enregistrement du différé interrompu : retour au direct.
            container.logger.warn(TAG, "Différé interrompu (${error.errorCodeName})", error)
            backToLive()
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Direct trop en retard sur la fenêtre disponible (pause trop longue) : on revient au direct.
            player.seekToDefaultPosition()
            player.prepare()
            mutableState.update { it.copy(liveDelayMs = 0, paused = false, pausedAtMillis = null) }
            return
        }
        val format = playbackRequest.stream.format
        if (!triedHlsFallback && format in HLS_FALLBACK_FORMATS && error.errorCode in HLS_FALLBACK_ERRORS) {
            // Beaucoup d'URL IPTV sans extension servent en réalité du HLS.
            triedHlsFallback = true
            container.logger.info(TAG, "Format non reconnu, nouvelle tentative en HLS")
            start(forceHls = true)
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

    fun retry() {
        triedHlsFallback = false
        val variant = playing
        if (request == null || variant == null) {
            load(channelId)
        } else {
            // Nouvel essai complet : toutes les versions redeviennent candidates.
            load(listed?.id ?: variant.id)
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
        val playback = request
        // Flux continu (MPEG-TS…) : le serveur le coupe si on cesse de le lire. Il est donc
        // enregistré pendant la pause, puis relu depuis l'enregistrement.
        if (timeshift == null && playback != null && !playingAsHls && playback.stream.format in RECORDABLE_FORMATS) {
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
        val playbackRequest = request ?: return
        releasePlayer()
        playingTimeshift = true
        val player = container.playerFactory.create(playbackRequest, bandwidthMeter, preferredDecoders).apply {
            addListener(listener)
            addAnalyticsListener(analytics)
            setMediaSource(container.playerFactory.timeshiftSource(buffer))
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
    }

    /** Retour au direct : fin du différé. */
    fun backToLive() {
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
            start(forceHls = playingAsHls && request?.stream?.format != StreamFormat.HLS)
        }
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
        if (playingTimeshift) {
            // Fin de l'enregistrement (limite atteinte ou flux arrêté) : retour au direct.
            backToLive()
            return
        }
        if (!state.value.isLive) {
            val player = mutablePlayer.value ?: return
            viewModelScope.launch { saveProgress(positionMs = player.duration, durationMs = player.duration) }
        }
    }

    // --- Reprise de lecture -----------------------------------------------------------------------

    /** @return vrai si la lecture attend le choix « reprendre / recommencer » (ou reprend un autre épisode). */
    private suspend fun handleResume(requested: ChannelEntity): Boolean {
        val mode = resumeMode
        resumeMode = RESUME_ASK
        startPositionMs = 0
        if (mode == RESUME_NO) return false
        val progress = container.progress.get(requested.sourceId, requested.itemKey) ?: return false
        val sameItem = progress.channelId == requested.id
        if (mode == RESUME_YES) {
            if (sameItem) {
                startPositionMs = progress.positionMs
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
                    startPositionMs = 0
                    playVariant(requested, notice = null)
                }
                progress.channelId == requested.id -> {
                    startPositionMs = progress.positionMs
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
        val item = playing ?: return
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

    private fun episodeLabel(item: ChannelEntity): String? {
        val season = item.season
        val episode = item.episode
        return when {
            season != null && episode != null -> "S%02dE%02d".format(season, episode)
            episode != null -> "E%02d".format(episode)
            else -> null
        }
    }

    private fun ChannelEntity.toStream() = StreamDescriptor(
        url = streamUrl,
        headers = HeaderCodec.decode(streamHeaders),
        format = StreamFormat.entries.firstOrNull { it.name == streamFormat } ?: StreamFormat.UNKNOWN,
        support = StreamSupport.entries.firstOrNull { it.name == streamSupport } ?: StreamSupport.SUPPORTED,
        issues = streamIssues.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }.toSet(),
    )

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
        val item = playing
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
        private const val QUALITY_CHECK_MILLIS = 500L
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
