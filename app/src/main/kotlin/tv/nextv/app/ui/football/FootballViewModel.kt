package tv.nextv.app.ui.football

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.football.Broadcast
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.Competition
import tv.nextv.core.football.FootballDataError
import tv.nextv.core.football.FootballDataResult
import tv.nextv.core.football.FootballMatches
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Standing
import tv.nextv.core.football.StandingsResult
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
    /** Autres chaînes qui le diffusent. */
    val otherChannels: Int = 0,
    /** Score en cours de match (football-data.org). */
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    /** Noms complets des équipes, pour les équipes suivies. */
    val homeTeam: String = home,
    val awayTeam: String = away,
) {
    /** « PSG – Marseille ». */
    val label: String get() = "$home – $away"

    /** « 2 – 1 », quand le score est connu. */
    val score: String? get() = if (homeScore != null && awayScore != null) "$homeScore – $awayScore" else null

    val key: String get() = "${competition.name}-$kickoffMillis-$label"

    fun isLive(now: Long): Boolean = kickoffMillis <= now && endMillis > now

    fun involves(teams: Set<String>): Boolean = homeTeam in teams || awayTeam in teams
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

    /** Toutes les 30 s, seuls les matchs terminés disparaissent (aucun recalcul). */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }

    val state: StateFlow<FootballUiState> =
        combine(assembled, clock) { result, now ->
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
        val rows = container.programmeDao.footballCandidates(sourceId, now, now + WINDOW_MILLIS).mapNotNull { programme ->
            channels[programme.epgChannelId]?.let { BroadcastRow(it, programme) }
        }
        return GuideData(rows, guideMatches(rows))
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
        const val LIVE_REFRESH_MILLIS = 2 * 60_000L
        const val TICK_MILLIS = 30_000L

        /** Le guide couvre en général une semaine. */
        const val WINDOW_MILLIS = 8 * 24 * 60 * 60_000L
    }
}

/** Programme du guide avec la chaîne de la playlist qui le diffuse. */
private class BroadcastRow(channel: EpgChannelRow, programme: FootballProgrammeRow) {
    val channelId = channel.id
    val channelName = channel.title.ifBlank { channel.name }
    val logoUrl = channel.logoUrl
    val title = programme.title
    val subtitle = programme.subtitle
    val description = programme.description
    val category = programme.category
    val year = programme.year
    val startMillis = programme.startMillis
    val stopMillis = programme.stopMillis
}

private const val MATCH_MILLIS = 2 * 60 * 60_000L
private const val SAME_SHOW_MILLIS = 30 * 60_000L
private const val HALVES_MILLIS = 3 * 60 * 60_000L

/** Matchs de football-data.org encore à jouer ou en cours, avec la chaîne trouvée dans le guide. */
private fun apiMatches(matches: List<ScheduledMatch>, rows: List<BroadcastRow>, now: Long): List<FootballMatch> {
    val upcoming = matches.filter {
        (it.status == MatchStatus.SCHEDULED || it.status == MatchStatus.LIVE) && it.kickoffMillis + MATCH_MILLIS > now
    }
    val broadcasts = rows.map { Broadcast(it, it.title, it.subtitle, it.category, it.description, it.startMillis, it.stopMillis) }
    val found = BroadcastFinder.assign(upcoming, broadcasts)
    return upcoming.map { match ->
        val shows = found[match].orEmpty().sortedBy { it.startMillis }.distinctBy { it.channelId }
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
            otherChannels = (shows.size - 1).coerceAtLeast(0),
            homeScore = match.homeScore.takeIf { match.status == MatchStatus.LIVE },
            awayScore = match.awayScore.takeIf { match.status == MatchStatus.LIVE },
            homeTeam = match.home.name,
            awayTeam = match.away.name,
        )
    }
}

/**
 * Matchs trouvés dans le guide seul. Une affiche diffusée sur
 * plusieurs chaînes, en deux mi-temps ou rediffusée ne fait qu'une ligne, à sa première diffusion ;
 * les diffusions de nuit et du matin (rediffusions) sont écartées.
 */
private fun guideMatches(rows: List<BroadcastRow>): List<FootballMatch> {
    val found = rows.mapNotNull { row ->
        FootballMatches.fixtureOf(row.title, row.subtitle, row.category, row.description)
            ?.takeIf { isMatchHour(row.startMillis) }
            ?.let { row to it }
    }
    return found.groupBy { (_, fixture) -> fixture.competition to simplify(fixture.label) }.values.map { group ->
        val sorted = group.sortedBy { (row, _) -> row.startMillis }
        val (first, fixture) = sorted.first()
        val sameShow = sorted.filter { (row, _) -> row.startMillis - first.startMillis < HALVES_MILLIS }
        val channels = sameShow.filter { (row, _) -> abs(row.startMillis - first.startMillis) <= SAME_SHOW_MILLIS }
            .map { (row, _) -> row.channelId }
            .distinct()
        FootballMatch(
            competition = fixture.competition,
            home = fixture.home,
            away = fixture.away,
            kickoffMillis = first.startMillis,
            endMillis = sameShow.maxOf { (row, _) -> row.stopMillis },
            channel = channelOf(first),
            programme = programmeOf(first),
            otherChannels = channels.size - 1,
        )
    }
}

/** Les compétitions suivies se jouent entre 11 h et minuit (heure de l'appareil). */
private fun isMatchHour(millis: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY) >= 11

private fun simplify(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]+"), "")

private fun channelOf(row: BroadcastRow) = GuideChannel(row.channelId, row.channelName, null, row.logoUrl, emptyList())

private fun programmeOf(row: BroadcastRow) =
    GuideProgramme(row.title, row.startMillis, row.stopMillis, row.subtitle, row.description, row.category, row.year)
