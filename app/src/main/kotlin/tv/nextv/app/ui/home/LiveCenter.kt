package tv.nextv.app.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.components.bottomFade
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.theme.NexTvColors

/**
 * Interface « Live Center » (écran large) : catégories à gauche (favoris, récentes, toutes, groupes),
 * liste au centre, et à droite le volet de la sélection : aperçu vidéo, chaîne, programme en cours
 * avec sa progression et les suivants (chaînes), ou visuel, détails et résumé (films, séries).
 */
@Composable
internal fun LiveCenterSection(
    section: HomeSection,
    window: WindowInfo,
    groups: List<GroupRow>,
    recentCount: Int?,
    allCount: Int,
    category: Category,
    query: String,
    favoritesCount: Int,
    resumeCount: Int?,
    title: String,
    count: String,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    hint: (@Composable () -> Unit)? = null,
    pane: @Composable (Modifier) -> Unit,
    body: @Composable () -> Unit,
) {
    val tv = section == HomeSection.TV
    Row(modifier.fillMaxSize()) {
        CategoryRail(section, window, groups, recentCount, allCount, category, query, favoritesCount, resumeCount, actions, Modifier.width(230.dp), dense = true)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action?.invoke()
            }
            hint?.invoke()
            // Retour depuis le volet (gauche) : sur l'élément choisi, pas sur le plus proche.
            Box(Modifier.weight(1f).focusRestorer()) { body() }
        }
        pane(Modifier.weight(if (tv) 0.82f else 0.62f).fillMaxHeight())
    }
}

/** Volet du direct : vidéo, chaîne, programme en cours (progression, description), puis les suivants. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LivePane(preview: ChannelPreview?, player: Player?, now: Long, modifier: Modifier) {
    ScrollingPane(preview?.channel?.id, modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            preview?.channel?.let { ChannelLogo(it.logoUrl, it.title, Modifier.fillMaxWidth(0.4f).aspectRatio(16f / 9f), monogramSize = 30.dp) }
            if (player != null) PreviewVideo(player, Modifier.fillMaxSize())
        }
        if (preview == null) return@ScrollingPane
        val channel = preview.channel
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            channel.channelNumber?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Text(channel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false).basicMarquee())
            qualityLabel(channel.quality)?.let { Badge(it, color = MaterialTheme.colorScheme.secondary) }
        }
        val current = preview.current
        if (current == null) {
            Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted)
        } else {
            Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${shortTime(current.startMillis)} – ${shortTime(current.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LiveProgress(current.startMillis, current.stopMillis, now, Modifier.weight(1f))
                val left = ((current.stopMillis - now) / 60_000L).coerceAtLeast(0)
                Text(stringResource(R.string.live_remaining, left), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        preview.next?.let { next ->
            PaneLabel(stringResource(R.string.box_next))
            UpcomingLine(next, highlight = true)
        }
        if (preview.later.isNotEmpty()) {
            PaneLabel(stringResource(R.string.box_later))
            preview.later.take(3).forEach { UpcomingLine(it) }
        }
    }
}

/**
 * Volet de droite, que la télécommande peut faire défiler quand il dépasse : « droite » depuis la
 * liste y entre (cadre de sélection), haut / bas le font défiler, « gauche » revient à la liste.
 * Il repart du haut à chaque nouvelle sélection ([resetKey]).
 */
@Composable
private fun ScrollingPane(resetKey: Any?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(resetKey) { scroll.scrollTo(0) }
    val step = with(LocalDensity.current) { PANE_SCROLL_STEP.toPx() }
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && scrollPane(scroll, event.key, step) { scope.launch { it() } } }
            .focusable(enabled = scroll.maxValue > 0)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .bottomFade({ scroll.canScrollForward })
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Haut / bas dans le volet : défilement d'un pas ; en haut, « haut » laisse le focus monter (onglets). */
private fun scrollPane(scroll: ScrollState, key: Key, step: Float, launch: (suspend () -> Unit) -> Unit): Boolean = when (key) {
    Key.DirectionDown -> {
        launch { scroll.animateScrollBy(step) }
        true
    }
    Key.DirectionUp -> scroll.canScrollBackward.also { if (it) launch { scroll.animateScrollBy(-step) } }
    else -> false
}

private val PANE_SCROLL_STEP = 120.dp

@Composable
private fun PaneLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun UpcomingLine(programme: GuideProgramme, highlight: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(shortTime(programme.startMillis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Text(
            programme.title,
            style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Volet d'un film ou d'une série : visuel, titre, année, genres, note et résumé (TMDB). */
@Composable
internal fun SpotlightPane(spotlight: Spotlight?, modifier: Modifier) {
    ScrollingPane(spotlight?.item?.itemKey, modifier) {
        val image = spotlight?.backdropUrl ?: spotlight?.item?.imageUrl
        Crossfade(image, animationSpec = tween(400), label = "pane", modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)) { url ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (url != null) {
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)))))
                }
            }
        }
        if (spotlight == null) return@ScrollingPane
        Text(spotlight.item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MetaLine(spotlight)
        spotlight.details?.tagline?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
        }
        spotlight.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
    }
}
