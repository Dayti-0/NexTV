package tv.nextv.app.ui.home

import tv.nextv.app.ui.components.FadingBottom
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.channelCount
import tv.nextv.app.ui.components.LocalTabsFocused
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.components.BrandMark
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.fieldKeyboardOptions
import tv.nextv.app.ui.components.RemoteTextField
import tv.nextv.app.ui.components.openKeyboardOnOk
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.football.FootballScreen
import tv.nextv.app.ui.guide.GuideScreen
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.ContentKind

/** Actions de l'accueil, regroupées pour alléger les signatures. */
class HomeActions(
    val onSection: (HomeSection) -> Unit = {},
    val onCategory: (Category) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPlayChannel: (Long) -> Unit = {},
    val onOpenMovie: (Long) -> Unit = {},
    val onOpenSeries: (Long) -> Unit = {},
    val onToggleFavorite: (ContentKind, String, String, String?) -> Unit = { _, _, _, _ -> },
    val onToggleReorder: () -> Unit = {},
    val onPick: (String) -> Unit = {},
    val onMove: (ContentKind, String, Int) -> Unit = { _, _, _ -> },
    val onSettings: () -> Unit = {},
    /** Reprendre un film ou un épisode commencé (sans question). */
    val onResume: (Long) -> Unit = {},
    val onClearProgress: (String) -> Unit = {},
    /** Appui long sur une catégorie de chaînes : proposer de la masquer ([String] vide : sans groupe). */
    val onHideCategory: (String) -> Unit = {},
    /** Chaîne sélectionnée dans la grille : aperçu et programme dans le panneau du haut. */
    val onChannelFocus: (ChannelListRow) -> Unit = {},
    /** Film ou série sélectionné : bannière, volet ou fond plein écran de l'interface. */
    val onPosterFocus: (PosterItem) -> Unit = {},
)

@Composable
fun HomeScreen(
    onPlay: (Long) -> Unit,
    onMovie: (Long) -> Unit,
    onSeries: (Long) -> Unit,
    onSettings: () -> Unit,
    onResume: (Long) -> Unit = onPlay,
    /** Programme passé d'une chaîne avec replay (chaîne, début, fin). */
    onReplay: (Long, Long, Long) -> Unit = { _, _, _ -> },
) {
    val resources = LocalContext.current.resources
    val viewModel = appViewModel { container, _ -> HomeViewModel(container, resources) }
    val section by viewModel.section.collectAsStateWithLifecycle()
    val sourceId by viewModel.sourceId.collectAsStateWithLifecycle()
    val sourceName by viewModel.sourceName.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val reorder by viewModel.reorder.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val favoriteChannels by viewModel.favoriteChannels.collectAsStateWithLifecycle()
    val favoriteMovies by viewModel.favoriteMovies.collectAsStateWithLifecycle()
    val favoriteSeries by viewModel.favoriteSeries.collectAsStateWithLifecycle()
    val continueMovies by viewModel.continueMovies.collectAsStateWithLifecycle()
    val continueSeries by viewModel.continueSeries.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val channelCount by viewModel.channelCount.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val previewEnabled by viewModel.previewEnabled.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val previewPlayer by viewModel.previewPlayer.collectAsStateWithLifecycle()
    val spotlight by viewModel.spotlight.collectAsStateWithLifecycle()
    val discover by (if (section == HomeSection.SERIES) viewModel.seriesDiscover else viewModel.movieDiscover).collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val movies = viewModel.movies.collectAsLazyPagingItems()
    val series = viewModel.series.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var pendingHide by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    // Programmes en cours : rechargement périodique sans vider la grille.
    LaunchedEffect(channels) {
        while (true) {
            delay(CHANNELS_REFRESH_MILLIS)
            channels.refresh()
        }
    }
    // « Retour » depuis l'accueil : l'application ne se ferme qu'après confirmation (pas de sortie par
    // maladresse). Déclaré en premier : les autres usages de « Retour » à l'accueil passent avant.
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    // Interfaces à rangées : « Retour » ramène d'une catégorie ou d'une recherche aux rangées.
    BackHandler(enabled = layout != HomeLayout.LIVE_CENTER && section.kind() != null && (category != Category.All || query.isNotEmpty())) {
        viewModel.showRows()
    }
    // Déclaré après : pendant la réorganisation des favoris, « Retour » la termine d'abord.
    BackHandler(enabled = reorder.active) { viewModel.toggleReorder() }
    // Aperçu seulement quand l'accueil est affiché (arrêté en arrière-plan et à l'ouverture d'un autre écran).
    LifecycleStartEffect(viewModel) {
        viewModel.setPreviewActive(true)
        onStopOrDispose { viewModel.setPreviewActive(false) }
    }

    // Connexion de l'aperçu libérée avant la lecture en plein écran.
    val playChannel: (Long) -> Unit = {
        viewModel.setPreviewActive(false)
        onPlay(it)
    }

    HomeContent(
        section = section,
        sourceName = sourceName,
        groups = groups,
        category = category,
        query = query,
        channels = channels,
        favoriteChannels = favoriteChannels,
        movies = movies,
        favoriteMovies = favoriteMovies,
        series = series,
        favoriteSeries = favoriteSeries,
        favoriteKeys = favoriteKeys,
        reorder = reorder,
        now = now,
        snackbarHostState = snackbar,
        actions = HomeActions(
            onSection = viewModel::selectSection,
            onCategory = viewModel::selectCategory,
            onQuery = viewModel::setQuery,
            onPlayChannel = playChannel,
            onOpenMovie = onMovie,
            onOpenSeries = onSeries,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleReorder = viewModel::toggleReorder,
            onPick = viewModel::pick,
            onMove = viewModel::moveFavorite,
            onSettings = onSettings,
            onResume = onResume,
            onClearProgress = viewModel::clearProgress,
            onHideCategory = { pendingHide = it },
            onChannelFocus = viewModel::onChannelFocused,
            onPosterFocus = viewModel::onPosterFocused,
        ),
        guide = { modifier ->
            sourceId?.let {
                GuideScreen(it, playChannel, modifier, onReplay = { channelId, start, stop ->
                    viewModel.setPreviewActive(false)
                    onReplay(channelId, start, stop)
                })
            }
        },
        football = { modifier -> sourceId?.let { FootballScreen(it, playChannel, modifier) } },
        continueMovies = continueMovies,
        continueSeries = continueSeries,
        gridColumns = gridColumns,
        channelCount = channelCount,
        recentChannels = recentChannels,
        showPreview = previewEnabled,
        preview = preview,
        previewPlayer = previewPlayer,
        layout = layout,
        rows = viewModel.rows,
        discover = discover,
        spotlight = spotlight,
    )

    if (confirmExit) {
        val activity = LocalActivity.current
        val stay = remember { FocusRequester() }
        // « Non » sélectionné d'office : un appui de trop sur OK ne ferme pas l'application.
        LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_message)) },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.exit_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }, modifier = Modifier.focusRequester(stay)) { Text(stringResource(R.string.exit_no)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.ignoreHeldOk(),
        )
    }

    pendingHide?.let { title ->
        val name = title.ifEmpty { stringResource(R.string.channels_no_group) }
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text(stringResource(R.string.category_hide_title, name)) },
            text = { Text(stringResource(R.string.category_hide_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.hideGroup(title)
                    pendingHide = null
                }) { Text(stringResource(R.string.category_hide)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingHide = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.ignoreHeldOk(),
        )
    }
}

/** Accueil : sections TV, Films, Séries, Guide TV et Foot. Sans dépendance au ViewModel (captures d'écran). */
@Composable
fun HomeContent(
    section: HomeSection,
    sourceName: String,
    groups: List<GroupRow>,
    category: Category,
    query: String,
    channels: LazyPagingItems<ChannelListRow>,
    favoriteChannels: List<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    favoriteMovies: List<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    favoriteSeries: List<SeriesRow>,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    now: Long,
    snackbarHostState: SnackbarHostState,
    actions: HomeActions,
    guide: @Composable (Modifier) -> Unit,
    /** Section « Foot » : matchs à venir, d'après le guide. */
    football: @Composable (Modifier) -> Unit = {},
    continueMovies: List<ProgressEntity> = emptyList(),
    continueSeries: List<ProgressEntity> = emptyList(),
    gridColumns: GridColumns = GridColumns(),
    /** Chaînes de la liste « Toutes » (une ligne par chaîne) ; `null` : somme des catégories. */
    channelCount: Int? = null,
    /** Chaînes regardées récemment (catégorie « Récentes »). */
    recentChannels: List<ChannelListRow> = emptyList(),
    /** Aperçu vidéo de la chaîne sélectionnée (réglages). */
    showPreview: Boolean = false,
    preview: ChannelPreview? = null,
    previewPlayer: Player? = null,
    /** Interface choisie dans les réglages (Apparence). */
    layout: HomeLayout = HomeLayout.DEFAULT,
    /** Contenu des rangées (interfaces Streaming et Cinéma Premium). */
    rows: HomeRows = HomeRows(),
    /** Tendances, nouveautés et recommandations de la section affichée. */
    discover: Discover = Discover(),
    /** Film ou série sélectionné, avec sa fiche TMDB. */
    spotlight: Spotlight? = null,
) {
    val window = rememberWindowInfo()
    // Dernier élément ouvert : sa liste revient à sa place et il reprend le focus au retour sur l'accueil.
    rememberHomeVisit()
    val lastOpened = rememberLastOpened()
    // Vignette sélectionnée : son nom complet s'affiche en haut à droite (les vignettes le tronquent).
    val focused = remember(section, category) { mutableStateOf<FocusedItem?>(null) }
    // Onglets sélectionnés : les sections qui s'ouvrent leur laissent le focus.
    val tabsFocused = remember { mutableStateOf(false) }
    val player = previewPlayer.takeIf { showPreview }
    val catalog = section.kind() != null
    val resumeItems = when (section) {
        HomeSection.MOVIES -> continueMovies
        HomeSection.SERIES -> continueSeries
        else -> null
    }
    val shelfContent = ShelfContent(
        section = section,
        groups = groups,
        rows = rows,
        favoriteChannels = favoriteChannels,
        recentChannels = recentChannels,
        favoriteMovies = favoriteMovies,
        favoriteSeries = favoriteSeries,
        continueItems = resumeItems.orEmpty(),
        discover = discover,
        favoriteKeys = favoriteKeys,
        now = now,
    )
    // Barre des onglets de l'interface choisie, la même dans toutes les sections (Guide TV et Foot compris).
    val topBar: @Composable () -> Unit = {
        HomeTopBar(section, sourceName, window, actions, onTabsFocused = { tabsFocused.value = it }, style = layout)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        CompositionLocalProvider(LocalTabsFocused provides { tabsFocused.value }) {
            // Cinéma Premium : la sélection occupe tout l'écran, onglets compris.
            if (catalog && layout == HomeLayout.PREMIUM && category == Category.All) {
                PremiumHome(
                    content = shelfContent,
                    window = window,
                    query = query,
                    channels = channels,
                    movies = movies,
                    series = series,
                    preview = preview,
                    previewPlayer = player,
                    spotlight = spotlight,
                    lastOpened = lastOpened,
                    actions = actions,
                    topBar = topBar,
                    modifier = Modifier.padding(padding),
                )
                return@CompositionLocalProvider
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                topBar()
                when {
                    section == HomeSection.GUIDE -> guide(Modifier.weight(1f))
                    section == HomeSection.FOOTBALL -> football(Modifier.weight(1f))
                    layout == HomeLayout.STREAMING && category == Category.All -> StreamingHome(
                        content = shelfContent,
                        window = window,
                        query = query,
                        channels = channels,
                        movies = movies,
                        series = series,
                        preview = preview,
                        previewPlayer = player,
                        spotlight = spotlight,
                        lastOpened = lastOpened,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                    else -> CatalogBody(
                        section = section,
                        window = window,
                        layout = layout,
                        groups = groups,
                        category = category,
                        query = query,
                        channels = channels,
                        favoriteChannels = favoriteChannels,
                        movies = movies,
                        favoriteMovies = favoriteMovies,
                        series = series,
                        favoriteSeries = favoriteSeries,
                        favoriteKeys = favoriteKeys,
                        reorder = reorder,
                        now = now,
                        actions = actions,
                        resumeItems = resumeItems,
                        gridColumns = gridColumns,
                        channelCount = channelCount,
                        recentChannels = recentChannels,
                        showPreview = showPreview,
                        preview = preview,
                        previewPlayer = player,
                        spotlight = spotlight,
                        lastOpened = lastOpened,
                        focused = focused,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Catégorie de chaînes, films ou séries : en Live Center (écran large), colonnes des catégories, de
 * la liste et du volet de la sélection ; sinon (catégorie ouverte depuis les rangées, téléphone),
 * catégories et grille, avec l'aperçu du direct en haut pour les chaînes.
 */
@Composable
private fun CatalogBody(
    section: HomeSection,
    window: WindowInfo,
    layout: HomeLayout,
    groups: List<GroupRow>,
    category: Category,
    query: String,
    channels: LazyPagingItems<ChannelListRow>,
    favoriteChannels: List<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    favoriteMovies: List<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    favoriteSeries: List<SeriesRow>,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    now: Long,
    actions: HomeActions,
    resumeItems: List<ProgressEntity>?,
    gridColumns: GridColumns,
    channelCount: Int?,
    recentChannels: List<ChannelListRow>,
    showPreview: Boolean,
    preview: ChannelPreview?,
    previewPlayer: Player?,
    spotlight: Spotlight?,
    lastOpened: MutableState<OpenedItem?>,
    focused: MutableState<FocusedItem?>,
    modifier: Modifier,
) {
    val favoritesCount = when (section) {
        HomeSection.TV -> favoriteChannels.size
        HomeSection.MOVIES -> favoriteMovies.size
        else -> favoriteSeries.size
    }
    // Une chaîne peut figurer dans plusieurs catégories (une par qualité) : compte à part.
    val allCount = channelCount?.takeIf { section == HomeSection.TV } ?: groups.sumOf { it.count }
    val body: @Composable (GridLayout) -> Unit = { grid ->
        if (resumeItems != null && category == Category.ContinueWatching) {
            ContinueBody(resumeItems, grid, window, lastOpened, actions, focused)
        } else when (section) {
            HomeSection.TV -> TvBody(channels, favoriteChannels, recentChannels, category, favoriteKeys, reorder, now, grid, window, lastOpened, actions, focused)
            HomeSection.MOVIES -> MoviesBody(movies, favoriteMovies, category, favoriteKeys, reorder, grid, window, lastOpened, actions, focused)
            else -> SeriesBody(series, favoriteSeries, category, favoriteKeys, reorder, grid, window, lastOpened, actions, focused)
        }
    }
    val reorderButton: (@Composable () -> Unit)? = if (category == Category.Favorites && favoritesCount > 1) {
        {
            PillButton(
                stringResource(if (reorder.active) R.string.favorites_reorder_done else R.string.favorites_reorder),
                actions.onToggleReorder,
                icon = if (reorder.active) Icons.Filled.Check else Icons.Filled.Edit,
            )
        }
    } else {
        null
    }
    if (layout == HomeLayout.LIVE_CENTER && window.isWide) {
        val count = when (category) {
            Category.Favorites -> favoritesCount
            Category.Recent -> recentChannels.size
            Category.ContinueWatching -> resumeItems?.size ?: 0
            is Category.Group -> groups.firstOrNull { it.title.orEmpty() == category.title }?.count ?: 0
            else -> allCount
        }
        LiveCenterSection(
            section = section,
            window = window,
            groups = groups,
            recentCount = recentChannels.size.takeIf { section == HomeSection.TV },
            allCount = allCount,
            category = category,
            query = query,
            favoritesCount = favoritesCount,
            resumeCount = resumeItems?.size,
            title = categoryTitle(section, category),
            count = itemCount(section, count),
            actions = actions,
            modifier = modifier,
            action = reorderButton,
            hint = if (reorder.active) {
                { ReorderHint(reorder, window) }
            } else {
                null
            },
            pane = { paneModifier ->
                if (section == HomeSection.TV) LivePane(preview, previewPlayer, now, paneModifier) else SpotlightPane(spotlight, paneModifier)
            },
        ) {
            // Chaînes : une par ligne avec leur programme, ou en mosaïque (réglages) ; films et séries : affiches.
            val channelList = section == HomeSection.TV && !gridColumns.liveCenterMosaic
            val columns = if (section == HomeSection.TV) gridColumns.copy(channelList = channelList) else gridColumns.copy(posters = (gridColumns.posters - 2).coerceAtLeast(3))
            CatalogGrid(section, window, columns, list = channelList, body = body)
        }
        return
    }
    CatalogSection(
        section = section,
        window = window,
        groups = groups,
        recentCount = recentChannels.size.takeIf { section == HomeSection.TV },
        hero = if (section == HomeSection.TV && window.isWide && showPreview) {
            { title, count, action -> ChannelHero(title, count, action, preview, previewPlayer, now, window) }
        } else {
            null
        },
        allCount = allCount,
        category = category,
        query = query,
        favoritesCount = favoritesCount,
        resumeCount = resumeItems?.size,
        reorder = reorder,
        actions = actions,
        modifier = modifier,
        gridColumns = gridColumns,
        focusedItem = focused.value,
        body = body,
    )
}

@Composable
private fun ReorderHint(reorder: ReorderState, window: WindowInfo) {
    Text(
        stringResource(if (reorder.movingKey == null) R.string.favorites_reorder_hint_pick else R.string.favorites_reorder_hint_move),
        style = MaterialTheme.typography.bodyMedium,
        color = NexTvColors.Warning,
        modifier = Modifier.padding(start = if (window.isWide) 24.dp else window.horizontalPadding, bottom = 8.dp),
    )
}

@Composable
private fun HomeTopBar(
    section: HomeSection,
    sourceName: String,
    window: WindowInfo,
    actions: HomeActions,
    onTabsFocused: (Boolean) -> Unit = {},
    /**
     * Interface choisie : Live Center, onglets avec icône ; Streaming, onglets en texte soulignés
     * (sélection) ; Cinéma Premium, onglets discrets sans icône dans une capsule translucide.
     */
    style: HomeLayout = HomeLayout.LIVE_CENTER,
) {
    val minimal = style == HomeLayout.PREMIUM
    val underlined = style == HomeLayout.STREAMING
    val initialFocus = remember { FocusRequester() }
    // Focus initial sur les onglets au premier affichage seulement (pas au retour d'un autre écran).
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    if (window.isTv && !initialFocusDone) {
        LaunchedEffect(Unit) {
            runCatching { initialFocus.requestFocus() }
            initialFocusDone = true
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = window.horizontalPadding, end = window.horizontalPadding, top = window.topPadding, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo NixTV ; écran étroit : onglets réduits à leur icône, pour que les sections restent visibles.
        BrandMark(compact = true, showName = window.isWide)
        Spacer(Modifier.width(if (window.isWide) 32.dp else 12.dp))
        Row(
            Modifier
                .weight(1f)
                .then(
                    if (minimal) {
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = PREMIUM_BAR_ALPHA)).padding(4.dp)
                    } else {
                        Modifier
                    },
                )
                .onFocusChanged { onTabsFocused(it.hasFocus) }
                // En remontant vers les onglets : celui de la section affichée (pas le plus proche, qui en changerait).
                .focusProperties { onEnter = { runCatching { initialFocus.requestFocus() } } }
                .focusGroup()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HomeSection.entries.forEach { target ->
                SectionTab(
                    icon = sectionIcon(target).takeUnless { (minimal || underlined) && window.isWide },
                    label = sectionLabel(target),
                    showLabel = window.isWide || target == section,
                    selected = target == section,
                    underlined = underlined,
                    onClick = { actions.onSection(target) },
                    // Télécommande : la section s'affiche dès que l'onglet est sélectionné, sans OK ni attente.
                    onFocused = { actions.onSection(target) },
                    modifier = if (target == section) Modifier.focusRequester(initialFocus) else Modifier,
                )
            }
        }
        if (window.isWide && sourceName.isNotEmpty() && !minimal) {
            // Largeur limitée : un nom très long ne doit jamais masquer les onglets.
            Text(
                sourceName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 220.dp),
            )
        }
        if (minimal) {
            // Sur l'image ou le direct (souvent clairs) : même fond translucide que les onglets, pour rester visible.
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = PREMIUM_BAR_ALPHA))) {
                IconAction(Icons.Filled.Settings, stringResource(R.string.settings_title), actions.onSettings)
            }
        } else {
            IconAction(Icons.Filled.Settings, stringResource(R.string.settings_title), actions.onSettings)
        }
    }
}

/** Fond des onglets et du bouton des réglages de Cinéma Premium, lisible sur une image claire. */
private const val PREMIUM_BAR_ALPHA = 0.72f

@Composable
private fun SectionTab(
    icon: ImageVector?,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Streaming : sélection en texte appuyé souligné de la couleur d'accent, sans pastille. */
    underlined: Boolean = false,
) {
    // La section s'affiche dès que l'onglet reçoit le focus (sans attente : la télécommande répond aussitôt).
    val currentSelected by rememberUpdatedState(selected)
    val currentOnFocused by rememberUpdatedState(onFocused)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.hasFocus && !currentSelected) currentOnFocused() },
        shape = CircleShape,
        color = if (selected && !underlined) MaterialTheme.colorScheme.primary else Color.Transparent,
        focusedColor = if (selected && !underlined) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = when {
            selected && !underlined -> MaterialTheme.colorScheme.onPrimary
            underlined && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        focusScale = 1.06f,
    ) {
        Column(
            Modifier.padding(horizontal = if (showLabel) 18.dp else 12.dp, vertical = if (underlined) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = if (showLabel) null else label, modifier = Modifier.size(20.dp))
                if (showLabel) {
                    Text(
                        label,
                        style = if (underlined && selected) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                }
            }
            if (underlined) {
                // Trait d'accent sous l'onglet affiché (place gardée sous les autres : rien ne bouge).
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(width = 22.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun sectionLabel(section: HomeSection) = stringResource(
    when (section) {
        HomeSection.TV -> R.string.home_tv
        HomeSection.MOVIES -> R.string.home_movies
        HomeSection.SERIES -> R.string.home_series
        HomeSection.GUIDE -> R.string.home_guide
        HomeSection.FOOTBALL -> R.string.home_football
    },
)

private fun sectionIcon(section: HomeSection): ImageVector = when (section) {
    HomeSection.TV -> NexTvIcons.Tv
    HomeSection.MOVIES -> NexTvIcons.Movie
    HomeSection.SERIES -> NexTvIcons.Series
    HomeSection.GUIDE -> NexTvIcons.Guide
    HomeSection.FOOTBALL -> NexTvIcons.Football
}

@Composable
private fun CatalogSection(
    section: HomeSection,
    window: WindowInfo,
    groups: List<GroupRow>,
    /** Chaînes récentes ; `null` : pas de catégorie « Récentes » (films, séries). */
    recentCount: Int?,
    hero: HeroSlot?,
    allCount: Int,
    category: Category,
    query: String,
    favoritesCount: Int,
    /** Films ou séries en cours ; `null` : pas de catégorie « Reprendre la lecture » (chaînes). */
    resumeCount: Int?,
    reorder: ReorderState,
    actions: HomeActions,
    modifier: Modifier,
    gridColumns: GridColumns,
    focusedItem: FocusedItem?,
    body: @Composable (grid: GridLayout) -> Unit,
) {
    // Chaînes : appui long sur une catégorie pour la masquer.
    val hide: (String) -> (() -> Unit)? = { title -> if (section.kind() != null) ({ actions.onHideCategory(title) }) else null }
    if (window.isWide) {
        Row(modifier.fillMaxSize()) {
            CategoryRail(section, window, groups, recentCount, allCount, category, query, favoritesCount, resumeCount, actions, Modifier.width(290.dp))
            SectionBody(section, category, groups, allCount, favoritesCount, resumeCount ?: 0, recentCount ?: 0, hero, reorder, window, actions, Modifier.weight(1f), gridColumns, focusedItem, body)
        }
    } else {
        Column(modifier.fillMaxSize()) {
            SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = window.horizontalPadding))
            LazyRow(
                contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CategoryChip(stringResource(R.string.home_favorites), category == Category.Favorites, star = true) { actions.onCategory(Category.Favorites) } }
                if (recentCount != null) {
                    item { CategoryChip(stringResource(R.string.home_recent), category == Category.Recent) { actions.onCategory(Category.Recent) } }
                }
                if (resumeCount != null) {
                    item {
                        CategoryChip(stringResource(R.string.home_continue), category == Category.ContinueWatching) {
                            actions.onCategory(Category.ContinueWatching)
                        }
                    }
                }
                item { CategoryChip(stringResource(R.string.channels_all_short), category == Category.All) { actions.onCategory(Category.All) } }
                items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                    val title = group.title.orEmpty()
                    CategoryChip(group.title ?: stringResource(R.string.channels_no_group), category == Category.Group(title), onLongClick = hide(title)) {
                        actions.onCategory(Category.Group(title))
                    }
                }
            }
            SectionBody(section, category, groups, allCount, favoritesCount, resumeCount ?: 0, recentCount ?: 0, hero, reorder, window, actions, Modifier.weight(1f), gridColumns, focusedItem, body)
        }
    }
}

/** Colonne des catégories (écran large) : recherche, favoris, récentes, reprise, toutes et groupes. */
@Composable
internal fun CategoryRail(
    section: HomeSection,
    window: WindowInfo,
    groups: List<GroupRow>,
    recentCount: Int?,
    allCount: Int,
    category: Category,
    query: String,
    favoritesCount: Int,
    resumeCount: Int?,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    /** Catégories plus serrées (interface « Box TV ») : davantage visibles à la fois. */
    dense: Boolean = false,
) {
    // Appui long sur une catégorie pour la masquer.
    val hide: (String) -> (() -> Unit)? = { title -> if (section.kind() != null) ({ actions.onHideCategory(title) }) else null }
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = window.horizontalPadding - 16.dp, end = 12.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchField(query, searchHint(section), actions.onQuery)
        val railState = rememberLazyListState()
        FadingBottom({ railState.canScrollForward }) { fade ->
            LazyColumn(state = railState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = fade) {
                item {
                    CategoryItem(stringResource(R.string.home_favorites), favoritesCount, category == Category.Favorites, star = true, dense = dense) {
                        actions.onCategory(Category.Favorites)
                    }
                }
                if (recentCount != null) {
                    item {
                        CategoryItem(stringResource(R.string.home_recent), recentCount, category == Category.Recent, dense = dense) {
                            actions.onCategory(Category.Recent)
                        }
                    }
                }
                if (resumeCount != null) {
                    item {
                        CategoryItem(stringResource(R.string.home_continue), resumeCount, category == Category.ContinueWatching, dense = dense) {
                            actions.onCategory(Category.ContinueWatching)
                        }
                    }
                }
                item { CategoryItem(allLabel(section), allCount, category == Category.All, dense = dense) { actions.onCategory(Category.All) } }
                items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                    val title = group.title.orEmpty()
                    CategoryItem(
                        group.title ?: stringResource(R.string.channels_no_group),
                        group.count,
                        category == Category.Group(title),
                        onLongClick = hide(title),
                        dense = dense,
                    ) {
                        actions.onCategory(Category.Group(title))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBody(
    section: HomeSection,
    category: Category,
    groups: List<GroupRow>,
    allCount: Int,
    favoritesCount: Int,
    resumeCount: Int,
    recentCount: Int,
    hero: HeroSlot?,
    reorder: ReorderState,
    window: WindowInfo,
    actions: HomeActions,
    modifier: Modifier,
    gridColumns: GridColumns,
    focusedItem: FocusedItem?,
    body: @Composable (grid: GridLayout) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        val title = categoryTitle(section, category)
        val count = itemCount(
            section,
            when (category) {
                Category.Favorites -> favoritesCount
                Category.ContinueWatching -> resumeCount
                Category.Recent -> recentCount
                Category.All -> allCount
                is Category.Group -> groups.firstOrNull { it.title.orEmpty() == category.title }?.count ?: 0
            },
        )
        val reorderButton: (@Composable () -> Unit)? = if (category == Category.Favorites && favoritesCount > 1) {
            {
                PillButton(
                    stringResource(if (reorder.active) R.string.favorites_reorder_done else R.string.favorites_reorder),
                    actions.onToggleReorder,
                    icon = if (reorder.active) Icons.Filled.Check else Icons.Filled.Edit,
                )
            }
        } else {
            null
        }
        if (hero != null) {
            hero(title, count, reorderButton)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = if (window.isWide) 28.dp else window.horizontalPadding, end = window.horizontalPadding, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (window.isWide && focusedItem != null) FocusedItemLabel(focusedItem, Modifier.weight(1.5f).padding(start = 24.dp, end = 8.dp))
                reorderButton?.invoke()
            }
        }
        if (reorder.active) {
            Text(
                stringResource(if (reorder.movingKey == null) R.string.favorites_reorder_hint_pick else R.string.favorites_reorder_hint_move),
                style = MaterialTheme.typography.bodyMedium,
                color = NexTvColors.Warning,
                modifier = Modifier.padding(start = if (window.isWide) 28.dp else window.horizontalPadding, bottom = 8.dp),
            )
        }
        CatalogGrid(section, window, gridColumns, list = section == HomeSection.TV && gridColumns.channelList, body = body)
    }
}

/** Grille de l'accueil : colonnes selon la largeur et les réglages ; [list] : une chaîne par ligne. */
@Composable
internal fun CatalogGrid(section: HomeSection, window: WindowInfo, gridColumns: GridColumns, list: Boolean, body: @Composable (grid: GridLayout) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val start = if (window.isWide) 28.dp else window.horizontalPadding
        val usable = maxWidth - start - window.horizontalPadding
        val columns = if (list) {
            // Disposition « liste » (réglages) : une chaîne par ligne avec son programme.
            1
        } else if (window.isWide) {
            // TV, tablette : nombre choisi dans les réglages, dans la limite de la largeur.
            val wanted = if (section == HomeSection.TV) gridColumns.channels else gridColumns.posters
            val fitting = ((usable + gridSpacing(wanted)) / (MIN_CELL + gridSpacing(wanted))).toInt()
            wanted.coerceAtMost(fitting).coerceAtLeast(1)
        } else {
            // Téléphone : liste des chaînes, affiches d'au moins 108 dp.
            val minCell = if (section == HomeSection.TV) usable else 108.dp
            maxOf(1, ((usable + GRID_SPACING) / (minCell + GRID_SPACING)).toInt())
        }
        val spacing = gridSpacing(columns)
        body(GridLayout(columns, (usable - spacing * (columns - 1)) / columns, spacing))
    }
}

@Composable
internal fun categoryTitle(section: HomeSection, category: Category): String = when (category) {
    Category.Favorites -> stringResource(R.string.home_favorites)
    Category.ContinueWatching -> stringResource(R.string.home_continue)
    Category.Recent -> stringResource(R.string.home_recent)
    Category.All -> allLabel(section)
    is Category.Group -> category.title.ifEmpty { stringResource(R.string.channels_no_group) }
}

@Composable
internal fun allLabel(section: HomeSection) = stringResource(
    when (section) {
        HomeSection.MOVIES -> R.string.home_all_movies
        HomeSection.SERIES -> R.string.home_all_series
        else -> R.string.channels_all_title
    },
)

@Composable
internal fun itemCount(section: HomeSection, count: Int): String = when (section) {
    HomeSection.MOVIES -> LocalContext.current.resources.getQuantityString(R.plurals.movies_count, count, count)
    HomeSection.SERIES -> LocalContext.current.resources.getQuantityString(R.plurals.series_count, count, count)
    else -> channelCount(count)
}

@Composable
internal fun searchHint(section: HomeSection) = stringResource(
    when (section) {
        HomeSection.MOVIES -> R.string.home_search_movies
        HomeSection.SERIES -> R.string.home_search_series
        else -> R.string.channels_search
    },
)

/** Déplacement de l'élément « soulevé » avec les flèches (réorganisation des favoris). */
private fun Modifier.reorderKeys(active: Boolean, columns: Int, onMove: (Int) -> Unit): Modifier =
    if (!active) {
        this
    } else {
        onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val offset = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                Key.DirectionUp -> -columns
                Key.DirectionDown -> columns
                else -> 0
            }
            if (offset != 0) onMove(offset)
            offset != 0
        }
    }

@Composable
internal fun TvBody(
    channels: LazyPagingItems<ChannelListRow>,
    favorites: List<ChannelListRow>,
    recent: List<ChannelListRow>,
    category: Category,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    now: Long,
    grid: GridLayout,
    window: WindowInfo,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    focused: MutableState<FocusedItem?>,
) {
    val padding = gridPadding(window)
    val favoritesMode = category == Category.Favorites
    val recentMode = category == Category.Recent
    if (favoritesMode && favorites.isEmpty()) {
        EmptyState(Icons.Filled.Star, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_channels))
        return
    }
    if (recentMode && recent.isEmpty()) {
        EmptyState(NexTvIcons.Tv, stringResource(R.string.recent_empty_title), stringResource(R.string.recent_empty_message))
        return
    }
    if (!favoritesMode && !recentMode && channels.itemCount == 0 && channels.loadState.refresh !is LoadState.Loading) {
        EmptyState(NexTvIcons.Tv, stringResource(R.string.channels_empty), stringResource(R.string.home_empty_hint))
        return
    }
    val card: @Composable (ChannelListRow, Int, Boolean) -> Unit = { channel, index, inFavorites ->
        val moving = inFavorites && reorder.movingKey == channel.itemKey
        val onClick = if (inFavorites && reorder.active) {
            { actions.onPick(channel.itemKey) }
        } else {
            {
                lastOpened.value = OpenedItem(channel.itemKey, index)
                actions.onPlayChannel(channel.id)
            }
        }
        val onLongClick = if (reorder.active) null else {
            { actions.onToggleFavorite(ContentKind.LIVE, channel.itemKey, channel.title, channel.logoUrl) }
        }
        val modifier = Modifier
            .restoreFocus(channel.itemKey, lastOpened)
            .showWhenFocused(focused, FocusedItem(listOfNotNull(channel.channelNumber, channel.title).joinToString("  "), channel.nowTitle))
            .onFocusChanged { if (it.isFocused) actions.onChannelFocus(channel) }
            .reorderKeys(moving, grid.columns) { actions.onMove(ContentKind.LIVE, channel.itemKey, it) }
        if (grid.columns == 1) {
            ChannelListItem(channel, now, channel.itemKey in favoriteKeys, onClick, onLongClick, modifier, moving)
        } else {
            ChannelCard(channel, now, channel.itemKey in favoriteKeys, onClick, onLongClick, modifier, moving, CardSize.of(grid.cellWidth))
        }
    }
    // Place gardée par catégorie, y compris au retour du lecteur.
    val state = rememberSaveable(category, saver = LazyGridState.Saver) { LazyGridState() }
    val count = when {
        favoritesMode -> favorites.size
        recentMode -> recent.size
        else -> channels.itemCount
    }
    RestoreScroll(state, lastOpened, count)
    FadingBottom({ state.canScrollForward }) { fade ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(grid.spacing),
            verticalArrangement = Arrangement.spacedBy(if (grid.columns == 1) 2.dp else grid.spacing),
            modifier = Modifier.fillMaxSize().then(fade).onFocusChanged { if (!it.hasFocus) focused.value = null },
        ) {
            if (favoritesMode) {
                items(favorites.size, key = { favorites[it].itemKey }) { index -> card(favorites[index], index, true) }
            } else if (recentMode) {
                items(recent.size, key = { recent[it].itemKey }) { index -> card(recent[index], index, false) }
            } else {
                items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                    channels[index]?.let { card(it, index, false) }
                }
            }
        }
    }
}

@Composable
private fun MoviesBody(
    movies: LazyPagingItems<MovieRow>,
    favorites: List<MovieRow>,
    category: Category,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    grid: GridLayout,
    window: WindowInfo,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    focused: MutableState<FocusedItem?>,
) {
    val favoritesMode = category == Category.Favorites
    if (favoritesMode && favorites.isEmpty()) {
        EmptyState(Icons.Filled.Star, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_movies))
        return
    }
    if (!favoritesMode && movies.itemCount == 0 && movies.loadState.refresh !is LoadState.Loading) {
        EmptyState(NexTvIcons.Movie, stringResource(R.string.home_no_movies), stringResource(R.string.home_no_movies_hint))
        return
    }
    val poster: @Composable (MovieRow, Int, Boolean) -> Unit = { movie, index, inFavorites ->
        val moving = inFavorites && reorder.movingKey == movie.itemKey
        PosterCard(
            title = movie.title,
            subtitle = movie.year?.toString(),
            imageUrl = movie.logoUrl,
            isFavorite = movie.itemKey in favoriteKeys,
            onClick = if (inFavorites && reorder.active) {
                { actions.onPick(movie.itemKey) }
            } else {
                {
                    lastOpened.value = OpenedItem(movie.itemKey, index)
                    actions.onOpenMovie(movie.id)
                }
            },
            onLongClick = if (reorder.active) null else {
                { actions.onToggleFavorite(ContentKind.MOVIE, movie.itemKey, movie.title, movie.logoUrl) }
            },
            modifier = Modifier
                .restoreFocus(movie.itemKey, lastOpened)
                .showWhenFocused(focused, FocusedItem(movie.title, movie.year?.toString()))
                .onFocusChanged { if (it.isFocused) actions.onPosterFocus(movie.toPoster()) }
                .reorderKeys(moving, grid.columns) { actions.onMove(ContentKind.MOVIE, movie.itemKey, it) },
            moving = moving,
            compact = grid.cellWidth < COMPACT_POSTER_WIDTH,
        )
    }
    val state = rememberSaveable(category, saver = LazyGridState.Saver) { LazyGridState() }
    RestoreScroll(state, lastOpened, if (favoritesMode) favorites.size else movies.itemCount)
    FadingBottom({ state.canScrollForward }) { fade ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            contentPadding = gridPadding(window),
            horizontalArrangement = Arrangement.spacedBy(grid.spacing),
            verticalArrangement = Arrangement.spacedBy(grid.spacing),
            modifier = Modifier.fillMaxSize().then(fade).onFocusChanged { if (!it.hasFocus) focused.value = null },
        ) {
            if (favoritesMode) {
                items(favorites.size, key = { favorites[it].itemKey }) { index -> poster(favorites[index], index, true) }
            } else {
                items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
                    movies[index]?.let { poster(it, index, false) }
                }
            }
        }
    }
}

@Composable
private fun SeriesBody(
    series: LazyPagingItems<SeriesRow>,
    favorites: List<SeriesRow>,
    category: Category,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    grid: GridLayout,
    window: WindowInfo,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    focused: MutableState<FocusedItem?>,
) {
    val resources = LocalContext.current.resources
    val favoritesMode = category == Category.Favorites
    if (favoritesMode && favorites.isEmpty()) {
        EmptyState(Icons.Filled.Star, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_series))
        return
    }
    if (!favoritesMode && series.itemCount == 0 && series.loadState.refresh !is LoadState.Loading) {
        EmptyState(NexTvIcons.Series, stringResource(R.string.home_no_series), stringResource(R.string.home_no_series_hint))
        return
    }
    val poster: @Composable (SeriesRow, Int, Boolean) -> Unit = { show, index, inFavorites ->
        val moving = inFavorites && reorder.movingKey == show.itemKey
        PosterCard(
            title = show.title,
            subtitle = resources.getQuantityString(R.plurals.episodes_count, show.episodeCount, show.episodeCount),
            imageUrl = show.logoUrl,
            isFavorite = show.itemKey in favoriteKeys,
            onClick = if (inFavorites && reorder.active) {
                { actions.onPick(show.itemKey) }
            } else {
                {
                    lastOpened.value = OpenedItem(show.itemKey, index)
                    actions.onOpenSeries(show.id)
                }
            },
            onLongClick = if (reorder.active) null else {
                { actions.onToggleFavorite(ContentKind.SERIES, show.itemKey, show.title, show.logoUrl) }
            },
            modifier = Modifier
                .restoreFocus(show.itemKey, lastOpened)
                .showWhenFocused(focused, FocusedItem(show.title, resources.getQuantityString(R.plurals.episodes_count, show.episodeCount, show.episodeCount)))
                .onFocusChanged { if (it.isFocused) actions.onPosterFocus(show.toPoster()) }
                .reorderKeys(moving, grid.columns) { actions.onMove(ContentKind.SERIES, show.itemKey, it) },
            moving = moving,
            compact = grid.cellWidth < COMPACT_POSTER_WIDTH,
        )
    }
    val state = rememberSaveable(category, saver = LazyGridState.Saver) { LazyGridState() }
    RestoreScroll(state, lastOpened, if (favoritesMode) favorites.size else series.itemCount)
    FadingBottom({ state.canScrollForward }) { fade ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            contentPadding = gridPadding(window),
            horizontalArrangement = Arrangement.spacedBy(grid.spacing),
            verticalArrangement = Arrangement.spacedBy(grid.spacing),
            modifier = Modifier.fillMaxSize().then(fade).onFocusChanged { if (!it.hasFocus) focused.value = null },
        ) {
            if (favoritesMode) {
                items(favorites.size, key = { favorites[it].itemKey }) { index -> poster(favorites[index], index, true) }
            } else {
                items(count = series.itemCount, key = series.itemKey { it.itemKey }) { index ->
                    series[index]?.let { poster(it, index, false) }
                }
            }
        }
    }
}

/** « Reprendre la lecture » : films et séries commencés. OK reprend, appui long retire de la liste. */
@Composable
private fun ContinueBody(
    items: List<ProgressEntity>,
    grid: GridLayout,
    window: WindowInfo,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    focused: MutableState<FocusedItem?>,
) {
    if (items.isEmpty()) {
        EmptyState(Icons.Filled.PlayArrow, stringResource(R.string.home_continue_empty_title), stringResource(R.string.home_continue_empty))
        return
    }
    val state = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    RestoreScroll(state, lastOpened, items.size)
    FadingBottom({ state.canScrollForward }) { fade ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            contentPadding = gridPadding(window),
            horizontalArrangement = Arrangement.spacedBy(grid.spacing),
            verticalArrangement = Arrangement.spacedBy(grid.spacing),
            modifier = Modifier.fillMaxSize().then(fade).onFocusChanged { if (!it.hasFocus) focused.value = null },
        ) {
            items(items.size, key = { items[it].itemKey }) { index ->
                val item = items[index]
                val remaining = (item.durationMs - item.positionMs).coerceAtLeast(0)
                val subtitle = when {
                    item.episodeLabel != null && item.positionMs <= 0 -> stringResource(R.string.home_continue_next, item.episodeLabel)
                    item.episodeLabel != null && item.durationMs > 0 ->
                        stringResource(R.string.home_continue_episode, item.episodeLabel, formatDuration(remaining))
                    item.episodeLabel != null -> item.episodeLabel
                    item.durationMs > 0 -> stringResource(R.string.home_continue_remaining, formatDuration(remaining))
                    else -> null
                }
                PosterCard(
                    title = item.title,
                    subtitle = subtitle,
                    imageUrl = item.logoUrl,
                    isFavorite = false,
                    onClick = {
                        lastOpened.value = OpenedItem(item.itemKey, index)
                        actions.onResume(item.channelId)
                    },
                    onLongClick = { actions.onClearProgress(item.itemKey) },
                    modifier = Modifier.restoreFocus(item.itemKey, lastOpened).showWhenFocused(focused, FocusedItem(item.title, subtitle)),
                    progress = if (item.durationMs > 0) item.positionMs.toFloat() / item.durationMs else null,
                    compact = grid.cellWidth < COMPACT_POSTER_WIDTH,
                )
            }
        }
    }
}

/** Nom complet et détail de la vignette sélectionnée, affichés en haut à droite de la grille. */
@Immutable
internal data class FocusedItem(val title: String, val detail: String?)

internal fun Modifier.showWhenFocused(focused: MutableState<FocusedItem?>, item: FocusedItem): Modifier =
    onFocusChanged { if (it.isFocused) focused.value = item }

/** Nom complet (défilant s'il est trop long) et détail : programme en cours, année… */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusedItemLabel(item: FocusedItem, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        item.detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun gridPadding(window: WindowInfo) = PaddingValues(
    start = if (window.isWide) 28.dp else window.horizontalPadding,
    end = window.horizontalPadding,
    top = 4.dp,
    bottom = 32.dp,
)

@Composable
internal fun CategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    star: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    dense: Boolean = false,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = if (dense) 7.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                name,
                style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CategoryChip(name: String, selected: Boolean, star: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) Icon(Icons.Filled.Star, contentDescription = null, tint = if (selected) LocalContentColor.current else NexTvColors.Warning, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun SearchField(query: String, hint: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    RemoteTextField(modifier.fillMaxWidth()) { fieldModifier, interactionSource ->
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = fieldKeyboardOptions(),
            interactionSource = interactionSource,
            modifier = fieldModifier.fillMaxWidth().openKeyboardOnOk(),
        )
    }
}

private val GRID_SPACING = 16.dp

/** Vignette la plus étroite acceptée, quel que soit le réglage. */
private val MIN_CELL = 56.dp

/** En dessous, affiche réduite (titre sur une ligne). */
internal val COMPACT_POSTER_WIDTH = 84.dp

/** Panneau du haut : reçoit le titre de la catégorie, son nombre d'éléments et une action éventuelle. */
internal typealias HeroSlot = @Composable (title: String, count: String, action: (@Composable () -> Unit)?) -> Unit

/** Grille de l'accueil : colonnes, largeur d'une vignette et espacement. */
@Immutable
internal data class GridLayout(val columns: Int, val cellWidth: Dp, val spacing: Dp)

/** Espacement resserré quand les vignettes sont nombreuses. */
internal fun gridSpacing(columns: Int): Dp = when {
    columns >= 8 -> 8.dp
    columns >= 6 -> 12.dp
    else -> GRID_SPACING
}
internal const val NO_GROUP_KEY = "\u0000"
private const val CHANNELS_REFRESH_MILLIS = 5 * 60_000L
