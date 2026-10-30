package tv.nextv.app.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose

/** Programme affiché dans le bandeau du lecteur. */
data class PlayerProgramme(val title: String, val startMillis: Long, val stopMillis: Long)

data class PlayerUiState(
    val channelName: String = "",
    val channelNumber: String? = null,
    val logoUrl: String? = null,
    val sourceName: String = "",
    val buffering: Boolean = true,
    val error: String? = null,
    /** Limites connues du flux (pas de proxy pour RTSP/UDP…). */
    val notices: List<String> = emptyList(),
    val now: PlayerProgramme? = null,
    val next: PlayerProgramme? = null,
    /** Incrémenté à chaque changement de chaîne : le bandeau se réaffiche. */
    val channelVersion: Int = 0,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    private val context: Context,
) : ViewModel() {

    private var channelId: Long = checkNotNull(handle.get<Long>(ARG_CHANNEL_ID))
    private var channel: ChannelEntity? = null
    private val resources = context.resources

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private var request: PlaybackRequest? = null
    private var triedHlsFallback = false
    private var resumeOnStart = true
    private var multicastLock: WifiManager.MulticastLock? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            mutableState.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    init {
        load(channelId)
        viewModelScope.launch {
            while (true) {
                refreshProgrammes()
                delay(PROGRAMME_REFRESH_MILLIS)
            }
        }
    }

    /** Chaîne suivante (+1) ou précédente (-1) de la playlist, en boucle. */
    fun zap(direction: Int) {
        val current = channel ?: return
        viewModelScope.launch {
            val dao = container.channelDao
            val target = if (direction > 0) {
                dao.nextChannelId(current.sourceId, current.position) ?: dao.nextChannelId(current.sourceId, -1)
            } else {
                dao.previousChannelId(current.sourceId, current.position) ?: dao.previousChannelId(current.sourceId, Int.MAX_VALUE)
            }
            if (target != null && target != channelId) load(target)
        }
    }

    private fun load(id: Long) {
        channelId = id
        handle[ARG_CHANNEL_ID] = id
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadChannel(id) }
    }

    private suspend fun loadChannel(id: Long) {
        val entity = container.channelDao.get(id)
        channel = entity
        if (entity == null) {
            releasePlayer()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_channel_missing)) }
            return
        }
        val stream = StreamDescriptor(
            url = entity.streamUrl,
            headers = HeaderCodec.decode(entity.streamHeaders),
            format = StreamFormat.entries.firstOrNull { it.name == entity.streamFormat } ?: StreamFormat.UNKNOWN,
            support = StreamSupport.entries.firstOrNull { it.name == entity.streamSupport } ?: StreamSupport.SUPPORTED,
            issues = entity.streamIssues.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }.toSet(),
        )
        val source = container.sourceRepository.getSource(entity.sourceId)
        mutableState.update {
            it.copy(
                channelName = entity.name,
                channelNumber = entity.channelNumber,
                logoUrl = entity.logoUrl,
                sourceName = source?.name.orEmpty(),
                notices = stream.issues.map { issue -> resources.describe(issue) },
                now = null,
                next = null,
                error = null,
                buffering = true,
                channelVersion = it.channelVersion + 1,
            )
        }
        refreshProgrammes()
        if (stream.support == StreamSupport.UNSUPPORTED) {
            releasePlayer()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.stream_unsupported)) }
            return
        }

        request = PlaybackRequest(
            stream = stream,
            userAgent = source?.userAgent,
            credentials = container.sourceRepository.credentialsFor(entity.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        if (stream.format == StreamFormat.UDP) acquireMulticastLock() else releaseMulticastLock()
        triedHlsFallback = false
        start(forceHls = false)
    }

    private suspend fun refreshProgrammes() {
        val now = System.currentTimeMillis()
        val upcoming = container.programmeDao.upcoming(channelId, now, limit = 2)
            .map { PlayerProgramme(it.title, it.startMillis, it.stopMillis) }
        val current = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
        mutableState.update { it.copy(now = current, next = upcoming.firstOrNull { p -> p != current }) }
    }

    private fun start(forceHls: Boolean) {
        val playbackRequest = request ?: return
        releasePlayer()
        val player = container.playerFactory.create(playbackRequest).apply {
            addListener(listener)
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
        val viaProxy = container.httpClients.usesProxy(RequestPurpose.STREAM) &&
            format != StreamFormat.RTSP && format != StreamFormat.UDP
        container.logger.warn(TAG, "Lecture impossible (${error.errorCodeName}) : ${playbackRequest.stream.url}", error)
        mutableState.update { it.copy(buffering = false, error = resources.describePlaybackError(error, viaProxy)) }
    }

    fun retry() {
        triedHlsFallback = false
        if (request == null) load(channelId) else start(forceHls = false)
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

    private fun releasePlayer() {
        mutablePlayer.value?.let {
            it.removeListener(listener)
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
        private const val PROGRAMME_REFRESH_MILLIS = 60_000L
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
