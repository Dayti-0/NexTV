package tv.nextv.app.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.model.ContentKind
import java.text.DateFormat
import java.util.Date

/**
 * Interface de l'accueil (réglages), en plus des thèmes de couleurs :
 * - [CLASSIC] : onglets, catégories à gauche, mosaïque ou liste avec l'aperçu en haut ;
 * - [STREAMING] : grande affiche de la sélection et rangées par catégorie ;
 * - [BOX] : liste des chaînes et grand volet du direct, comme une box d'opérateur (écran large).
 */
enum class HomeLayout { CLASSIC, STREAMING, BOX }

/** Élément sélectionné dans les rangées de films et de séries, montré en grand en haut. */
@Immutable
internal data class Billboard(val title: String, val detail: String?, val imageUrl: String?)

/**
 * Interface « Streaming » : grande affiche de l'élément sélectionné (aperçu vidéo pour une chaîne),
 * puis la recherche et une rangée par catégorie, chacune avec « Tout voir ». Une recherche remplace
 * les rangées par celle de ses résultats ([channels], [movies] ou [series], déjà filtrés).
 */
@Composable
internal fun StreamingHome(
    section: HomeSection,
    window: WindowInfo,
    groups: List<GroupRow>,
    rows: HomeRows,
    query: String,
    channels: LazyPagingItems<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    favoriteChannels: List<ChannelListRow>,
    recentChannels: List<ChannelListRow>,
    favoriteMovies: List<MovieRow>,
    favoriteSeries: List<SeriesRow>,
    continueItems: List<ProgressEntity>,
    favoriteKeys: Set<String>,
    now: Long,
    preview: ChannelPreview?,
    previewPlayer: Player?,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val billboard = remember(section) { mutableStateOf<Billboard?>(null) }
    val padding = if (window.isWide) 28.dp else window.horizontalPadding
    val listState = rememberLazyListState()
    // Rangées parcourues (recherche sortie de l'écran) : l'affiche se réduit pour en montrer davantage,
    // et reprend sa taille en haut de la liste. Chaînes : l'aperçu reste, en plus petit.
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val billboardHeight by animateDpAsState(
        when {
            !scrolled -> BILLBOARD_HEIGHT
            section == HomeSection.TV -> COMPACT_BILLBOARD_HEIGHT
            else -> 0.dp
        },
        label = "billboard",
    )
    Column(modifier.fillMaxSize()) {
        if (window.isWide && billboardHeight > 0.dp) {
            Box(Modifier.fillMaxWidth().height(billboardHeight).clipToBounds()) {
                if (section == HomeSection.TV) {
                    ChannelBillboard(preview, previewPlayer, now, padding, compact = scrolled)
                } else {
                    PosterBillboard(section, billboard.value, padding)
                }
            }
        }
        val sizes = ShelfSizes.of(window)
        val nowBucket = now / ROW_REFRESH_MILLIS
        // Toutes les rangées sont lues d'avance, dans l'ordre de l'écran : la télécommande ne peut descendre que
        // sur une rangée déjà remplie (une rangée en chargement n'a rien à sélectionner, « bas » resterait bloqué).
        LaunchedEffect(section, groups, nowBucket) {
            for (group in groups) {
                loadSafely { rows.fresh<Any>(section, group.title.orEmpty(), rowVersion(section, group, nowBucket)) }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Recherche en tête des rangées : elle défile avec elles (et garde le clavier pendant la saisie).
            item(key = "search") {
                SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = padding).widthIn(max = 460.dp))
            }
            if (query.isNotEmpty()) {
                item(key = "results") {
                    val count = when (section) {
                        HomeSection.TV -> channels.itemCount
                        HomeSection.MOVIES -> movies.itemCount
                        else -> series.itemCount
                    }
                    val ratio = if (section == HomeSection.TV) CHANNEL_RATIO else POSTER_RATIO
                    Shelf(stringResource(R.string.home_rows_results), itemCount(section, count), padding, null, if (section == HomeSection.TV) sizes.channel else sizes.poster, ratio) {
                        when (section) {
                            HomeSection.TV -> items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
                                channels[index]?.let { ShelfChannel(it, sizes.channel, favoriteKeys, now, lastOpened, actions) }
                            }
                            HomeSection.MOVIES -> items(movies.itemCount, key = movies.itemKey { it.id }) { index ->
                                movies[index]?.let { MoviePoster(it, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                            }
                            else -> items(series.itemCount, key = series.itemKey { it.itemKey }) { index ->
                                series[index]?.let { SeriesPoster(it, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                            }
                        }
                    }
                }
                return@LazyColumn
            }
            when (section) {
                HomeSection.TV -> {
                    channelShelf(R.string.home_favorites, favoriteChannels, Category.Favorites, sizes, padding, favoriteKeys, now, lastOpened, actions)
                    channelShelf(R.string.home_recent, recentChannels, Category.Recent, sizes, padding, favoriteKeys, now, lastOpened, actions)
                }
                HomeSection.MOVIES, HomeSection.SERIES -> {
                    if (continueItems.isNotEmpty()) {
                        item(key = "continue") {
                            Shelf(stringResource(R.string.home_continue), null, padding, { actions.onCategory(Category.ContinueWatching) }, sizes.poster, POSTER_RATIO) {
                                items(continueItems, key = { it.itemKey }) { item -> ContinueCard(item, sizes.poster, billboard, lastOpened, actions) }
                            }
                        }
                    }
                    if (section == HomeSection.MOVIES && favoriteMovies.isNotEmpty()) {
                        item(key = "favorites") {
                            Shelf(stringResource(R.string.home_favorites), null, padding, { actions.onCategory(Category.Favorites) }, sizes.poster, POSTER_RATIO) {
                                items(favoriteMovies, key = { it.itemKey }) { movie -> MoviePoster(movie, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                            }
                        }
                    }
                    if (section == HomeSection.SERIES && favoriteSeries.isNotEmpty()) {
                        item(key = "favorites") {
                            Shelf(stringResource(R.string.home_favorites), null, padding, { actions.onCategory(Category.Favorites) }, sizes.poster, POSTER_RATIO) {
                                items(favoriteSeries, key = { it.itemKey }) { show -> SeriesPoster(show, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                            }
                        }
                    }
                }
                HomeSection.GUIDE, HomeSection.FOOTBALL -> Unit
            }
            items(groups, key = { "group:" + (it.title ?: NO_GROUP_KEY) }) { group ->
                val title = group.title.orEmpty()
                val name = group.title ?: stringResource(R.string.channels_no_group)
                val seeAll = { actions.onCategory(Category.Group(title)) }
                val count = itemCount(section, group.count)
                val version = rowVersion(section, group, nowBucket)
                when (section) {
                    HomeSection.TV -> {
                        // Rangée déjà lue : affichée tout de suite, puis relue si elle a changé.
                        val channels by produceState(rows.cached<ChannelListRow>(section, title), title, version) {
                            value = loadSafely { rows.fresh<ChannelListRow>(section, title, version) } ?: value.orEmpty()
                        }
                        Shelf(name, count, padding, seeAll, sizes.channel, CHANNEL_RATIO, loading = channels == null) {
                            items(channels.orEmpty(), key = { it.id }) { channel -> ShelfChannel(channel, sizes.channel, favoriteKeys, now, lastOpened, actions) }
                        }
                    }
                    HomeSection.MOVIES -> {
                        val movies by produceState(rows.cached<MovieRow>(section, title), title, version) {
                            value = loadSafely { rows.fresh<MovieRow>(section, title, version) } ?: value.orEmpty()
                        }
                        Shelf(name, count, padding, seeAll, sizes.poster, POSTER_RATIO, loading = movies == null) {
                            items(movies.orEmpty(), key = { it.id }) { movie -> MoviePoster(movie, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                        }
                    }
                    else -> {
                        val series by produceState(rows.cached<SeriesRow>(section, title), title, version) {
                            value = loadSafely { rows.fresh<SeriesRow>(section, title, version) } ?: value.orEmpty()
                        }
                        Shelf(name, count, padding, seeAll, sizes.poster, POSTER_RATIO, loading = series == null) {
                            items(series.orEmpty(), key = { it.id }) { show -> SeriesPoster(show, sizes.poster, favoriteKeys, billboard, lastOpened, actions) }
                        }
                    }
                }
            }
        }
    }
}

/** Largeur des vignettes des rangées : sur la TV, six chaînes et huit affiches par rangée. */
private data class ShelfSizes(val channel: Dp, val poster: Dp) {
    companion object {
        fun of(window: WindowInfo) = if (window.isWide) ShelfSizes(channel = 148.dp, poster = 104.dp) else ShelfSizes(channel = 156.dp, poster = 104.dp)
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

private fun LazyListScope.channelShelf(
    title: Int,
    channels: List<ChannelListRow>,
    category: Category,
    sizes: ShelfSizes,
    padding: Dp,
    favoriteKeys: Set<String>,
    now: Long,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    if (channels.isEmpty()) return
    item(key = "shelf:$title") {
        Shelf(stringResource(title), null, padding, { actions.onCategory(category) }, sizes.channel, CHANNEL_RATIO) {
            items(channels, key = { it.itemKey }) { channel -> ShelfChannel(channel, sizes.channel, favoriteKeys, now, lastOpened, actions) }
        }
    }
}

/**
 * Rangée : titre, nombre d'éléments et défilement horizontal, terminé par « Tout voir ».
 * Pendant le chargement, des emplacements vides gardent la hauteur de la rangée.
 */
@Composable
private fun Shelf(
    title: String,
    count: String?,
    padding: Dp,
    /** `null` : pas de « Tout voir » (résultats de recherche). */
    onSeeAll: (() -> Unit)?,
    itemWidth: Dp,
    imageRatio: Float,
    loading: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.padding(horizontal = padding), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            count?.let {
                Text("  ·  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        LazyRow(
            // Marge verticale : les vignettes grossissent sous le focus.
            contentPadding = PaddingValues(horizontal = padding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                if (onSeeAll != null) item(key = "see-all") { SeeAllCard(onSeeAll, itemWidth, imageRatio) }
            }
        }
    }
}

@Composable
private fun SeeAllCard(onClick: () -> Unit, width: Dp, imageRatio: Float) {
    // Même hauteur que les visuels de la rangée, assez large pour « Tout voir ».
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
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_rows_see_all), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ShelfChannel(
    channel: ChannelListRow,
    width: Dp,
    favoriteKeys: Set<String>,
    now: Long,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    ChannelCard(
        channel = channel,
        now = now,
        isFavorite = channel.itemKey in favoriteKeys,
        onClick = {
            lastOpened.value = channel.itemKey
            actions.onPlayChannel(channel.id)
        },
        onLongClick = { actions.onToggleFavorite(ContentKind.LIVE, channel.itemKey, channel.title, channel.logoUrl) },
        modifier = Modifier
            .width(width)
            .restoreFocus(channel.itemKey, lastOpened)
            .onFocusChanged { if (it.isFocused) actions.onChannelFocus(channel) },
        size = CardSize.of(width),
    )
}

@Composable
private fun MoviePoster(
    movie: MovieRow,
    width: Dp,
    favoriteKeys: Set<String>,
    billboard: MutableState<Billboard?>,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    val detail = movie.year?.toString()
    PosterCard(
        title = movie.title,
        subtitle = detail,
        imageUrl = movie.logoUrl,
        isFavorite = movie.itemKey in favoriteKeys,
        onClick = {
            lastOpened.value = movie.itemKey
            actions.onOpenMovie(movie.id)
        },
        onLongClick = { actions.onToggleFavorite(ContentKind.MOVIE, movie.itemKey, movie.title, movie.logoUrl) },
        modifier = Modifier
            .width(width)
            .restoreFocus(movie.itemKey, lastOpened)
            .onFocusChanged { if (it.isFocused) billboard.value = Billboard(movie.title, detail, movie.logoUrl) },
        compact = width < COMPACT_POSTER_WIDTH,
    )
}

@Composable
private fun SeriesPoster(
    show: SeriesRow,
    width: Dp,
    favoriteKeys: Set<String>,
    billboard: MutableState<Billboard?>,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    val detail = LocalContext.current.resources.getQuantityString(R.plurals.episodes_count, show.episodeCount, show.episodeCount)
    PosterCard(
        title = show.title,
        subtitle = detail,
        imageUrl = show.logoUrl,
        isFavorite = show.itemKey in favoriteKeys,
        onClick = {
            lastOpened.value = show.itemKey
            actions.onOpenSeries(show.id)
        },
        onLongClick = { actions.onToggleFavorite(ContentKind.SERIES, show.itemKey, show.title, show.logoUrl) },
        modifier = Modifier
            .width(width)
            .restoreFocus(show.itemKey, lastOpened)
            .onFocusChanged { if (it.isFocused) billboard.value = Billboard(show.title, detail, show.logoUrl) },
        compact = width < COMPACT_POSTER_WIDTH,
    )
}

@Composable
private fun ContinueCard(
    item: ProgressEntity,
    width: Dp,
    billboard: MutableState<Billboard?>,
    lastOpened: MutableState<String?>,
    actions: HomeActions,
) {
    val remaining = (item.durationMs - item.positionMs).coerceAtLeast(0)
    val detail = when {
        item.episodeLabel != null && item.positionMs <= 0 -> stringResource(R.string.home_continue_next, item.episodeLabel)
        item.episodeLabel != null && item.durationMs > 0 -> stringResource(R.string.home_continue_episode, item.episodeLabel, formatDuration(remaining))
        item.episodeLabel != null -> item.episodeLabel
        item.durationMs > 0 -> stringResource(R.string.home_continue_remaining, formatDuration(remaining))
        else -> null
    }
    PosterCard(
        title = item.title,
        subtitle = detail,
        imageUrl = item.logoUrl,
        isFavorite = false,
        onClick = {
            lastOpened.value = item.itemKey
            actions.onResume(item.channelId)
        },
        onLongClick = { actions.onClearProgress(item.itemKey) },
        modifier = Modifier
            .width(width)
            .restoreFocus(item.itemKey, lastOpened)
            .onFocusChanged { if (it.isFocused) billboard.value = Billboard(item.title, detail, item.logoUrl) },
        progress = if (item.durationMs > 0) item.positionMs.toFloat() / item.durationMs else null,
        compact = width < COMPACT_POSTER_WIDTH,
    )
}

/** Grande affiche d'une chaîne : direct à droite, fondu dans le fond, programme en cours à gauche. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBillboard(preview: ChannelPreview?, player: Player?, now: Long, padding: Dp, compact: Boolean = false) {
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
                Text(stringResource(R.string.preview_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val channel = preview.channel
            if (compact) {
                // Affiche réduite : chaîne, programme en cours et progression.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
                    channel.channelNumber?.let { Badge(it) }
                    Text(channel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false).basicMarquee())
                }
                preview.current?.let { current ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScrollingText(current.title, MaterialTheme.typography.titleMedium, Modifier.weight(1f, fill = false))
                        Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LiveProgress(current.startMillis, current.stopMillis, now, Modifier.width(320.dp))
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
                channel.channelNumber?.let { Badge(it) }
                qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
            }
            Text(
                channel.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            val current = preview.current
            if (current == null) {
                Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScrollingText(current.title, MaterialTheme.typography.titleLarge, Modifier.weight(1f, fill = false))
                    Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LiveProgress(current.startMillis, current.stopMillis, now, Modifier.width(320.dp))
                current.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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

/** Grande affiche d'un film ou d'une série : visuel à droite, titre et détail à gauche. */
@Composable
private fun PosterBillboard(section: HomeSection, item: Billboard?, padding: Dp) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(BILLBOARD_HEIGHT)) {
        if (item?.imageUrl != null) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
                BillboardFade(background)
            }
        } else {
            // Sans visuel : halo aux couleurs de l'application.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))))
        }
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).padding(start = padding, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                sectionLabel(section).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                item?.title ?: allLabel(section),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item?.detail?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.billboard_hint_poster), style = MaterialTheme.typography.bodyMedium, color = NexTvColors.TextMuted)
        }
    }
}

/** Fondu du visuel vers le fond, à gauche et en bas. */
@Composable
private fun BillboardFade(background: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to background, 0.35f to background.copy(alpha = 0.55f), 1f to Color.Transparent),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to background)))
}

/**
 * Interface « Box TV » (section TV, écran large) : catégories, liste des chaînes avec leur programme,
 * et grand volet du direct avec le programme en cours et les suivants.
 */
@Composable
internal fun BoxTvSection(
    window: WindowInfo,
    groups: List<GroupRow>,
    recentCount: Int,
    allCount: Int,
    category: Category,
    query: String,
    favoritesCount: Int,
    title: String,
    count: String,
    preview: ChannelPreview?,
    previewPlayer: Player?,
    now: Long,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    list: @Composable () -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        CategoryRail(HomeSection.TV, window, groups, recentCount, allCount, category, query, favoritesCount, null, actions, Modifier.width(220.dp), dense = true)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action?.invoke()
            }
            Box(Modifier.weight(1f)) { list() }
        }
        BoxLivePane(preview, previewPlayer, now, Modifier.weight(0.7f).fillMaxHeight())
    }
}

/** Volet du direct : vidéo, chaîne, programme en cours (description complète) puis les suivants. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxLivePane(preview: ChannelPreview?, player: Player?, now: Long, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            preview?.channel?.let { ChannelLogo(it.logoUrl, it.title, Modifier.fillMaxSize(0.4f), monogramSize = 30.dp) }
            if (player != null) PreviewVideo(player, Modifier.fillMaxSize())
        }
        if (preview == null) {
            Text(stringResource(R.string.box_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val channel = preview.channel
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            channel.channelNumber?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Text(channel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false).basicMarquee())
            qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
        }
        Text(stringResource(R.string.box_now).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        val current = preview.current
        if (current == null) {
            Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted)
        } else {
            Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LiveProgress(current.startMillis, current.stopMillis, now, Modifier.weight(1f))
            }
            current.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        val following = listOfNotNull(preview.next) + preview.later
        if (following.isNotEmpty()) {
            Text(stringResource(R.string.box_later).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            following.take(4).forEach { programme -> UpcomingLine(programme) }
        }
    }
}

@Composable
private fun UpcomingLine(programme: GuideProgramme) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(shortTime(programme.startMillis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Text(programme.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun shortTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private val BILLBOARD_HEIGHT = 176.dp
private val COMPACT_BILLBOARD_HEIGHT = 104.dp
private val SEE_ALL_MIN_WIDTH = 104.dp
private const val CHANNEL_RATIO = 16f / 9f
private const val POSTER_RATIO = 2f / 3f
private const val PLACEHOLDERS = 6

/** Rangées de chaînes relues toutes les 5 minutes (programmes en cours). */
private const val ROW_REFRESH_MILLIS = 5 * 60_000L
