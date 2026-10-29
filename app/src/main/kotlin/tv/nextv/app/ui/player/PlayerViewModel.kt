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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.app.data.db.ProgrammeEntity
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose

data class PlayerUiState(
    val channelName: String = "",
    val buffering: Boolean = true,
    val error: String? = null,
    /** Limites connues du flux (pas de proxy pour RTSP/UDP…). */
    val notices: List<String> = emptyList(),
    val now: ProgrammeEntity? = null,
    val next: ProgrammeEntity? = null,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
    private val context: Context,
) : ViewModel() {

    private val channelId: Long = checkNotNull(handle.get<Long>(ARG_CHANNEL_ID))
    private val resources = context.resources

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private var request: PlaybackRequest? = null
    private var triedHlsFallback = false
    private var resumeOnStart = true
    private var multicastLock: WifiManager.MulticastLock? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            mutableState.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (true) {
                val upcoming = container.programmeDao.upcoming(channelId, System.currentTimeMillis(), limit = 2)
                val now = upcoming.firstOrNull()?.takeIf { it.startMillis <= System.currentTimeMillis() }
                mutableState.update { it.copy(now = now, next = upcoming.firstOrNull { p -> p != now }) }
                delay(PROGRAMME_REFRESH_MILLIS)
            }
        }
    }

    private suspend fun load() {
        val channel = container.channelDao.get(channelId)
        if (channel == null) {
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_channel_missing)) }
            return
        }
        val stream = StreamDescriptor(
            url = channel.streamUrl,
            headers = HeaderCodec.decode(channel.streamHeaders),
            format = StreamFormat.entries.firstOrNull { it.name == channel.streamFormat } ?: StreamFormat.UNKNOWN,
            support = StreamSupport.entries.firstOrNull { it.name == channel.streamSupport } ?: StreamSupport.SUPPORTED,
            issues = channel.streamIssues.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }.toSet(),
        )
        mutableState.update {
            it.copy(channelName = channel.name, notices = stream.issues.map { issue -> resources.describe(issue) })
        }
        if (stream.support == StreamSupport.UNSUPPORTED) {
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.stream_unsupported)) }
            return
        }

        val source = container.sourceRepository.getSource(channel.sourceId)
        request = PlaybackRequest(
            stream = stream,
            userAgent = source?.userAgent,
            credentials = container.sourceRepository.credentialsFor(channel.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        if (stream.format == StreamFormat.UDP) acquireMulticastLock()
        start(forceHls = false)
    }

    private fun start(forceHls: Boolean) {
        val playbackRequest = request ?: return
        mutablePlayer.value?.release()
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
        if (request == null) {
            viewModelScope.launch { load() }
        } else {
            start(forceHls = false)
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

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("nextv-udp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onCleared() {
        mutablePlayer.value?.release()
        mutablePlayer.value = null
        multicastLock?.takeIf { it.isHeld }?.release()
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
