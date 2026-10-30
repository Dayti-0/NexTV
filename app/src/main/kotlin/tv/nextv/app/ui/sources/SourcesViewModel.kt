package tv.nextv.app.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.core.model.PlaylistSource

data class SourceItem(val source: PlaylistSource, val refreshing: Boolean)

data class SourcesUiState(
    val loaded: Boolean = false,
    val items: List<SourceItem> = emptyList(),
    /** Playlist affichée à l'accueil. */
    val activeId: Long? = null,
)

class SourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sourceRepository

    val state: StateFlow<SourcesUiState> = combine(
        repository.sources,
        repository.refreshing,
        container.settings.activePlaylistId,
    ) { sources, refreshing, activeId ->
        SourcesUiState(loaded = true, items = sources.map { SourceItem(it, it.id in refreshing) }, activeId = activeId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    /** La playlist active a changé (choisie ou supprimée) : l'accueil doit être rechargé. */
    private val mutableReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reload: SharedFlow<Unit> = mutableReload.asSharedFlow()

    val refreshEvents = container.refreshController.events

    val redactor get() = container.redactor

    fun refresh(id: Long) = container.refreshController.refresh(id)

    fun refreshAll() = container.refreshController.refreshAll()

    /** Fait de [id] la playlist de l'accueil, puis la charge avec l'écran de démarrage. */
    fun activate(id: Long) {
        viewModelScope.launch {
            container.settings.setActivePlaylist(id)
            mutableReload.emit(Unit)
        }
    }

    fun delete(id: Long) {
        container.applicationScope.launch {
            val wasActive = container.settings.activePlaylistId.first() == id
            repository.delete(id)
            if (wasActive) {
                container.settings.setActivePlaylist(null)
                mutableReload.emit(Unit)
            }
        }
    }

    fun sourceName(id: Long): String? = state.value.items.firstOrNull { it.source.id == id }?.source?.name
}
