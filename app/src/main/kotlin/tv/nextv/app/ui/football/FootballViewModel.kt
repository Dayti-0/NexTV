package tv.nextv.app.ui.football

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.football.Broadcast
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.BroadcastLanguage
import tv.nextv.core.football.Fixture
import tv.nextv.core.football.Competition
import tv.nextv.core.football.FootballDataError
import tv.nextv.core.football.FootballDataResult
import tv.nextv.core.football.FootballMatches
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Standing
import tv.nextv.core.football.StandingsResult
import tv.nextv.core.football.Team
import tv.nextv.core.football.TeamMatches
import tv.nextv.core.football.TeamMatchesResult
import tv.nextv.core.model.ContentKind
import java.text.Normalizer
import java.util.Calendar
import kotlin.math.abs

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
    /** Trouvé dans le guide seul : [kickoffMillis] est le début de sa diffusion. */
    val fromGuide: Boolean = false,
) {
    /** « PSG – Marseille ». */
    val label: String get() = "$home – $away"

    /** « 2 – 1 », quand le score est connu. */
    val score: String? get() = if (homeScore != null && awayScore != null) "$homeScore – $awayScore" else null

    val key: String get() = "${competition.name}-$kickoffMillis-$label"

    fun isLive(now: Long): Boolean = kickoffMillis <= now && endMillis > now

    fun involves(teams: Set<String>): Boolean = homeTeam in teams || awayTeam in teams
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

/** Pourquoi la liste vient du guide seul (football-data.org n'a pas servi). */
enum class FootballNotice { NO_KEY, INVALID_KEY, UNAVAILABLE }

data class FootballUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val matches: List<FootballMatch> = emptyList(),
    val notice: FootballNotice? = null,
)

/**
 * Matchs à venir et en cours : la liste vient de football-data.org (compétitions de son offre
 * gratuite) et le guide des programmes donne la chaîne qui les diffuse. Les autres compétitions
 * (Ligue Europa, Conférence, équipes nationales), ou toutes sans clé d'API, viennent du guide seul.
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

    /** Programmes susceptibles de diffuser un match, et matchs que le guide annonce seul. */
    private class GuideData(val rows: List<BroadcastRow>, val fixtures: List<FootballMatch>)

    /** Matchs prêts à afficher pour un guide et une réponse de football-data.org donnés. */
    private class Assembled(val matches: List<FootballMatch>, val notice: FootballNotice?)

    private var cachedGuide: Pair<Any, GuideData>? = null
    private var cachedAssembly: Triple<GuideData, FootballDataResult, Assembled>? = null

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
     * Réponse de football-data.org, redemandée toutes les 30 min, et toutes les 2 min pendant les
     * matchs (scores en direct) ; le client la garde ce temps-là.
     */
    private val api: Flow<FootballDataResult> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val result = container.footballData.matches(now, now + WINDOW_MILLIS)
            emit(result)
            val matches = (result as? FootballDataResult.Success)?.matches.orEmpty()
            val after = System.currentTimeMillis()
            val nextKickoff = matches.filter { it.kickoffMillis > after }.minOfOrNull { it.kickoffMillis }
            delay(
                when {
                    matches.any { it.isActive(after) } -> LIVE_REFRESH_MILLIS
                    // Prochain coup d'envoi : score suivi dès le début du match.
                    nextKickoff != null -> (nextKickoff - after - LIVE_REFRESH_MILLIS).coerceIn(LIVE_REFRESH_MILLIS, API_REFRESH_MILLIS)
                    else -> API_REFRESH_MILLIS
                },
            )
        }
    }.distinctUntilChanged()

    private val assembled: Flow<Assembled> = combine(guide, api) { guide, api ->
        cachedAssembly?.takeIf { it.first === guide && it.second == api }?.third
            ?: assemble(guide, api).also { cachedAssembly = Triple(guide, api, it) }
    }

    /**
     * Affiches du guide seul qui ne sont pas des directs (match de la veille au soir rediffusé le midi,
     * vieille affiche de coupe d'Europe) : écartées d'après la source des scores, qui connaît les vrais
     * coups d'envoi. Seules celles
     * des prochaines heures sont vérifiées (les suivantes le seront le moment venu).
     */
    private val withoutReplays: Flow<Assembled> = assembled.transformLatest { result ->
        val now = System.currentTimeMillis()
        val suspects = result.matches.filter { it.fromGuide && it.endMillis > now && it.kickoffMillis < now + REPLAY_CHECK_AHEAD_MILLIS }
        if (suspects.isEmpty()) {
            emit(result)
            return@transformLatest
        }
        // Réponse rapide : liste directement sans les rediffusions ; sinon, affichée telle quelle puis corrigée.
        withTimeoutOrNull(REPLAY_CHECK_WAIT_MILLIS) { replaysAmong(suspects) }?.let { replays ->
            emit(result.without(replays))
            return@transformLatest
        }
        emit(result)
        emit(result.without(replaysAmong(suspects)))
    }

    private suspend fun replaysAmong(matches: List<FootballMatch>): Set<String> =
        matches.filter { container.liveScores.isReplay(it.competition, it.home, it.away, it.kickoffMillis) }.mapTo(HashSet()) { it.key }

    private fun Assembled.without(keys: Set<String>): Assembled =
        if (keys.isEmpty()) this else Assembled(matches.filterNot { it.key in keys }, notice)

    /**
     * Matchs en cours sans score (coupes d'Europe, sélections, ou tous sans clé football-data.org) :
     * score et minute de jeu d'après la source des scores en direct, chaque minute.
     */
    private val withLiveScores: Flow<Assembled> = withoutReplays.transformLatest { result ->
        while (true) {
            val now = System.currentTimeMillis()
            val pending = result.matches.filter { it.score == null && it.kickoffMillis <= now + KICKOFF_SOON_MILLIS && it.endMillis > now }
            emit(if (pending.isEmpty()) result else Assembled(withLive(result.matches, pending), result.notice))
            val nextKickoff = result.matches.filter { it.kickoffMillis > now }.minOfOrNull { it.kickoffMillis }
            delay(
                when {
                    pending.isNotEmpty() -> LIVE_REFRESH_MILLIS
                    nextKickoff != null -> (nextKickoff - now - KICKOFF_SOON_MILLIS).coerceIn(LIVE_REFRESH_MILLIS, API_REFRESH_MILLIS)
                    else -> API_REFRESH_MILLIS
                },
            )
        }
    }

    private suspend fun withLive(matches: List<FootballMatch>, pending: List<FootballMatch>): List<FootballMatch> {
        val live = pending.mapNotNull { match ->
            container.liveScores.find(match.competition, match.home, match.away, match.kickoffMillis, match.endMillis)?.let { match.key to it }
        }.toMap()
        if (live.isEmpty()) return matches
        return matches.map { match ->
            val score = live[match.key]?.takeIf { it.inPlay || it.finished } ?: return@map match
            match.copy(
                homeScore = score.homeScore,
                awayScore = score.awayScore,
                clock = score.clock,
                halfTime = score.halfTime,
                finished = score.finished,
            )
        }
    }

    /** Toutes les 30 s, seuls les matchs terminés disparaissent (aucun recalcul). */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }

    val state: StateFlow<FootballUiState> =
        combine(withLiveScores, clock) { result, now ->
            FootballUiState(loading = false, now = now, matches = result.matches.filter { it.endMillis > now }, notice = result.notice)
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
        // Deux jours passés en plus : les diffusions déjà faites révèlent les rediffusions à venir.
        val aired = container.programmeDao.footballCandidates(sourceId, now - HISTORY_MILLIS, now + WINDOW_MILLIS).mapNotNull { programme ->
            channels[programme.epgChannelId]?.let { BroadcastRow(it, null, programme) }
                ?: guideOnly[programme.epgChannelId]?.let { BroadcastRow(null, it, programme) }
        }
        val rows = aired.filter { it.stopMillis > now }
        return GuideData(rows, guideMatches(rows, aired))
    }

    private fun assemble(guide: GuideData, api: FootballDataResult): Assembled {
        val matches = when (api) {
            is FootballDataResult.Success -> {
                // Écussons des matchs du guide seul (coupes d'Europe…) : ceux des équipes connues de football-data.org.
                val crest = BroadcastFinder.crests(api.matches.flatMap { listOf(it.home, it.away) })
                apiMatches(api.matches, guide.rows, System.currentTimeMillis()) +
                    guide.fixtures.filter { it.competition.apiId == null }
                        .map { it.copy(homeCrest = it.homeCrest ?: crest(it.home), awayCrest = it.awayCrest ?: crest(it.away)) }
            }
            is FootballDataResult.Failed -> guide.fixtures
        }
        val notice = (api as? FootballDataResult.Failed)?.error?.let(::noticeOf)
        return Assembled(matches.sortedWith(compareBy({ it.kickoffMillis }, { it.competition.ordinal })), notice)
    }

    private fun noticeOf(error: FootballDataError): FootballNotice = when (error) {
        FootballDataError.MISSING_KEY -> FootballNotice.NO_KEY
        FootballDataError.INVALID_KEY -> FootballNotice.INVALID_KEY
        else -> FootballNotice.UNAVAILABLE
    }

    private companion object {
        const val API_REFRESH_MILLIS = 30 * 60_000L
        /** Pendant les matchs : score et minute de jeu redemandés chaque minute. */
        const val LIVE_REFRESH_MILLIS = 60_000L
        const val TICK_MILLIS = 30_000L
        const val KICKOFF_SOON_MILLIS = 5 * 60_000L

        /** Rediffusions cherchées auprès de la source des scores : diffusions des prochaines 24 h. */
        const val REPLAY_CHECK_AHEAD_MILLIS = 24 * 60 * 60_000L

        /** Attente maximale de cette vérification avant d'afficher la liste. */
        const val REPLAY_CHECK_WAIT_MILLIS = 3_000L

        /** Le guide garde les programmes des deux derniers jours. */
        const val HISTORY_MILLIS = 2 * 24 * 60 * 60_000L

        /** Le guide couvre en général une semaine. */
        const val WINDOW_MILLIS = 8 * 24 * 60 * 60_000L
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
private const val HALVES_MILLIS = 3 * 60 * 60_000L
/** Diffusions du même match sur plusieurs chaînes (avant-match plus ou moins long). */
private const val SAME_FIXTURE_MILLIS = 75 * 60_000L

/** Matchs de football-data.org encore à jouer ou en cours, avec la chaîne trouvée dans le guide. */
private fun apiMatches(matches: List<ScheduledMatch>, rows: List<BroadcastRow>, now: Long): List<FootballMatch> {
    val upcoming = matches.filter {
        (it.status == MatchStatus.SCHEDULED || it.status == MatchStatus.LIVE) && it.kickoffMillis + MATCH_MILLIS > now
    }
    val broadcasts = rows.map { Broadcast(it, it.title, it.subtitle, it.category, it.description, it.startMillis, it.stopMillis) }
    val found = BroadcastFinder.assign(upcoming, broadcasts)
    return upcoming.map { match ->
        // Chaînes de la playlist d'abord (celles du guide seul ne se regardent pas), françaises en tête.
        val shows = ranked(found[match].orEmpty())
        val first = shows.firstOrNull()
        FootballMatch(
            competition = match.competition,
            home = match.home.displayName,
            away = match.away.displayName,
            kickoffMillis = match.kickoffMillis,
            endMillis = first?.stopMillis?.coerceAtLeast(match.kickoffMillis + MATCH_MILLIS) ?: (match.kickoffMillis + MATCH_MILLIS),
            homeCrest = match.home.crestUrl,
            awayCrest = match.away.crestUrl,
            channel = first?.let(::channelOf),
            programme = first?.let(::programmeOf),
            guideChannel = first?.takeIf { it.channelId == null }?.channelName,
            otherChannels = (shows.size - 1).coerceAtLeast(0),
            channels = shows.mapNotNull(::channelOf),
            homeScore = match.homeScore.takeIf { match.status == MatchStatus.LIVE },
            awayScore = match.awayScore.takeIf { match.status == MatchStatus.LIVE },
            clock = match.clock.takeIf { match.status == MatchStatus.LIVE },
            halfTime = match.halfTime && match.status == MatchStatus.LIVE,
            homeTeam = match.home.name,
            awayTeam = match.away.name,
        )
    }
}

/**
 * Matchs trouvés dans le guide seul ([rows] : diffusions en cours et à venir). Une affiche diffusée sur
 * plusieurs chaînes, en deux mi-temps ou rediffusée ne fait qu'une ligne, à sa première diffusion ;
 * les diffusions de nuit et du matin, et celles d'une affiche déjà passée à l'antenne ([aired] :
 * diffusions des deux derniers jours en plus), sont des rediffusions écartées. Les chaînes qui diffusent le même
 * match sous un autre titre (sans la compétition, noms d'équipes complets) s'y ajoutent.
 */
internal fun guideMatches(rows: List<BroadcastRow>, aired: List<BroadcastRow>): List<FootballMatch> {
    val current = rows.toHashSet()
    // Toutes les diffusions reconnues, passées comprises, pour repérer les rediffusions.
    val all = aired.mapNotNull { row ->
        FootballMatches.fixtureOf(row.title, row.subtitle, row.category, row.description)?.let { Aired(row, it) }
    }
    val byWord = HashMap<String, MutableList<Aired>>()
    all.forEach { airing -> (airing.home + airing.away).forEach { byWord.getOrPut(it) { ArrayList() } += airing } }
    val found = all.filter { it.row in current && isMatchHour(it.row.startMillis) && !isReplay(it, byWord) }
        .map { it.row to it.fixture }
    val groups = found.groupBy { (_, fixture) -> fixture.competition to simplify(fixture.label) }.values
        .map { group -> group.sortedWith(compareBy({ (row, _) -> row.channelId == null }, { (row, _) -> row.startMillis })) }
        .sortedBy { group -> group.first().first.startMillis }
    val broadcasts = rows.map { Broadcast(it, it.title, it.subtitle, it.category, it.description, it.startMillis, it.stopMillis) }
    val others = BroadcastFinder.sameFixtures(
        groups.map { group -> group.first().let { (row, fixture) -> BroadcastFinder.GuideFixture(fixture.home, fixture.away, row.startMillis) } },
        broadcasts,
    )
    // Deux affiches du même match (titres différents) : une seule ligne, la première.
    val merged = ArrayList<Pair<Pair<BroadcastRow, Fixture>, MutableSet<BroadcastRow>>>()
    val owner = HashMap<BroadcastRow, Int>()
    groups.forEachIndexed { index, group ->
        val first = group.first()
        val shows = (group.map { it.first }.filter { it.startMillis - first.first.startMillis < HALVES_MILLIS } + others[index]).toMutableSet()
        val existing = shows.firstNotNullOfOrNull { owner[it] }
        val target = existing ?: merged.size.also { merged += first to mutableSetOf() }
        merged[target].second += shows
        shows.forEach { owner[it] = target }
    }
    return merged.map { (head, shows) ->
        val (first, fixture) = head
        // Chaînes du même match (pas sa rediffusion), la préférée en premier.
        val live = ranked(shows.filter { abs(it.startMillis - first.startMillis) <= SAME_FIXTURE_MILLIS })
        val preferred = live.firstOrNull() ?: first
        FootballMatch(
            competition = fixture.competition,
            home = fixture.home,
            away = fixture.away,
            kickoffMillis = first.startMillis,
            endMillis = shows.filter { it.startMillis - first.startMillis < HALVES_MILLIS }.maxOf { it.stopMillis },
            channel = channelOf(preferred),
            programme = programmeOf(preferred),
            guideChannel = preferred.channelName.takeIf { preferred.channelId == null },
            otherChannels = (live.size - 1).coerceAtLeast(0),
            channels = live.mapNotNull(::channelOf),
            fromGuide = true,
        )
    }
}

/** Diffusion d'un match reconnue dans le guide, avec les mots qui désignent ses équipes. */
private class Aired(val row: BroadcastRow, val fixture: Fixture) {
    val home = FootballMatches.teamWords(fixture.home)
    val away = FootballMatches.teamWords(fixture.away)

    fun sameTeams(other: Aired): Boolean = FootballMatches.sameTeams(home, away, other.home, other.away)
}

/**
 * Rediffusion : la même affiche est passée (ou passera) à l'antenne au moins [REPLAY_GAP_MILLIS]
 * plus tôt dans les deux jours précédents, sous ce titre ou un autre (« Crystal Palace / Hoffenheim »
 * le jeudi soir, « Ligue Europa : Crystal Palace - Hoffenheim » le vendredi midi).
 */
private fun isReplay(airing: Aired, byWord: Map<String, List<Aired>>): Boolean {
    val start = airing.row.startMillis
    // Seules les diffusions qui partagent un mot d'équipe sont comparées.
    return airing.home.any { word ->
        byWord[word].orEmpty().any { other ->
            other.row.startMillis <= start - REPLAY_GAP_MILLIS && other.row.startMillis >= start - REPLAY_LOOKBACK_MILLIS && airing.sameTeams(other)
        }
    }
}

private const val REPLAY_GAP_MILLIS = 3 * 60 * 60_000L
private const val REPLAY_LOOKBACK_MILLIS = 2 * 24 * 60 * 60_000L

/**
 * Une ligne par chaîne : celles de la playlist d'abord (celles du guide seul ne se regardent pas),
 * les françaises en tête, puis la première à commencer.
 */
private fun ranked(shows: Collection<BroadcastRow>): List<BroadcastRow> =
    shows.sortedWith(compareBy({ it.channelId == null }, { BroadcastLanguage.rank(it.channelName) }, { it.startMillis }))
        .distinctBy { it.channelKey }

/** Les compétitions suivies se jouent entre 11 h et minuit (heure de l'appareil). */
private fun isMatchHour(millis: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY) >= 11

private fun simplify(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(NON_ALPHANUMERIC, "")

private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

private fun channelOf(row: BroadcastRow): GuideChannel? =
    row.channelId?.let { GuideChannel(it, row.channelName, null, row.logoUrl, emptyList()) }

private fun programmeOf(row: BroadcastRow) =
    GuideProgramme(row.title, row.startMillis, row.stopMillis, row.subtitle, row.description, row.category, row.year)
