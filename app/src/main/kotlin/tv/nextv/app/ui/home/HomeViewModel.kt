package tv.nextv.app.ui.home

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.asStateFlow
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
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.app.ui.detail.recommendedInPlaylist
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
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.core.meta.MediaType
import tv.nextv.core.meta.MediaDetails
import tv.nextv.app.ui.detail.itemKeyOfCache
import tv.nextv.app.ui.detail.metadataCacheKey
import tv.nextv.app.data.db.ItemKeys
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Immutable

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

/**
 * Contenu des rangées des interfaces « Streaming » et « Classique » : premiers éléments d'une
 * catégorie ([String] vide : sans groupe), au plus le nombre demandé (« Voir plus » l'augmente).
 */
class HomeRows(
    val channels: suspend (group: String, limit: Int) -> List<ChannelListRow> = { _, _ -> emptyList() },
    val movies: suspend (group: String, limit: Int) -> List<MovieRow> = { _, _ -> emptyList() },
    val series: suspend (group: String, limit: Int) -> List<SeriesRow> = { _, _ -> emptyList() },
) {
    private class Entry(val version: String, val limit: Int, val rows: List<Any>)

    /**
     * Rangées déjà lues, avec la version et la longueur de leur lecture : une rangée qui revient à
     * l'écran (ou que la télécommande vise juste en dessous) est remplie aussitôt, sans attendre la base.
     */
    private val cache = ConcurrentHashMap<Pair<HomeSection, String>, Entry>()

    /** Dernière lecture de la rangée, quelle que soit sa version. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cached(section: HomeSection, group: String): List<T>? = cache[section to group]?.rows as List<T>?

    /** La rangée dans la [version] demandée, avec au moins [limit] éléments s'il y en a : gardée si elle est déjà lue, sinon lue dans la base. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> fresh(section: HomeSection, group: String, version: String, limit: Int = ROW_SIZE): List<T> {
        cache[section to group]?.takeIf { it.version == version && it.limit >= limit }?.let { return it.rows as List<T> }
        val rows: List<Any> = when (section) {
            HomeSection.TV -> channels(group, limit)
            HomeSection.MOVIES -> movies(group, limit)
            else -> series(group, limit)
        }
        cache[section to group] = Entry(version, limit, rows)
        return rows as List<T>
    }

    /** Autre playlist : rien n'est gardé. */
    fun clear() = cache.clear()

    companion object {
        /** Éléments d'une rangée à son premier affichage. */
        const val ROW_SIZE = 24

        /** Éléments ajoutés par « Voir plus ». */
        const val MORE_SIZE = 48
    }
}

/**
 * Film ou série affiché en affiche (rangées, grilles, Classique), avec les informations de la
 * tendance TMDB quand il en vient ([backdropUrl], [overview], [rating]).
 */
@Immutable
data class PosterItem(
    val id: Long,
    val kind: ContentKind,
    val itemKey: String,
    val title: String,
    val year: Int?,
    val imageUrl: String?,
    /** Épisodes d'une série. */
    val episodeCount: Int = 0,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    /** Qualité annoncée par la playlist ([tv.nextv.core.model.StreamQuality]), si elle l'indique. */
    val quality: String? = null,
)

fun MovieRow.toPoster() = PosterItem(id, ContentKind.MOVIE, itemKey, title, year, logoUrl, quality = quality)

fun SeriesRow.toPoster() = PosterItem(id, ContentKind.SERIES, itemKey, title, year, logoUrl, episodeCount, quality = quality)

/** Rangées de découverte des films ou des séries : tendances de la semaine, nouveautés, recommandations. */
@Immutable
data class Discover(
    val trending: List<PosterItem> = emptyList(),
    val newest: List<PosterItem> = emptyList(),
    val recommended: List<PosterItem> = emptyList(),
    /** Titre regardé qui inspire les recommandations. */
    val recommendedFrom: String? = null,
)

/** Bandes-annonces du bandeau de Streaming : lecture automatique, avec le son ou non, délai avant le lancement. */
@Immutable
data class TrailerSettings(val autoplay: Boolean = false, val sound: Boolean = false, val delayMillis: Long = 3_000L)

/** Affiche sélectionnée, puis sa fiche TMDB (visuel plein écran, résumé, genres, note) une fois lue. */
@Immutable
data class Spotlight(
    val item: PosterItem,
    val details: MediaDetails? = null,
    /** Fiche TMDB en cours de lecture : l'image de fond attend la sienne (pas d'affiche entre-temps). */
    val pending: Boolean = false,
    /** Saisons de la série dans la playlist (TMDB peut en compter moins ou plus que ce qui est proposé). */
    val playlistSeasons: Int? = null,
) {
    /** Nombre de saisons affiché : celles de la playlist, sinon celui de TMDB. */
    val seasonCount: Int? get() = playlistSeasons ?: details?.seasonCount

    val backdropUrl: String? get() = details?.backdropUrl ?: item.backdropUrl
    val overview: String? get() = details?.overview ?: item.overview
    /** Note TMDB, seulement quand assez de votes la rendent parlante (pas de « 10,0 » sur 2 votes). */
    val rating: Double? get() = if (details != null) details.rating?.takeIf { details.voteCount >= MIN_VOTES } else item.rating

    private companion object {
        const val MIN_VOTES = 10
    }
}

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

    /** Bandes-annonces du bandeau de Streaming (réglages) : lecture automatique, son, délai. */
    val trailers: StateFlow<TrailerSettings> = combine(
        container.settings.trailerAutoplay,
        container.settings.trailerSound,
        container.settings.trailerDelaySeconds,
    ) { autoplay, sound, delay -> TrailerSettings(autoplay, sound, delay * 1_000L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrailerSettings())

    val section = MutableStateFlow(HomeSection.TV)

    init {
        // Section affichée : notée pour « Dernier incident » (où l'application en était si le système la ferme).
        viewModelScope.launch { section.collect { container.crashLog?.section(it.name.lowercase()) } }
    }

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

    /** Nombres affichés sur les tuiles du menu de Classique : chaînes, films et séries. */
    val menuCounts: StateFlow<Map<HomeSection, Int>> = sourceId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyMap())
        } else {
            combine(
                dao.observeListedCount(id),
                dao.observeGroups(id, ContentKind.MOVIE.name).map { groups -> groups.sumOf { it.count } },
                dao.observeGroups(id, ContentKind.SERIES.name).map { groups -> groups.sumOf { it.count } },
            ) { channels, movies, series ->
                buildMap {
                    channels?.let { put(HomeSection.TV, it) }
                    put(HomeSection.MOVIES, movies)
                    put(HomeSection.SERIES, series)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Films et épisodes déjà vus (badge « Déjà vu »). */
    val watchedKeys: StateFlow<Set<String>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet()) else container.watched.observeKeys(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    private val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)

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
                GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year, it.imageUrl)
            }
            val current = upcoming.firstOrNull()?.takeIf { it.startMillis <= System.currentTimeMillis() }
            val following = upcoming.filter { it != current }
            ChannelPreview(row, current, following.firstOrNull(), following.drop(1))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Son de l'aperçu (réglages).
        viewModelScope.launch { container.settings.previewVolume.collect { previewer.volume = it.volume } }
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.DEFAULT)

    init {
        // Interfaces à rangées (Streaming, Classique) : aucune catégorie ne s'y choisit. Celle laissée
        // ouverte dans le Live Center (« Français ») les afficherait en catégories et grille, sans leurs rangées.
        viewModelScope.launch {
            layout.collect { if (it != HomeLayout.LIVE_CENTER) categories.value = emptyMap() }
        }
    }

    /** Premiers éléments de chaque catégorie, lus d'avance et gardés pendant que l'accueil existe. */
    val rows = HomeRows(
        channels = { group, limit -> sourceId.value?.let { dao.channelRow(it, group, System.currentTimeMillis(), limit) }.orEmpty() },
        movies = { group, limit -> sourceId.value?.let { dao.movieRow(it, group.ifEmpty { null }, limit) }.orEmpty() },
        series = { group, limit -> sourceId.value?.let { dao.seriesRow(it, group.ifEmpty { null }, limit) }.orEmpty() },
    )

    init {
        viewModelScope.launch { sourceId.collect { rows.clear() } }
    }

    /**
     * Rangées de découverte, lues quand une interface qui les montre est choisie (Streaming, Cinéma
     * Premium) : les tendances demandent TMDB.
     */
    private fun discover(kind: ContentKind, progress: Flow<List<ProgressEntity>>): StateFlow<Discover> =
        combine(sourceId.filterNotNull(), layout, progress.map { it.firstOrNull() }.distinctUntilChangedBy { it?.itemKey }, ::Triple)
            .mapLatest { (id, layout, lastWatched) ->
                if (layout == HomeLayout.LIVE_CENTER) return@mapLatest Discover()
                val isMovie = kind == ContentKind.MOVIE
                val minYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1
                val newest = runCatching {
                    if (isMovie) dao.newestMovies(id, minYear, ROW_SIZE).map { it.toPoster() } else dao.newestSeries(id, minYear, ROW_SIZE).map { it.toPoster() }
                }.getOrDefault(emptyList())
                // « Parce que vous avez regardé » : les titres que TMDB recommande pour le dernier titre regardé,
                // présents dans la playlist, sans ceux déjà commencés. Sans TMDB ou sans résultat : pas de rangée
                // (prendre la même catégorie de la playlist donnait des titres au hasard).
                val watched = runCatching { container.progress.observeAll(id, kind.name).first().mapTo(HashSet()) { it.itemKey } }.getOrDefault(emptySet())
                val recommended = if (lastWatched == null || !container.hasTmdbKey()) {
                    emptyList()
                } else {
                    runCatching { recommendedFor(id, kind, lastWatched) }.getOrDefault(emptyList())
                        .filter { it.itemKey !in watched }.take(ROW_SIZE)
                }
                val trending = trending(id, kind)
                // Streaming : fiches TMDB des contenus mis en avant lues d'avance (bande-annonce connue tout de suite).
                if (layout == HomeLayout.STREAMING) prefetchDetails(trending.ifEmpty { newest }.take(PREFETCH_COUNT))
                Discover(trending = trending, newest = newest, recommended = recommended, recommendedFrom = lastWatched?.title?.takeIf { recommended.isNotEmpty() })
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Discover())

    /** Recommandations TMDB du titre [watched] (fiche lue, ou déjà en cache), retrouvées dans la playlist. */
    private suspend fun recommendedFor(sourceId: Long, kind: ContentKind, watched: ProgressEntity): List<PosterItem> {
        val entity = dao.get(watched.channelId)
        val type = if (kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE
        val rawTitle = (if (kind == ContentKind.SERIES) entity?.seriesName ?: entity?.title else entity?.title) ?: watched.title
        val title = ContentClassifier.cleanTitle(rawTitle).ifBlank { rawTitle }
        val state = container.metadata.details(metadataCacheKey(watched.itemKey), type, title, entity?.year)
        val details = (state as? MetadataState.Available)?.details ?: return emptyList()
        return recommendedInPlaylist(container, sourceId, kind, watched.itemKey, details, ROW_SIZE * 2)
    }

    /** Tendances TMDB de la semaine retrouvées dans la playlist (titre et année, ou titre original). */
    private suspend fun trending(sourceId: Long, kind: ContentKind): List<PosterItem> {
        if (!container.hasTmdbKey()) return emptyList()
        val isMovie = kind == ContentKind.MOVIE
        val titles = runCatching { container.metadata.trending(if (isMovie) MediaType.MOVIE else MediaType.SERIES) }.getOrDefault(emptyList())
        if (titles.isEmpty()) return emptyList()
        fun keyOf(title: String, year: Int?) =
            if (isMovie) "movie:${ItemKeys.normalize(title)}:${year ?: ""}" else "series:${ItemKeys.normalize(title)}"
        val keys = titles.associateBy { keyOf(it.title, it.year) } +
            titles.filter { it.originalTitle != null }.associateBy { keyOf(it.originalTitle!!, it.year) }
        val found = runCatching {
            if (isMovie) dao.moviesByKey(sourceId, keys.keys.toList()).map { it.toPoster() } else dao.seriesByKey(sourceId, keys.keys.toList()).map { it.toPoster() }
        }.getOrDefault(emptyList()).distinctBy { it.itemKey }
        return found.mapNotNull { item ->
            val trend = keys[item.itemKey] ?: return@mapNotNull null
            // Affiche TMDB d'abord : celles des playlists sont souvent des images en largeur recadrées, floues ou absentes.
            item.copy(backdropUrl = trend.backdropUrl, overview = trend.overview, rating = trend.rating, imageUrl = trend.posterUrl ?: item.imageUrl)
        }.sortedBy { item -> titles.indexOf(keys[item.itemKey]) }
    }

    val movieDiscover: StateFlow<Discover> by lazy { discover(ContentKind.MOVIE, continueMovies) }
    val seriesDiscover: StateFlow<Discover> by lazy { discover(ContentKind.SERIES, continueSeries) }

    private val spotlightTarget = MutableStateFlow<PosterItem?>(null)

    /** Affiche sélectionnée et sa fiche TMDB, lue après un court arrêt dessus (pas à chaque déplacement). */
    val spotlight: StateFlow<Spotlight?> = spotlightTarget
        .transformLatest { item ->
            if (item == null) {
                emit(null)
                return@transformLatest
            }
            val seasons = if (item.kind == ContentKind.SERIES) {
                sourceId.value?.let { id -> runCatching { dao.seasonCount(id, item.itemKey) }.getOrNull() }?.takeIf { it > 0 }
            } else {
                null
            }
            if (!container.hasTmdbKey()) {
                emit(Spotlight(item, playlistSeasons = seasons))
                return@transformLatest
            }
            val type = if (item.kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE
            val key = metadataCacheKey(item.itemKey)
            // Fiche déjà lue : son image tout de suite, sans passer par l'affiche de la liste.
            runCatching { container.metadata.cachedDetails(key, type) }.getOrNull()?.let {
                emit(Spotlight(item, it, playlistSeasons = seasons))
                return@transformLatest
            }
            // Sinon l'image de fond précédente reste jusqu'à la bonne (l'affiche puis l'image TMDB, une demi-seconde
            // après, faisaient deux changements d'image).
            emit(Spotlight(item, pending = item.backdropUrl == null, playlistSeasons = seasons))
            delay(SPOTLIGHT_DELAY_MILLIS)
            val state = runCatching { container.metadata.details(key, type, item.title, item.year) }.getOrNull()
            emit(Spotlight(item, (state as? MetadataState.Available)?.details, playlistSeasons = seasons))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Fiches TMDB lues en arrière-plan, l'une après l'autre (mises en cache pour le bandeau). */
    private fun prefetchDetails(items: List<PosterItem>) {
        if (items.isEmpty() || !container.hasTmdbKey()) return
        viewModelScope.launch {
            for (item in items) {
                val type = if (item.kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE
                val key = metadataCacheKey(item.itemKey)
                if (runCatching { container.metadata.cachedDetails(key, type) }.getOrNull() != null) continue
                runCatching { container.metadata.details(key, type, item.title, item.year) }
            }
        }
    }

    // --- Affiches TMDB des rangées ---------------------------------------------------------------

    /** Affiches TMDB connues, par clé de titre, et titres sans affiche TMDB. */
    private val posterArt = MutableStateFlow(emptyMap<String, String>() to emptySet<String>())
    val posters: StateFlow<Pair<Map<String, String>, Set<String>>> = posterArt.asStateFlow()

    /** Titres à chercher sur TMDB (les plus récemment affichés d'abord), et ceux déjà demandés. */
    private val posterQueue = ArrayDeque<PosterItem>()
    private val posterAsked = HashSet<String>()
    private val posterSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        // Affiches des fiches déjà lues (base de l'appli) : tout de suite, sans réseau.
        viewModelScope.launch {
            val cached = runCatching { container.metadata.cachedPosters() }.getOrDefault(emptyMap())
            val posters = HashMap<String, String>()
            val missing = HashSet<String>()
            for ((cacheKey, url) in cached) {
                val itemKey = itemKeyOfCache(cacheKey) ?: continue
                if (url != null) posters[itemKey] = url else missing += itemKey
            }
            posterArt.update { (known, absent) -> (posters + known) to (missing - known.keys + absent) }
        }
        // Recherche sur TMDB des titres affichés, quelques-uns à la fois.
        repeat(POSTER_WORKERS) {
            viewModelScope.launch {
                while (true) {
                    val item = synchronized(posterQueue) { posterQueue.removeLastOrNull() }
                    if (item == null) {
                        posterSignal.receive()
                        continue
                    }
                    val type = if (item.kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE
                    val state = runCatching { container.metadata.details(metadataCacheKey(item.itemKey), type, item.title, item.year) }.getOrNull()
                    val url = (state as? MetadataState.Available)?.details?.posterUrl
                    posterArt.update { (known, absent) -> if (url != null) (known + (item.itemKey to url)) to absent else known to (absent + item.itemKey) }
                }
            }
        }
    }

    /** Films affichés sans badge de qualité : leur fiche est demandée au serveur Xtream, un à la fois. */
    private val qualityQueue = ArrayDeque<Long>()
    private val qualitySignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val qualityAsked = HashSet<Long>()
    private var qualityWorker: kotlinx.coroutines.Job? = null

    fun requestQuality(item: PosterItem) {
        if (item.kind != ContentKind.MOVIE || item.quality != null) return
        synchronized(qualityQueue) {
            if (!qualityAsked.add(item.id)) return
            qualityQueue.addLast(item.id)
            // Défilement rapide : les films passés depuis longtemps ne sont plus demandés.
            while (qualityQueue.size > QUALITY_QUEUE_SIZE) qualityQueue.removeFirstOrNull()?.let { qualityAsked.remove(it) }
        }
        if (qualityWorker == null) {
            qualityWorker = viewModelScope.launch {
                while (true) {
                    val id = synchronized(qualityQueue) { qualityQueue.removeLastOrNull() }
                    if (id == null) {
                        qualitySignal.receive()
                        continue
                    }
                    val channel = runCatching { container.channelDao.get(id) }.getOrNull() ?: continue
                    // La ligne mise à jour fait apparaître le badge dans les listes (base observée).
                    runCatching { container.measuredQualities.probe(channel) }
                }
            }
        }
        qualitySignal.trySend(Unit)
    }

    /** Affiche d'un film ou d'une série qui s'affiche dans une rangée : cherchée sur TMDB si elle n'est pas connue. */
    fun requestPoster(item: PosterItem) {
        if (item.kind == ContentKind.LIVE || !container.hasTmdbKey()) {
            posterArt.update { (known, absent) -> known to (absent + item.itemKey) }
            return
        }
        val (known, absent) = posterArt.value
        if (item.itemKey in known || item.itemKey in absent) return
        synchronized(posterQueue) {
            if (!posterAsked.add(item.itemKey)) return
            posterQueue.addLast(item)
            // Défilement rapide : les titres passés depuis longtemps ne sont plus cherchés.
            while (posterQueue.size > POSTER_QUEUE_SIZE) posterQueue.removeFirst()?.let { posterAsked.remove(it.itemKey) }
        }
        posterSignal.trySend(Unit)
    }

    fun onPosterFocused(item: PosterItem) {
        if (spotlightTarget.value?.itemKey != item.itemKey) spotlightTarget.value = item
    }

    /** Retour aux rangées (interface « Streaming ») : catégorie « Toutes » et recherche vidée. */
    /** Catégories et recherches des sections avant la recherche de partout, rendues à sa fermeture. */
    private var beforeSearch: Pair<Map<HomeSection, Category>, Map<HomeSection, String>>? = null

    /** Recherche de partout : le même texte dans les chaînes, les films et les séries (toutes catégories). */
    fun searchEverywhere(text: String) {
        if (beforeSearch == null) beforeSearch = categories.value to queries.value
        val searched = listOf(HomeSection.TV, HomeSection.MOVIES, HomeSection.SERIES)
        categories.update { it + searched.associateWith { Category.All } }
        queries.update { it + searched.associateWith { text } }
    }

    fun closeSearchEverywhere() {
        val (savedCategories, savedQueries) = beforeSearch ?: return
        beforeSearch = null
        categories.value = savedCategories
        queries.value = savedQueries
    }

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
    val continueMovies: StateFlow<List<ProgressEntity>> = continueWatching(ContentKind.MOVIE)

    val continueSeries: StateFlow<List<ProgressEntity>> = continueWatching(ContentKind.SERIES)

    /**
     * Reprises d'un type, sans celles dont le film ou l'épisode n'est plus dans la playlist (titre retiré par le
     * fournisseur) : « Reprendre » ouvrirait un titre introuvable. Elles reviennent si le titre revient. Relu à
     * chaque reprise enregistrée et à chaque actualisation de la playlist.
     */
    private fun continueWatching(kind: ContentKind): StateFlow<List<ProgressEntity>> = sourceId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            combine(
                container.progress.observeAll(id, kind.name),
                container.playlistStore.observeSource(id).map { it?.status?.lastSuccessAtMillis }.distinctUntilChanged(),
            ) { rows, _ -> rows }.mapLatest { rows ->
                if (rows.isEmpty()) return@mapLatest rows
                val present = runCatching {
                    rows.map { it.channelId }.distinct().chunked(SQL_VARIABLES).flatMap { dao.identities(it) }.associateBy { it.id }
                }.getOrNull() ?: return@mapLatest rows
                rows.filter { row -> present[row.channelId]?.let { it.sourceId == row.sourceId && it.itemKey == row.itemKey } == true }
            }
        }
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
        // Le lecteur de l'aperçu (et ses décodeurs) est rendu : le plein écran en a besoin.
        if (!active) previewer.release()
    }

    /**
     * Chaîne [channelId] ouverte en plein écran : si l'aperçu la joue déjà, son lecteur est confié au plein
     * écran (même connexion, pas de nouvelle ouverture du flux) ; sinon l'aperçu est simplement arrêté.
     */
    fun openFromPreview(channelId: Long) {
        previewer.handOff(channelId)?.let(container.previewHandoff::offer)
        setPreviewActive(false)
    }

    override fun onCleared() {
        previewer.release()
    }

    fun selectSection(target: HomeSection) {
        if (section.value != target) spotlightTarget.value = null
        section.value = target
        reorder.value = ReorderState()
    }

    /** Catégorie choisie : la recherche en cours de la section est effacée (la catégorie s'affiche en entier). */
    fun selectCategory(target: Category) {
        categories.update { it + (section.value to target) }
        if (queries.value[section.value].orEmpty().isNotEmpty()) queries.update { it + (section.value to "") }
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

    /** Recherche : dans toute la section (tous les films, toutes les séries, toutes les chaînes), quelle que soit la catégorie. */
    fun setQuery(text: String) {
        queries.update { it + (section.value to text) }
        if (text.isNotBlank() && (categories.value[section.value] ?: Category.All) != Category.All) {
            categories.update { it + (section.value to Category.All) }
        }
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

    /** Déplace un favori parmi ceux affichés (la liste de l'écran). */
    fun moveFavorite(kind: ContentKind, itemKey: String, offset: Int) {
        val id = sourceId.value ?: return
        val shown = when (kind) {
            ContentKind.LIVE -> favoriteChannels.value.map { it.itemKey }
            ContentKind.MOVIE -> favoriteMovies.value.map { it.itemKey }
            ContentKind.SERIES -> favoriteSeries.value.map { it.itemKey }
        }
        viewModelScope.launch { container.favorites.move(id, kind, itemKey, offset, shown.takeIf { itemKey in it }) }
    }

    private fun <T : Any> pager(source: () -> androidx.paging.PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false), pagingSourceFactory = source).flow

    private fun Category.filterFlag() = if (this is Category.Group) 1 else 0

    private fun Category.groupTitle() = (this as? Category.Group)?.title.orEmpty()

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        /** Affiches TMDB cherchées en même temps, et titres en attente au plus. */
        const val POSTER_WORKERS = 4
        const val POSTER_QUEUE_SIZE = 40
        const val QUALITY_QUEUE_SIZE = 40

        const val PAGE_SIZE = 60

        /** Variables d'une requête SQL (anciennes versions de SQLite : 999 au plus). */
        const val SQL_VARIABLES = 500
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val NOW_REFRESH_MILLIS = 30_000L
        const val PREVIEW_DELAY_MILLIS = 900L
        const val PREVIEW_PROGRAMMES = 6
        const val ROW_SIZE = HomeRows.ROW_SIZE
        const val SPOTLIGHT_DELAY_MILLIS = 350L

        /** Contenus mis en avant dont la fiche TMDB est lue d'avance. */
        const val PREFETCH_COUNT = 6
    }
}
