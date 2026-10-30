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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.settings.Reminder
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.source.Catchup
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Guide TV de la playlist active (section de l'accueil). [onReplay] : programme passé d'une chaîne
 * avec replay (chaîne, début, fin).
 */
@Composable
fun GuideScreen(
    sourceId: Long,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onReplay: (Long, Long, Long) -> Unit = { _, _, _ -> },
) {
    val viewModel = appViewModel(key = "guide-$sourceId") { container, _ -> GuideViewModel(container, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    GuideContent(
        state = state,
        groups = groups,
        selectedGroup = selectedGroup,
        onSelectGroup = { viewModel.selectedGroup.value = it },
        onPlay = onPlay,
        onReplay = { channel, programme -> onReplay(channel.id, programme.startMillis, programme.stopMillis) },
        reminders = reminders,
        onToggleReminder = viewModel::toggleReminder,
        modifier = modifier,
    )
}

/**
 * Grille des programmes : chaînes à gauche, programmes sur l'axe du temps (défilement commun).
 * Haut/bas : chaînes ; gauche/droite : horaires (jusqu'à la veille) ; OK sur un programme : fiche
 * détaillée, avec « Revoir » pour les programmes passés des chaînes avec replay.
 * Seules les informations du guide sont affichées : une période sans programme reste vide.
 */
@Composable
fun GuideContent(
    state: GuideUiState,
    groups: List<GroupRow>,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onReplay: (GuideChannel, GuideProgramme) -> Unit = { _, _ -> },
    /** Programmes avec un rappel ([Reminder.keyOf]). */
    reminders: Set<String> = emptySet(),
    onToggleReminder: (GuideChannel, GuideProgramme) -> Unit = { _, _ -> },
    initialDetails: Pair<GuideChannel, GuideProgramme>? = null,
) {
    val window = rememberWindowInfo()
    val minuteWidth = if (window.isWide) 4.6.dp else 3.2.dp
    val channelColumnWidth = if (window.isWide) 230.dp else 112.dp
    val rowHeight = if (window.isWide) 72.dp else 64.dp
    val scroll = rememberScrollState()
    var focused by remember { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
    var details by remember { mutableStateOf(initialDetails) }
    val highlighted = focused ?: state.channels.firstNotNullOfOrNull { channel ->
        channel.programmes.firstOrNull { it.isLive(state.now) }?.let { channel to it }
    }

    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GuideGroupChip(stringResource(R.string.home_favorites), selectedGroup == GuideViewModel.FAVORITES, star = true) { onSelectGroup(GuideViewModel.FAVORITES) } }
            item { GuideGroupChip(stringResource(R.string.channels_all_short), selectedGroup == null) { onSelectGroup(null) } }
            items(groups, key = { it.title ?: "\u0000" }) { group ->
                GuideGroupChip(group.title ?: stringResource(R.string.channels_no_group), selectedGroup == group.title.orEmpty()) {
                    onSelectGroup(group.title.orEmpty())
                }
            }
        }

        if (!state.loading && state.channels.isEmpty()) {
            EmptyState(
                icon = NexTvIcons.Guide,
                title = stringResource(if (selectedGroup == GuideViewModel.FAVORITES) R.string.guide_no_favorites_title else R.string.guide_empty_title),
                message = stringResource(if (selectedGroup == GuideViewModel.FAVORITES) R.string.guide_no_favorites_message else R.string.guide_empty_message),
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
                requestInitialFocus = window.isTv,
                reminders = reminders,
                onPlay = onPlay,
                onFocus = { focused = it },
                onOpen = { details = it },
            )
        }
    }

    details?.let { (channel, programme) ->
        ProgrammeSheet(
            channel = channel,
            programme = programme,
            now = state.now,
            onWatch = {
                details = null
                onPlay(channel.id)
            },
            onDismiss = { details = null },
            onReplay = if (Catchup.isAvailable(channel.catchupDays, programme.startMillis, state.now)) {
                {
                    details = null
                    onReplay(channel, programme)
                }
            } else {
                null
            },
            reminded = Reminder.keyOf(channel.id, programme.startMillis) in reminders,
            onRemind = if (programme.startMillis > state.now) ({ onToggleReminder(channel, programme) }) else null,
        )
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
    requestInitialFocus: Boolean,
    reminders: Set<String>,
    onPlay: (Long) -> Unit,
    onFocus: (Pair<GuideChannel, GuideProgramme>) -> Unit,
    onOpen: (Pair<GuideChannel, GuideProgramme>) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    // Ouverture sur la demi-heure écoulée ; la veille reste accessible vers la gauche.
    var initialScrollDone by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    if (!initialScrollDone && !state.loading) {
        LaunchedEffect(Unit) {
            scroll.scrollTo(with(density) { (minutes(state.openAt - state.windowStart) * minuteWidth).roundToPx() })
            initialScrollDone = true
        }
    }
    val firstLive = state.channels.firstOrNull()?.let { channel -> channel.programmes.firstOrNull { it.isLive(state.now) } }
    if (requestInitialFocus && initialScrollDone && !initialFocusDone && firstLive != null) {
        LaunchedEffect(firstLive) {
            runCatching { initialFocus.requestFocus() }
            initialFocusDone = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProgrammeDetails(highlighted, state.now, Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp))
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
                    reminders = reminders,
                    focusRequester = initialFocus.takeIf { channel == state.channels.first() },
                    onPlay = { onPlay(channel.id) },
                    onFocus = { programme -> onFocus(channel to programme) },
                    onOpen = { programme -> onOpen(channel to programme) },
                )
            }
        }
    }
}

@Composable
private fun GuideGroupChip(name: String, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ProgrammeDetails(highlighted: Pair<GuideChannel, GuideProgramme>?, now: Long, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 84.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (highlighted != null) ProgrammeDetailsText(highlighted.first, highlighted.second, now)
    }
}

@Composable
private fun ProgrammeDetailsText(channel: GuideChannel, programme: GuideProgramme, now: Long) {
    Text(
        "${channel.name}  ·  ${dayPrefix(programme.startMillis, now)}${timeRange(programme.startMillis, programme.stopMillis)}  ·  ${duration(programme)}" +
            (programme.category?.let { "  ·  $it" } ?: "") +
            (programme.year?.let { "  ·  $it" } ?: ""),
        style = MaterialTheme.typography.labelLarge,
        color = if (programme.isLive(now)) NexTvColors.Live else MaterialTheme.colorScheme.primary,
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
                    val midnight = isMidnight(t)
                    Text(
                        if (midnight) dayOf(t) else timeOf(t),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
    reminders: Set<String>,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
    onFocus: (GuideProgramme) -> Unit,
    onOpen: (GuideProgramme) -> Unit,
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
                        val live = programme.isLive(state.now)
                        ProgrammeBlock(
                            programme = programme,
                            live = live,
                            past = programme.stopMillis <= state.now,
                            replay = Catchup.isAvailable(channel.catchupDays, programme.startMillis, state.now),
                            reminded = Reminder.keyOf(channel.id, programme.startMillis) in reminders,
                            modifier = Modifier
                                .offset(x = minutes(start - state.windowStart) * minuteWidth)
                                .width(minutes(end - start) * minuteWidth)
                                .fillMaxHeight()
                                .padding(end = 4.dp)
                                .then(if (live && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                            onClick = { onOpen(programme) },
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
    replay: Boolean,
    reminded: Boolean,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Programme passé disponible en replay.
                if (replay && past) Icon(NexTvIcons.Replay, contentDescription = null, tint = NexTvColors.Primary, modifier = Modifier.size(12.dp))
                // Rappel programmé.
                if (reminded) Icon(Icons.Filled.Notifications, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(12.dp))
                Text(
                    timeRange(programme.startMillis, programme.stopMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) NexTvColors.Live else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Fiche détaillée d'un programme (`null` : aucun programme connu), avec l'action « Regarder » et,
 * si [onReplay] est donné, « Revoir » (programme passé) ou « Depuis le début » (programme en cours).
 */
@Composable
fun ProgrammeSheet(
    channel: GuideChannel,
    programme: GuideProgramme?,
    now: Long,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    onReplay: (() -> Unit)? = null,
    reminded: Boolean = false,
    onRemind: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        ProgrammeSheetContent(channel, programme, now, onWatch, onDismiss, onReplay, reminded, onRemind)
    }
}

@Composable
fun ProgrammeSheetContent(
    channel: GuideChannel,
    programme: GuideProgramme?,
    now: Long,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    onReplay: (() -> Unit)? = null,
    reminded: Boolean = false,
    onRemind: (() -> Unit)? = null,
) {
    val watchFocus = remember { FocusRequester() }
    // Programme terminé : « Revoir » passe en premier.
    val replayFirst = onReplay != null && programme != null && programme.stopMillis <= now
    LaunchedEffect(Unit) { runCatching { watchFocus.requestFocus() } }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 640.dp),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 64.dp, height = 44.dp), monogramSize = 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (programme != null) {
                        Text(
                            "${dayPrefix(programme.startMillis, now)}${timeRange(programme.startMillis, programme.stopMillis)}  ·  ${duration(programme)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    programme == null -> Unit
                    programme.isLive(now) -> StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
                    programme.stopMillis <= now -> StatusPill(stringResource(R.string.guide_ended), NexTvColors.TextSecondary)
                    else -> StatusPill(stringResource(R.string.guide_upcoming), NexTvColors.Primary)
                }
            }
            if (programme == null) {
                Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.headlineSmall)
            } else {
                Text(programme.title, style = MaterialTheme.typography.headlineSmall)
                programme.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // Genre et année de production, quand le guide les indique.
                listOfNotNull(programme.category, programme.year?.toString()).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (programme.isLive(now)) LiveProgress(programme.startMillis, programme.stopMillis, now)
                Text(
                    programme.description ?: stringResource(R.string.guide_no_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (replayFirst && onReplay != null) {
                    PillButton(
                        stringResource(R.string.guide_replay),
                        onReplay,
                        icon = NexTvIcons.Replay,
                        style = PillStyle.Primary,
                        modifier = Modifier.focusRequester(watchFocus),
                    )
                }
                PillButton(
                    stringResource(if (replayFirst) R.string.guide_watch_live else R.string.guide_watch),
                    onWatch,
                    icon = Icons.Filled.PlayArrow,
                    style = if (replayFirst) PillStyle.Secondary else PillStyle.Primary,
                    modifier = if (replayFirst) Modifier else Modifier.focusRequester(watchFocus),
                )
                if (!replayFirst && onReplay != null) {
                    PillButton(stringResource(R.string.guide_restart), onReplay, icon = NexTvIcons.Replay)
                }
                // Programme à venir : bandeau 5 minutes avant le début.
                if (onRemind != null) {
                    PillButton(
                        stringResource(if (reminded) R.string.reminder_on else R.string.reminder_set),
                        onRemind,
                        icon = if (reminded) Icons.Filled.Check else Icons.Filled.Notifications,
                    )
                }
                PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
            }
        }
    }
}

private fun GuideProgramme.isLive(now: Long) = startMillis <= now && stopMillis > now

private const val HALF_HOUR = 30 * 60_000L

private fun minutes(millis: Long): Float = millis / 60_000f

private operator fun Float.times(dp: Dp): Dp = dp * this

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun isMidnight(millis: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = millis }.let { it.get(Calendar.HOUR_OF_DAY) == 0 && it.get(Calendar.MINUTE) == 0 }

/** « ven. 30 ». */
private fun dayOf(millis: Long): String = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(millis))

/** Jour du programme s'il n'est pas aujourd'hui (« Hier · », « Demain · », « sam. 1 · »), sinon rien. */
@Composable
private fun dayPrefix(millis: Long, now: Long): String {
    return when (dayIndex(millis) - dayIndex(now)) {
        0L -> ""
        -1L -> stringResource(R.string.guide_yesterday) + "  ·  "
        1L -> stringResource(R.string.guide_tomorrow) + "  ·  "
        else -> dayOf(millis) + "  ·  "
    }
}

/** Numéro du jour local (jours depuis l'époque, dans le fuseau de l'appareil). */
private fun dayIndex(millis: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(millis)
    return Math.floorDiv(millis + offset, 86_400_000L)
}

private fun timeRange(start: Long, stop: Long) = "${timeOf(start)} – ${timeOf(stop)}"

/** « 45 min », « 1 h 30 ». */
fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h ${minutes.toString().padStart(2, '0')}"
    }
}

private fun duration(programme: GuideProgramme) = formatDuration(programme.stopMillis - programme.startMillis)
