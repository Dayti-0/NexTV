package tv.nextv.app.ui.guide

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.GroupRow

@Immutable
data class GuideProgramme(
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)

@Immutable
data class GuideChannel(
    val id: Long,
    val name: String,
    val number: String?,
    val logoUrl: String?,
    val programmes: List<GuideProgramme>,
)

data class GuideUiState(
    val loading: Boolean = true,
    val windowStart: Long = 0,
    val windowEnd: Long = 0,
    val now: Long = 0,
    val channels: List<GuideChannel> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {

    val sourceId: Long = checkNotNull(handle.get<Long>(ARG_SOURCE_ID))

    val sourceName: StateFlow<String> = container.playlistStore.observeSource(sourceId)
        .mapLatest { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val groups: StateFlow<List<GroupRow>> = container.channelDao.observeGroups(sourceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `null` : toutes les chaînes ; `""` : chaînes sans groupe. */
    val selectedGroup = MutableStateFlow<String?>(null)

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MILLIS)
        }
    }

    val state: StateFlow<GuideUiState> = combine(selectedGroup, clock) { group, now -> group to now }
        .mapLatest { (group, now) -> load(group, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    private suspend fun load(group: String?, now: Long): GuideUiState {
        val windowStart = now - now % HALF_HOUR
        val windowEnd = windowStart + WINDOW_MILLIS
        val rows = container.channelDao.guideChannels(
            sourceId = sourceId,
            filterGroup = if (group == null) 0 else 1,
            group = group.orEmpty(),
            limit = MAX_CHANNELS,
        )
        val programmes = rows.map { it.epgChannelId }.distinct()
            .chunked(SQL_VARIABLES_PER_QUERY)
            .flatMap { ids -> container.programmeDao.inWindow(sourceId, ids, windowStart, windowEnd) }
            .groupBy { it.epgChannelId }
        val channels = rows.map { row ->
            GuideChannel(
                id = row.id,
                name = row.name,
                number = row.channelNumber,
                logoUrl = row.logoUrl,
                programmes = programmes[row.epgChannelId].orEmpty().map {
                    GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category)
                },
            )
        }
        return GuideUiState(loading = false, windowStart = windowStart, windowEnd = windowEnd, now = now, channels = channels)
    }

    companion object {
        const val ARG_SOURCE_ID = "sourceId"
        private const val HALF_HOUR = 30 * 60_000L
        private const val WINDOW_MILLIS = 6 * 60 * 60_000L
        private const val REFRESH_MILLIS = 60_000L
        private const val MAX_CHANNELS = 300
        private const val SQL_VARIABLES_PER_QUERY = 500
    }
}
