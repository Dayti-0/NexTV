package tv.nextv.app.ui.football

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.ProgrammeSheet
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.football.Competition
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Section « Foot » de l'accueil. */
@Composable
fun FootballScreen(sourceId: Long, onPlay: (Long) -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = "football-$sourceId") { container, _ -> FootballViewModel(container, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val competition by viewModel.competition.collectAsStateWithLifecycle()
    FootballContent(
        state = state,
        competition = competition,
        onCompetition = { viewModel.competition.value = it },
        onPlay = onPlay,
        modifier = modifier,
    )
}

/**
 * Compétitions à gauche (en haut sur écran étroit), matchs par jour à droite.
 * OK : regarder la chaîne ; appui long : fiche du programme (description du guide).
 */
@Composable
fun FootballContent(
    state: FootballUiState,
    competition: Competition?,
    onCompetition: (Competition?) -> Unit,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberWindowInfo()
    var details by remember { mutableStateOf<FootballMatch?>(null) }
    val shown = state.matches.filter { competition == null || it.competition == competition }
    val choices = listOf<Competition?>(null) + Competition.entries
    fun count(choice: Competition?) = state.matches.count { choice == null || it.competition == choice }

    val list: @Composable (Modifier) -> Unit = { listModifier ->
        if (state.loading) {
            Column(listModifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.football_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else if (shown.isEmpty()) {
            EmptyState(
                icon = NexTvIcons.Football,
                title = stringResource(R.string.football_empty_title),
                message = listOfNotNull(stringResource(R.string.football_empty_message), state.notice?.let { noticeText(it) }).joinToString("\n\n"),
                modifier = listModifier,
            )
        } else {
            MatchList(
                matches = shown,
                now = state.now,
                notice = state.notice,
                horizontalPadding = window.horizontalPadding,
                compact = !window.isWide,
                onPlay = onPlay,
                onDetails = { details = it },
                modifier = listModifier,
            )
        }
    }

    if (window.isWide) {
        Row(modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = window.horizontalPadding - 16.dp, end = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(choices) { choice ->
                    CompetitionItem(competitionLabel(choice), count(choice), choice?.let(::competitionColor), choice == competition) {
                        onCompetition(choice)
                    }
                }
            }
            list(Modifier.weight(1f))
        }
    } else {
        Column(modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(choices) { choice ->
                    CompetitionChip(competitionLabel(choice), choice == competition) { onCompetition(choice) }
                }
            }
            list(Modifier.weight(1f))
        }
    }

    val shownDetails = details
    val channel = shownDetails?.channel
    if (shownDetails != null && channel != null) {
        ProgrammeSheet(
            channel = channel,
            programme = shownDetails.programme,
            now = state.now,
            onWatch = {
                details = null
                onPlay(channel.id)
            },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun MatchList(
    matches: List<FootballMatch>,
    now: Long,
    notice: FootballNotice?,
    horizontalPadding: Dp,
    compact: Boolean,
    onPlay: (Long) -> Unit,
    onDetails: (FootballMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(matches) { matches.groupBy { dayStart(it.kickoffMillis) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = if (compact) horizontalPadding else 28.dp, end = horizontalPadding, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (notice != null) {
            item(key = "notice") {
                Text(
                    noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        days.forEach { (day, dayMatches) ->
            item(key = "day-$day") {
                Text(
                    dayLabel(day, now),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(dayMatches, key = { it.key }) { match ->
                val channel = match.channel
                MatchRow(
                    match = match,
                    now = now,
                    compact = compact,
                    onClick = { if (channel != null) onPlay(channel.id) },
                    onLongClick = { if (channel != null) onDetails(match) },
                )
            }
        }
    }
}

@Composable
private fun MatchRow(match: FootballMatch, now: Long, compact: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.01f,
    ) {
        if (compact) CompactMatch(match, now) else WideMatch(match, now)
    }
}

/** TV, tablette : heure, affiche et chaîne sur une ligne. */
@Composable
private fun WideMatch(match: FootballMatch, now: Long) {
    val live = match.isLive(now)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(timeOf(match.kickoffMillis), style = MaterialTheme.typography.titleLarge)
            if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(match.competition.label, style = MaterialTheme.typography.labelLarge, color = competitionColor(match.competition))
            Teams(match, large = true)
            if (live) LiveProgress(match.kickoffMillis, match.endMillis, now, Modifier.padding(top = 4.dp))
        }
        Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelInfo(match, logoWidth = 56.dp)
        }
    }
}

/** Écran étroit (téléphone) : heure et compétition, affiche, puis chaîne, les uns sous les autres. */
@Composable
private fun CompactMatch(match: FootballMatch, now: Long) {
    val live = match.isLive(now)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(timeOf(match.kickoffMillis), style = MaterialTheme.typography.titleMedium)
            if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
            Text(match.competition.label, style = MaterialTheme.typography.labelLarge, color = competitionColor(match.competition), maxLines = 1)
        }
        Teams(match, large = false)
        if (live) LiveProgress(match.kickoffMillis, match.endMillis, now)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelInfo(match, logoWidth = 40.dp)
        }
    }
}

/** L'affiche en clair, avec les écussons quand football-data.org les fournit : « PSG – Marseille ». */
@Composable
private fun Teams(match: FootballMatch, large: Boolean) {
    val crest = if (large) 30.dp else 24.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        match.homeCrest?.let { ChannelLogo(it, match.home, Modifier.size(crest), monogramSize = 10.dp) }
        Text(
            match.label,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        match.awayCrest?.let { ChannelLogo(it, match.away, Modifier.size(crest), monogramSize = 10.dp) }
    }
}

/** Chaîne de la playlist qui diffuse le match (et combien d'autres), ou son absence du guide. */
@Composable
private fun ChannelInfo(match: FootballMatch, logoWidth: Dp) {
    val channel = match.channel
    if (channel == null) {
        Text(stringResource(R.string.football_no_channel), style = MaterialTheme.typography.labelLarge, color = NexTvColors.TextMuted, maxLines = 2)
    } else {
        ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = logoWidth, height = logoWidth * 0.68f), monogramSize = 12.dp)
        Column {
            Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (match.otherChannels > 0) {
                Text(
                    LocalContext.current.resources.getQuantityString(R.plurals.football_other_channels, match.otherChannels, match.otherChannels),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompetitionItem(name: String, count: Int, color: Color?, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (color != null) Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompetitionChip(name: String, selected: Boolean, onClick: () -> Unit) {
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
private fun competitionLabel(competition: Competition?): String = competition?.label ?: stringResource(R.string.football_all)

@Composable
private fun noticeText(notice: FootballNotice): String = stringResource(
    when (notice) {
        FootballNotice.NO_KEY -> R.string.football_notice_no_key
        FootballNotice.INVALID_KEY -> R.string.football_notice_invalid_key
        FootballNotice.UNAVAILABLE -> R.string.football_notice_unavailable
    },
)

private fun competitionColor(competition: Competition): Color = when (competition) {
    Competition.LIGUE_1 -> Color(0xFF5C9DFF)
    Competition.PREMIER_LEAGUE -> Color(0xFFC792EA)
    Competition.LA_LIGA -> Color(0xFFFF8A65)
    Competition.SERIE_A -> Color(0xFF4DD0E1)
    Competition.BUNDESLIGA -> Color(0xFFFF6B6B)
    Competition.CHAMPIONS_LEAGUE -> Color(0xFFE3E8F2)
    Competition.EUROPA_LEAGUE -> Color(0xFFFFB347)
    Competition.CONFERENCE_LEAGUE -> Color(0xFF6BD89A)
    Competition.NATIONAL_TEAMS -> Color(0xFFFFD54F)
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** « Aujourd'hui », « Demain », puis « Samedi 1 novembre ». */
@Composable
private fun dayLabel(day: Long, now: Long): String {
    val today = dayStart(now)
    val tomorrow = dayStart(today + 36 * 60 * 60_000L)
    return when (day) {
        today -> stringResource(R.string.football_today)
        tomorrow -> stringResource(R.string.football_tomorrow)
        else -> SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(day)).replaceFirstChar { it.titlecase(Locale.FRANCE) }
    }
}
