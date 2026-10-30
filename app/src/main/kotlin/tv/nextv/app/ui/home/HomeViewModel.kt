package tv.nextv.app.ui.home

import android.content.res.Resources
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.core.model.ContentKind

enum class HomeSection { TV, MOVIES, SERIES, GUIDE }

/** Catégorie affichée dans une section. */
sealed interface Category {
    data object Favorites : Category
    data object All : Category

    /** [title] vide : éléments sans groupe. */
    data class Group(val title: String) : Category
}

/** Réorganisation des favoris : [movingKey] est l'élément « soulevé », déplacé avec les flèches. */
data class ReorderState(val active: Boolean = false, val movingKey: String? = null)

fun HomeSection.kind(): ContentKind? = when (this) {
    HomeSection.TV -> ContentKind.LIVE
    HomeSection.MOVIES -> ContentKind.MOVIE
    HomeSection.SERIES -> ContentKind.SERIES
    HomeSection.GUIDE -> null
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val container: AppContainer,
    private val resources: Resources,
) : ViewModel() {

    private val dao = container.channelDao

    val sourceId: StateFlow<Long?> = container.settings.activePlaylistId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sourceName: StateFlow<String> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf("") else container.playlistStore.observeSource(id).map { it?.name.orEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val section = MutableStateFlow(HomeSection.TV)

    private val categories = MutableStateFlow<Map<HomeSection, Category>>(emptyMap())
    private val queries = MutableStateFlow<Map<HomeSection, String>>(emptyMap())

    val category: StateFlow<Category> = combine(section, categories) { s, c -> c[s] ?: Category.All }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Category.All)

    val query: StateFlow<String> = combine(section, queries) { s, q -> q[s].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val reorder = MutableStateFlow(ReorderState())

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    val groups: StateFlow<List<GroupRow>> = combine(sourceId, section) { id, s -> id to s.kind() }
        .flatMapLatest { (id, kind) -> if (id == null || kind == null) flowOf(emptyList()) else dao.observeGroups(id, kind.name) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet()) else container.favorites.observeKeys(id).map { it.toSet() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(NOW_REFRESH_MILLIS)
        }
    }

    val now: StateFlow<Long> = clock.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    private fun filterFor(target: HomeSection): Flow<Triple<Long, Category, String>> =
        combine(
            sourceId.filterNotNull(),
            categories.map { it[target] ?: Category.All }.distinctUntilChanged(),
            queries.map { it[target].orEmpty() }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS).onStart { emit("") },
        ) { id, c, q -> Triple(id, c, escapeLike(q.trim())) }.distinctUntilChanged()

    /**
     * Chaînes de la catégorie choisie. L'heure courante est relue à chaque rechargement :
     * `LazyPagingItems.refresh()` met à jour les programmes en cours sans vider la liste
     * (le focus de la télécommande est conservé).
     */
    val channels: Flow<PagingData<ChannelListRow>> = filterFor(HomeSection.TV)
        .flatMapLatest { (id, c, q) ->
            if (c == Category.Favorites) {
                emptyFlow()
            } else {
                pager { dao.pageChannels(id, c.filterFlag(), c.groupTitle(), q, System.currentTimeMillis()) }
            }
        }
        .cachedIn(viewModelScope)

    val favoriteChannels: StateFlow<List<ChannelListRow>> = combine(sourceId.filterNotNull(), clock) { id, now -> id to now }
        .flatMapLatest { (id, now) -> dao.observeFavoriteChannels(id, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val movies: Flow<PagingData<MovieRow>> = filterFor(HomeSection.MOVIES)
        .flatMapLatest { (id, c, q) ->
            if (c == Category.Favorites) emptyFlow() else pager { dao.pageMovies(id, c.filterFlag(), c.groupTitle(), q) }
        }
        .cachedIn(viewModelScope)

    val favoriteMovies: StateFlow<List<MovieRow>> = sourceId.filterNotNull()
        .flatMapLatest { dao.observeFavoriteMovies(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: Flow<PagingData<SeriesRow>> = filterFor(HomeSection.SERIES)
        .flatMapLatest { (id, c, q) ->
            if (c == Category.Favorites) emptyFlow() else pager { dao.pageSeries(id, c.filterFlag(), c.groupTitle(), q) }
        }
        .cachedIn(viewModelScope)

    val favoriteSeries: StateFlow<List<SeriesRow>> = sourceId.filterNotNull()
        .flatMapLatest { dao.observeFavoriteSeries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSection(target: HomeSection) {
        section.value = target
        reorder.value = ReorderState()
    }

    fun selectCategory(target: Category) {
        categories.update { it + (section.value to target) }
        reorder.value = ReorderState()
    }

    fun setQuery(text: String) {
        queries.update { it + (section.value to text) }
    }

    fun toggleFavorite(kind: ContentKind, itemKey: String, title: String, logoUrl: String?) {
        val id = sourceId.value ?: return
        viewModelScope.launch {
            val added = container.favorites.toggle(id, kind, itemKey, title, logoUrl)
            mutableMessages.emit(resources.getString(if (added) R.string.favorite_added else R.string.favorite_removed, title))
        }
    }

    fun toggleReorder() {
        reorder.update { if (it.active) ReorderState() else ReorderState(active = true) }
    }

    /** OK pendant la réorganisation : soulève ou repose l'élément. */
    fun pick(itemKey: String) {
        reorder.update { it.copy(movingKey = if (it.movingKey == itemKey) null else itemKey) }
    }

    fun moveFavorite(kind: ContentKind, itemKey: String, offset: Int) {
        val id = sourceId.value ?: return
        viewModelScope.launch { container.favorites.move(id, kind, itemKey, offset) }
    }

    private fun <T : Any> pager(source: () -> androidx.paging.PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false), pagingSourceFactory = source).flow

    private fun Category.filterFlag() = if (this is Category.Group) 1 else 0

    private fun Category.groupTitle() = (this as? Category.Group)?.title.orEmpty()

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val PAGE_SIZE = 60
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val NOW_REFRESH_MILLIS = 30_000L
    }
}
