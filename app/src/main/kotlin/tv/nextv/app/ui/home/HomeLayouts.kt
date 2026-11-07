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
 * Interface « Streaming » : grande bannière de la sélection (aperçu vidéo pour une chaîne, visuel
 * TMDB pour un film ou une série), puis la recherche et les rangées : reprises, tendances,
 * nouveautés, recommandations, favoris et une rangée par catégorie, chacune avec « Tout voir ».
 * Une recherche remplace les rangées par celle de ses résultats.
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
    /** Bande-annonce en fond de la grande affiche (réglages). */
    trailers: Boolean = false,
) {
    val section = content.section
    val padding = if (window.isWide) 28.dp else window.horizontalPadding
    val listState = rememberSaveable(section, saver = LazyListState.Saver) { LazyListState() }
    // Rangées parcourues : la bannière se réduit pour en montrer davantage, et reprend sa taille en haut.
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    val billboardHeight by animateDpAsState(
        when {
            // Films et séries : place des boutons « Lecture » et « Plus d'infos » (sans onglets en haut).
            !scrolled -> if (section == HomeSection.TV) STREAMING_BILLBOARD_HEIGHT else STREAMING_HERO_HEIGHT
            section == HomeSection.TV -> COMPACT_BILLBOARD_HEIGHT
            else -> SLIM_BILLBOARD_HEIGHT
        },
        animationSpec = tween(260),
        label = "billboard",
    )
    Column(modifier.fillMaxSize()) {
        if (window.isWide) {
            Box(Modifier.fillMaxWidth().height(billboardHeight).clipToBounds()) {
                if (section == HomeSection.TV) {
                    ChannelBillboard(preview, previewPlayer, content.now, padding, compact = scrolled)
                } else {
                    // Avant toute sélection : la première tendance de la semaine.
                    SpotlightBillboard(section, spotlight ?: content.discover.trending.firstOrNull()?.let { Spotlight(it) }, padding, compact = scrolled, actions = actions, trailer = trailers)
                }
            }
        }
        val sizes = ShelfSizes.of(window)
        PreloadRows(content)
        val shelves = shelfList(content)
        RestoreRow(listState, lastOpened, listOf("search") + shelves.map { it.key })
        AnchoredRows { anchor ->
            LazyColumn(
                Modifier.weight(1f).bottomFade({ listState.canScrollForward }, maxPx = { anchor.spaceBelowFocusedRow(listState) }),
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Recherche en tête des rangées : elle défile avec elles (et garde le clavier pendant la saisie).
                item(key = "search") {
                    SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = padding).widthIn(max = 460.dp))
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
            if (content.favoriteChannels.isNotEmpty()) add(ShelfSpec.Channels("favorites", R.string.home_favorites, content.favoriteChannels))
            if (content.recentChannels.isNotEmpty()) add(ShelfSpec.Channels("recent", R.string.home_recent, content.recentChannels))
        }
        HomeSection.MOVIES, HomeSection.SERIES -> {
            if (content.continueItems.isNotEmpty()) add(ShelfSpec.Continue("continue", content.continueItems))
            val discover = content.discover
            // Assez de tendances : un Top 10 à rangs, sinon la rangée ordinaire.
            if (discover.trending.size >= TOP10_MIN) {
                add(ShelfSpec.Top10("top10", discover.trending.take(10)))
            } else if (discover.trending.isNotEmpty()) {
                add(ShelfSpec.Posters("trending", R.string.home_rows_trending, discover.trending))
            }
            if (discover.newest.isNotEmpty()) add(ShelfSpec.Posters("newest", R.string.home_rows_newest, discover.newest))
            if (discover.recommended.isNotEmpty()) add(ShelfSpec.Posters("recommended", R.string.home_rows_recommended, discover.recommended, discover.recommendedFrom))
            val favorites = if (content.section == HomeSection.MOVIES) content.favoriteMovies.map { it.toPoster() } else content.favoriteSeries.map { it.toPoster() }
            if (favorites.isNotEmpty()) add(ShelfSpec.Posters("favorites", R.string.home_favorites, favorites))
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

/** Défilement habituel, rendu aux rangées horizontales : l'ancrage ne vaut que pour la liste des rangées. */
internal val LocalRowDefaultSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnchoredRows(content: @Composable (RowAnchor) -> Unit) {
    val default = LocalBringIntoViewSpec.current
    val anchor = remember { RowAnchor() }
    val spec = remember(anchor, default) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec get() = default.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
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
    items(shelves, key = { it.key }) { spec ->
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
            is ShelfSpec.Top10 -> Shelf(
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
    // « Voir plus » : la rangée s'allonge sur place, dans la même interface.
    var limit by rememberSaveable(spec.key) { mutableIntStateOf(HomeRows.ROW_SIZE) }
    val more = remember(spec.key) { MoreFocus() }
    // Pendant la lecture de la suite, « Voir plus » reste là (et choisi) jusqu'à l'arrivée des nouveaux éléments.
    fun onMore(shown: Int): (() -> Unit)? = if (shown < group.count && (shown >= limit || more.index == shown)) {
        {
            more.index = shown
            limit = shown + HomeRows.MORE_SIZE
        }
    } else {
        null
    }
    if (section == HomeSection.TV) {
        // Rangée déjà lue : affichée tout de suite, puis relue si elle a changé.
        val channels by produceState(content.rows.cached<ChannelListRow>(section, title), title, version, limit) {
            value = loadSafely { content.rows.fresh<ChannelListRow>(section, title, version, limit) } ?: value.orEmpty()
        }
        val list = channels.orEmpty()
        LaunchedEffect(list.size) { more.focusNewItem(list.size) }
        Shelf(name, count, padding, onMore(list.size), sizes.channel, CHANNEL_RATIO, spec.key, list.size, lastOpened, loading = channels == null, style = style) {
            itemsIndexed(list, key = { _, it -> it.id }) { index, channel ->
                ShelfChannel(channel, index, spec.key, sizes.channel, content, lastOpened, actions, style, more.requesterFor(index))
            }
        }
    } else {
        val posters by produceState(content.rows.cachedPosters(section, title), title, version, limit) {
            value = loadSafely { content.rows.freshPosters(section, title, version, limit) } ?: value.orEmpty()
        }
        val list = posters.orEmpty()
        LaunchedEffect(list.size) { more.focusNewItem(list.size) }
        Shelf(name, count, padding, onMore(list.size), sizes.poster, POSTER_RATIO, spec.key, list.size, lastOpened, loading = posters == null, style = style) {
            itemsIndexed(list, key = { _, it -> it.itemKey }) { index, item ->
                ShelfPoster(item, index, spec.key, sizes.poster, content.favoriteKeys, lastOpened, actions, style, more.requesterFor(index))
            }
        }
    }
}

/** « Voir plus » : une fois la rangée allongée, la sélection passe au premier élément ajouté ([index]). */
private class MoreFocus {
    private val requester = FocusRequester()
    var index by mutableIntStateOf(-1)

    fun requesterFor(item: Int): FocusRequester? = requester.takeIf { item == index }

    suspend fun focusNewItem(size: Int) {
        if (index !in 0 until size) return
        // L'élément est créé par la rangée à l'image suivante.
        repeat(FOCUS_TRIES) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) {
                index = -1
                return
            }
        }
        index = -1
    }

    private companion object {
        const val FOCUS_TRIES = 10
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
internal fun PreloadRows(content: ShelfContent) {
    val nowBucket = content.now / ROW_REFRESH_MILLIS
    LaunchedEffect(content.section, content.groups, nowBucket) {
        for (group in content.groups) {
            loadSafely { content.rows.fresh<Any>(content.section, group.title.orEmpty(), rowVersion(content.section, group, nowBucket)) }
        }
    }
}

/** Largeur des vignettes des rangées : sur la TV, six chaînes et huit affiches par rangée. */
internal data class ShelfSizes(val channel: Dp, val poster: Dp) {
    companion object {
        fun of(window: WindowInfo) = if (window.isWide) ShelfSizes(channel = 148.dp, poster = 104.dp) else ShelfSizes(channel = 156.dp, poster = 104.dp)

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
    content: LazyListScope.() -> Unit,
) {
    val rowState = rememberSaveable(rowKey, saver = LazyListState.Saver) { LazyListState() }
    RestoreScroll(rowState, lastOpened, itemCount, row = rowKey)
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
        actions.onPlayChannel(channel.id)
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
    if (style == ShelfStyle.PREMIUM) {
        PremiumPoster(item.title, item.imageUrl, item.itemKey in favoriteKeys, onClick, onLongClick, modifier)
    } else {
        PosterCard(
            title = item.title,
            subtitle = posterDetail(item),
            imageUrl = item.imageUrl,
            isFavorite = item.itemKey in favoriteKeys,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            compact = width < COMPACT_POSTER_WIDTH,
        )
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
    val remaining = (item.durationMs - item.positionMs).coerceAtLeast(0)
    val detail = when {
        item.episodeLabel != null && item.positionMs <= 0 -> stringResource(R.string.home_continue_next, item.episodeLabel)
        item.episodeLabel != null && item.durationMs > 0 -> stringResource(R.string.home_continue_episode, item.episodeLabel, formatDuration(remaining))
        item.episodeLabel != null -> item.episodeLabel
        item.durationMs > 0 -> stringResource(R.string.home_continue_remaining, formatDuration(remaining))
        else -> null
    }
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
        PosterCard(
            title = item.title,
            subtitle = detail,
            imageUrl = item.logoUrl,
            isFavorite = false,
            onClick = onClick,
            onLongClick = { actions.onClearProgress(item.itemKey) },
            modifier = modifier,
            progress = progress,
            compact = width < COMPACT_POSTER_WIDTH,
        )
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
 * Bannière d'un film ou d'une série : visuel TMDB à droite fondu dans le fond, titre, année,
 * genres, note et résumé à gauche. [compact] : titre et détails seulement.
 */
@Composable
internal fun SpotlightBillboard(
    section: HomeSection,
    spotlight: Spotlight?,
    padding: Dp,
    compact: Boolean = false,
    /** Boutons « Lecture » (film) ou « Épisodes » (série) et « Plus d'infos », sous le résumé. */
    actions: HomeActions? = null,
    /** Bande-annonce en fond après quelques secondes (réglages), quand TMDB en a une. */
    trailer: Boolean = false,
) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        val image = spotlight?.backdropUrl ?: spotlight?.item?.imageUrl
        Crossfade(image, animationSpec = tween(450), label = "backdrop", modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.66f)) { url ->
            Box(Modifier.fillMaxSize()) {
                if (url != null) {
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))))
                }
                BillboardFade(background)
            }
        }
        val trailerKey = spotlight?.details?.trailerYoutubeKey
        if (trailer && !compact && trailerKey != null) {
            // Cadre au format de la vidéo (16/9) sur toute la hauteur : l'image entière, rien de coupé.
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f)) {
                TrailerBackdrop(trailerKey, spotlight?.details?.trailerLanguage, Modifier.fillMaxSize())
                TrailerFade(background)
            }
        }
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).padding(start = padding, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!compact) {
                Text(
                    sectionLabel(section).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                spotlight?.item?.title ?: allLabel(section),
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            spotlight?.let { MetaLine(it) }
            if (!compact) {
                spotlight?.overview?.let { overview ->
                    Text(overview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val item = spotlight?.item
                if (actions != null && item != null) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val series = item.kind == ContentKind.SERIES
                        PillButton(
                            stringResource(if (series) R.string.hero_episodes else R.string.hero_play),
                            { if (series) actions.onOpenSeries(item.id) else actions.onPlayChannel(item.id) },
                            icon = Icons.Filled.PlayArrow,
                            style = PillStyle.Primary,
                        )
                        PillButton(
                            stringResource(R.string.hero_info),
                            { if (series) actions.onOpenSeries(item.id) else actions.onOpenMovie(item.id) },
                            icon = Icons.Filled.Info,
                        )
                    }
                }
            }
        }
    }
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
        details?.seasonCount?.let { LocalContext.current.resources.getQuantityString(R.plurals.seasons_count, it, it) },
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
@Composable
private fun TrailerFade(background: Color) {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.12f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.88f to Color.Transparent, 1f to background)))
}

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
private val STREAMING_HERO_HEIGHT = 260.dp
private val COMPACT_BILLBOARD_HEIGHT = 104.dp
private val SLIM_BILLBOARD_HEIGHT = 72.dp
private val SEE_ALL_MIN_WIDTH = 104.dp
internal const val CHANNEL_RATIO = 16f / 9f
internal const val POSTER_RATIO = 2f / 3f
private const val PLACEHOLDERS = 6

/** Rangées de chaînes relues toutes les 5 minutes (programmes en cours). */
private const val ROW_REFRESH_MILLIS = 5 * 60_000L

/** Tendances nécessaires pour un Top 10 (sinon une rangée ordinaire). */
private const val TOP10_MIN = 5

/** Place du rang à gauche de chaque affiche du Top 10. */
private val TOP10_NUMBER_WIDTH = 84.dp
