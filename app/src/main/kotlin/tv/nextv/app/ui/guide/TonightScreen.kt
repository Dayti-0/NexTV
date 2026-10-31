package tv.nextv.app.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Ce soir » : le programme du soir comme dans un magazine télé. Une ligne par chaîne (TNT
 * d'abord, avec son numéro), le programme principal vers 21 h et la deuxième partie de soirée.
 * Gauche/droite : soirs suivants (puces) ou deuxième partie ; OK sur un programme : sa fiche
 * (« Regarder » lance la chaîne).
 */
@Composable
internal fun TonightView(
    state: TonightUiState,
    window: WindowInfo,
    onDay: (Int) -> Unit,
    onOpen: (GuideChannel, GuideProgramme) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(state.day) { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
    val shown = focused ?: state.rows.firstOrNull()?.let { row -> row.first?.let { row.channel to it } }
    val firstBlock = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    if (window.isTv && !initialFocusDone && state.rows.isNotEmpty()) {
        LaunchedEffect(Unit) {
            runCatching { firstBlock.requestFocus() }
            initialFocusDone = true
        }
    }

    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.evenings) { index, evening ->
                EveningChip(eveningLabel(index, evening), selected = index == state.day) { onDay(index) }
            }
        }
        // Programme sélectionné : horaires, genre et résumé.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(horizontal = window.horizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shown?.let { (channel, programme) ->
                Text(
                    listOfNotNull(
                        channel.name,
                        "${shortTime(programme.startMillis)} – ${shortTime(programme.stopMillis)}",
                        formatDuration(programme.stopMillis - programme.startMillis),
                        programme.category,
                        programme.year?.toString(),
                    ).joinToString("  ·  "),
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
        }
        if (!state.loading && state.rows.isEmpty()) {
            EmptyState(NexTvIcons.Guide, stringResource(R.string.tonight_empty_title), stringResource(R.string.tonight_empty_message))
            return@Column
        }
        if (window.isWide) {
            Row(
                Modifier.padding(start = window.horizontalPadding + TONIGHT_CHANNEL_WIDTH + 12.dp, end = window.horizontalPadding, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ColumnTitle(stringResource(R.string.tonight_first_part), Modifier.weight(1.15f))
                ColumnTitle(stringResource(R.string.tonight_second_part), Modifier.weight(1f))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = window.horizontalPadding, end = window.horizontalPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.rows, key = { _, row -> row.channel.id }) { index, row ->
                TonightLine(
                    row = row,
                    now = state.now,
                    wide = window.isWide,
                    firstFocus = firstBlock.takeIf { index == 0 },
                    onFocus = { programme -> focused = row.channel to programme },
                    onOpen = { programme -> onOpen(row.channel, programme) },
                )
            }
        }
    }
}

@Composable
private fun TonightLine(
    row: TonightRow,
    now: Long,
    wide: Boolean,
    firstFocus: FocusRequester?,
    onFocus: (GuideProgramme) -> Unit,
    onOpen: (GuideProgramme) -> Unit,
) {
    val channel = row.channel
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelCell(channel, Modifier.width(TONIGHT_CHANNEL_WIDTH))
            row.first?.let { ProgrammeCell(it, now, main = true, Modifier.weight(1.15f).then(firstFocus?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen) }
            if (row.second != null) {
                ProgrammeCell(row.second, now, main = false, Modifier.weight(1f), onFocus, onOpen)
            } else {
                Box(Modifier.weight(1f))
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChannelCell(channel, Modifier.fillMaxWidth())
            row.first?.let { ProgrammeCell(it, now, main = true, Modifier.fillMaxWidth().then(firstFocus?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen) }
            row.second?.let { ProgrammeCell(it, now, main = false, Modifier.fillMaxWidth(), onFocus, onOpen) }
        }
    }
}

/** Numéro TNT en grand, logo et nom : la chaîne se repère au premier coup d'œil. */
@Composable
private fun ChannelCell(channel: GuideChannel, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
            channel.number?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
        }
        ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 60.dp, height = 42.dp), monogramSize = 15.dp)
        Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgrammeCell(
    programme: GuideProgramme,
    now: Long,
    main: Boolean,
    modifier: Modifier,
    onFocus: (GuideProgramme) -> Unit,
    onOpen: (GuideProgramme) -> Unit,
) {
    val live = programme.startMillis <= now && programme.stopMillis > now
    FocusSurface(
        onClick = { onOpen(programme) },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus(programme) },
        shape = MaterialTheme.shapes.medium,
        color = if (main) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.02f,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    shortTime(programme.startMillis),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (live) NexTvColors.Live else MaterialTheme.colorScheme.primary,
                )
                if (live) StatusPill(stringResource(R.string.guide_now), NexTvColors.Live)
                Text(
                    listOfNotNull(formatDuration(programme.stopMillis - programme.startMillis), programme.category).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                programme.title,
                style = if (main) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (main) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live) LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ColumnTitle(text: String, modifier: Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun EveningChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

/** « Ce soir », « Demain soir », puis « lundi 3 ». */
@Composable
private fun eveningLabel(index: Int, evening: Long): String = when (index) {
    0 -> stringResource(R.string.tonight_today)
    1 -> stringResource(R.string.tonight_tomorrow)
    else -> SimpleDateFormat("EEEE d", Locale.getDefault()).format(Date(evening)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun shortTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private val TONIGHT_CHANNEL_WIDTH = 250.dp
