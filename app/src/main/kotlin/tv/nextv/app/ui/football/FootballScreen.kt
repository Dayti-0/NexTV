package tv.nextv.app.ui.football

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.ProgrammeSheet
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.epg.Competition
import tv.nextv.core.epg.FootballMatches
import tv.nextv.core.model.ContentKind
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Match trouvé dans le guide, avec la chaîne qui le diffuse. */
@Immutable
data class FootballMatch(val competition: Competition, val channel: GuideChannel, val programme: GuideProgramme)

data class FootballUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val matches: List<FootballMatch> = emptyList(),
)

/** Matchs à venir (et en cours) des compétitions suivies, d'après le guide des programmes. */
@OptIn(ExperimentalCoroutinesApi::class)
class FootballViewModel(private val container: AppContainer, private val sourceId: Long) : ViewModel() {

    /** `null` : toutes les compétitions. */
    val competition = MutableStateFlow<Competition?>(null)

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MILLIS)
        }
    }

    val state: StateFlow<FootballUiState> =
        combine(clock, container.hiddenGroupDao.observeTitles(sourceId, ContentKind.LIVE.name)) { now, _ -> now }
            .mapLatest { now -> load(now) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FootballUiState())

    private suspend fun load(now: Long): FootballUiState {
        val matches = container.programmeDao.footballCandidates(sourceId, now, now + WINDOW_MILLIS).mapNotNull { row ->
            val competition = FootballMatches.competitionOf(row.title, row.subtitle, row.category, row.description) ?: return@mapNotNull null
            FootballMatch(
                competition = competition,
                channel = GuideChannel(row.channelId, row.channelTitle.ifBlank { row.channelName }, null, row.logoUrl, emptyList()),
                programme = GuideProgramme(row.title, row.startMillis, row.stopMillis, row.subtitle, row.description, row.category, row.year),
            )
        }
        return FootballUiState(loading = false, now = now, matches = matches)
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L

        /** Le guide couvre en général une semaine. */
        const val WINDOW_MILLIS = 8 * 24 * 60 * 60_000L
    }
}

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
 * OK : regarder la chaîne ; appui long : fiche du match (description du guide).
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
        if (!state.loading && shown.isEmpty()) {
            EmptyState(
                icon = NexTvIcons.Football,
                title = stringResource(R.string.football_empty_title),
                message = stringResource(R.string.football_empty_message),
                modifier = listModifier,
            )
        } else {
            MatchList(shown, state.now, window.horizontalPadding, onPlay, onDetails = { details = it }, modifier = listModifier)
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
                    CompetitionItem(competitionLabel(choice), count(choice), choice == competition) { onCompetition(choice) }
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

    details?.let { match ->
        ProgrammeSheet(
            channel = match.channel,
            programme = match.programme,
            now = state.now,
            onWatch = {
                details = null
                onPlay(match.channel.id)
            },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun MatchList(
    matches: List<FootballMatch>,
    now: Long,
    horizontalPadding: Dp,
    onPlay: (Long) -> Unit,
    onDetails: (FootballMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(matches) { matches.groupBy { dayStart(it.programme.startMillis) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = horizontalPadding, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { (day, dayMatches) ->
            item(key = "day-$day") {
                Text(
                    dayLabel(day, now),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(dayMatches, key = { "${it.channel.id}-${it.programme.startMillis}" }) { match ->
                MatchRow(match, now, onClick = { onPlay(match.channel.id) }, onLongClick = { onDetails(match) })
            }
        }
    }
}

@Composable
private fun MatchRow(match: FootballMatch, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val programme = match.programme
    val live = programme.startMillis <= now && programme.stopMillis > now
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.01f,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(76.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(timeOf(programme.startMillis), style = MaterialTheme.typography.titleLarge)
                if (live) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(match.competition.label, style = MaterialTheme.typography.labelLarge, color = competitionColor(match.competition))
                Text(programme.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                programme.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (live) LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.padding(top = 4.dp))
            }
            Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLogo(match.channel.logoUrl, match.channel.name, Modifier.size(width = 56.dp, height = 38.dp), monogramSize = 13.dp)
                Text(match.channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CompetitionItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
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

private fun competitionColor(competition: Competition): Color = when (competition) {
    Competition.LIGUE_1 -> NexTvColors.Primary
    Competition.PREMIER_LEAGUE -> NexTvColors.Warning
    Competition.CHAMPIONS_LEAGUE -> NexTvColors.Live
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
