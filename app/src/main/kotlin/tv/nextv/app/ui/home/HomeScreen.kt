package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.channelCount
import tv.nextv.app.ui.components.BrandMark
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.GuideScreen
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
)

@Composable
fun HomeScreen(onPlay: (Long) -> Unit, onMovie: (Long) -> Unit, onSeries: (Long) -> Unit, onSettings: () -> Unit) {
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
    val now by viewModel.now.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val movies = viewModel.movies.collectAsLazyPagingItems()
    val series = viewModel.series.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }

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
    BackHandler(enabled = reorder.active) { viewModel.toggleReorder() }

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
            onPlayChannel = onPlay,
            onOpenMovie = onMovie,
            onOpenSeries = onSeries,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleReorder = viewModel::toggleReorder,
            onPick = viewModel::pick,
            onMove = viewModel::moveFavorite,
            onSettings = onSettings,
        ),
        guide = { modifier -> sourceId?.let { GuideScreen(it, onPlay, modifier) } },
    )
}

/** Accueil : sections TV, Films, Séries et Guide TV. Sans dépendance au ViewModel (captures d'écran). */
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
) {
    val window = rememberWindowInfo()
    // Dernière vignette ouverte : elle reprend le focus au retour sur l'accueil (télécommande).
    val lastOpened = rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HomeTopBar(section, sourceName, window, actions)
            if (section == HomeSection.GUIDE) {
                guide(Modifier.weight(1f))
            } else {
                val favoritesCount = when (section) {
                    HomeSection.TV -> favoriteChannels.size
                    HomeSection.MOVIES -> favoriteMovies.size
                    else -> favoriteSeries.size
                }
                CatalogSection(
                    section = section,
                    window = window,
                    groups = groups,
                    category = category,
                    query = query,
                    favoritesCount = favoritesCount,
                    reorder = reorder,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                ) { columns ->
                    when (section) {
                        HomeSection.TV -> TvBody(channels, favoriteChannels, category, favoriteKeys, reorder, now, columns, window, lastOpened, actions)
                        HomeSection.MOVIES -> MoviesBody(movies, favoriteMovies, category, favoriteKeys, reorder, columns, window, lastOpened, actions)
                        else -> SeriesBody(series, favoriteSeries, category, favoriteKeys, reorder, columns, window, lastOpened, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(section: HomeSection, sourceName: String, window: WindowInfo, actions: HomeActions) {
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
        Modifier.fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Écran étroit : logo seul et onglets réduits à leur icône, pour que les quatre sections restent visibles.
        BrandMark(compact = true, showName = window.isWide)
        Spacer(Modifier.width(if (window.isWide) 32.dp else 12.dp))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HomeSection.entries.forEach { target ->
                SectionTab(
                    icon = sectionIcon(target),
                    label = sectionLabel(target),
                    showLabel = window.isWide || target == section,
                    selected = target == section,
                    onClick = { actions.onSection(target) },
                    modifier = if (target == section) Modifier.focusRequester(initialFocus) else Modifier,
                )
            }
        }
        if (window.isWide && sourceName.isNotEmpty()) {
            Text(
                sourceName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        IconAction(Icons.Filled.Settings, stringResource(R.string.settings_title), actions.onSettings)
    }
}

@Composable
private fun SectionTab(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.06f,
    ) {
        Row(
            Modifier.padding(horizontal = if (showLabel) 18.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = if (showLabel) null else label, modifier = Modifier.size(20.dp))
            if (showLabel) Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

@Composable
private fun sectionLabel(section: HomeSection) = stringResource(
    when (section) {
        HomeSection.TV -> R.string.home_tv
        HomeSection.MOVIES -> R.string.home_movies
        HomeSection.SERIES -> R.string.home_series
        HomeSection.GUIDE -> R.string.home_guide
    },
)

private fun sectionIcon(section: HomeSection): ImageVector = when (section) {
    HomeSection.TV -> NexTvIcons.Tv
    HomeSection.MOVIES -> NexTvIcons.Movie
    HomeSection.SERIES -> NexTvIcons.Series
    HomeSection.GUIDE -> NexTvIcons.Guide
}

@Composable
private fun CatalogSection(
    section: HomeSection,
    window: WindowInfo,
    groups: List<GroupRow>,
    category: Category,
    query: String,
    favoritesCount: Int,
    reorder: ReorderState,
    actions: HomeActions,
    modifier: Modifier,
    body: @Composable (columns: Int) -> Unit,
) {
    val total = groups.sumOf { it.count }
    if (window.isWide) {
        Row(modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = window.horizontalPadding - 16.dp, end = 12.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchField(query, searchHint(section), actions.onQuery)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item {
                        CategoryItem(stringResource(R.string.home_favorites), favoritesCount, category == Category.Favorites, star = true) {
                            actions.onCategory(Category.Favorites)
                        }
                    }
                    item { CategoryItem(allLabel(section), total, category == Category.All) { actions.onCategory(Category.All) } }
                    items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                        val title = group.title.orEmpty()
                        CategoryItem(group.title ?: stringResource(R.string.channels_no_group), group.count, category == Category.Group(title)) {
                            actions.onCategory(Category.Group(title))
                        }
                    }
                }
            }
            SectionBody(section, category, groups, favoritesCount, reorder, window, actions, Modifier.weight(1f), body)
        }
    } else {
        Column(modifier.fillMaxSize()) {
            SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = window.horizontalPadding))
            LazyRow(
                contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CategoryChip(stringResource(R.string.home_favorites), category == Category.Favorites, star = true) { actions.onCategory(Category.Favorites) } }
                item { CategoryChip(stringResource(R.string.channels_all_short), category == Category.All) { actions.onCategory(Category.All) } }
                items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                    val title = group.title.orEmpty()
                    CategoryChip(group.title ?: stringResource(R.string.channels_no_group), category == Category.Group(title)) {
                        actions.onCategory(Category.Group(title))
                    }
                }
            }
            SectionBody(section, category, groups, favoritesCount, reorder, window, actions, Modifier.weight(1f), body)
        }
    }
}

@Composable
private fun SectionBody(
    section: HomeSection,
    category: Category,
    groups: List<GroupRow>,
    favoritesCount: Int,
    reorder: ReorderState,
    window: WindowInfo,
    actions: HomeActions,
    modifier: Modifier,
    body: @Composable (columns: Int) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (window.isWide) 28.dp else window.horizontalPadding, end = window.horizontalPadding, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(categoryTitle(section, category), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = when (category) {
                    Category.Favorites -> favoritesCount
                    Category.All -> groups.sumOf { it.count }
                    is Category.Group -> groups.firstOrNull { it.title.orEmpty() == category.title }?.count ?: 0
                }
                Text(itemCount(section, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (category == Category.Favorites && favoritesCount > 1) {
                PillButton(
                    stringResource(if (reorder.active) R.string.favorites_reorder_done else R.string.favorites_reorder),
                    actions.onToggleReorder,
                    icon = if (reorder.active) Icons.Filled.Check else Icons.Filled.Edit,
                )
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val start = if (window.isWide) 28.dp else window.horizontalPadding
            val usable = maxWidth - start - window.horizontalPadding
            val minCell: Dp = when {
                section == HomeSection.TV && window.isWide -> 180.dp
                section == HomeSection.TV -> usable
                // Affiches : 5 colonnes sur TV pour voir deux rangées à la fois.
                window.isTv -> 104.dp
                window.isWide -> 150.dp
                else -> 108.dp
            }
            val columns = maxOf(1, ((usable + GRID_SPACING) / (minCell + GRID_SPACING)).toInt())
            body(columns)
        }
    }
}

@Composable
private fun categoryTitle(section: HomeSection, category: Category): String = when (category) {
    Category.Favorites -> stringResource(R.string.home_favorites)
    Category.All -> allLabel(section)
    is Category.Group -> category.title.ifEmpty { stringResource(R.string.channels_no_group) }
}

@Composable
private fun allLabel(section: HomeSection) = stringResource(
    when (section) {
        HomeSection.MOVIES -> R.string.home_all_movies
        HomeSection.SERIES -> R.string.home_all_series
        else -> R.string.channels_all_title
    },
)

@Composable
private fun itemCount(section: HomeSection, count: Int): String = when (section) {
    HomeSection.MOVIES -> LocalContext.current.resources.getQuantityString(R.plurals.movies_count, count, count)
    HomeSection.SERIES -> LocalContext.current.resources.getQuantityString(R.plurals.series_count, count, count)
    else -> channelCount(count)
}

@Composable
private fun searchHint(section: HomeSection) = stringResource(
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
private fun TvBody(
    channels: LazyPagingItems<ChannelListRow>,
    favorites: List<ChannelListRow>,
    category: Category,
    favoriteKeys: Set<String>,
    reorder: ReorderState,
    now: Long,
    columns: Int,
    window: WindowInfo,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    val padding = gridPadding(window)
    val favoritesMode = category == Category.Favorites
    if (favoritesMode && favorites.isEmpty()) {
        EmptyState(Icons.Filled.Star, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_channels))
        return
    }
    if (!favoritesMode && channels.itemCount == 0 && channels.loadState.refresh !is LoadState.Loading) {
        EmptyState(NexTvIcons.Tv, stringResource(R.string.channels_empty), stringResource(R.string.home_empty_hint))
        return
    }
    val card: @Composable (ChannelListRow, Boolean) -> Unit = { channel, inFavorites ->
        val moving = inFavorites && reorder.movingKey == channel.itemKey
        val onClick = if (inFavorites && reorder.active) {
            { actions.onPick(channel.itemKey) }
        } else {
            {
                lastOpened.value = channel.itemKey
                actions.onPlayChannel(channel.id)
            }
        }
        val onLongClick = if (reorder.active) null else {
            { actions.onToggleFavorite(ContentKind.LIVE, channel.itemKey, channel.title, channel.logoUrl) }
        }
        val modifier = Modifier
            .restoreFocus(channel.itemKey, lastOpened)
            .reorderKeys(moving, columns) { actions.onMove(ContentKind.LIVE, channel.itemKey, it) }
        if (columns == 1) {
            ChannelListItem(channel, now, channel.itemKey in favoriteKeys, onClick, onLongClick, modifier, moving)
        } else {
            ChannelCard(channel, now, channel.itemKey in favoriteKeys, onClick, onLongClick, modifier, moving)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(if (columns == 1) 2.dp else GRID_SPACING),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (favoritesMode) {
            items(favorites.size, key = { favorites[it].itemKey }) { index -> card(favorites[index], true) }
        } else {
            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                channels[index]?.let { card(it, false) }
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
    columns: Int,
    window: WindowInfo,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
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
    val poster: @Composable (MovieRow, Boolean) -> Unit = { movie, inFavorites ->
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
                    lastOpened.value = movie.itemKey
                    actions.onOpenMovie(movie.id)
                }
            },
            onLongClick = if (reorder.active) null else {
                { actions.onToggleFavorite(ContentKind.MOVIE, movie.itemKey, movie.title, movie.logoUrl) }
            },
            modifier = Modifier
                .restoreFocus(movie.itemKey, lastOpened)
                .reorderKeys(moving, columns) { actions.onMove(ContentKind.MOVIE, movie.itemKey, it) },
            moving = moving,
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = gridPadding(window),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (favoritesMode) {
            items(favorites.size, key = { favorites[it].itemKey }) { index -> poster(favorites[index], true) }
        } else {
            items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
                movies[index]?.let { poster(it, false) }
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
    columns: Int,
    window: WindowInfo,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
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
    val poster: @Composable (SeriesRow, Boolean) -> Unit = { show, inFavorites ->
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
                    lastOpened.value = show.itemKey
                    actions.onOpenSeries(show.id)
                }
            },
            onLongClick = if (reorder.active) null else {
                { actions.onToggleFavorite(ContentKind.SERIES, show.itemKey, show.title, show.logoUrl) }
            },
            modifier = Modifier
                .restoreFocus(show.itemKey, lastOpened)
                .reorderKeys(moving, columns) { actions.onMove(ContentKind.SERIES, show.itemKey, it) },
            moving = moving,
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = gridPadding(window),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (favoritesMode) {
            items(favorites.size, key = { favorites[it].itemKey }) { index -> poster(favorites[index], true) }
        } else {
            items(count = series.itemCount, key = series.itemKey { it.itemKey }) { index ->
                series[index]?.let { poster(it, false) }
            }
        }
    }
}

/**
 * Rend le focus à la vignette [key] si c'est la dernière ouverte, puis oublie cette dernière :
 * faire défiler la grille ensuite ne déplace plus le focus.
 */
@Composable
private fun Modifier.restoreFocus(key: String, lastOpened: MutableState<String?>): Modifier {
    if (lastOpened.value != key) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { requester.requestFocus() }
        lastOpened.value = null
    }
    return focusRequester(requester)
}

private fun gridPadding(window: WindowInfo) = PaddingValues(
    start = if (window.isWide) 28.dp else window.horizontalPadding,
    end = window.horizontalPadding,
    top = 4.dp,
    bottom = 32.dp,
)

@Composable
private fun CategoryItem(name: String, count: Int, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryChip(name: String, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
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
private fun SearchField(query: String, hint: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxWidth(),
    )
}

private val GRID_SPACING = 16.dp
private const val NO_GROUP_KEY = "\u0000"
private const val CHANNELS_REFRESH_MILLIS = 5 * 60_000L
