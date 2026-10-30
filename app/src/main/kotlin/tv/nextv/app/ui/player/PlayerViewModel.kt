package tv.nextv.app.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.app.player.PlaybackRequest
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

/** Menu latéral des chaînes, ouvert par « Retour » pendant la lecture. */
data class ChannelMenuState(
    val open: Boolean = false,
    val category: Category = Category.All,
    val currentChannelId: Long = 0,
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

    val menuChannels: Flow<PagingData<ChannelListRow>> = combine(sourceId, mutableMenu) { id, menu -> id to menu.category }
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
                }.flow
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

    /** Qualité que l'appareil n'a pas su décoder (images perdues), retenue d'une chaîne à l'autre. */
    private var decoderCeilingRank: Int? = null
    private val bandwidthMeter: DefaultBandwidthMeter = container.playerFactory.newBandwidthMeter()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> switcher?.onBufferingStarted()
                Player.STATE_READY -> switcher?.onReady()
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

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            updateInfo { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
            // L'appareil ne suit pas cette qualité (4K sur une petite box…) : version inférieure si elle existe.
            val decision = switcher?.onDroppedFrames(droppedFrames)
            if (decision is QualitySwitcher.Decision.Switch) {
                decoderCeilingRank = switcher?.decoderCeilingRank
                switchQuality(decision)
            }
        }
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
        // Surveillance de la lecture : mise en mémoire tampon prolongée, retour à une meilleure qualité.
        viewModelScope.launch {
            while (true) {
                delay(QUALITY_CHECK_MILLIS)
                val estimate = state.value.bandwidthMbps?.let { (it * 1_000_000).toLong() }
                val decision = switcher?.evaluate(estimate)
                if (decision is QualitySwitcher.Decision.Switch) switchQuality(decision)
            }
        }
    }

    /** Chaîne suivante (+1) ou précédente (-1) de la playlist, en boucle. */
    fun zap(direction: Int) {
        val current = channel ?: return
        if (current.kind != ContentKind.LIVE.name) return
        viewModelScope.launch {
            val dao = container.channelDao
            val target = if (direction > 0) {
                dao.nextChannelId(current.sourceId, current.position) ?: dao.nextChannelId(current.sourceId, -1)
            } else {
                dao.previousChannelId(current.sourceId, current.position) ?: dao.previousChannelId(current.sourceId, Int.MAX_VALUE)
            }
            if (target != null && target != current.id) load(target)
        }
    }

    fun openMenu() {
        val current = channel ?: return
        viewModelScope.launch {
            val category = Category.Group(current.groupTitle.orEmpty())
            val index = container.channelDao.indexInList(current.sourceId, 1, category.title, current.position)
            mutableMenu.value = ChannelMenuState(open = true, category = category, currentChannelId = current.id, initialIndex = index)
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
        if (id != channel?.id) load(id)
    }

    private fun load(id: Long) {
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
        val firstId = switcher?.current?.channelId ?: startId
        val variant = if (firstId == requested.id) requested else dao.get(firstId) ?: requested
        playVariant(variant, notice = null)
    }

    /** Lance la lecture d'une version précise de la chaîne. */
    private suspend fun playVariant(variant: ChannelEntity, notice: String?) {
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
        val player = container.playerFactory.create(playbackRequest, bandwidthMeter, preferredDecoders).apply {
            addListener(listener)
            addAnalyticsListener(analytics)
            setMediaItem(container.playerFactory.mediaItem(playbackRequest.stream, forceHls))
            playWhenReady = true
            prepare()
        }
        mutablePlayer.value = player
        mutableState.update { it.copy(error = null, buffering = true) }
    }

    private fun handleError(error: PlaybackException) {
        val player = mutablePlayer.value ?: return
        val playbackRequest = request ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Direct trop en retard sur la fenêtre disponible : on revient au direct.
            player.seekToDefaultPosition()
            player.prepare()
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
            load(channel?.id ?: variant.id)
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
        releasePlayer()
        releaseMulticastLock()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
        private const val TAG = "Player"
        private const val PROGRAMME_REFRESH_MILLIS = 30_000L
        private const val QUALITY_CHECK_MILLIS = 2_000L
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
