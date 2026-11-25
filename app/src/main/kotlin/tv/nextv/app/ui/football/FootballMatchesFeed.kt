package tv.nextv.app.ui.football

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
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

/**
 * Matchs à venir et en cours avec les chaînes de la playlist qui les diffusent : calendrier d'ESPN (scores en direct) et
 * guide des programmes, comme dans la section Foot. Partagé par la section Foot et par le lecteur (catégories des matchs
 * en direct du menu des chaînes) ; les relectures tournent tant que [state] est suivi.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FootballMatchesFeed(private val container: AppContainer, private val sourceId: Long, private val scope: CoroutineScope) {

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
     * Calendrier d'ESPN, redemandé toutes les 20 s pendant les matchs (scores, minute de jeu), juste
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
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), FootballUiState())

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

    private companion object {
        const val SCHEDULE_REFRESH_MILLIS = 30 * 60_000L
        /** Pendant les matchs : score et minute de jeu redemandés toutes les 20 s. */
        const val LIVE_REFRESH_MILLIS = 20_000L
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
