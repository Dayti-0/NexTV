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

    /** Calendrier des matchs, de l'une ou l'autre source. */
    private data class Schedule(val matches: List<ScheduledMatch>, val notice: FootballNotice?)

    /** Programmes du guide qui peuvent être des matchs, et chaînes « événement » de la playlist (sans guide). */
    private class GuideData(val rows: List<BroadcastRow>, val events: List<Pair<EventChannelRow, EventChannel>>)

    private var cachedGuide: Pair<Any, GuideData>? = null

    /**
     * Ce qui change le résultat du guide : playlist ou guide rechargés (environ toutes les 12 h),
     * catégories masquées ou réaffichées. Rien d'autre ne relance le calcul.
     */
    private val guideVersion: Flow<Any> = combine(
        container.playlistStore.observeSource(sourceId)
            .map { source -> source?.status?.let { it.lastSuccessAtMillis to it.epg.lastSuccessAtMillis } }
            .distinctUntilChanged(),
        container.hiddenGroupDao.observeTitles(sourceId, ContentKind.LIVE.name),
    ) { version, hidden -> version to hidden }

    // Jamais annulé par le changement suivant (conflate + map) : un appareil lent finit toujours son calcul.
    private val guide: Flow<GuideData> = guideVersion
        .conflate()
        .map { version -> cachedGuide?.takeIf { it.first == version }?.second ?: loadGuide().also { cachedGuide = version to it } }

    /**
     * Calendrier d'ESPN, redemandé chaque minute pendant les matchs (scores, minute de jeu), juste
     * avant le prochain coup d'envoi, et toutes les 30 min sinon ; le client garde les réponses.
     */
    private val schedule: Flow<Schedule> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val result = loadSchedule(now)
            emit(result)
            val after = System.currentTimeMillis()
            val nextKickoff = result.matches.filter { it.kickoffMillis > after }.minOfOrNull { it.kickoffMillis }
            delay(
                when {
                    result.matches.any { it.isActive(after) } -> LIVE_REFRESH_MILLIS
                    // Prochain coup d'envoi : score suivi dès le début du match.
                    nextKickoff != null -> (nextKickoff - after - LIVE_REFRESH_MILLIS).coerceIn(LIVE_REFRESH_MILLIS, SCHEDULE_REFRESH_MILLIS)
                    else -> SCHEDULE_REFRESH_MILLIS
                },
            )
        }
    }.distinctUntilChanged()

    private suspend fun loadSchedule(now: Long): Schedule {
        // Depuis minuit : les chaînes « événement » numérotent les matchs de la journée, terminés compris ;
        // et depuis le match retenu la veille, dont le tour continue.
        val remembered = container.eventTurns.read().values.map { it.kickoffMillis }.filter { it >= now - TURN_MEMORY_MILLIS }
        val from = (remembered + (now - LIVE_WINDOW_MILLIS) + localDayStart(now)).min()
        container.liveScores.schedule(from, now + WINDOW_MILLIS)?.let { return Schedule(it, null) }
        // ESPN injoignable : liste de football-data.org (compétitions de son offre gratuite), si une clé est là.
        return when (val api = container.footballData.matches(now, now + WINDOW_MILLIS)) {
            is FootballDataResult.Success -> Schedule(api.matches, FootballNotice.SCHEDULE_PARTIAL)
            is FootballDataResult.Failed -> Schedule(emptyList(), FootballNotice.SCHEDULE_UNAVAILABLE)
        }
    }

    /** Toutes les 30 s, seuls les matchs terminés disparaissent (aucun recalcul). */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }

    /**
     * Programmes que le fournisseur annonce sur ses chaînes « événement » (guide du compte Xtream), relus
     * toutes les 10 min : la source sûre du match de chacune, quand il la donne. Vide sinon.
     */
    private val providerGuide: Flow<List<BroadcastRow>> = guide
        .map { it.events }
        .distinctUntilChanged()
        .transformLatest { events ->
            emit(emptyList())
            if (events.isEmpty()) return@transformLatest
            while (true) {
                emit(loadProviderGuide(events))
                delay(PROVIDER_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()

    val state: StateFlow<FootballUiState> =
        combine(combine(guide, schedule, providerGuide, ::assemble), clock) { assembled, now ->
            FootballUiState(
                loading = false,
                now = now,
                matches = assembled.matches.filter { it.endMillis > now },
                notice = assembled.notice,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FootballUiState())

    private suspend fun loadGuide(): GuideData {
        val now = System.currentTimeMillis()
        // Chaîne qui diffuse les programmes de chaque identifiant du guide : sa meilleure version visible.
        val channels = HashMap<String, EpgChannelRow>()
        for (channel in container.channelDao.epgChannels(sourceId)) {
            val id = channel.epgChannelId ?: continue
            if (id !in channels) channels[id] = channel
        }
        // Chaînes de sport du guide absentes de la playlist : le match est affiché avec leur nom.
        val guideOnly = container.guideOnlyChannelDao.list(sourceId).associate { it.epgChannelId to it.name }
        val rows = container.programmeDao.footballCandidates(sourceId, now - LIVE_WINDOW_MILLIS, now + WINDOW_MILLIS).mapNotNull { programme ->
            channels[programme.epgChannelId]?.let { BroadcastRow(it, null, programme) }
                ?: guideOnly[programme.epgChannelId]?.let { BroadcastRow(null, it, programme) }
        }
        // Chaînes « événement » (« FR| DAZN LALIGA 2 (LIVE MATCH) ») : seulement celles que le guide ne décrit
        // pas, françaises d'abord (leur famille donne la numérotation).
        val events = container.channelDao.eventChannelCandidates(sourceId, now - LIVE_WINDOW_MILLIS, now + WINDOW_MILLIS)
            .filterNot { it.hasGuide }
            .mapNotNull { channel -> EventChannels.parse(channel.name)?.let { channel to it } }
            .sortedBy { (channel, _) -> BroadcastLanguage.rank(channel.name) }
        return GuideData(rows, events)
    }

    /**
     * Guide du fournisseur pour une chaîne de chaque numéro (« LALIGA 1 » à « LALIGA 5 ») : programmes
     * annoncés autour du moment présent. Rien pour une playlist qui n'est pas un compte Xtream.
     */
    private suspend fun loadProviderGuide(events: List<Pair<EventChannelRow, EventChannel>>): List<BroadcastRow> {
        val source = container.playlistStore.getSource(sourceId) ?: return emptyList()
        val now = System.currentTimeMillis()
        return coroutineScope {
            EventChannels.primaries(events).map { (channel, _) ->
                async {
                    val streamId = Catchup.streamId(channel.streamUrl) ?: return@async emptyList()
                    container.xtreamApi.shortEpg(source, streamId).orEmpty()
                        .filter { it.stopMillis > now - LIVE_WINDOW_MILLIS }
                        .map { programme -> providerRow(channel, programme) }
                }
            }.awaitAll().flatten()
        }
    }

    /** Matchs prêts à afficher. */
    private class Assembled(val matches: List<FootballMatch>, val notice: FootballNotice?)

    private fun assemble(guide: GuideData, schedule: Schedule, provider: List<BroadcastRow>): Assembled {
        val now = System.currentTimeMillis()
        val turns = container.eventTurns.read()
            .filterValues { it.kickoffMillis >= now - TURN_MEMORY_MILLIS }
            .mapValues { (_, turn) -> turn.matchId to turn.number }
        val matches = scheduledMatches(schedule.matches, guide.rows + provider, now, guide.events, turns = turns) { first ->
            container.eventTurns.write(first.mapValues { (_, turn) -> EventTurnStore.Turn(turn.first.id, turn.first.kickoffMillis, turn.second) })
        }
        return Assembled(
            matches.sortedWith(compareBy({ it.kickoffMillis }, { it.competition.ordinal })),
            schedule.notice,
        )
    }

    private fun noticeOf(error: FootballDataError): FootballNotice = when (error) {
        FootballDataError.MISSING_KEY -> FootballNotice.NO_KEY
        FootballDataError.INVALID_KEY -> FootballNotice.INVALID_KEY
        else -> FootballNotice.UNAVAILABLE
    }

    private companion object {
        const val SCHEDULE_REFRESH_MILLIS = 30 * 60_000L
        /** Pendant les matchs : score et minute de jeu redemandés chaque minute. */
        const val LIVE_REFRESH_MILLIS = 60_000L
        const val TICK_MILLIS = 30_000L

        /** Matchs commencés depuis moins de 3 h : peut-être encore en cours (prolongations). */
        const val LIVE_WINDOW_MILLIS = 3 * 60 * 60_000L

        /** Le guide couvre en général une semaine. */
        const val WINDOW_MILLIS = 8 * 24 * 60 * 60_000L

        /** Tour des chaînes « événement » retenu : utilisé pendant 4 jours. */
        const val TURN_MEMORY_MILLIS = 4 * 24 * 60 * 60_000L

        /** Guide du fournisseur pour ses chaînes « événement » : relu toutes les 10 min. */
        const val PROVIDER_REFRESH_MILLIS = 10 * 60_000L
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
