package tv.nextv.app.ui.home

import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.PillButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import coil3.request.ImageRequest
import coil3.imageLoader
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.components.bottomFade
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.model.ContentKind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Interface de l'accueil (Réglages › Apparence), en plus des palettes de couleurs :
 * - [LIVE_CENTER] : catégories à gauche, liste des chaînes au centre, aperçu et programme à droite ;
 * - [STREAMING] : grande bannière et rangées de jaquettes (reprises, nouveautés, tendances) ;
 * - [PREMIUM] : visuel plein écran de la sélection, grandes affiches et typographie soignée.
 */
enum class HomeLayout {
    LIVE_CENTER,
    STREAMING,
    PREMIUM,
    ;

    companion object {
        val DEFAULT = LIVE_CENTER

        /** Interface enregistrée, y compris celles des versions précédentes (« CLASSIC », « BOX »). */
        fun fromStored(name: String?): HomeLayout = when (name) {
            null -> DEFAULT
            "CLASSIC", "BOX" -> LIVE_CENTER
            else -> entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}

/** Contenu commun des interfaces à rangées (Streaming, Cinéma Premium). */
@Immutable
internal data class ShelfContent(
    val section: HomeSection,
    val groups: List<GroupRow>,
    val rows: HomeRows,
    val favoriteChannels: List<ChannelListRow>,
    val recentChannels: List<ChannelListRow>,
    val favoriteMovies: List<MovieRow>,
    val favoriteSeries: List<SeriesRow>,
    val continueItems: List<ProgressEntity>,
    val discover: Discover,
    val favoriteKeys: Set<String>,
    val now: Long,
)

/**
 * Interface « Streaming » : grand bandeau (aperçu vidéo pour une chaîne ; pour un film ou une série, le
 * bandeau vedette [StreamingHero] avec sa bande-annonce), puis les rangées : reprises, tendances,
 * nouveautés, recommandations, favoris et une rangée par catégorie. Une recherche remplace les rangées
 * par celle de ses résultats.
 */
@Composable
internal fun StreamingHome(
    content: ShelfContent,
    window: WindowInfo,
    query: String,
    channels: LazyPagingItems<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    preview: ChannelPreview?,
    previewPlayer: Player?,
    spotlight: Spotlight?,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    /** Bandes-annonces du bandeau (réglages). */
    trailers: TrailerSettings = TrailerSettings(),
) {
    val section = content.section
    val padding = if (window.isWide) 28.dp else window.horizontalPadding
    val listState = rememberSaveable(section, saver = LazyListState.Saver) { LazyListState() }
    // Grand écran : pas de recherche de section (« Recherche Globale » du menu) ; ailleurs, en tête des rangées.
    val searchInList = !window.isWide
    val scrolled by remember(searchInList) { derivedStateOf { listState.firstVisibleItemIndex > if (searchInList) 1 else 0 } }
    val hero = window.isWide && section != HomeSection.TV
    // Bandeau vedette : tant que la sélection est sur le bandeau (et à l'ouverture), pas sur les rangées.
    var onHero by rememberSaveable(section) { mutableStateOf(true) }
    val featuredItems = remember(content.discover) {
        content.discover.trending.ifEmpty { content.discover.newest }.take(FEATURED_COUNT)
    }
    var featuredIndex by rememberSaveable(section) { mutableIntStateOf(0) }
    val featuredMode = hero && onHero && query.isEmpty()
    // Sélection passée par les rangées : en remontant au bandeau, il garde l'affiche qui était choisie.
    var fromRows by remember { mutableStateOf(false) }
    // Contenu mis en avant : sa fiche TMDB est lue comme pour une affiche choisie (visuel, bande-annonce).
    LaunchedEffect(featuredMode, featuredIndex, featuredItems) {
        if (!featuredMode || featuredItems.isEmpty()) return@LaunchedEffect
        if (fromRows) {
            fromRows = false
            // Retour des rangées (n°4 du Top 10…) : le bandeau reste sur ce contenu, la rotation reprend après lui.
            val kept = spotlight?.item?.itemKey
            val index = featuredItems.indexOfFirst { it.itemKey == kept }
            if (index >= 0) {
                if (index != featuredIndex % featuredItems.size) featuredIndex = index
                return@LaunchedEffect
            }
            if (kept != null) return@LaunchedEffect
        }
        actions.onPosterFocus(featuredItems[featuredIndex % featuredItems.size])
    }
    val shown = spotlight ?: featuredItems.firstOrNull()?.let { Spotlight(it) } ?: content.discover.trending.firstOrNull()?.let { Spotlight(it) }
    // Bande-annonce : une seule (celle du bandeau), terminée une fois pour le contenu affiché.
    var finishedTrailer by remember { mutableStateOf<String?>(null) }
    val muted = !trailers.sound
    val trailerKey = shown?.details?.trailerYoutubeKey
    val trailerDone = trailerKey != null && trailerKey == finishedTrailer
    // Rotation des contenus mis en avant : après la bande-annonce (si elle se lit), sinon toutes les 9 s.
    LaunchedEffect(featuredMode, featuredIndex, featuredItems.size, trailerKey, trailerDone, trailers.autoplay) {
        if (!featuredMode || featuredItems.size < 2) return@LaunchedEffect
        val trailerWillPlay = trailers.autoplay && trailerKey != null && !trailerDone
        if (trailerWillPlay) return@LaunchedEffect
        delay(if (trailerDone) FEATURED_AFTER_TRAILER_MILLIS else FEATURED_ROTATE_MILLIS)
        featuredIndex = (featuredIndex + 1) % featuredItems.size
    }
    val heroHeight = when {
        !window.isWide -> 0.dp
        section == HomeSection.TV -> if (scrolled) COMPACT_BILLBOARD_HEIGHT else STREAMING_BILLBOARD_HEIGHT
        else -> HERO_BROWSE_HEIGHT
    }
    val billboardHeight by animateDpAsState(heroHeight, animationSpec = tween(320), label = "billboard")
    // Bandes-annonces : le navigateur intégré est préparé dès l'arrivée (la première ouverture est lente).
    val context = LocalContext.current
    LaunchedEffect(hero && trailers.autoplay) {
        if (!hero || !trailers.autoplay || webViewWarmed) return@LaunchedEffect
        webViewWarmed = true
        warmUpWebView(context)
    }
    // Bandeau sans boutons : à la première ouverture sur la télé, la sélection va sur la première affiche des rangées.
    val heroFocus = remember { FocusRequester() }
    var heroFocusDone by rememberSaveable(section) { mutableStateOf(false) }
    if (hero && window.isTv && !heroFocusDone && lastOpened.value == null) {
        LaunchedEffect(shown?.item?.itemKey != null) {
            if (shown?.item == null) return@LaunchedEffect
            for (attempt in 1..30) {
                withFrameNanos { }
                if (runCatching { heroFocus.requestFocus() }.getOrDefault(false)) break
            }
            heroFocusDone = true
        }
    }
    Column(modifier.fillMaxSize()) {
        if (window.isWide) {
            Box(Modifier.fillMaxWidth().height(billboardHeight).clipToBounds()) {
                if (section == HomeSection.TV) {
                    ChannelBillboard(preview, previewPlayer, content.now, padding, compact = scrolled)
                } else {
                    StreamingHero(
                        section = section,
                        spotlight = shown,
                        featured = featuredMode,
                        frameHeight = heroHeight,
                        padding = padding,
                        trailers = trailers,
                        trailerFinished = trailerDone,
                        muted = muted,
                        onTrailerShown = {},
                        onTrailerFinished = { finishedTrailer = trailerKey },
                    )
                }
            }
        }
        val sizes = ShelfSizes.of(window)
        PreloadRows(content, posterWidth = sizes.poster.takeIf { section != HomeSection.TV })
        val shelves = shelfList(content)
        RestoreRow(listState, lastOpened, (if (searchInList) listOf("search") else emptyList()) + shelves.map { it.key })
        // Petit écran : la recherche précède les rangées dans la liste.
        RowJumpEffect(LocalRowJump.current, listState, content, shelves, offset = if (searchInList) 1 else 0, onOpen = actions.onCategory)
        AnchoredRows(peekBelow = STREAMING_NEXT_ROW_PEEK, listState = listState) { _ ->
            LazyColumn(
                Modifier
                    .weight(1f)
                    // Sélection dans les rangées : le bandeau montre l'affiche choisie.
                    .onFocusChanged {
                        if (it.hasFocus) {
                            onHero = false
                            fromRows = true
                        }
                    }
                    // Sélection de départ : la première affiche de la liste.
                    .focusRequester(heroFocus)
                    .focusGroup(),
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Petit écran : recherche en tête des rangées, elle défile avec elles (et garde le clavier pendant la saisie).
                if (searchInList) {
                    item(key = "search") {
                        SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = padding).widthIn(max = 460.dp))
                    }
                }
                if (query.isNotEmpty()) {
                    item(key = "results") {
                        SearchResults(section, channels, movies, series, sizes, padding, content, lastOpened, actions)
                    }
                    return@LazyColumn
                }
                shelfItems(shelves, content, sizes, padding, lastOpened, actions, ShelfStyle.STREAMING)
            }
        }
    }
}

/** Résultats de la recherche, sur une rangée. */
@Composable
internal fun SearchResults(
    section: HomeSection,
    channels: LazyPagingItems<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    sizes: ShelfSizes,
    padding: Dp,
    content: ShelfContent,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle = ShelfStyle.STREAMING,
    /** Titre de la rangée (« Résultats » ; « Chaînes », « Films »… dans la recherche de partout). */
    title: String = stringResource(R.string.home_rows_results),
    rowKey: String = "results",
) {
    val count = when (section) {
        HomeSection.TV -> channels.itemCount
        HomeSection.MOVIES -> movies.itemCount
        else -> series.itemCount
    }
    val tv = section == HomeSection.TV
    Shelf(
        title = title,
        count = itemCount(section, count),
        padding = padding,
        onMore = null,
        itemWidth = if (tv) sizes.channel else sizes.poster,
        imageRatio = if (tv) CHANNEL_RATIO else POSTER_RATIO,
        rowKey = rowKey,
        itemCount = count,
        lastOpened = lastOpened,
        style = style,
    ) {
        when (section) {
            HomeSection.TV -> items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
                channels[index]?.let { ShelfChannel(it, index, rowKey, sizes.channel, content, lastOpened, actions, style) }
            }
            HomeSection.MOVIES -> items(movies.itemCount, key = movies.itemKey { it.id }) { index ->
                movies[index]?.let { ShelfPoster(it.toPoster(), index, rowKey, sizes.poster, content.favoriteKeys, lastOpened, actions, style) }
            }
            else -> items(series.itemCount, key = series.itemKey { it.itemKey }) { index ->
                series[index]?.let { ShelfPoster(it.toPoster(), index, rowKey, sizes.poster, content.favoriteKeys, lastOpened, actions, style) }
            }
        }
    }
}

/** Une rangée de l'accueil : clé (gardée au retour), titre et contenu. */
internal sealed interface ShelfSpec {
    val key: String

    data class Channels(override val key: String, val title: Int, val channels: List<ChannelListRow>) : ShelfSpec
    data class Posters(override val key: String, val title: Int, val items: List<PosterItem>, val from: String? = null) : ShelfSpec
    data class Continue(override val key: String, val items: List<ProgressEntity>) : ShelfSpec
    data class Group(override val key: String, val group: GroupRow) : ShelfSpec

    /** Les dix tendances de la semaine, avec leur rang en grand à côté de l'affiche. */
    data class Top10(override val key: String, val items: List<PosterItem>) : ShelfSpec
}

/** Rangées dans l'ordre de l'écran ; celles qui sont vides n'apparaissent pas. */
internal fun shelfList(content: ShelfContent): List<ShelfSpec> = buildList {
    when (content.section) {
        HomeSection.TV -> {
            if (content.favoriteChannels.isNotEmpty()) add(ShelfSpec.Channels(FAVORITES_ROW, R.string.home_favorites, content.favoriteChannels))
            if (content.recentChannels.isNotEmpty()) add(ShelfSpec.Channels("recent", R.string.home_recent, content.recentChannels))
        }
        HomeSection.MOVIES, HomeSection.SERIES -> {
            val discover = content.discover
            // Assez de tendances : un Top 10 à rangs, sinon la rangée ordinaire. Tout en haut, avant « Reprendre la lecture ».
            if (discover.trending.size >= TOP10_MIN) {
                add(ShelfSpec.Top10("top10", discover.trending.take(10)))
            } else if (discover.trending.isNotEmpty()) {
                add(ShelfSpec.Posters("trending", R.string.home_rows_trending, discover.trending))
            }
            if (content.continueItems.isNotEmpty()) add(ShelfSpec.Continue("continue", content.continueItems))
            // Favoris juste après : ajoutés par la fiche ou l'appui long, ils se retrouvent tout de suite.
            val favorites = if (content.section == HomeSection.MOVIES) content.favoriteMovies.map { it.toPoster() } else content.favoriteSeries.map { it.toPoster() }
            if (favorites.isNotEmpty()) add(ShelfSpec.Posters("favorites", R.string.home_favorites, favorites))
            if (discover.newest.isNotEmpty()) add(ShelfSpec.Posters("newest", R.string.home_rows_newest, discover.newest))
            if (discover.recommended.isNotEmpty()) add(ShelfSpec.Posters("recommended", R.string.home_rows_recommended, discover.recommended, discover.recommendedFrom))
        }
        HomeSection.GUIDE, HomeSection.FOOTBALL -> Unit
    }
    content.groups.forEach { add(ShelfSpec.Group("group:" + (it.title ?: NO_GROUP_KEY), it)) }
}

/** Présentation des rangées : Streaming (vignettes serrées) ou Cinéma Premium (grandes affiches). */
internal enum class ShelfStyle { STREAMING, PREMIUM }

/**
 * Rangées calées en haut de la liste (Streaming, Cinéma Premium) : la vignette choisie s'arrête
 * toujours à la même hauteur ([RowAnchor.cardTopPx], mesurée sur l'en-tête des rangées), donc sa
 * rangée tout en haut, sans morceau de la précédente au-dessus (sur TV, Compose place sinon la
 * vignette à 30 % de la hauteur). Calcul fait par le défilement lui-même : rien n'attend la fin
 * d'une animation (appareils lents), et la place tient quand la bannière change de taille.
 */
internal class RowAnchor {
    var cardTopPx by mutableFloatStateOf(0f)

    /** Rangée qui a le focus (sa clé dans la liste). */
    var focusedRow by mutableStateOf<String?>(null)

    /** Place sous la rangée choisie, jusqu'au bas de la liste : le fondu du bas ne la recouvre jamais. */
    fun spaceBelowFocusedRow(state: LazyListState): Float {
        val info = state.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.key == focusedRow } ?: return Float.MAX_VALUE
        return (info.viewportEndOffset - (row.offset + row.size)).toFloat()
    }
}

internal val LocalRowAnchor = staticCompositionLocalOf<RowAnchor?> { null }

/**
 * Saut à la rangée d'une catégorie (volet des catégories de Streaming et Cinéma Premium) : toutes les rangées
 * restent, la liste défile jusqu'à celle de la catégorie et la sélection s'y pose.
 */
internal class RowJump {
    /** Catégorie demandée, en attente du saut. */
    var target by mutableStateOf<Category?>(null)

    /** Rangées affichées, par clé : de quoi y poser la sélection. */
    val rows = HashMap<String, FocusRequester>()
}

internal val LocalRowJump = staticCompositionLocalOf<RowJump?> { null }

/** Rang du Top 10 de Cinéma Premium : « N°1 » doré, discret, dans le coin de l'affiche. */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        "N°$rank",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF2A1E00),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF5D77A), Color(0xFFD4A537))))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Clé de la rangée des favoris. */
internal const val FAVORITES_ROW = "favorites"

/** Clé de la rangée d'une catégorie dans [shelves] (`null` : pas de rangée pour elle). */
internal fun rowKeyOf(category: Category, content: ShelfContent, shelves: List<ShelfSpec>): String? {
    val key = when (category) {
        Category.All -> shelves.firstOrNull()?.key
        Category.Favorites -> FAVORITES_ROW
        Category.Recent -> "recent"
        Category.ContinueWatching -> "continue"
        is Category.Group -> content.groups.firstOrNull { it.title.orEmpty() == category.title }?.let { "group:" + (it.title ?: NO_GROUP_KEY) }
    }
    return key?.takeIf { k -> shelves.any { it.key == k } }
}

/**
 * Exécute le saut demandé : défilement jusqu'à la rangée ([offset] : éléments avant les rangées dans la liste),
 * puis sélection sur elle. Catégorie sans rangée (favoris vides…) : elle s'ouvre comme avant.
 */
@Composable
internal fun RowJumpEffect(jump: RowJump?, listState: LazyListState, content: ShelfContent, shelves: List<ShelfSpec>, offset: Int, onOpen: (Category) -> Unit) {
    val target = jump?.target ?: return
    LaunchedEffect(target) {
        val key = rowKeyOf(target, content, shelves)
        if (key == null) {
            jump.target = null
            onOpen(target)
            return@LaunchedEffect
        }
        runCatching { listState.scrollToItem(shelves.indexOfFirst { it.key == key } + offset) }
        // La rangée et ses vignettes arrivent à l'image suivante ; rangée encore en lecture (vignettes d'attente,
        // pas sélectionnables) : jusqu'à 3 s. La liste reste sur la rangée pendant ce temps.
        for (attempt in 1..60) {
            withFrameNanos { }
            if (runCatching { jump.rows[key]?.requestFocus() }.getOrNull() == true) break
            delay(50)
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) runCatching { listState.scrollToItem(shelves.indexOfFirst { it.key == key } + offset) }
        }
        jump.target = null
    }
}

/** Défilement habituel, rendu aux rangées horizontales : l'ancrage ne vaut que pour la liste des rangées. */
internal val LocalRowDefaultSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnchoredRows(
    /**
     * Streaming : la rangée choisie est calée vers le bas, de sorte qu'il ne reste en dessous que cette hauteur
     * (le titre de la rangée suivante, ses affiches cachées). `null` : rangée calée en haut (Cinéma Premium).
     */
    peekBelow: Dp? = null,
    /** Liste des rangées : avec [peekBelow], c'est le bas de la rangée entière (titres sous les affiches compris) qui est calé. */
    listState: LazyListState? = null,
    content: @Composable (RowAnchor) -> Unit,
) {
    val default = LocalBringIntoViewSpec.current
    val anchor = remember { RowAnchor() }
    val peekPx = with(LocalDensity.current) { peekBelow?.toPx() }
    val spec = remember(anchor, default, peekPx, listState) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec get() = default.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (peekPx != null) {
                    // Bas de la rangée choisie (et non de la vignette : le titre écrit dessous en fait partie)
                    // juste au-dessus de la place laissée au titre de la rangée suivante.
                    val info = listState?.layoutInfo
                    val row = info?.visibleItemsInfo?.firstOrNull { it.key == anchor.focusedRow }
                    // Jamais le haut de la rangée (son titre) au-dessus du haut de la liste : liste plus basse
                    // que la rangée (bandeau qui change de hauteur), la rangée est calée en haut.
                    if (info != null && row != null) {
                        return minOf(row.offset + row.size - (info.viewportEndOffset - peekPx), (row.offset - info.viewportStartOffset).toFloat())
                    }
                    return offset + size - (containerSize - peekPx)
                }
                val top = anchor.cardTopPx
                // Avant la première mesure des rangées : défilement habituel.
                return if (top <= 0f) default.calculateScrollDistance(offset, size, containerSize) else offset - top
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalRowAnchor provides anchor, LocalRowDefaultSpec provides default) {
        content(anchor)
    }
}

internal fun LazyListScope.shelfItems(
    shelves: List<ShelfSpec>,
    content: ShelfContent,
    sizes: ShelfSizes,
    padding: Dp,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle,
) {
    // Type de rangée : la liste réutilise une rangée du même genre en défilant (moins de travail par rangée).
    items(shelves, key = { it.key }, contentType = { it::class }) { spec ->
        when (spec) {
            is ShelfSpec.Channels -> Shelf(
                title = stringResource(spec.title),
                count = null,
                padding = padding,
                onMore = null,
                itemWidth = sizes.channel,
                imageRatio = CHANNEL_RATIO,
                rowKey = spec.key,
                itemCount = spec.channels.size,
                lastOpened = lastOpened,
                style = style,
            ) {
                itemsIndexed(spec.channels, key = { _, it -> it.itemKey }) { index, channel ->
                    ShelfChannel(channel, index, spec.key, sizes.channel, content, lastOpened, actions, style)
                }
            }
            is ShelfSpec.Posters -> Shelf(
                title = spec.from?.let { stringResource(R.string.home_rows_because, it) } ?: stringResource(spec.title),
                count = null,
                padding = padding,
                onMore = null,
                itemWidth = sizes.poster,
                imageRatio = POSTER_RATIO,
                rowKey = spec.key,
                itemCount = spec.items.size,
                lastOpened = lastOpened,
                style = style,
            ) {
                itemsIndexed(spec.items, key = { _, it -> it.itemKey }) { index, item ->
                    ShelfPoster(item, index, spec.key, sizes.poster, content.favoriteKeys, lastOpened, actions, style)
                }
            }
            is ShelfSpec.Continue -> Shelf(
                title = stringResource(R.string.home_continue),
                count = null,
                padding = padding,
                onMore = null,
                itemWidth = sizes.poster,
                imageRatio = POSTER_RATIO,
                rowKey = spec.key,
                itemCount = spec.items.size,
                lastOpened = lastOpened,
                style = style,
            ) {
                itemsIndexed(spec.items, key = { _, it -> it.itemKey }) { index, item ->
                    ContinueCard(item, index, spec.key, sizes.poster, lastOpened, actions, style)
                }
            }
            is ShelfSpec.Group -> GroupShelf(spec, content, sizes, padding, lastOpened, actions, style)
            // Cinéma Premium : affiches ordinaires avec un petit rang doré dans le coin (pas les grands chiffres de Streaming).
            is ShelfSpec.Top10 -> if (style == ShelfStyle.PREMIUM) Shelf(
                title = stringResource(R.string.home_rows_top10),
                count = null,
                padding = padding,
                onMore = null,
                itemWidth = sizes.poster,
                imageRatio = POSTER_RATIO,
                rowKey = spec.key,
                itemCount = spec.items.size,
                lastOpened = lastOpened,
                style = style,
            ) {
                itemsIndexed(spec.items, key = { _, it -> it.itemKey }) { index, item ->
                    Box {
                        ShelfPoster(item, index, spec.key, sizes.poster, content.favoriteKeys, lastOpened, actions, style, ranked = true)
                        RankBadge(index + 1, Modifier.align(Alignment.TopStart).padding(7.dp))
                    }
                }
            } else Shelf(
                title = stringResource(R.string.home_rows_top10),
                count = null,
                padding = padding,
                onMore = null,
                itemWidth = sizes.poster + TOP10_NUMBER_WIDTH,
                imageRatio = POSTER_RATIO,
                rowKey = spec.key,
                itemCount = spec.items.size,
                lastOpened = lastOpened,
                style = style,
            ) {
                itemsIndexed(spec.items, key = { _, it -> it.itemKey }) { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Rang en grand, en contour, comme sur les plateformes de streaming.
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 76.sp,
                                fontWeight = FontWeight.Black,
                                drawStyle = Stroke(width = 4f),
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(TOP10_NUMBER_WIDTH),
                        )
                        ShelfPoster(item, index, spec.key, sizes.poster, content.favoriteKeys, lastOpened, actions, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupShelf(
    spec: ShelfSpec.Group,
    content: ShelfContent,
    sizes: ShelfSizes,
    padding: Dp,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle,
) {
    val section = content.section
    val group = spec.group
    val title = group.title.orEmpty()
    val name = group.title ?: stringResource(R.string.channels_no_group)
    val count = itemCount(section, group.count)
    val version = rowVersion(section, group, content.now / ROW_REFRESH_MILLIS)
    // Suite de la rangée lue d'elle-même en approchant du bout (plus de « Voir plus »). La rangée ne dessine
    // que les vignettes à l'écran : celles qui sont passées sont libérées en défilant.
    var limit by rememberSaveable(spec.key) { mutableIntStateOf(HomeRows.ROW_SIZE) }
    fun loadMore(shown: Int): (() -> Unit)? = if (shown < group.count && shown >= limit) {
        { limit = shown + HomeRows.MORE_SIZE }
    } else {
        null
    }
    if (section == HomeSection.TV) {
        // Rangée déjà lue : affichée tout de suite, puis relue si elle a changé.
        val channels by produceState(content.rows.cached<ChannelListRow>(section, title), title, version, limit) {
            value = loadSafely { content.rows.fresh<ChannelListRow>(section, title, version, limit) } ?: value.orEmpty()
        }
        val list = channels.orEmpty()
        Shelf(name, count, padding, null, sizes.channel, CHANNEL_RATIO, spec.key, list.size, lastOpened, loading = channels == null, style = style, onNearEnd = loadMore(list.size), prefetch = remember(list) { list.map { it.logoUrl } }) {
            itemsIndexed(list, key = { _, it -> it.id }) { index, channel ->
                ShelfChannel(channel, index, spec.key, sizes.channel, content, lastOpened, actions, style)
            }
        }
    } else {
        val posters by produceState(content.rows.cachedPosters(section, title), title, version, limit) {
            value = loadSafely { content.rows.freshPosters(section, title, version, limit) } ?: value.orEmpty()
        }
        val list = posters.orEmpty()
        Shelf(name, count, padding, null, sizes.poster, POSTER_RATIO, spec.key, list.size, lastOpened, loading = posters == null, style = style, onNearEnd = loadMore(list.size), prefetch = posterPrefetch(list)) {
            itemsIndexed(list, key = { _, it -> it.itemKey }) { index, item ->
                ShelfPoster(item, index, spec.key, sizes.poster, content.favoriteKeys, lastOpened, actions, style)
            }
        }
    }
}


/** Films ou séries d'une rangée de catégorie, en affiches. */
private fun HomeRows.cachedPosters(section: HomeSection, group: String): List<PosterItem>? = when (section) {
    HomeSection.MOVIES -> cached<MovieRow>(section, group)?.map { it.toPoster() }
    else -> cached<SeriesRow>(section, group)?.map { it.toPoster() }
}

private suspend fun HomeRows.freshPosters(section: HomeSection, group: String, version: String, limit: Int): List<PosterItem> = when (section) {
    HomeSection.MOVIES -> fresh<MovieRow>(section, group, version, limit).map { it.toPoster() }
    else -> fresh<SeriesRow>(section, group, version, limit).map { it.toPoster() }
}

/**
 * Toutes les rangées sont lues d'avance, dans l'ordre de l'écran : la télécommande ne peut descendre
 * que sur une rangée déjà remplie (une rangée en chargement n'a rien à sélectionner, « bas » resterait bloqué).
 */
@Composable
internal fun PreloadRows(content: ShelfContent, posterWidth: Dp? = null) {
    val nowBucket = content.now / ROW_REFRESH_MILLIS
    val context = LocalContext.current
    val widthPx = posterWidth?.let { with(LocalDensity.current) { it.roundToPx() } }
    LaunchedEffect(content.section, content.groups, nowBucket) {
        content.groups.forEachIndexed { index, group ->
            val rows = loadSafely { content.rows.fresh<Any>(content.section, group.title.orEmpty(), rowVersion(content.section, group, nowBucket)) }
            // Affiches des premières rangées téléchargées d'avance (premières affiches seulement) : elles sont
            // là quand on y descend, même vite. Pas toutes : la mémoire et la connexion des petites box.
            if (widthPx != null && index < PREFETCH_ROWS && rows != null) {
                prefetchImages(context, rows.take(PREFETCH_FIRST_POSTERS).map(::imageOf), widthPx, (widthPx / POSTER_RATIO).roundToInt())
            }
        }
    }
}

/** Affiche ou logo d'une ligne de rangée. */
private fun imageOf(row: Any): String? = when (row) {
    is MovieRow -> row.logoUrl
    is SeriesRow -> row.logoUrl
    is ChannelListRow -> row.logoUrl
    else -> null
}

/** Télécharge et met en cache ces images, à la taille où elles seront affichées. */
private fun prefetchImages(context: android.content.Context, urls: List<String?>, widthPx: Int, heightPx: Int) {
    val loader = context.imageLoader
    urls.forEach { url ->
        if (url.isNullOrBlank()) return@forEach
        loader.enqueue(ImageRequest.Builder(context).data(url).size(widthPx, heightPx).build())
    }
}

/** Largeur des vignettes des rangées : sur la TV, six chaînes et huit affiches par rangée. */
internal data class ShelfSizes(val channel: Dp, val poster: Dp) {
    companion object {
        /** Streaming : grandes affiches seules (titre et détails dans le bandeau). */
        fun of(window: WindowInfo) = if (window.isWide) ShelfSizes(channel = 148.dp, poster = 120.dp) else ShelfSizes(channel = 156.dp, poster = 104.dp)

        /** Cinéma Premium : affiches plus grandes, moins nombreuses. */
        fun premium(window: WindowInfo) = if (window.isWide) ShelfSizes(channel = 200.dp, poster = 116.dp) else ShelfSizes(channel = 180.dp, poster = 116.dp)
    }
}

/** Version d'une rangée : relue quand sa catégorie change de taille, et toutes les 5 minutes pour les chaînes (programmes). */
private fun rowVersion(section: HomeSection, group: GroupRow, nowBucket: Long): String =
    if (section == HomeSection.TV) "$nowBucket:${group.count}" else "${group.count}"

/** Lecture d'une rangée ; une erreur laisse la rangée vide (l'annulation, elle, est transmise). */
private suspend fun <T> loadSafely(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Rangée : titre, nombre d'éléments et défilement horizontal, terminé par « Voir plus » quand la
 * catégorie a d'autres éléments. Sa place est gardée au retour (lecteur, fiche). Pendant le
 * chargement, des emplacements vides gardent sa hauteur.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    title: String,
    count: String?,
    padding: Dp,
    /** « Voir plus » : ajoute les éléments suivants ; `null` : la rangée est complète. */
    onMore: (() -> Unit)?,
    itemWidth: Dp,
    imageRatio: Float,
    rowKey: String,
    itemCount: Int,
    lastOpened: MutableState<OpenedItem?>,
    loading: Boolean = false,
    style: ShelfStyle = ShelfStyle.STREAMING,
    /** Suite à lire : appelé quand les dernières vignettes arrivent à l'écran (`null` : rangée complète). */
    onNearEnd: (() -> Unit)? = null,
    /** Images des vignettes, dans l'ordre : les suivantes sont téléchargées d'avance en avançant. */
    prefetch: List<String?> = emptyList(),
    content: LazyListScope.() -> Unit,
) {
    val rowState = rememberSaveable(rowKey, saver = LazyListState.Saver) { LazyListState() }
    // Vignettes à venir (à droite de l'écran) téléchargées d'avance : pas d'affiche vide en avançant vite.
    if (prefetch.isNotEmpty()) {
        val context = LocalContext.current
        val widthPx = with(LocalDensity.current) { itemWidth.roundToPx() }
        val heightPx = (widthPx / imageRatio).roundToInt()
        LaunchedEffect(rowState, prefetch) {
            var fetched = -1
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
                val until = minOf(prefetch.size, last + 1 + PREFETCH_AHEAD)
                val from = maxOf(fetched + 1, last + 1)
                if (from < until) prefetchImages(context, prefetch.subList(from, until), widthPx, heightPx)
                fetched = maxOf(fetched, until - 1)
            }
        }
    }
    val nearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(rowState, itemCount, onNearEnd != null) {
        if (onNearEnd == null || itemCount == 0) return@LaunchedEffect
        snapshotFlow { (rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= itemCount - NEAR_END_ITEMS }
            .first { it }
        nearEnd?.invoke()
    }
    RestoreScroll(rowState, lastOpened, itemCount, row = rowKey)
    // Saut à cette rangée depuis le volet des catégories : la sélection se pose sur sa première vignette visible.
    val jump = LocalRowJump.current
    val jumpFocus = remember { FocusRequester() }
    if (jump != null) {
        DisposableEffect(jump, rowKey) {
            jump.rows[rowKey] = jumpFocus
            onDispose { if (jump.rows[rowKey] === jumpFocus) jump.rows.remove(rowKey) }
        }
    }
    val premium = style == ShelfStyle.PREMIUM
    val spacing = if (premium) 0.dp else 2.dp
    val rowPadding = if (premium) 12.dp else 8.dp
    // Hauteur entre le haut de la rangée et ses vignettes : là où la vignette choisie s'arrête.
    val anchor = LocalRowAnchor.current
    val belowTitle = with(LocalDensity.current) { (spacing + rowPadding).toPx() }
    Column(
        if (anchor != null) {
            Modifier.onFocusChanged {
                if (it.hasFocus) anchor.focusedRow = rowKey else if (anchor.focusedRow == rowKey) anchor.focusedRow = null
            }
        } else {
            Modifier
        },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            Modifier
                .padding(horizontal = padding)
                .then(if (anchor != null) Modifier.onSizeChanged { anchor.cardTopPx = it.height + belowTitle } else Modifier),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (premium) FontWeight.Medium else FontWeight.SemiBold,
                color = if (premium) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            count?.let {
                Text("  ·  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides (LocalRowDefaultSpec.current ?: LocalBringIntoViewSpec.current)) {
            LazyRow(
                modifier = Modifier.focusRequester(jumpFocus),
                state = rowState,
                // Marge verticale : les vignettes grossissent sous le focus.
                contentPadding = PaddingValues(horizontal = padding, vertical = rowPadding),
                horizontalArrangement = Arrangement.spacedBy(if (premium) 18.dp else 12.dp),
            ) {
                if (loading) {
                    items(PLACEHOLDERS) {
                        Box(
                            Modifier
                                .width(itemWidth)
                                .aspectRatio(imageRatio)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                } else {
                    content()
                    if (onMore != null) item(key = "more") { MoreCard(onMore, itemWidth, imageRatio) }
                }
            }
        }
    }
}

@Composable
private fun MoreCard(onClick: () -> Unit, width: Dp, imageRatio: Float) {
    // Même hauteur que les visuels de la rangée, assez large pour « Voir plus ».
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(maxOf(width * 0.7f, SEE_ALL_MIN_WIDTH)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusScale = 1.06f,
    ) {
        Column(
            Modifier.fillMaxWidth().height(width / imageRatio).padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_rows_more), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun ShelfChannel(
    channel: ChannelListRow,
    index: Int,
    row: String,
    width: Dp,
    content: ShelfContent,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle = ShelfStyle.STREAMING,
    /** Premier élément ajouté par « Voir plus » : il prend la sélection. */
    focusRequester: FocusRequester? = null,
) {
    val modifier = Modifier
        .width(width)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .restoreFocus(channel.itemKey, lastOpened)
        .onFocusChanged { if (it.isFocused) actions.onChannelFocus(channel) }
    val onClick = {
        lastOpened.value = OpenedItem(channel.itemKey, index, row)
        if (row == FAVORITES_ROW) actions.onPlayFavoriteChannel(channel.id) else actions.onPlayChannel(channel.id)
    }
    val onLongClick = { actions.onToggleFavorite(ContentKind.LIVE, channel.itemKey, channel.title, channel.logoUrl) }
    if (style == ShelfStyle.PREMIUM) {
        PremiumChannelTile(channel, content.now, channel.itemKey in content.favoriteKeys, onClick, onLongClick, modifier)
    } else {
        ChannelCard(
            channel = channel,
            now = content.now,
            isFavorite = channel.itemKey in content.favoriteKeys,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            size = CardSize.of(width),
        )
    }
}

/** Sous-titre d'une affiche : année d'un film, nombre d'épisodes d'une série. */
@Composable
internal fun posterDetail(item: PosterItem): String? =
    if (item.kind == ContentKind.SERIES && item.episodeCount > 0) {
        LocalContext.current.resources.getQuantityString(R.plurals.episodes_count, item.episodeCount, item.episodeCount)
    } else {
        item.year?.toString()
    }

/**
 * Image d'une affiche des rangées : l'affiche TMDB (celle de la fiche) si elle est connue. Pas encore
 * cherchée : elle l'est, et l'emplacement reste vide un court instant plutôt que de montrer l'image de la
 * playlist puis de la remplacer (deux images de suite). Sans réponse à temps, ou sans affiche TMDB :
 * l'image de la playlist.
 */
@Composable
private fun posterImage(item: PosterItem): String? {
    val art = LocalPosterArt.current ?: return item.imageUrl
    art.posters[item.itemKey]?.let { return it }
    if (item.itemKey in art.missing) return item.imageUrl
    var waited by remember(item.itemKey) { mutableStateOf(false) }
    LaunchedEffect(item.itemKey) {
        art.request(item)
        delay(POSTER_WAIT_MILLIS)
        waited = true
    }
    return if (waited) item.imageUrl else null
}

/** Images à télécharger d'avance pour une rangée d'affiches : les affiches TMDB connues, sinon celles de la playlist. */
@Composable
private fun posterPrefetch(items: List<PosterItem>): List<String?> {
    val known = LocalPosterArt.current?.posters
    return remember(items, known) { items.map { known?.get(it.itemKey) ?: it.imageUrl } }
}

/** Attente de l'affiche TMDB d'un titre jamais affiché, avant de montrer l'image de la playlist. */
private const val POSTER_WAIT_MILLIS = 1_500L

@Composable
internal fun ShelfPoster(
    item: PosterItem,
    index: Int,
    row: String,
    width: Dp,
    favoriteKeys: Set<String>,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle = ShelfStyle.STREAMING,
    /** Premier élément ajouté par « Voir plus » : il prend la sélection. */
    focusRequester: FocusRequester? = null,
    /** Affiche du Top 10 de Cinéma Premium : son rang occupe le coin haut gauche. */
    ranked: Boolean = false,
) {
    val onClick = {
        lastOpened.value = OpenedItem(item.itemKey, index, row)
        if (item.kind == ContentKind.SERIES) actions.onOpenSeries(item.id) else actions.onOpenMovie(item.id)
    }
    val onLongClick = { actions.onToggleFavorite(item.kind, item.itemKey, item.title, item.imageUrl) }
    val modifier = Modifier
        .width(width)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .restoreFocus(item.itemKey, lastOpened)
        .onFocusChanged { if (it.isFocused) actions.onPosterFocus(item) }
    val watched = item.kind == ContentKind.MOVIE && item.itemKey in LocalWatchedKeys.current
    val image = posterImage(item)
    if (style == ShelfStyle.PREMIUM) {
        PremiumPoster(item.title, image, item.itemKey in favoriteKeys, onClick, onLongClick, modifier, watched = watched, quality = item.quality, badgeTop = if (ranked) 40.dp else 6.dp)
    } else {
        // Streaming : l'affiche seule, ses informations sont dans le bandeau.
        StreamingPoster(item.title, image, item.itemKey in favoriteKeys, onClick, onLongClick, modifier, width, watched = watched, quality = item.quality)
    }
}

/** « S01E08 · reste 12 min », « Épisode suivant : S01E09 » ou « Reste 1 h 15 ». */
@Composable
internal fun continueDetail(item: ProgressEntity): String? {
    val remaining = (item.durationMs - item.positionMs).coerceAtLeast(0)
    return when {
        item.episodeLabel != null && item.positionMs <= 0 -> stringResource(R.string.home_continue_next, item.episodeLabel)
        item.episodeLabel != null && item.durationMs > 0 -> stringResource(R.string.home_continue_episode, item.episodeLabel, formatDuration(remaining))
        item.episodeLabel != null -> item.episodeLabel
        item.durationMs > 0 -> stringResource(R.string.home_continue_remaining, formatDuration(remaining))
        else -> null
    }
}

@Composable
private fun ContinueCard(
    item: ProgressEntity,
    index: Int,
    row: String,
    width: Dp,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
    style: ShelfStyle,
) {
    val detail = continueDetail(item)
    val kind = if (item.episodeLabel != null) ContentKind.SERIES else ContentKind.MOVIE
    val poster = PosterItem(item.channelId, kind, item.itemKey, item.title, null, item.logoUrl)
    val onClick = {
        lastOpened.value = OpenedItem(item.itemKey, index, row)
        actions.onResume(item.channelId)
    }
    val modifier = Modifier
        .width(width)
        .restoreFocus(item.itemKey, lastOpened)
        .onFocusChanged { if (it.isFocused) actions.onPosterFocus(poster) }
    val progress = if (item.durationMs > 0) item.positionMs.toFloat() / item.durationMs else null
    if (style == ShelfStyle.PREMIUM) {
        PremiumPoster(item.title, item.logoUrl, false, onClick, { actions.onClearProgress(item.itemKey) }, modifier, progress = progress, detail = detail)
    } else {
        StreamingPoster(item.title, item.logoUrl, false, onClick, { actions.onClearProgress(item.itemKey) }, modifier, width, progress = progress, detail = detail)
    }
}

/** Grande affiche d'une chaîne : direct à droite, fondu dans le fond, programme en cours à gauche. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelBillboard(preview: ChannelPreview?, player: Player?, now: Long, padding: Dp, compact: Boolean = false) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            preview?.channel?.let { ChannelLogo(it.logoUrl, it.title, Modifier.fillMaxSize(0.4f), monogramSize = 34.dp) }
            if (player != null) PreviewVideo(player, Modifier.fillMaxSize())
            BillboardFade(background)
        }
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.52f).padding(start = padding, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (preview == null) {
                Text(stringResource(R.string.home_tv), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                return@Column
            }
            val channel = preview.channel
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                channel.channelNumber?.let { Badge(it) }
                Text(
                    channel.title,
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee(),
                )
                if (!compact) qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
            }
            val current = preview.current
            if (current == null) {
                Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted)
                return@Column
            }
            ScrollingText(current.title, if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LiveProgress(current.startMillis, current.stopMillis, now, Modifier.width(260.dp))
            }
            if (!compact) {
                current.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                preview.next?.let { next ->
                    Text(
                        stringResource(R.string.preview_next, shortTime(next.startMillis), next.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Image de fond de la mise en avant : l'image TMDB du film ou de la série, l'affiche de la liste à défaut.
 * Pendant la lecture de la fiche TMDB ([Spotlight.pending]), l'image précédente reste : sinon l'affiche
 * passait, puis l'image TMDB une demi-seconde après (deux changements d'image à chaque déplacement).
 */
@Composable
internal fun spotlightImage(spotlight: Spotlight?): String? {
    val last = remember { arrayOfNulls<String>(1) }
    val image = when {
        spotlight == null -> null
        spotlight.pending -> last[0]
        else -> spotlight.backdropUrl ?: spotlight.item.imageUrl
    }
    SideEffect { last[0] = image }
    return image
}

/** « 2024 · Action, Aventure · 2 h 05 · ★ 7,8 ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetaLine(spotlight: Spotlight, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val details = spotlight.details
    val parts = listOfNotNull(
        (details?.year ?: spotlight.item.year)?.toString(),
        details?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
        details?.runtimeMinutes?.let { formatDuration(it * 60_000L) },
        spotlight.seasonCount?.let { LocalContext.current.resources.getQuantityString(R.plurals.seasons_count, it, it) },
    )
    // Note en tête ; ligne trop longue (volet étroit) : la suite passe dessous, rien n'est écrasé ni coupé.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        spotlight.rating?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
                Text(String.format(Locale.FRENCH, "%.1f", rating), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
            }
        }
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Fondu léger autour de la bande-annonce : bord gauche et bas seulement, la vidéo reste visible. */
/** Fondu du visuel vers le fond, à gauche et en bas. */
@Composable
internal fun BillboardFade(background: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to background, 0.35f to background.copy(alpha = 0.55f), 1f to Color.Transparent),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to background)))
}

internal fun shortTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Bannière pleine : la première rangée tient en entier dessous, au-dessus de la marge de sécurité du bas des TV. */
private val STREAMING_BILLBOARD_HEIGHT = 200.dp
private val COMPACT_BILLBOARD_HEIGHT = 104.dp

/** Bandeau pendant le parcours des rangées : l'affiche choisie, la rangée entière dessous. */
private val HERO_BROWSE_HEIGHT = 244.dp

/** Navigateur intégré déjà préparé (une fois par lancement de l'appli). */
private var webViewWarmed = false

/** Contenus mis en avant dans le bandeau vedette. */
private const val FEATURED_COUNT = 6
private const val FEATURED_ROTATE_MILLIS = 9_000L
private const val FEATURED_AFTER_TRAILER_MILLIS = 1_500L
private val SEE_ALL_MIN_WIDTH = 104.dp
internal const val CHANNEL_RATIO = 16f / 9f
internal const val POSTER_RATIO = 2f / 3f
private const val PLACEHOLDERS = 6

/** Rangées de chaînes relues toutes les 5 minutes (programmes en cours). */
private const val ROW_REFRESH_MILLIS = 5 * 60_000L

/** Tendances nécessaires pour un Top 10 (sinon une rangée ordinaire). */
private const val TOP10_MIN = 5

/** Place du rang à gauche de chaque affiche du Top 10. */
/** Streaming : sous la rangée choisie, juste la place du titre de la suivante. */
private val STREAMING_NEXT_ROW_PEEK = 46.dp

/** Vignettes téléchargées d'avance à droite de l'écran, dans une rangée. */
private const val PREFETCH_AHEAD = 8

/** Rangées dont les premières affiches sont téléchargées d'avance, et combien. */
private const val PREFETCH_ROWS = 8
private const val PREFETCH_FIRST_POSTERS = 7

/** Vignettes restantes avant le bout de la rangée quand la suite est demandée. */
private const val NEAR_END_ITEMS = 6

private val TOP10_NUMBER_WIDTH = 84.dp
