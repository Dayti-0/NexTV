package tv.nextv.app.ui.football

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.football.EventTurnStore
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.EventChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.football.Broadcast
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.BroadcastLanguage
import tv.nextv.core.football.Competition
import tv.nextv.core.football.CountryNames
import tv.nextv.core.football.EventChannel
import tv.nextv.core.football.EventChannels
import tv.nextv.core.football.FootballDataError
import tv.nextv.core.football.FootballDataResult
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Standing
import tv.nextv.core.football.StandingsResult
import tv.nextv.core.football.Team
import tv.nextv.core.football.TeamMatches
import tv.nextv.core.football.TeamMatchesResult
import tv.nextv.core.model.ContentKind
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.XtreamProgramme
import java.util.Calendar

/** Match de la section Foot, avec la chaîne de la playlist qui le diffuse quand le guide la donne. */
@Immutable
data class FootballMatch(
    val competition: Competition,
    val home: String,
    val away: String,
    val kickoffMillis: Long,
    /** Fin : celle du programme du guide, sinon 2 h après le coup d'envoi. */
    val endMillis: Long,
    val homeCrest: String? = null,
    val awayCrest: String? = null,
    val channel: GuideChannel? = null,
    val programme: GuideProgramme? = null,
    /** Chaîne du guide qui diffuse le match quand aucune chaîne de la playlist ne lui est associée. */
    val guideChannel: String? = null,
    /** Autres chaînes qui le diffusent. */
    val otherChannels: Int = 0,
    /**
     * Chaînes de la playlist qui le diffusent, la préférée en premier ([channel]) : françaises
     * d'abord, puis la première à commencer.
     */
    val channels: List<GuideChannel> = listOfNotNull(channel),
    /** Score en cours de match. */
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    /** Minute de jeu (« 81' », « 45+2' ») pendant le match, si elle est connue. */
    val clock: String? = null,
    /** Mi-temps. */
    val halfTime: Boolean = false,
    /** Match terminé (score final), l'émission continuant. */
    val finished: Boolean = false,
    /** Noms complets des équipes, pour les équipes suivies. */
    val homeTeam: String = home,
    val awayTeam: String = away,
    /**
     * Chaînes « événement » seulement probables (« FR| DAZN LALIGA 2 (LIVE MATCH) ») : le guide ne les
     * décrit pas, le match y est déduit de leur numéro.
     */
    val probableChannels: Set<Long> = emptySet(),
) {
    /** La chaîne proposée n'est que probable (chaîne événement, voir [probableChannels]). */
    val probable: Boolean get() = channel != null && channel.id in probableChannels

    /** « PSG – Marseille ». */
    val label: String get() = "$home – $away"

    /** « 2 – 1 », quand le score est connu. */
    val score: String? get() = if (homeScore != null && awayScore != null) "$homeScore – $awayScore" else null

    val key: String get() = "${competition.name}-$kickoffMillis-$label"

    fun isLive(now: Long): Boolean = kickoffMillis <= now && endMillis > now

    /** Match d'une équipe suivie (noms de football-data.org, calendrier d'ESPN : comparés club par club). */
    fun involves(teams: Set<String>): Boolean = teams.any { BroadcastFinder.sameClub(it, homeTeam) || BroadcastFinder.sameClub(it, awayTeam) }
}

/** Fiche d'un club du classement : ses derniers et prochains matchs. */
sealed interface TeamMatchesState {
    val team: Team

    data class Loading(override val team: Team) : TeamMatchesState

    data class Loaded(override val team: Team, val matches: TeamMatches) : TeamMatchesState

    data class Failed(override val team: Team, val notice: FootballNotice) : TeamMatchesState
}

/** Classement d'une compétition : en chargement, ses lignes, ou la raison de son absence. */
sealed interface StandingsState {
    data object Loading : StandingsState

    data class Loaded(val table: List<Standing>) : StandingsState

    data class Failed(val notice: FootballNotice) : StandingsState
}

/**
 * Ce qui manque : clé ou réponse de football-data.org (classements, fiches des clubs), ou calendrier
 * d'ESPN (liste des matchs) remplacé par football-data.org ([SCHEDULE_PARTIAL]) ou indisponible.
 */
enum class FootballNotice { NO_KEY, INVALID_KEY, UNAVAILABLE, SCHEDULE_PARTIAL, SCHEDULE_UNAVAILABLE }

data class FootballUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val matches: List<FootballMatch> = emptyList(),
    val notice: FootballNotice? = null,
)

/**
 * Matchs à venir et en cours : la liste vient du calendrier d'ESPN (toutes les compétitions, vrais
 * coups d'envoi, scores en direct), et le guide des programmes donne seulement la chaîne qui les
 * diffuse. Sans réponse d'ESPN, la liste de football-data.org (compétitions de son offre gratuite)
 * la remplace. Rien n'est déduit du guide seul : une rediffusion n'est jamais prise pour un match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FootballViewModel(private val container: AppContainer, private val sourceId: Long) : ViewModel() {

    /** `null` : toutes les compétitions. */
    val competition = MutableStateFlow<Competition?>(null)

    /** « Mes équipes » : matchs des équipes suivies, toutes compétitions confondues. */
    val myTeams = MutableStateFlow(false)

    /** Classement affiché à la place des matchs (championnats suivis par football-data.org). */
    val showStandings = MutableStateFlow(false)

    val favoriteTeams: StateFlow<Set<String>> = container.settings.favoriteTeams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val standings: StateFlow<StandingsState?> = combine(competition, showStandings, myTeams) { competition, shown, mine ->
        competition?.takeIf { shown && !mine && it.apiId != null }
    }
        .distinctUntilChanged()
        .transformLatest { competition ->
            if (competition == null) {
                emit(null)
            } else {
                emit(StandingsState.Loading)
                emit(
                    when (val result = container.footballData.standings(competition)) {
                        is StandingsResult.Success -> StandingsState.Loaded(result.table)
                        is StandingsResult.Failed -> StandingsState.Failed(noticeOf(result.error))
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Une chaîne du [match] va être lancée : le lecteur reçoit toutes ses chaînes (▲ ▼ de l'une à l'autre, menu des
     * chaînes ouvert sur le match), quand il y en a plusieurs.
     */
    fun prepareMatch(match: FootballMatch) {
        container.pendingMatch.value = tv.nextv.app.ui.player.menuMatchesOf(listOf(match), match.kickoffMillis).firstOrNull()
            ?.takeIf { it.channelIds.size > 1 }
    }

    fun toggleTeam(name: String) {
        viewModelScope.launch { container.settings.toggleFavoriteTeam(name) }
    }

    private val mutableTeamMatches = MutableStateFlow<TeamMatchesState?>(null)

    /** Fiche du club choisi dans le classement, `null` : fermée. */
    val teamMatches: StateFlow<TeamMatchesState?> = mutableTeamMatches

    private var teamJob: Job? = null

    fun showTeam(team: Team) {
        val id = team.id ?: return
        teamJob?.cancel()
        mutableTeamMatches.value = TeamMatchesState.Loading(team)
        teamJob = viewModelScope.launch {
            mutableTeamMatches.value = when (val result = container.footballData.teamMatches(id)) {
                is TeamMatchesResult.Success -> TeamMatchesState.Loaded(team, result.matches)
                is TeamMatchesResult.Failed -> TeamMatchesState.Failed(team, noticeOf(result.error))
            }
        }
    }

    fun hideTeam() {
        teamJob?.cancel()
        mutableTeamMatches.value = null
    }

    /** Matchs à venir et en cours, avec leurs chaînes (calcul partagé avec le lecteur : [FootballMatchesFeed]). */
    val state: StateFlow<FootballUiState> = FootballMatchesFeed(container, sourceId, viewModelScope).state

    private fun noticeOf(error: FootballDataError): FootballNotice = when (error) {
        FootballDataError.MISSING_KEY -> FootballNotice.NO_KEY
        FootballDataError.INVALID_KEY -> FootballNotice.INVALID_KEY
        else -> FootballNotice.UNAVAILABLE
    }
}

/**
 * Programme du guide avec la chaîne de la playlist qui le diffuse, ou seulement le nom de la chaîne
 * du guide ([guideName]) quand elle n'est pas dans la playlist.
 */
internal class BroadcastRow(channel: EpgChannelRow?, guideName: String?, programme: FootballProgrammeRow) {
    val channelId: Long? = channel?.id
    val channelName: String = channel?.let { it.title.ifBlank { it.name } } ?: guideName.orEmpty()
    val logoUrl = channel?.logoUrl

    /** Chaîne de la playlist, ou à défaut, nom de la chaîne du guide (une ligne par chaîne). */
    val channelKey: Any = channelId ?: channelName

    /** Identifiant du guide (« canalplussport3.af ») : la grille d'un autre pays passe après la française (section MMA). */
    val epgChannelId: String = programme.epgChannelId
    val title = programme.title
    val subtitle = programme.subtitle
    val description = programme.description
    val category = programme.category
    val year = programme.year
    val startMillis = programme.startMillis
    val stopMillis = programme.stopMillis
}

private const val MATCH_MILLIS = 2 * 60 * 60_000L

/** Début du jour (heure de l'appareil) : les chaînes « événement » numérotent les matchs de la journée. */
internal fun localDayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Match probablement diffusé en ce moment sur une chaîne « événement » (« FR| DAZN LALIGA 2 (LIVE MATCH ONLY) »),
 * pour le score du bandeau du lecteur : ces chaînes n'ont pas de programme dans le guide. Même règle que
 * la section Foot (tour de rôle, retenu d'un jour à l'autre) ; `null` pour une autre chaîne.
 */
internal suspend fun eventMatchOnAir(container: AppContainer, sourceId: Long, channelName: String, now: Long): ScheduledMatch? {
    val target = EventChannels.parse(channelName) ?: return null
    val turns = container.eventTurns.read().filterValues { it.kickoffMillis >= now - EVENT_TURN_MEMORY_MILLIS }
    val from = (turns.values.map { it.kickoffMillis } + localDayStart(now) + (now - EVENT_LIVE_WINDOW_MILLIS)).min()
    val matches = container.liveScores.schedule(from, now + EVENT_LIVE_WINDOW_MILLIS) ?: return null
    val events = container.channelDao.eventChannelCandidates(sourceId, now - EVENT_LIVE_WINDOW_MILLIS, now + EVENT_LIVE_WINDOW_MILLIS)
        .filterNot { it.hasGuide }
        .mapNotNull { channel -> EventChannels.parse(channel.name)?.let { channel to it } }
        .sortedBy { (channel, _) -> BroadcastLanguage.rank(channel.name) }
    val numbers = events.associate { (channel, event) -> channel.id to event }
    val playable = matches.filter { it.status == MatchStatus.SCHEDULED || it.status == MatchStatus.LIVE || it.status == MatchStatus.FINISHED }
    val byId = playable.associateBy { it.id }
    val anchors = turns.mapNotNull { (competition, turn) ->
        byId[turn.matchId]?.takeIf { it.competition == competition }?.let { it to turn.number }
    }.toMap()
    return EventChannels.assign(playable, events, localDayStart(now), anchors).entries
        .filter { (match, channels) ->
            val first = channels.firstOrNull()?.let { numbers[it.id] }
            first?.competition == target.competition && first.number == target.number &&
                match.kickoffMillis - EVENT_EARLY_MILLIS <= now &&
                (match.status == MatchStatus.LIVE || now < match.kickoffMillis + EVENT_MATCH_MILLIS)
        }
        .minByOrNull { (match, _) -> kotlin.math.abs(now - match.kickoffMillis) }
        ?.key
}

private const val EVENT_TURN_MEMORY_MILLIS = 4 * 24 * 60 * 60_000L
private const val EVENT_LIVE_WINDOW_MILLIS = 3 * 60 * 60_000L

/** Le match s'affiche un peu avant le coup d'envoi, et jusqu'à la fin de sa diffusion (score final). */
private const val EVENT_EARLY_MILLIS = 15 * 60_000L
private const val EVENT_MATCH_MILLIS = 135 * 60_000L

/** Programme du guide du fournisseur sur une chaîne « événement » (« Espanyol vs. Deportivo de La Coruña »). */
internal fun providerRow(channel: EventChannelRow, programme: XtreamProgramme): BroadcastRow {
    val key = "provider:${channel.id}"
    return BroadcastRow(
        EpgChannelRow(key, channel.id, channel.name, channel.title, channel.logoUrl),
        null,
        FootballProgrammeRow(key, programme.title, null, programme.description, null, null, programme.startMillis, programme.stopMillis),
    )
}

/** Un match en cours reste affiché au moins jusque-là, prolongations comprises. */
private const val LIVE_GRACE_MILLIS = 15 * 60_000L

/**
 * Matchs du calendrier encore à jouer, en cours ou tout juste terminés (diffusion pas encore finie),
 * avec les chaînes trouvées dans le guide : un programme qui couvre le coup d'envoi et cite les
 * équipes. Les matchs de sélections ne sont gardés que s'ils sont diffusés (le calendrier en compte
 * des dizaines chaque jour de trêve, partout dans le monde).
 */
internal fun scheduledMatches(
    matches: List<ScheduledMatch>,
    rows: List<BroadcastRow>,
    now: Long,
    /** Chaînes « événement » de la playlist, sans programme dans le guide, la meilleure d'abord. */
    events: List<Pair<EventChannelRow, EventChannel>> = emptyList(),
    /** Début de la journée : les chaînes « événement » numérotent ses matchs. */
    dayStart: Long = localDayStart(now),
    /** Tour retenu d'un jour précédent : match (identifiant) et numéro de sa chaîne, par compétition. */
    turns: Map<Competition, Pair<Long, Int>> = emptyMap(),
    /** Premier match de la journée de chaque compétition et sa chaîne, à retenir pour le lendemain. */
    onDayTurn: (Map<Competition, Pair<ScheduledMatch, Int>>) -> Unit = {},
): List<FootballMatch> {
    val playable = matches.filter { it.status == MatchStatus.SCHEDULED || it.status == MatchStatus.LIVE || it.status == MatchStatus.FINISHED }
    val broadcasts = rows.map { Broadcast(it, it.title, it.subtitle, it.category, it.description, it.startMillis, it.stopMillis) }
    val found = BroadcastFinder.assign(playable, broadcasts)
    // « FR| DAZN LALIGA 2 (LIVE MATCH) » : chaînes prises à tour de rôle par les matchs. Un match dont le guide
    // du fournisseur donne la chaîne (source sûre) fixe le tour des autres ; sinon, rang dans la journée.
    val numbers = events.associate { (channel, event) -> channel.id to event }
    val announced = found.entries.mapNotNull { (match, rows) ->
        rows.firstNotNullOfOrNull { row -> numbers[row.channelId]?.takeIf { it.competition == match.competition }?.number }
            ?.let { match to it }
    }.toMap()
    // Sans annonce du fournisseur pour une compétition : le tour retenu la veille.
    val byId = playable.associateBy { it.id }
    val remembered = turns.mapNotNull { (competition, turn) ->
        byId[turn.first]?.takeIf { it.competition == competition && announced.keys.none { known -> known.competition == competition } }
            ?.let { it to turn.second }
    }.toMap()
    val anchors = announced + remembered
    val probable = EventChannels.assign(playable, events, dayStart, anchors)
    onDayTurn(EventChannels.firstOfDay(playable, events, dayStart, anchors))
    return playable.mapNotNull { match ->
        // Chaînes de la playlist d'abord (celles du guide seul ne se regardent pas), françaises en tête.
        val shows = ranked(found[match].orEmpty(), match.competition)
        if (match.competition == Competition.NATIONAL_TEAMS && shows.isEmpty()) return@mapNotNull null
        // Chaînes événement probables, après celles que le guide annonce.
        val guideChannels = shows.mapNotNull(::channelOf)
        val guessed = probable[match].orEmpty()
            .map { GuideChannel(it.id, it.title.ifBlank { it.name }, null, it.logoUrl, emptyList()) }
            .filter { channel -> guideChannels.none { it.id == channel.id } }
        val first = shows.firstOrNull()
        val live = match.status == MatchStatus.LIVE
        val finished = match.status == MatchStatus.FINISHED
        val end = (first?.stopMillis?.coerceAtLeast(match.kickoffMillis + MATCH_MILLIS) ?: (match.kickoffMillis + MATCH_MILLIS))
            .let { if (live) it.coerceAtLeast(now + LIVE_GRACE_MILLIS) else it }
        // Match terminé : affiché tant que sa diffusion continue (score final).
        if (end <= now) return@mapNotNull null
        // Sélections : noms français (« Italie – France »), le calendrier les donne en anglais.
        val national = match.competition == Competition.NATIONAL_TEAMS
        val home = CountryNames.french(match.home.name).takeIf { national } ?: match.home.displayName
        val away = CountryNames.french(match.away.name).takeIf { national } ?: match.away.displayName
        FootballMatch(
            competition = match.competition,
            home = home,
            away = away,
            kickoffMillis = match.kickoffMillis,
            endMillis = end,
            homeCrest = match.home.crestUrl,
            awayCrest = match.away.crestUrl,
            // Sans chaîne de la playlist dans le guide : la chaîne événement probable, qui se regarde.
            channel = first?.let(::channelOf) ?: guessed.firstOrNull(),
            programme = first?.let(::programmeOf),
            guideChannel = first?.takeIf { it.channelId == null && guessed.isEmpty() }?.channelName,
            otherChannels = (shows.size + guessed.size - 1).coerceAtLeast(0),
            channels = guideChannels + guessed,
            probableChannels = guessed.mapTo(HashSet()) { it.id },
            homeScore = match.homeScore.takeIf { live || finished },
            awayScore = match.awayScore.takeIf { live || finished },
            clock = match.clock.takeIf { live },
            halfTime = match.halfTime && live,
            finished = finished,
            homeTeam = if (national) home else match.home.name,
            awayTeam = if (national) away else match.away.name,
        )
    }
}

/**
 * Une ligne par chaîne : celles de la playlist d'abord (celles du guide seul ne se regardent pas),
 * puis le diffuseur officiel de la compétition (Ligue 1 : Ligue 1+), les françaises, et la première
 * à commencer. Une chaîne française qui n'est pas du diffuseur est écartée quand il est là (guide
 * d'un autre pays, voir [BroadcastLanguage.likelyBroadcasters]).
 */
private fun ranked(shows: Collection<BroadcastRow>, competition: Competition): List<BroadcastRow> =
    BroadcastLanguage.likelyBroadcasters(competition, shows.toList()) { it.channelName }.sortedWith(
        compareBy(
            { it.channelId == null },
            { !BroadcastLanguage.isOfficial(competition, it.channelName) },
            { BroadcastLanguage.rank(it.channelName) },
            { it.startMillis },
        ),
    ).distinctBy { it.channelKey }

private fun channelOf(row: BroadcastRow): GuideChannel? =
    row.channelId?.let { GuideChannel(it, row.channelName, null, row.logoUrl, emptyList()) }

private fun programmeOf(row: BroadcastRow) =
    GuideProgramme(row.title, row.startMillis, row.stopMillis, row.subtitle, row.description, row.category, row.year)
