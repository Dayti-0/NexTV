package tv.nextv.app.ui.home

import tv.nextv.app.ui.components.bottomFade
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.WindowInfo

/**
 * Interface « Cinéma Premium » : la sélection occupe tout l'écran (visuel TMDB d'un film ou d'une
 * série, direct de la chaîne), avec son titre en grand, ses détails et son résumé ; dessous, de
 * grandes affiches sur des rangées aérées. Les onglets, discrets, flottent sur l'image.
 */
@Composable
internal fun PremiumHome(
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
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val section = content.section
    val background = MaterialTheme.colorScheme.background
    // Avant toute sélection : la première tendance de la semaine.
    val spotlight = spotlight ?: content.discover.trending.firstOrNull()?.let { Spotlight(it) }
    val padding = if (window.isWide) 48.dp else window.horizontalPadding
    Box(modifier.fillMaxSize().background(background)) {
        // Fond : direct de la chaîne ou visuel de la sélection, en fondu.
        if (section == HomeSection.TV) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                // En attendant l'image : le logo, à droite (le texte occupe la gauche).
                preview?.channel?.let {
                    ChannelLogo(it.logoUrl, it.title, Modifier.align(BiasAlignment(0.7f, -0.3f)).fillMaxWidth(0.2f).aspectRatio(16f / 9f), monogramSize = 40.dp)
                }
                if (previewPlayer != null) PreviewVideo(previewPlayer, Modifier.fillMaxSize(), zoom = true)
            }
        } else {
            val image = spotlightImage(spotlight)
            Crossfade(image, animationSpec = tween(600), label = "premium", modifier = Modifier.fillMaxSize()) { url ->
                if (url != null) {
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f), background, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))),
                        ),
                    )
                }
            }
        }
        // Voiles : lisibilité du texte à gauche, des rangées en bas et des onglets en haut.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background.copy(alpha = 0.92f), 0.45f to background.copy(alpha = 0.55f), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to background.copy(alpha = 0.6f), 0.18f to Color.Transparent, 0.5f to Color.Transparent, 0.72f to background.copy(alpha = 0.88f), 1f to background)))
        Column(Modifier.fillMaxSize()) {
            topBar()
            Box(Modifier.fillMaxWidth().height(if (window.isWide) PREMIUM_INFO_HEIGHT else 150.dp).padding(start = padding, end = padding)) {
                if (section == HomeSection.TV) PremiumLiveInfo(preview, content.now) else PremiumInfo(section, spotlight)
            }
            val sizes = ShelfSizes.premium(window)
            PreloadRows(content)
            val shelves = shelfList(content)
            val listState = rememberSaveable(section, saver = LazyListState.Saver) { LazyListState() }
            RestoreRow(listState, lastOpened, shelves.map { it.key })
            RowJumpEffect(LocalRowJump.current, listState, content, shelves, offset = 0, onOpen = actions.onCategory)
            AnchoredRows { anchor ->
                LazyColumn(
                    Modifier.weight(1f).bottomFade({ listState.canScrollForward }, maxPx = { anchor.spaceBelowFocusedRow(listState) }),
                    state = listState,
                    contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Recherche en cours : ses résultats d'abord, sinon les rangées ; le champ, discret, vient après.
                    if (query.isNotEmpty()) {
                        item(key = "results") { SearchResults(section, channels, movies, series, sizes, padding, content, lastOpened, actions, ShelfStyle.PREMIUM) }
                    } else {
                        shelfItems(shelves, content, sizes, padding, lastOpened, actions, ShelfStyle.PREMIUM)
                    }
                    item(key = "search") {
                        SearchField(query, searchHint(section), actions.onQuery, Modifier.padding(horizontal = padding, vertical = 12.dp).width(360.dp))
                    }
                }
            }
        }
    }
}

/** Film ou série sélectionné : rubrique, grand titre, détails et résumé. */
@Composable
private fun PremiumInfo(section: HomeSection, spotlight: Spotlight?) {
    Column(Modifier.fillMaxWidth(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Text(
            spotlight?.item?.title ?: allLabel(section),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
            fontWeight = FontWeight.Bold,
            // Une ligne : la zone du titre reste basse, la rangée choisie remonte et celle du dessous se voit.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        spotlight?.let { MetaLine(it, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)) }
        spotlight?.overview?.let { overview ->
            Text(
                overview,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Chaîne sélectionnée : direct, programme en grand, horaires et progression. */
@Composable
private fun PremiumLiveInfo(preview: ChannelPreview?, now: Long) {
    Column(Modifier.fillMaxWidth(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        if (preview == null) {
            Text(stringResource(R.string.home_tv), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            return@Column
        }
        Text(
            listOfNotNull(preview.channel.channelNumber, preview.channel.title).joinToString("  "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val current = preview.current
        Text(
            current?.title ?: preview.channel.title,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                LiveProgress(current.startMillis, current.stopMillis, now, Modifier.width(240.dp))
            }
            current.description?.let {
                // Une ligne quand le programme suivant est annoncé : tout tient dans la hauteur du volet.
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    maxLines = if (preview.next != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        preview.next?.let { next ->
            Text(
                stringResource(R.string.preview_next, shortTime(next.startMillis), next.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Grande affiche aux coins arrondis, titre discret dessous ; elle s'avance sous le focus. */
@Composable
internal fun PremiumPoster(
    title: String,
    imageUrl: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    progress: Float? = null,
    detail: String? = null,
) {
    val width = POSTER_COLUMN_WIDTH
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.width(width).aspectRatio(POSTER_RATIO),
            shape = PREMIUM_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            focusScale = 1.08f,
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(title, imageUrl, Modifier.fillMaxSize())
                if (isFavorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.4f))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Chaîne en grande vignette : logo sur un dégradé, nom et programme en cours dessous. */
@Composable
internal fun PremiumChannelTile(
    channel: ChannelListRow,
    now: Long,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val width = CHANNEL_COLUMN_WIDTH
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.width(width).aspectRatio(CHANNEL_RATIO),
            shape = PREMIUM_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            focusScale = 1.08f,
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainer)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel.logoUrl, channel.title, Modifier.fillMaxWidth(0.5f).aspectRatio(16f / 9f), monogramSize = 22.dp)
                if (isFavorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(6.dp))
                val start = channel.nowStart
                val stop = channel.nowStop
                if (start != null && stop != null) {
                    LiveProgress(start, stop, now, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
        Text(channel.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        channel.nowTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private val PREMIUM_SHAPE = RoundedCornerShape(14.dp)
/** Hauteur du titre et du résumé au-dessus des rangées (assez basse pour voir la rangée suivante). */
private val PREMIUM_INFO_HEIGHT = 140.dp
private val POSTER_COLUMN_WIDTH: Dp = 116.dp
private val CHANNEL_COLUMN_WIDTH: Dp = 200.dp
