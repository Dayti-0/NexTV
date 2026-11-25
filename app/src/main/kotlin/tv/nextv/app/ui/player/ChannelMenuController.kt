package tv.nextv.app.ui.player

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.football.FootballMatch
import tv.nextv.app.ui.football.FootballMatchesFeed
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.home.Category
import tv.nextv.core.model.ContentKind

/**
 * Menu des chaînes du lecteur (ouvert par « Retour ») : catégories, liste paginée, favoris, volet du
 * programme de la chaîne sélectionnée et fiche d'un programme (appui long sur OK).
 *
 * @param sourceId playlist de la chaîne en cours (`null` avant son chargement).
 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelMenuController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    sourceId: StateFlow<Long?>,
    /** Le programme regardé est un match : les matchs en direct sont suivis (zapping entre les chaînes du match). */
    watchingMatch: Flow<Boolean> = flowOf(false),
) {
    private val mutableMenu = MutableStateFlow(ChannelMenuState())
    val menu: StateFlow<ChannelMenuState> = mutableMenu.asStateFlow()

    private val mutableProgrammeSheet = MutableStateFlow<ProgrammeSheetState?>(null)
    val programmeSheet: StateFlow<ProgrammeSheetState?> = mutableProgrammeSheet.asStateFlow()

    /** Chaîne sélectionnée dans le menu des chaînes. */
    private val menuFocus = MutableStateFlow<ChannelListRow?>(null)

    /** Programme de la chaîne sélectionnée dans le menu (volet de droite), lu après un court arrêt dessus. */
    val menuProgramme: StateFlow<MenuProgramme?> = menuFocus
        .transformLatest { row ->
            if (row == null) {
                emit(null)
                return@transformLatest
            }
            delay(MENU_PROGRAMME_DELAY_MILLIS)
            // Relu à la fin du programme affiché (ou au début du suivant) tant que la chaîne reste sélectionnée.
            while (true) {
                val now = System.currentTimeMillis()
                val upcoming = container.programmeDao.upcoming(row.id, now, limit = 2)
                    .map { GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year, it.imageUrl) }
                val current = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
                val next = upcoming.firstOrNull { it != current }
                emit(MenuProgramme(row, current, next))
                val change = current?.stopMillis ?: next?.startMillis ?: (now + MENU_PROGRAMME_RECHECK_MILLIS)
                delay((change - now).coerceIn(1_000L, MENU_PROGRAMME_RECHECK_MILLIS))
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<GroupRow>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.channelDao.observeGroups(id, ContentKind.LIVE.name)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: Flow<PagingData<MenuChannel>> = combine(sourceId, mutableMenu) { id, menu -> id to menu.category }
        .distinctUntilChanged()
        .flatMapLatest { (id, category) ->
            if (id == null || category == Category.Favorites) {
                emptyFlow()
            } else {
                Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
                    container.channelDao.pageChannels(
                        sourceId = id,
                        filterGroup = if (category is Category.Group) 1 else 0,
                        group = (category as? Category.Group)?.title.orEmpty(),
                        query = "",
                        now = System.currentTimeMillis(),
                    )
                }.flow.map { data -> data.map { MenuChannel(category, it) } }
            }
        }
        .cachedIn(scope)

    /** Heure relue régulièrement : programmes en cours des favoris (sinon figés à leur premier chargement). */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(FAVORITES_CLOCK_MILLIS)
        }
    }

    val favorites: StateFlow<List<ChannelListRow>> = combine(sourceId, clock) { id, now -> id to now }
        .flatMapLatest { (id, now) ->
            if (id == null) flowOf(emptyList()) else container.channelDao.observeFavoriteChannels(id, now)
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Calcul des matchs de la section Foot, un par playlist (son guide est gardé d'une ouverture du menu à l'autre). */
    private val feeds = HashMap<Long, FootballMatchesFeed>()

    /**
     * Matchs en direct (ou sur le point de commencer) avec les chaînes qui les diffusent, dans l'ordre de la section
     * Foot : une catégorie chacun en tête du menu. Suivis tant que le menu est ouvert ou qu'un match est regardé.
     */
    val liveMatches: StateFlow<List<MenuMatch>> = combine(sourceId, mutableMenu.map { it.open }.distinctUntilChanged(), watchingMatch) { id, open, watching ->
        id.takeIf { open || watching }
    }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) {
                emptyFlow()
            } else {
                feeds.getOrPut(id) { FootballMatchesFeed(container, id, scope) }.state
                    .filter { !it.loading }
                    .map { state -> menuMatchesOf(state.matches, System.currentTimeMillis()) }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Match choisi dans la section Foot (avant que le calcul ne le donne ici) : sa catégorie est là tout de suite. */
    private val pinnedMatch = MutableStateFlow<MenuMatch?>(null)

    val matches: StateFlow<List<MenuMatch>> = combine(liveMatches, pinnedMatch) { live, pinned ->
        if (pinned == null || live.any { it.category.key == pinned.category.key }) live else listOf(pinned) + live
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun pin(match: MenuMatch?) {
        pinnedMatch.value = match
    }

    /** Chaînes du match dont la catégorie est affichée, avec leur programme en cours, dans l'ordre du match. */
    val matchRows: StateFlow<MatchRows?> = combine(mutableMenu.map { it.category }.distinctUntilChanged(), matches, clock) { category, list, now ->
        Triple(category, list, now)
    }
        .transformLatest { (category, list, now) ->
            val match = (category as? Category.Match)?.let { wanted -> list.firstOrNull { it.category.key == wanted.key } }
            if (match == null) {
                emit(null)
                return@transformLatest
            }
            val byId = container.channelDao.channelRowsById(match.channelIds, now).associateBy { it.id }
            emit(MatchRows(match.category, match.channelIds.mapNotNull { byId[it] }))
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Chaîne sélectionnée dans le menu : son programme s'affiche à côté. */
    fun focus(row: ChannelListRow) {
        menuFocus.value = row
    }

    /**
     * Ouvre le menu sur la catégorie de la chaîne [current], à sa place dans la liste. [fromFavorites] : chaîne
     * ouverte depuis les favoris, menu ouvert sur les favoris (si elle en fait toujours partie).
     */
    fun open(current: ChannelEntity, fromFavorites: Boolean = false) {
        // Match en direct sur cette chaîne : le menu s'ouvre sur sa catégorie (ses autres chaînes, ◀ ▶ les autres matchs).
        matches.value.firstOrNull { current.id in it.channelIds }?.let { match ->
            mutableMenu.value = ChannelMenuState(
                open = true,
                category = match.category,
                currentChannelId = current.id,
                currentItemKey = current.itemKey,
                initialIndex = match.channelIds.indexOf(current.id).coerceAtLeast(0),
            )
            return
        }
        scope.launch {
            val dao = container.channelDao
            // Catégorie où la chaîne est rangée (chaînes rassemblées : sa catégorie par thème).
            val group = current.listGroup.orEmpty()
            // Lu dans la base : la liste des favoris du menu n'est pas encore chargée à la première ouverture.
            val favoriteIndex = suspend {
                dao.observeFavoriteChannels(current.sourceId, System.currentTimeMillis()).first()
                    .indexOfFirst { it.itemKey == current.itemKey }
            }
            val hiddenGroups = container.hiddenGroupDao
            val hidden = hiddenGroups.isHidden(current.sourceId, ContentKind.LIVE.name, group) ||
                hiddenGroups.isHidden(current.sourceId, ContentKind.LIVE.name, current.groupTitle.orEmpty())
            val favorite = if (fromFavorites || hidden) favoriteIndex() else -1
            val (category, index) = when {
                favorite >= 0 -> Category.Favorites to favorite
                // Catégorie masquée et chaîne hors des favoris : la liste « Toutes ».
                hidden -> Category.All to dao.indexInList(current.sourceId, 0, "", current.listOrder)
                else -> Category.Group(group) to dao.indexInList(current.sourceId, 1, group, current.listOrder)
            }
            mutableMenu.value = ChannelMenuState(
                open = true,
                category = category,
                currentChannelId = current.id,
                currentItemKey = current.itemKey,
                initialIndex = index,
            )
        }
    }

    fun close() {
        mutableMenu.update { it.copy(open = false) }
        menuFocus.value = null
    }

    fun selectCategory(category: Category) {
        mutableMenu.update { it.copy(category = category, initialIndex = 0) }
    }

    /** Programme en cours (ou le prochain) d'une chaîne du menu, avec sa description. */
    fun showProgramme(row: ChannelListRow) {
        scope.launch {
            val programme = container.programmeDao.upcoming(row.id, System.currentTimeMillis(), limit = 1).firstOrNull()
            mutableProgrammeSheet.value = ProgrammeSheetState(
                channel = GuideChannel(row.id, row.title.ifBlank { row.name }, row.channelNumber, row.logoUrl, emptyList()),
                programme = programme?.let {
                    GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year, it.imageUrl)
                },
            )
        }
    }

    fun hideProgramme() {
        mutableProgrammeSheet.value = null
    }

    private companion object {
        /** Défilement rapide dans le menu des chaînes : le programme n'est lu qu'à l'arrêt sur une chaîne. */
        const val MENU_PROGRAMME_DELAY_MILLIS = 150L

        /** Volet du programme : relu au plus tard après ce délai (programmes ajoutés par une actualisation). */
        const val MENU_PROGRAMME_RECHECK_MILLIS = 5 * 60_000L

        const val FAVORITES_CLOCK_MILLIS = 30_000L
    }
}

/** Match en direct du menu des chaînes : sa catégorie, ses chaînes (la préférée d'abord), son score et sa minute. */
data class MenuMatch(val category: Category.Match, val channelIds: List<Long>, val score: String? = null, val minute: String? = null)

/** Chaînes du match [category], dans son ordre. */
data class MatchRows(val category: Category.Match, val rows: List<ChannelListRow>)

/** Matchs de la section Foot montrés dans le menu : commencés (ou à moins de 15 min), pas encore finis, avec une chaîne. */
internal fun menuMatchesOf(matches: List<FootballMatch>, now: Long): List<MenuMatch> = matches
    .filter { it.channels.isNotEmpty() && it.kickoffMillis <= now + MATCH_EARLY_MILLIS && it.endMillis > now }
    .map { match ->
        MenuMatch(
            category = Category.Match(match.key, match.label),
            channelIds = match.channels.map { it.id },
            score = match.score,
            minute = if (match.halfTime) null else match.clock,
        )
    }

private const val MATCH_EARLY_MILLIS = 15 * 60_000L
