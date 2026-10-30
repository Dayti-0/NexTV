package tv.nextv.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.core.meta.MediaType
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.model.ContentKind

/** Ce que la playlist elle-même dit du film ou de la série. */
data class PlaylistItem(
    val sourceId: Long,
    val firstId: Long,
    val title: String,
    val year: Int?,
    val logoUrl: String?,
    val itemKey: String,
)

data class Season(val number: Int?, val episodes: List<EpisodeRow>)

data class DetailUiState(
    val item: PlaylistItem? = null,
    val metadata: MetadataState = MetadataState.Loading,
    val isFavorite: Boolean = false,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int = 0,
    val missing: Boolean = false,
)

/** Fiche d'un film ou d'une série : données de la playlist, enrichies par TMDB si possible. */
class DetailViewModel(
    private val container: AppContainer,
    private val channelId: Long,
    private val kind: ContentKind,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val entity = container.channelDao.get(channelId)
        if (entity == null) {
            mutableState.update { it.copy(missing = true, metadata = MetadataState.NotFound) }
            return
        }
        val title = if (kind == ContentKind.SERIES) entity.seriesName ?: entity.title else entity.title
        val item = PlaylistItem(entity.sourceId, entity.id, title, entity.year, entity.logoUrl, entity.itemKey)
        mutableState.update { it.copy(item = item) }

        if (kind == ContentKind.SERIES) {
            val seasons = container.channelDao.episodes(entity.sourceId, entity.itemKey)
                .groupBy { it.season }
                .map { (number, episodes) -> Season(number, episodes) }
                .sortedBy { it.number ?: 0 }
            mutableState.update { it.copy(seasons = seasons) }
        }

        viewModelScope.launch {
            container.favorites.observeKeys(entity.sourceId).collect { keys ->
                mutableState.update { it.copy(isFavorite = entity.itemKey in keys) }
            }
        }

        val metadata = if (!container.hasTmdbKey()) {
            MetadataState.Unavailable(TmdbError.MISSING_KEY)
        } else {
            container.metadata.details(
                cacheKey = entity.itemKey,
                type = if (kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE,
                title = title,
                year = entity.year,
            )
        }
        mutableState.update { it.copy(metadata = metadata) }
    }

    fun selectSeason(index: Int) {
        mutableState.update { it.copy(selectedSeason = index) }
    }

    fun toggleFavorite() {
        val item = state.value.item ?: return
        viewModelScope.launch {
            container.favorites.toggle(item.sourceId, kind, item.itemKey, item.title, item.logoUrl)
        }
    }
}
