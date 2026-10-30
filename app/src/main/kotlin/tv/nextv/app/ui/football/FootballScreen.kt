package tv.nextv.app.ui.football

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
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
import tv.nextv.core.football.Competition
import tv.nextv.core.football.Standing
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
    val myTeams by viewModel.myTeams.collectAsStateWithLifecycle()
    val favoriteTeams by viewModel.favoriteTeams.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val showStandings by viewModel.showStandings.collectAsStateWithLifecycle()
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    FootballContent(
        state = state,
        competition = competition,
        onCompetition = {
            viewModel.myTeams.value = false
            viewModel.competition.value = it
        },
        onPlay = onPlay,
        modifier = modifier,
        favoriteTeams = favoriteTeams,
        reminders = reminders,
        myTeams = myTeams,
        onMyTeams = {
            viewModel.myTeams.value = true
            viewModel.competition.value = null
        },
        standings = standings,
        showStandings = showStandings,
        onShowStandings = { viewModel.showStandings.value = it },
        onToggleTeam = viewModel::toggleTeam,
        onToggleReminder = viewModel::toggleReminder,
    )
}

/**
 * Compétitions à gauche (en haut sur écran étroit), matchs par jour à droite, ou classement du
 * championnat choisi. OK : regarder la chaîne ; appui long : fiche du match (rappel, équipes suivies).
 */
@Composable
fun FootballContent(
    state: FootballUiState,
    competition: Competition?,
    onCompetition: (Competition?) -> Unit,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Équipes suivies ([FootballMatch.homeTeam], [FootballMatch.awayTeam]). */
    favoriteTeams: Set<String> = emptySet(),
    /** Matchs avec un rappel ([FootballMatch.reminderKey]). */
    reminders: Set<String> = emptySet(),
    /** « Mes équipes » choisi à la place d'une compétition. */
    myTeams: Boolean = false,
    onMyTeams: () -> Unit = {},
    standings: StandingsState? = null,
    showStandings: Boolean = false,
    onShowStandings: (Boolean) -> Unit = {},
    onToggleTeam: (String) -> Unit = {},
    onToggleReminder: (FootballMatch) -> Unit = {},
) {
    val window = rememberWindowInfo()
    var details by remember { mutableStateOf<FootballMatch?>(null) }
    val shown = state.matches.filter { if (myTeams) it.involves(favoriteTeams) else competition == null || it.competition == competition }
    fun count(choice: Competition?) = state.matches.count { choice == null || it.competition == choice }
    // Classement : championnats et coupes suivis par football-data.org.
    val withTable = !myTeams && competition?.apiId != null

    val list: @Composable (Modifier) -> Unit = { listModifier ->
        Column(listModifier.fillMaxSize()) {
            if (withTable) {
                Row(
                    Modifier.padding(start = if (window.isWide) 28.dp else window.horizontalPadding, end = window.horizontalPadding, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompetitionChip(stringResource(R.string.football_matches), !showStandings) { onShowStandings(false) }
                    CompetitionChip(stringResource(R.string.football_standings), showStandings) { onShowStandings(true) }
                }
            }
            val body = Modifier.weight(1f)
            when {
                withTable && showStandings -> StandingsTable(
                    state = standings ?: StandingsState.Loading,
                    favoriteTeams = favoriteTeams,
                    horizontalPadding = window.horizontalPadding,
                    compact = !window.isWide,
                    onToggleTeam = onToggleTeam,
                    modifier = body,
                )
                state.loading -> Column(body.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.football_loading),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                shown.isEmpty() -> EmptyState(
                    icon = NexTvIcons.Football,
                    title = stringResource(if (myTeams) R.string.football_my_teams_empty_title else R.string.football_empty_title),
                    message = if (myTeams) {
                        stringResource(if (favoriteTeams.isEmpty()) R.string.football_my_teams_none else R.string.football_my_teams_no_match)
                    } else {
                        listOfNotNull(stringResource(R.string.football_empty_message), state.notice?.let { noticeText(it) }).joinToString("\n\n")
                    },
                    modifier = body,
                )
                else -> MatchList(
                    matches = shown,
                    now = state.now,
                    notice = state.notice,
                    favoriteTeams = favoriteTeams,
                    reminders = reminders,
                    horizontalPadding = window.horizontalPadding,
                    compact = !window.isWide,
                    onPlay = onPlay,
                    onDetails = { details = it },
                    modifier = body,
                )
            }
        }
    }

    val myTeamsCount = state.matches.count { it.involves(favoriteTeams) }
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
                item { CompetitionItem(competitionLabel(null), count(null), null, !myTeams && competition == null) { onCompetition(null) } }
                item { CompetitionItem(stringResource(R.string.football_my_teams), myTeamsCount, null, myTeams, star = true, onClick = onMyTeams) }
                items(Competition.entries) { choice ->
                    CompetitionItem(competitionLabel(choice), count(choice), competitionColor(choice), !myTeams && choice == competition) {
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
                item { CompetitionChip(competitionLabel(null), !myTeams && competition == null) { onCompetition(null) } }
                item { CompetitionChip(stringResource(R.string.football_my_teams), myTeams, onClick = onMyTeams) }
                items(Competition.entries) { choice ->
                    CompetitionChip(competitionLabel(choice), !myTeams && choice == competition) { onCompetition(choice) }
                }
            }
            list(Modifier.weight(1f))
        }
    }

    details?.let { match ->
        MatchSheet(
            match = match,
            now = state.now,
            favoriteTeams = favoriteTeams,
            reminded = match.reminderKey in reminders,
            onWatch = match.channel?.let { channel ->
                {
                    details = null
                    onPlay(channel.id)
                }
            },
            onToggleTeam = onToggleTeam,
            onToggleReminder = if (match.kickoffMillis > state.now) ({ onToggleReminder(match) }) else null,
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun MatchList(
    matches: List<FootballMatch>,
    now: Long,
    notice: FootballNotice?,
    favoriteTeams: Set<String>,
    reminders: Set<String>,
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
                    followed = match.involves(favoriteTeams),
                    reminded = match.reminderKey in reminders,
                    onClick = { if (channel != null) onPlay(channel.id) else onDetails(match) },
                    onLongClick = { onDetails(match) },
                )
            }
        }
    }
}

@Composable
private fun MatchRow(
    match: FootballMatch,
    now: Long,
    compact: Boolean,
    followed: Boolean,
    reminded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.01f,
    ) {
        if (compact) CompactMatch(match, now, followed, reminded) else WideMatch(match, now, followed, reminded)
    }
}

/** TV, tablette : heure (ou score en direct), affiche et chaîne sur une ligne. */
@Composable
private fun WideMatch(match: FootballMatch, now: Long, followed: Boolean, reminded: Boolean) {
    val live = match.isLive(now)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val score = match.score
            if (live && score != null) {
                Text(score, style = MaterialTheme.typography.titleLarge, color = NexTvColors.Live)
            } else {
                Text(timeOf(match.kickoffMillis), style = MaterialTheme.typography.titleLarge)
            }
            if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MatchHeading(match, followed, reminded)
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
private fun CompactMatch(match: FootballMatch, now: Long, followed: Boolean, reminded: Boolean) {
    val live = match.isLive(now)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val score = match.score
            if (live && score != null) {
                Text(score, style = MaterialTheme.typography.titleMedium, color = NexTvColors.Live)
            } else {
                Text(timeOf(match.kickoffMillis), style = MaterialTheme.typography.titleMedium)
            }
            if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
            MatchHeading(match, followed, reminded)
        }
        Teams(match, large = false)
        if (live) LiveProgress(match.kickoffMillis, match.endMillis, now)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelInfo(match, logoWidth = 40.dp)
        }
    }
}

/** Compétition, avec une étoile (équipe suivie) et une cloche (rappel programmé). */
@Composable
private fun MatchHeading(match: FootballMatch, followed: Boolean, reminded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (followed) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
        if (reminded) Icon(Icons.Filled.Notifications, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
        Text(match.competition.label, style = MaterialTheme.typography.labelLarge, color = competitionColor(match.competition), maxLines = 1)
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

/**
 * Fiche d'un match : affiche, chaîne et description du guide, avec « Regarder », « Me prévenir »
 * (match à venir) et le suivi de chaque équipe (« Mes équipes »).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchSheet(
    match: FootballMatch,
    now: Long,
    favoriteTeams: Set<String>,
    reminded: Boolean,
    onWatch: (() -> Unit)?,
    onToggleTeam: (String) -> Unit,
    onToggleReminder: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 680.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(match.competition.label, style = MaterialTheme.typography.labelLarge, color = competitionColor(match.competition))
                    Text(
                        "${dayLabel(dayStart(match.kickoffMillis), now)}  ·  ${timeOf(match.kickoffMillis)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (match.isLive(now)) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
                }
                Teams(match, large = true)
                match.score?.let { Text(it, style = MaterialTheme.typography.headlineMedium, color = NexTvColors.Live) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChannelInfo(match, logoWidth = 56.dp)
                }
                match.programme?.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onWatch != null) {
                        PillButton(
                            stringResource(R.string.guide_watch),
                            onWatch,
                            icon = Icons.Filled.PlayArrow,
                            style = PillStyle.Primary,
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                    }
                    if (onToggleReminder != null) {
                        PillButton(
                            stringResource(if (reminded) R.string.reminder_on else R.string.reminder_set),
                            onToggleReminder,
                            icon = if (reminded) Icons.Filled.Check else Icons.Filled.Notifications,
                            modifier = if (onWatch == null) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                    listOf(match.homeTeam to match.home, match.awayTeam to match.away).forEachIndexed { index, (team, name) ->
                        val followed = team in favoriteTeams
                        PillButton(
                            stringResource(if (followed) R.string.football_unfollow else R.string.football_follow, name),
                            { onToggleTeam(team) },
                            icon = Icons.Filled.Star,
                            modifier = if (index == 0 && onWatch == null && onToggleReminder == null) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                    PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
                }
            }
        }
    }
}

/** Classement : rang, équipe, matchs joués, différence de buts, points. OK sur une équipe : la suivre. */
@Composable
private fun StandingsTable(
    state: StandingsState,
    favoriteTeams: Set<String>,
    horizontalPadding: Dp,
    compact: Boolean,
    onToggleTeam: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        StandingsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is StandingsState.Failed -> EmptyState(
            icon = NexTvIcons.Football,
            title = stringResource(R.string.football_standings_unavailable),
            message = noticeText(state.notice),
            modifier = modifier,
        )
        is StandingsState.Loaded -> if (state.table.isEmpty()) {
            EmptyState(
                icon = NexTvIcons.Football,
                title = stringResource(R.string.football_standings_unavailable),
                message = stringResource(R.string.football_standings_empty),
                modifier = modifier,
            )
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = if (compact) horizontalPadding else 28.dp, end = horizontalPadding, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "header") {
                    StandingLine(
                        position = "#",
                        crest = null,
                        name = stringResource(R.string.football_table_team),
                        played = stringResource(R.string.football_table_played),
                        difference = stringResource(R.string.football_table_difference),
                        points = stringResource(R.string.football_table_points),
                        header = true,
                        followed = false,
                    )
                }
                items(state.table, key = { "${it.position}-${it.team.name}" }) { row ->
                    FocusSurface(
                        onClick = { onToggleTeam(row.team.name) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        focusScale = 1.01f,
                    ) {
                        StandingLine(
                            position = row.position.toString(),
                            crest = row.team.crestUrl,
                            name = row.team.displayName,
                            played = row.played.toString(),
                            difference = if (row.goalDifference > 0) "+${row.goalDifference}" else row.goalDifference.toString(),
                            points = row.points.toString(),
                            header = false,
                            followed = row.team.name in favoriteTeams,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingLine(
    position: String,
    crest: String?,
    name: String,
    played: String,
    difference: String,
    points: String,
    header: Boolean,
    followed: Boolean,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = if (header) 4.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(position, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
        Box(Modifier.size(24.dp)) {
            if (crest != null) ChannelLogo(crest, name, Modifier.size(24.dp), monogramSize = 9.dp)
        }
        Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (followed) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
        Text(played, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
        Text(difference, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text(points, style = style, color = if (header) color else MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun CompetitionItem(name: String, count: Int, color: Color?, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
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
            if (star) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(14.dp))
            if (color != null) Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompetitionChip(name: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
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
