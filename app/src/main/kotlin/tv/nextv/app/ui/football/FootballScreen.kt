package tv.nextv.app.ui.football

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.window.DialogProperties
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
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.football.Competition
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Standing
import tv.nextv.core.football.Team
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
    val showStandings by viewModel.showStandings.collectAsStateWithLifecycle()
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    val teamMatches by viewModel.teamMatches.collectAsStateWithLifecycle()
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
        myTeams = myTeams,
        onMyTeams = {
            viewModel.myTeams.value = true
            viewModel.competition.value = null
        },
        standings = standings,
        showStandings = showStandings,
        onShowStandings = { viewModel.showStandings.value = it },
        onToggleTeam = viewModel::toggleTeam,
        teamMatches = teamMatches,
        onTeam = viewModel::showTeam,
        onCloseTeam = viewModel::hideTeam,
    )
}

/**
 * Compétitions à gauche (en haut sur écran étroit), matchs par jour à droite, ou classement du
 * championnat choisi. OK : regarder la chaîne ; appui long : fiche du match (équipes suivies).
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
    /** « Mes équipes » choisi à la place d'une compétition. */
    myTeams: Boolean = false,
    onMyTeams: () -> Unit = {},
    standings: StandingsState? = null,
    showStandings: Boolean = false,
    onShowStandings: (Boolean) -> Unit = {},
    onToggleTeam: (String) -> Unit = {},
    /** Fiche du club choisi dans le classement (derniers et prochains matchs). */
    teamMatches: TeamMatchesState? = null,
    onTeam: (Team) -> Unit = {},
    onCloseTeam: () -> Unit = {},
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
                    onTeam = onTeam,
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
            onWatch = match.channel?.let { channel ->
                {
                    details = null
                    onPlay(channel.id)
                }
            },
            onToggleTeam = onToggleTeam,
            onDismiss = { details = null },
        )
    }

    teamMatches?.let {
        TeamSheet(
            state = it,
            now = state.now,
            followed = it.team.name in favoriteTeams,
            onToggleTeam = { onToggleTeam(it.team.name) },
            onDismiss = onCloseTeam,
        )
    }
}

@Composable
private fun MatchList(
    matches: List<FootballMatch>,
    now: Long,
    notice: FootballNotice?,
    favoriteTeams: Set<String>,
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
        if (compact) CompactMatch(match, now, followed) else WideMatch(match, now, followed)
    }
}

/**
 * TV, tablette : heure (ou score en direct) à gauche ; à droite la compétition, l'affiche sur toute
 * la largeur (noms d'équipes en entier), puis la chaîne.
 */
@Composable
private fun WideMatch(match: FootballMatch, now: Long, followed: Boolean) {
    val live = match.isLive(now)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val score = match.score
            if (live && score != null) {
                Text(score, style = MaterialTheme.typography.titleLarge, color = NexTvColors.Live)
            } else {
                Text(timeOf(match.kickoffMillis), style = MaterialTheme.typography.titleLarge)
            }
            if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MatchHeading(match, followed)
            Teams(match, large = true)
            if (live) LiveProgress(match.kickoffMillis, match.endMillis, now, Modifier.widthIn(max = 480.dp))
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelInfo(match, logoWidth = 44.dp, inline = true)
            }
        }
    }
}

/** Écran étroit (téléphone) : heure et compétition, affiche, puis chaîne, les uns sous les autres. */
@Composable
private fun CompactMatch(match: FootballMatch, now: Long, followed: Boolean) {
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
            MatchHeading(match, followed)
        }
        Teams(match, large = false)
        if (live) LiveProgress(match.kickoffMillis, match.endMillis, now)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelInfo(match, logoWidth = 40.dp)
        }
    }
}

/** Compétition, avec une étoile quand une équipe suivie joue. */
@Composable
private fun MatchHeading(match: FootballMatch, followed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (followed) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
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

/**
 * Chaîne de la playlist qui diffuse le match (et combien d'autres), ou son absence du guide.
 * [inline] : nom et autres chaînes sur une seule ligne.
 */
@Composable
private fun RowScope.ChannelInfo(match: FootballMatch, logoWidth: Dp, inline: Boolean = false) {
    val channel = match.channel
    if (channel == null) {
        // Chaîne connue du guide mais pas reliée à la playlist : son nom, pour la retrouver soi-même.
        val guideChannel = match.guideChannel
        if (guideChannel != null) {
            Text(
                stringResource(R.string.football_guide_only_channel, guideChannel),
                style = MaterialTheme.typography.labelLarge,
                color = NexTvColors.Warning,
                maxLines = 2,
            )
        } else {
            Text(stringResource(R.string.football_no_channel), style = MaterialTheme.typography.labelLarge, color = NexTvColors.TextMuted, maxLines = 2)
        }
        return
    }
    ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = logoWidth, height = logoWidth * 0.68f), monogramSize = 12.dp)
    val others = match.otherChannels.takeIf { it > 0 }?.let {
        LocalContext.current.resources.getQuantityString(R.plurals.football_other_channels, it, it)
    }
    if (inline) {
        Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        others?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    } else {
        Column {
            Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            others?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Fiche d'un match : affiche, chaîne et description du guide, avec « Regarder » et le suivi de
 * chaque équipe (« Mes équipes »).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchSheet(
    match: FootballMatch,
    now: Long,
    favoriteTeams: Set<String>,
    onWatch: (() -> Unit)?,
    onToggleTeam: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 680.dp).ignoreHeldOk(),
        ) {
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
                    listOf(match.homeTeam to match.home, match.awayTeam to match.away).forEachIndexed { index, (team, name) ->
                        val followed = team in favoriteTeams
                        PillButton(
                            stringResource(if (followed) R.string.football_unfollow else R.string.football_follow, name),
                            { onToggleTeam(team) },
                            icon = Icons.Filled.Star,
                            modifier = if (index == 0 && onWatch == null) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                    PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
                }
            }
        }
    }
}

/**
 * Classement : rang, équipe, matchs joués, différence de buts, points. OK sur une équipe : ses
 * derniers et prochains matchs ; appui long : la suivre.
 */
@Composable
private fun StandingsTable(
    state: StandingsState,
    favoriteTeams: Set<String>,
    horizontalPadding: Dp,
    compact: Boolean,
    onTeam: (Team) -> Unit,
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
                item(key = "hint") {
                    Text(
                        stringResource(R.string.football_standings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                    )
                }
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
                        onClick = { onTeam(row.team) },
                        onLongClick = { onToggleTeam(row.team.name) },
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

/** Fiche d'un club : derniers résultats et prochains matchs (deux colonnes sur TV), et son suivi. */
@Composable
private fun TeamSheet(state: TeamMatchesState, now: Long, followed: Boolean, onToggleTeam: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TeamSheetContent(state, now, followed, onToggleTeam, onDismiss)
    }
}

/** Contenu de la fiche d'un club (captures d'écran). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamSheetContent(state: TeamMatchesState, now: Long, followed: Boolean, onToggleTeam: () -> Unit, onDismiss: () -> Unit) {
    val window = rememberWindowInfo()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val team = state.team
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(if (window.isWide) 0.86f else 0.94f).widthIn(max = 1000.dp).ignoreHeldOk(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                team.crestUrl?.let { ChannelLogo(it, team.displayName, Modifier.size(44.dp), monogramSize = 14.dp) }
                Text(team.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (followed) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning)
            }
            when (state) {
                is TeamMatchesState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is TeamMatchesState.Failed -> Text(noticeText(state.notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is TeamMatchesState.Loaded -> {
                    val last: @Composable (Modifier) -> Unit = { columnModifier ->
                        TeamMatchColumn(stringResource(R.string.football_team_last), state.matches.last, team, now, past = true, columnModifier)
                    }
                    val next: @Composable (Modifier) -> Unit = { columnModifier ->
                        TeamMatchColumn(stringResource(R.string.football_team_next), state.matches.next, team, now, past = false, columnModifier)
                    }
                    if (window.isWide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            last(Modifier.weight(1f))
                            next(Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            last(Modifier.fillMaxWidth())
                            next(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    stringResource(if (followed) R.string.football_unfollow else R.string.football_follow, team.displayName),
                    onToggleTeam,
                    icon = Icons.Filled.Star,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
            }
        }
    }
}

@Composable
private fun TeamMatchColumn(title: String, matches: List<ScheduledMatch>, team: Team, now: Long, past: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (matches.isEmpty()) {
            Text(stringResource(R.string.football_team_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        matches.forEach { TeamMatchLine(it, team, now, past) }
    }
}

/**
 * Un match du club sur deux lignes, pour que l'adversaire ne soit jamais tronqué : date, lieu et
 * compétition (« sam. 25 oct. · Extérieur · Ligue 1 »), puis l'adversaire en grand ; à droite, le score
 * du point de vue du club et le résultat (V, N, D), ou « En direct ».
 */
@Composable
private fun TeamMatchLine(match: ScheduledMatch, team: Team, now: Long, past: Boolean) {
    val home = match.home.id == team.id || (team.id == null && match.home.name == team.name)
    val opponent = if (home) match.away else match.home
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).background(competitionColor(match.competition), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                listOf(
                    SimpleDateFormat(if (past) "EEE d MMM" else "EEE d MMM, HH:mm", Locale.FRANCE).format(Date(match.kickoffMillis)),
                    stringResource(if (home) R.string.football_home else R.string.football_away),
                    competitionLabel(match.competition),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(opponent.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val homeScore = match.homeScore
        val awayScore = match.awayScore
        if (past && homeScore != null && awayScore != null) {
            val (own, other) = if (home) homeScore to awayScore else awayScore to homeScore
            val (letter, color) = when {
                own > other -> stringResource(R.string.football_result_win) to NexTvColors.Live
                own < other -> stringResource(R.string.football_result_loss) to NexTvColors.Error
                else -> stringResource(R.string.football_result_draw) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("$own – $other", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Box(Modifier.size(26.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Text(letter, style = MaterialTheme.typography.labelMedium, color = Color.Black, maxLines = 1)
            }
        } else if (!past && match.kickoffMillis <= now) {
            StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
        }
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
