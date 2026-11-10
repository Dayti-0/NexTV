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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
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
) {
    private val mutableMenu = MutableStateFlow(ChannelMenuState())
    val menu: StateFlow<ChannelMenuState> = mutableMenu.asStateFlow()

    private val mutableProgrammeSheet = MutableStateFlow<ProgrammeSheetState?>(null)
    val programmeSheet: StateFlow<ProgrammeSheetState?> = mutableProgrammeSheet.asStateFlow()

    /** Chaîne sélectionnée dans le menu des chaînes. */
    private val menuFocus = MutableStateFlow<ChannelListRow?>(null)

    /** Programme de la chaîne sélectionnée dans le menu (volet de droite), lu après un court arrêt dessus. */
    val menuProgramme: StateFlow<MenuProgramme?> = menuFocus
        .mapLatest { row ->
            if (row == null) return@mapLatest null
            delay(MENU_PROGRAMME_DELAY_MILLIS)
            val now = System.currentTimeMillis()
            val upcoming = container.programmeDao.upcoming(row.id, now, limit = 2)
                .map { GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year, it.imageUrl) }
            val current = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
            MenuProgramme(row, current, upcoming.firstOrNull { it != current })
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

    val favorites: StateFlow<List<ChannelListRow>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.channelDao.observeFavoriteChannels(id, System.currentTimeMillis())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chaîne sélectionnée dans le menu : son programme s'affiche à côté. */
    fun focus(row: ChannelListRow) {
        menuFocus.value = row
    }

    /**
     * Ouvre le menu sur la catégorie de la chaîne [current], à sa place dans la liste. [fromFavorites] : chaîne
     * ouverte depuis les favoris, menu ouvert sur les favoris (si elle en fait toujours partie).
     */
    fun open(current: ChannelEntity, fromFavorites: Boolean = false) {
        scope.launch {
            val dao = container.channelDao
            val group = current.groupTitle.orEmpty()
            // Lu dans la base : la liste des favoris du menu n'est pas encore chargée à la première ouverture.
            val favoriteIndex = suspend {
                dao.observeFavoriteChannels(current.sourceId, System.currentTimeMillis()).first()
                    .indexOfFirst { it.itemKey == current.itemKey }
            }
            val hidden = container.hiddenGroupDao.isHidden(current.sourceId, ContentKind.LIVE.name, group)
            val favorite = if (fromFavorites || hidden) favoriteIndex() else -1
            val (category, index) = when {
                favorite >= 0 -> Category.Favorites to favorite
                // Catégorie masquée et chaîne hors des favoris : la liste « Toutes ».
                hidden -> Category.All to dao.indexInList(current.sourceId, 0, "", current.position)
                else -> Category.Group(group) to dao.indexInList(current.sourceId, 1, group, current.position)
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
    }
}
