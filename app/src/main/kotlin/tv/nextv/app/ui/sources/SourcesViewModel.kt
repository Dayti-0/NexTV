package tv.nextv.app.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.core.model.PlaylistSource

data class SourceItem(val source: PlaylistSource, val refreshing: Boolean)

data class SourcesUiState(
    val loaded: Boolean = false,
    val items: List<SourceItem> = emptyList(),
)

class SourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sourceRepository

    val state: StateFlow<SourcesUiState> = combine(repository.sources, repository.refreshing) { sources, refreshing ->
        SourcesUiState(loaded = true, items = sources.map { SourceItem(it, it.id in refreshing) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    val refreshEvents = container.refreshController.events

    val redactor get() = container.redactor

    fun refresh(id: Long) = container.refreshController.refresh(id)

    fun refreshEpg(id: Long) = container.refreshController.refreshEpg(id)

    fun refreshAll() = container.refreshController.refreshAll()

    fun delete(id: Long) {
        container.applicationScope.launch { repository.delete(id) }
    }

    fun sourceName(id: Long): String? = state.value.items.firstOrNull { it.source.id == id }?.source?.name
}
