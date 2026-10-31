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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
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
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.HiddenGroupEntity
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.player.ChannelPreviewPlayer
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.model.ContentKind

enum class HomeSection { TV, MOVIES, SERIES, GUIDE, FOOTBALL }

/** Catégorie affichée dans une section. */
sealed interface Category {
    data object Favorites : Category
    data object All : Category

    /** Films et séries commencés (reprise de lecture). */
    data object ContinueWatching : Category

    /** Chaînes regardées récemment, la dernière en premier. */
    data object Recent : Category

    /** [title] vide : éléments sans groupe. */
    data class Group(val title: String) : Category
}

/**
 * Chaîne sélectionnée à l'accueil, avec son programme en cours, le suivant (panneau du haut) et
 * les programmes d'après ([later], volet de l'interface « Box TV »).
 */
data class ChannelPreview(
    val channel: ChannelListRow,
    val current: GuideProgramme?,
    val next: GuideProgramme?,
    val later: List<GuideProgramme> = emptyList(),
)

/** Contenu des rangées de l'interface « Streaming » : premiers éléments d'une catégorie ([String] vide : sans groupe). */
class HomeRows(
    val channels: suspend (group: String) -> List<ChannelListRow> = { emptyList() },
    val movies: suspend (group: String) -> List<MovieRow> = { emptyList() },
    val series: suspend (group: String) -> List<SeriesRow> = { emptyList() },
)

/** Réorganisation des favoris : [movingKey] est l'élément « soulevé », déplacé avec les flèches. */
data class ReorderState(val active: Boolean = false, val movingKey: String? = null)

fun HomeSection.kind(): ContentKind? = when (this) {
    HomeSection.TV -> ContentKind.LIVE
    HomeSection.MOVIES -> ContentKind.MOVIE
    HomeSection.SERIES -> ContentKind.SERIES
    HomeSection.GUIDE, HomeSection.FOOTBALL -> null
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val container: AppContainer,
    private val resources: Resources,
) : ViewModel() {

    private val dao = container.channelDao
    private val hiddenGroups = container.hiddenGroupDao

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

    init {
        // Catégorie masquée (ici ou dans les réglages) : retour à « Toutes » dans sa section.
        for (section in listOf(HomeSection.TV, HomeSection.MOVIES, HomeSection.SERIES)) {
            viewModelScope.launch {
                sourceId.filterNotNull()
                    .flatMapLatest { hiddenGroups.observeTitles(it, checkNotNull(section.kind()).name) }
                    .collect { hidden ->
                        categories.update { current ->
                            val selected = current[section] as? Category.Group
                            if (selected != null && selected.title in hidden) current + (section to Category.All) else current
                        }
                    }
            }
        }
    }

    /** Chaînes de la liste « Toutes » (une ligne par chaîne, catégories masquées exclues). */
    val channelCount: StateFlow<Int?> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(null) else dao.observeListedCount(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** Chaînes regardées récemment (catégorie « Récentes »). */
    val recentChannels: StateFlow<List<ChannelListRow>> = combine(sourceId.filterNotNull(), clock) { id, now -> id to now }
        .flatMapLatest { (id, now) ->
            container.settings.recentChannels(id).flatMapLatest { keys ->
                if (keys.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    dao.observeChannelsByKey(id, keys, now).map { rows -> rows.sortedBy { keys.indexOf(it.itemKey) } }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Aperçu vidéo de la chaîne sélectionnée (réglages). */
    val previewEnabled: StateFlow<Boolean> = container.settings.channelPreview
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository)

    /** Lecteur de l'aperçu (sans le son), `null` sans aperçu. */
    val previewPlayer = previewer.player

    /** Dernière chaîne sélectionnée dans la grille des chaînes. */
    private val focusedChannel = MutableStateFlow<ChannelListRow?>(null)

    /** Accueil affiché (aperçu possible). */
    private val previewActive = MutableStateFlow(false)

    /** Chaîne sélectionnée avec son programme (panneau du haut). */
    val preview: StateFlow<ChannelPreview?> = focusedChannel
        .mapLatest { row ->
            row ?: return@mapLatest null
            val upcoming = container.programmeDao.upcoming(row.id, System.currentTimeMillis(), limit = PREVIEW_PROGRAMMES).map {
                GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year)
            }
            val current = upcoming.firstOrNull()?.takeIf { it.startMillis <= System.currentTimeMillis() }
            val following = upcoming.filter { it != current }
            ChannelPreview(row, current, following.firstOrNull(), following.drop(1))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Aperçu : la chaîne sélectionnée démarre après un court arrêt dessus, section TV affichée.
        viewModelScope.launch {
            combine(focusedChannel, previewActive, previewEnabled, section) { row, active, enabled, current ->
                row.takeIf { active && enabled && current == HomeSection.TV }
            }
                .distinctUntilChangedBy { it?.id }
                .collectLatest { row ->
                    if (row == null) {
                        previewer.stop()
                        return@collectLatest
                    }
                    delay(PREVIEW_DELAY_MILLIS)
                    dao.get(row.id)?.let { previewer.show(it) }
                }
        }
    }

    /** Chaînes et affiches par ligne (réglages). */
    val gridColumns: StateFlow<GridColumns> = container.settings.gridColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GridColumns())

    /** Interface de l'accueil (réglages). */
    val layout: StateFlow<HomeLayout> = container.settings.homeLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.CLASSIC)

    /** Premiers éléments de chaque catégorie, lus à l'affichage de sa rangée. */
    val rows = HomeRows(
        channels = { group -> sourceId.value?.let { dao.channelRow(it, group, System.currentTimeMillis(), ROW_SIZE) }.orEmpty() },
        movies = { group -> sourceId.value?.let { dao.movieRow(it, group.ifEmpty { null }, ROW_SIZE) }.orEmpty() },
        series = { group -> sourceId.value?.let { dao.seriesRow(it, group.ifEmpty { null }, ROW_SIZE) }.orEmpty() },
    )

    /** Retour aux rangées (interface « Streaming ») : catégorie « Toutes » et recherche vidée. */
    fun showRows() {
        categories.update { it + (section.value to Category.All) }
        queries.update { it + (section.value to "") }
    }

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
            if (c == Category.Favorites || c == Category.ContinueWatching || c == Category.Recent) {
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
            if (c == Category.Favorites || c == Category.ContinueWatching) emptyFlow() else pager { dao.pageMovies(id, c.filterFlag(), c.groupTitle(), q) }
        }
        .cachedIn(viewModelScope)

    val favoriteMovies: StateFlow<List<MovieRow>> = sourceId.filterNotNull()
        .flatMapLatest { dao.observeFavoriteMovies(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: Flow<PagingData<SeriesRow>> = filterFor(HomeSection.SERIES)
        .flatMapLatest { (id, c, q) ->
            if (c == Category.Favorites || c == Category.ContinueWatching) emptyFlow() else pager { dao.pageSeries(id, c.filterFlag(), c.groupTitle(), q) }
        }
        .cachedIn(viewModelScope)

    val favoriteSeries: StateFlow<List<SeriesRow>> = sourceId.filterNotNull()
        .flatMapLatest { dao.observeFavoriteSeries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Films et séries commencés, du plus récent au plus ancien. */
    val continueMovies: StateFlow<List<ProgressEntity>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.progress.observeAll(id, ContentKind.MOVIE.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueSeries: StateFlow<List<ProgressEntity>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.progress.observeAll(id, ContentKind.SERIES.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Retire un film ou une série de « Reprendre la lecture ». */
    fun clearProgress(itemKey: String) {
        val id = sourceId.value ?: return
        viewModelScope.launch { container.progress.clear(id, itemKey) }
    }

    fun onChannelFocused(row: ChannelListRow) {
        focusedChannel.value = row
    }

    /** Accueil affiché ou quitté ; l'aperçu s'arrête aussitôt (une seule connexion pour la lecture). */
    fun setPreviewActive(active: Boolean) {
        previewActive.value = active
        if (!active) previewer.stop()
    }

    override fun onCleared() {
        previewer.stop()
    }

    fun selectSection(target: HomeSection) {
        section.value = target
        reorder.value = ReorderState()
    }

    fun selectCategory(target: Category) {
        categories.update { it + (section.value to target) }
        reorder.value = ReorderState()
    }

    /** Masque une catégorie de chaînes ([title] vide : chaînes sans groupe). */
    /** Masque la catégorie [title] de la section affichée (chaînes, films ou séries). */
    fun hideGroup(title: String) {
        val id = sourceId.value ?: return
        val kind = section.value.kind() ?: return
        viewModelScope.launch {
            hiddenGroups.hide(HiddenGroupEntity(id, kind.name, title))
            val name = title.ifEmpty { resources.getString(R.string.channels_no_group) }
            mutableMessages.emit(resources.getString(R.string.category_hidden, name))
        }
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
        const val PREVIEW_DELAY_MILLIS = 900L
        const val PREVIEW_PROGRAMMES = 6
        const val ROW_SIZE = 24
    }
}
