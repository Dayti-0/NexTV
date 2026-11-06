package tv.nextv.app.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.rememberLazyListState
import tv.nextv.app.ui.home.HomeVisit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
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
import tv.nextv.app.ui.components.LocalTabsFocused
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
    val tabsFocused = LocalTabsFocused.current
    // Programme ouvert : ligne, colonne (0 : début de soirée, 1 : suite) et affichage de l'accueil.
    var opened by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    val visit = HomeVisit.current
    val back = opened?.takeIf { it.size == 3 && it[2] != visit && it[0] in state.rows.indices }
    val listState = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    if (back != null) {
        LaunchedEffect(back) {
            listState.scrollToItem(back[0])
            initialFocusDone = true
            repeat(8) {
                if (runCatching { backFocus.requestFocus() }.isSuccess) {
                    opened = null
                    return@LaunchedEffect
                }
                delay(120)
            }
            opened = null
        }
    }
    if (window.isTv && !initialFocusDone && state.rows.isNotEmpty() && back == null) {
        LaunchedEffect(Unit) {
            if (!tabsFocused()) runCatching { firstBlock.requestFocus() }
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
        // Programme sélectionné : image, horaires, genre et résumé.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(horizontal = window.horizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown != null && rememberProgrammeImage(shown.second) != null) {
                ProgrammeArt(shown.second, Modifier.width(150.dp).aspectRatio(16f / 9f))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            state = listState,
            contentPadding = PaddingValues(start = window.horizontalPadding, end = window.horizontalPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.rows, key = { _, row -> row.channel.id }) { index, row ->
                TonightLine(
                    row = row,
                    now = state.now,
                    wide = window.isWide,
                    firstFocus = firstBlock.takeIf { index == 0 && back?.get(0) != 0 },
                    backFocus = backFocus.takeIf { back?.get(0) == index },
                    backColumn = back?.get(1) ?: 0,
                    onFocus = { programme -> focused = row.channel to programme },
                    onOpen = { programme ->
                        opened = listOf(index, if (programme == row.first) 0 else 1, visit)
                        onOpen(row.channel, programme)
                    },
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
    /** Retour d'un programme ouvert sur cette ligne : colonne [backColumn] à resélectionner. */
    backFocus: FocusRequester? = null,
    backColumn: Int = 0,
) {
    val channel = row.channel
    fun requesterFor(column: Int): FocusRequester? = backFocus.takeIf { column == backColumn } ?: firstFocus.takeIf { column == 0 }
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelCell(channel, Modifier.width(TONIGHT_CHANNEL_WIDTH))
            row.first?.let { ProgrammeCell(it, now, main = true, Modifier.weight(1.15f).then(requesterFor(0)?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen) }
            if (row.second != null) {
                ProgrammeCell(row.second, now, main = false, Modifier.weight(1f).then(requesterFor(1)?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen)
            } else {
                Box(Modifier.weight(1f))
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChannelCell(channel, Modifier.fillMaxWidth())
            row.first?.let { ProgrammeCell(it, now, main = true, Modifier.fillMaxWidth().then(requesterFor(0)?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen) }
            row.second?.let { ProgrammeCell(it, now, main = false, Modifier.fillMaxWidth().then(requesterFor(1)?.let { r -> Modifier.focusRequester(r) } ?: Modifier), onFocus, onOpen) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
        // Vignette du programme quand le guide en donne une (soirée : programme principal).
        if (main && programme.imageUrl != null) {
            AsyncImage(
                model = programme.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 8.dp).width(84.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    shortTime(programme.startMillis),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (live) NexTvColors.Live else MaterialTheme.colorScheme.primary,
                )
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
