package tv.nextv.app.ui.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.core.model.PlaylistSource

class ChannelsViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {

    val sourceId: Long = checkNotNull(handle.get<Long>(ARG_SOURCE_ID))

    val source: StateFlow<PlaylistSource?> = container.playlistStore.observeSource(sourceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<GroupRow>> = container.channelDao.observeGroups(sourceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val refreshing: StateFlow<Boolean> = container.sourceRepository.refreshing
        .map { sourceId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** `null` : toutes les chaînes ; `""` : chaînes sans groupe. */
    val selectedGroup = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")

    /** Horloge pour le programme en cours, rafraîchie régulièrement. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(NOW_REFRESH_MILLIS)
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val channels: Flow<PagingData<ChannelListRow>> =
        combine(
            selectedGroup,
            query.debounce(SEARCH_DEBOUNCE_MILLIS).onStart { emit(query.value) }.distinctUntilChanged(),
            clock,
        ) { group, text, now ->
            Triple(group, escapeLike(text.trim()), now)
        }
            .flatMapLatest { (group, text, now) ->
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                    container.channelDao.pageChannels(
                        sourceId = sourceId,
                        filterGroup = if (group == null) 0 else 1,
                        group = group.orEmpty(),
                        query = text,
                        now = now,
                    )
                }.flow
            }
            .cachedIn(viewModelScope)

    fun refresh() = container.refreshController.refresh(sourceId)

    private fun escapeLike(text: String) = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    companion object {
        const val ARG_SOURCE_ID = "sourceId"
        private const val PAGE_SIZE = 60
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val NOW_REFRESH_MILLIS = 120_000L
    }
}
