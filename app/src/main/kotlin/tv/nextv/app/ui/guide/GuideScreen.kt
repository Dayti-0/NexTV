package tv.nextv.app.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import java.text.DateFormat
import java.util.Date

@Composable
fun GuideScreen(onBack: () -> Unit, onPlay: (Long) -> Unit) {
    val viewModel = appViewModel { container, handle -> GuideViewModel(container, handle) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourceName by viewModel.sourceName.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    GuideContent(
        state = state,
        sourceName = sourceName,
        groups = groups,
        selectedGroup = selectedGroup,
        onBack = onBack,
        onSelectGroup = { viewModel.selectedGroup.value = it },
        onPlay = onPlay,
    )
}

/** Grille des programmes : une ligne par chaîne, l'axe horizontal est le temps (défilement commun). */
@Composable
fun GuideContent(
    state: GuideUiState,
    sourceName: String,
    groups: List<GroupRow>,
    selectedGroup: String?,
    onBack: () -> Unit,
    onSelectGroup: (String?) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val window = rememberWindowInfo()
    val minuteWidth = if (window.isWide) 4.6.dp else 3.2.dp
    val channelColumnWidth = if (window.isWide) 230.dp else 112.dp
    val rowHeight = if (window.isWide) 72.dp else 64.dp
    val scroll = rememberScrollState()
    var focused by remember { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
    val highlighted = focused ?: state.channels.firstNotNullOfOrNull { channel ->
        channel.programmes.firstOrNull { it.startMillis <= state.now && it.stopMillis > state.now }?.let { channel to it }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(R.string.guide_title),
                subtitle = sourceName,
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = 12.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
            if (groups.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { GuideGroupChip(stringResource(R.string.channels_all_short), selectedGroup == null) { onSelectGroup(null) } }
                    items(groups, key = { it.title ?: "\u0000" }) { group ->
                        GuideGroupChip(group.title ?: stringResource(R.string.channels_no_group), selectedGroup == group.title.orEmpty()) {
                            onSelectGroup(group.title.orEmpty())
                        }
                    }
                }
            }

            if (!state.loading && state.channels.isEmpty()) {
                EmptyState(
                    icon = NexTvIcons.Guide,
                    title = stringResource(R.string.guide_empty_title),
                    message = stringResource(R.string.guide_empty_message),
                )
            } else {
                GuideGrid(
                    state = state,
                    highlighted = highlighted,
                    scroll = scroll,
                    minuteWidth = minuteWidth,
                    channelColumnWidth = channelColumnWidth,
                    rowHeight = rowHeight,
                    compact = !window.isWide,
                    horizontalPadding = window.horizontalPadding,
                    onPlay = onPlay,
                    onFocus = { focused = it },
                )
            }
        }
    }
}

@Composable
private fun GuideGrid(
    state: GuideUiState,
    highlighted: Pair<GuideChannel, GuideProgramme>?,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    rowHeight: Dp,
    compact: Boolean,
    horizontalPadding: Dp,
    onPlay: (Long) -> Unit,
    onFocus: (Pair<GuideChannel, GuideProgramme>) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ProgrammeDetails(highlighted, Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp))
        TimeRuler(
            state = state,
            scroll = scroll,
            minuteWidth = minuteWidth,
            channelColumnWidth = channelColumnWidth,
            modifier = Modifier.padding(start = horizontalPadding),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = horizontalPadding, bottom = 24.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.channels, key = { it.id }) { channel ->
                GuideRow(
                    channel = channel,
                    state = state,
                    scroll = scroll,
                    minuteWidth = minuteWidth,
                    channelColumnWidth = channelColumnWidth,
                    rowHeight = rowHeight,
                    compact = compact,
                    onPlay = { onPlay(channel.id) },
                    onFocus = { programme -> onFocus(channel to programme) },
                )
            }
        }
    }
}

@Composable
private fun GuideGroupChip(name: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun ProgrammeDetails(highlighted: Pair<GuideChannel, GuideProgramme>?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 84.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (highlighted != null) ProgrammeDetailsText(highlighted.first, highlighted.second)
    }
}

@Composable
private fun ProgrammeDetailsText(channel: GuideChannel, programme: GuideProgramme) {
    Text(
        "${channel.name}  ·  ${timeRange(programme.startMillis, programme.stopMillis)}" +
            (programme.category?.let { "  ·  $it" } ?: ""),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(programme.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    (programme.description ?: programme.subtitle)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TimeRuler(
    state: GuideUiState,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val totalWidth = minutes(state.windowEnd - state.windowStart) * minuteWidth
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(state.now)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(channelColumnWidth + 6.dp),
            maxLines = 1,
        )
        Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                var t = state.windowStart
                while (t < state.windowEnd) {
                    Text(
                        timeOf(t),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = minutes(t - state.windowStart) * minuteWidth + 6.dp).align(Alignment.CenterStart),
                    )
                    t += HALF_HOUR
                }
                Box(
                    Modifier
                        .offset(x = minutes(state.now - state.windowStart) * minuteWidth - 4.dp)
                        .align(Alignment.BottomStart)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NexTvColors.Live),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel,
    state: GuideUiState,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    rowHeight: Dp,
    compact: Boolean,
    onPlay: () -> Unit,
    onFocus: (GuideProgramme) -> Unit,
) {
    val totalWidth = minutes(state.windowEnd - state.windowStart) * minuteWidth
    Row(Modifier.fillMaxWidth().height(rowHeight)) {
        FocusSurface(
            onClick = onPlay,
            modifier = Modifier.width(channelColumnWidth).fillMaxHeight(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            focusScale = 1f,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(if (compact) 36.dp else 44.dp), monogramSize = 14.dp)
                if (!compact) {
                    Column(Modifier.weight(1f)) {
                        channel.number?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(channel.number ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                if (channel.programmes.isEmpty()) {
                    Text(
                        stringResource(R.string.guide_no_programme),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexTvColors.TextMuted,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    )
                }
                channel.programmes.forEach { programme ->
                    val start = maxOf(programme.startMillis, state.windowStart)
                    val end = minOf(programme.stopMillis, state.windowEnd)
                    if (end - start >= 60_000) {
                        ProgrammeBlock(
                            programme = programme,
                            live = programme.startMillis <= state.now && programme.stopMillis > state.now,
                            past = programme.stopMillis <= state.now,
                            modifier = Modifier
                                .offset(x = minutes(start - state.windowStart) * minuteWidth)
                                .width(minutes(end - start) * minuteWidth)
                                .fillMaxHeight()
                                .padding(end = 4.dp),
                            onClick = onPlay,
                            onFocus = { onFocus(programme) },
                        )
                    }
                }
                Box(
                    Modifier
                        .offset(x = minutes(state.now - state.windowStart) * minuteWidth - 1.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(NexTvColors.Live),
                )
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: GuideProgramme,
    live: Boolean,
    past: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = when {
            live -> NexTvColors.PrimaryContainer
            past -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        focusScale = 1f,
        onFocusChange = { if (it) onFocus() },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(programme.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                timeRange(programme.startMillis, programme.stopMillis),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) NexTvColors.Live else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private const val HALF_HOUR = 30 * 60_000L

private fun minutes(millis: Long): Float = millis / 60_000f

private operator fun Float.times(dp: Dp): Dp = dp * this

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun timeRange(start: Long, stop: Long) = "${timeOf(start)} – ${timeOf(stop)}"
