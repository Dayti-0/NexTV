package tv.nextv.app.ui.mma

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.ui.football.BroadcastRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.football.BroadcastLanguage
import tv.nextv.core.model.ContentKind
import tv.nextv.core.mma.MmaEvent
import tv.nextv.core.mma.MmaGuide
import tv.nextv.core.mma.MmaPromotion

/**
 * Gala de la section MMA : celui du calendrier d'ESPN ([event], avec sa carte et ses résultats), ou seulement trouvé
 * dans le guide (KSW, ARES, Hexagone… : [event] `null`), avec les chaînes de la playlist qui le diffusent.
 */
@Immutable
data class MmaCard(
    val promotion: MmaPromotion,
    val name: String,
    val startMillis: Long,
    /** Début de la carte principale, quand il est connu. */
    val mainCardMillis: Long?,
    val endMillis: Long,
    val event: MmaEvent? = null,
    /** Chaînes de la playlist qui le diffusent, la préférée (française, la première à commencer) en premier. */
    val channels: List<GuideChannel> = emptyList(),
    /** Programme du guide de la chaîne préférée (horaires de diffusion, description). */
    val programme: GuideProgramme? = null,
    /** Chaîne du guide qui le diffuse quand aucune chaîne de la playlist ne lui est associée. */
    val guideChannel: String? = null,
    /**
     * [channels] n'est que la chaîne habituelle de l'organisation (RMC Sport pour l'UFC) : le guide ne couvre pas encore
     * la date du gala, ou ne l'annonce pas.
     */
    val usualChannel: Boolean = false,
    /** Diffusions trouvées dans le guide (chaîne, horaires, titre), dans l'ordre des chaînes proposées puis des horaires. */
    val airings: List<MmaAiring> = emptyList(),
    /** Heure connue (calendrier ou guide) ; faux : seul le jour est connu (« Heure à venir »). */
    val timeKnown: Boolean = true,
) {
    val channel: GuideChannel? get() = channels.firstOrNull()

    val key: String get() = "${promotion.name}-$startMillis-$name"

    fun isLive(now: Long): Boolean = when {
        !timeKnown -> false
        event != null && event.timeKnown -> event.isLive(now)
        else -> !finished && startMillis <= now && endMillis > now
    }

    /** Gala terminé (résultats connus), la diffusion pouvant continuer. */
    val finished: Boolean get() = event?.finished == true
}

/** Une diffusion d'un gala dans le guide : « Canal+ Sport 360 · 20:30 – 22:15 · ARES 45. 1re partie ». */
@Immutable
data class MmaAiring(val channelName: String, val startMillis: Long, val stopMillis: Long, val title: String)

data class MmaUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val cards: List<MmaCard> = emptyList(),
    /** Calendrier d'ESPN injoignable : seuls les galas du guide sont affichés. */
    val scheduleUnavailable: Boolean = false,
)

/**
 * Galas à venir et en cours : le calendrier d'ESPN (UFC, PFL, Cage Warriors) donne les galas, leurs cartes et leurs
 * résultats ; le guide donne la chaîne qui les diffuse, et les galas des autres organisations.
 */
class MmaViewModel(private val container: AppContainer, private val sourceId: Long) : ViewModel() {

    /** `null` : toutes les organisations. */
    val promotion = MutableStateFlow<MmaPromotion?>(null)

    /** Programmes MMA du guide, et chaînes de la playlist par identifiant du guide (chaînes habituelles). */
    private class GuideData(val rows: List<BroadcastRow>, val usual: Map<String, GuideChannel>)

    private var cachedGuide: Pair<Any, GuideData>? = null

    /** Playlist ou guide rechargés, catégories masquées : seuls changements qui relancent la lecture du guide. */
    private val guideVersion: Flow<Any> = combine(
        container.playlistStore.observeSource(sourceId)
            .map { source -> source?.status?.let { it.lastSuccessAtMillis to it.epg.lastSuccessAtMillis } }
            .distinctUntilChanged(),
        container.hiddenGroupDao.observeTitles(sourceId, ContentKind.LIVE.name),
    ) { version, hidden -> version to hidden }

    private val guide: Flow<GuideData> = guideVersion
        .conflate()
        .map { version -> cachedGuide?.takeIf { it.first == version }?.second ?: loadGuide().also { cachedGuide = version to it } }

    /** Calendrier d'ESPN : toutes les minutes pendant un gala, toutes les 30 min sinon (le client garde les réponses). */
    private val schedule: Flow<List<MmaEvent>?> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val events = container.mmaSchedule.events(now - HISTORY_MILLIS, now + WINDOW_MILLIS)
            emit(events)
            val live = events.orEmpty().any { it.isLive(System.currentTimeMillis()) }
            delay(if (live) LIVE_REFRESH_MILLIS else SCHEDULE_REFRESH_MILLIS)
        }
    }.distinctUntilChanged()

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }

    val state: StateFlow<MmaUiState> = combine(guide, schedule, clock) { guide, events, now ->
        MmaUiState(
            loading = false,
            now = now,
            cards = mmaCards(events.orEmpty(), guide.rows, now, guide.usual),
            scheduleUnavailable = events == null,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MmaUiState())

    private suspend fun loadGuide(): GuideData {
        val now = System.currentTimeMillis()
        val channels = HashMap<String, EpgChannelRow>()
        for (channel in container.channelDao.epgChannels(sourceId)) {
            val id = channel.epgChannelId ?: continue
            if (id !in channels) channels[id] = channel
        }
        val guideOnly = container.guideOnlyChannelDao.list(sourceId).associate { it.epgChannelId to it.name }
        // Depuis une semaine : un programme déjà passé sous le même titre est une rediffusion.
        val rows = container.programmeDao.mmaCandidates(sourceId, now - RERUN_MEMORY_MILLIS, now + WINDOW_MILLIS).mapNotNull { programme ->
            channels[programme.epgChannelId]?.let { BroadcastRow(it, null, programme) }
                ?: guideOnly[programme.epgChannelId]?.let { BroadcastRow(null, it, programme) }
        }
        val usualIds = MmaPromotion.entries.flatMap { it.usualGuideIds }.toSet()
        val usual = channels.filterKeys { it in usualIds }
            .mapValues { (_, channel) -> GuideChannel(channel.id, channel.title.ifBlank { channel.name }, null, channel.logoUrl, emptyList()) }
        return GuideData(rows, usual)
    }

    companion object {
        const val SCHEDULE_REFRESH_MILLIS = 30 * 60_000L
        const val LIVE_REFRESH_MILLIS = 60_000L
        const val TICK_MILLIS = 30_000L

        /** Galas commencés depuis moins de 12 h : peut-être encore en cours, ou résultats du jour. */
        const val HISTORY_MILLIS = 12 * 60 * 60_000L

        /** Le guide couvre une semaine environ ; le calendrier d'ESPN, un mois. */
        const val WINDOW_MILLIS = 31 * 24 * 60 * 60_000L

        const val RERUN_MEMORY_MILLIS = 7 * 24 * 60 * 60_000L
    }
}

/** Marge avant le début d'un gala où un programme du guide peut encore être sa diffusion (avant-programme). */
private const val EARLY_MILLIS = 60 * 60_000L

/** Gala dont seul le jour est connu : programmes du guide cherchés de 6 h (UTC) ce jour-là à 8 h le lendemain. */
private const val UNKNOWN_TIME_BEFORE_MILLIS = 14 * 60 * 60_000L
private const val UNKNOWN_TIME_AFTER_MILLIS = 12 * 60 * 60_000L

/** Fin d'un gala trouvé seulement dans le guide, sans horaire de fin. */
private const val GUIDE_EVENT_MILLIS = 3 * 60 * 60_000L

/**
 * Galas à afficher : ceux d'ESPN avec les chaînes dont un programme de l'organisation recouvre la soirée ; puis ceux du
 * guide seul (organisations absentes d'ESPN, ou gala inconnu d'ESPN), sans rediffusion (même titre et sous-titre déjà
 * passés sur la même chaîne dans la semaine) ni doublon. Galas terminés depuis plus de [HISTORY][MmaViewModel.HISTORY_MILLIS] retirés.
 */
internal fun mmaCards(
    events: List<MmaEvent>,
    rows: List<BroadcastRow>,
    now: Long,
    /** Chaînes de la playlist par identifiant du guide, pour la chaîne habituelle d'une organisation. */
    usual: Map<String, GuideChannel> = emptyMap(),
): List<MmaCard> {
    val classified = rows.mapNotNull { row -> MmaGuide.promotionOf(row.title, row.subtitle, row.category)?.let { row to it } }
    val used = HashSet<BroadcastRow>()
    val cards = ArrayList<MmaCard>()
    // Rediffusion : même chaîne, même titre et même sous-titre déjà commencés entre 1 h et 7 jours plus tôt.
    fun rerun(row: BroadcastRow): Boolean = rows.any { other ->
        other !== row && other.channelKey == row.channelKey && other.title == row.title && other.subtitle == row.subtitle &&
            other.startMillis in (row.startMillis - MmaViewModel.RERUN_MEMORY_MILLIS)..(row.startMillis - 60 * 60_000L)
    }
    for (event in events) {
        // Heure inconnue (Sherdog : le jour seulement) : toute la journée du gala et la nuit qui suit, sans les rediffusions.
        val window = if (event.timeKnown) {
            (event.startMillis - EARLY_MILLIS)..event.endMillis
        } else {
            (event.startMillis - UNKNOWN_TIME_BEFORE_MILLIS)..(event.startMillis + UNKNOWN_TIME_AFTER_MILLIS)
        }
        val shows = classified.filter { (row, promotion) ->
            promotion == event.promotion && row.startMillis < window.last && row.stopMillis > window.first && (event.timeKnown || !rerun(row))
        }.map { it.first }
        used += shows
        val card = if (event.timeKnown || shows.isEmpty()) {
            MmaCard(event.promotion, event.name, event.startMillis, event.mainCardMillis, event.endMillis, event = event, timeKnown = event.timeKnown)
        } else {
            // L'heure du gala est celle de sa diffusion dans le guide : celle du soir ou de la nuit d'abord (un magazine ou
            // une ancienne soirée le matin donnait « 11:21 » à un gala Hexagone diffusé à 20:30, vu sur la box le 28/11/2026).
            val start = shows.filter { eveningOrNight(it.startMillis) }.ifEmpty { shows }.minOf { it.startMillis }
            MmaCard(event.promotion, event.name, start, null, maxOf(shows.maxOf { it.stopMillis }, start + GUIDE_EVENT_MILLIS / 2), event = event)
        }
        cards += withChannels(card, shows)
    }
    val espnCovered = events.map { it.promotion }.toSet()
    val guideOnly = classified
        .filter { (row, promotion) ->
            row !in used && row.stopMillis > now - MmaViewModel.HISTORY_MILLIS && !rerun(row) &&
                // UFC et PFL : ESPN fait foi (un programme hors de ses galas est une rediffusion ou un différé). Cage
                // Warriors (ESPN incomplet) et ARES (site officiel) : le guide seul tant que leur calendrier n'a aucun gala.
                (promotion.espnLeague == null || promotion == MmaPromotion.CAGE_WARRIORS) && promotion !in espnCovered
        }
        // Une émission en plusieurs parties (« 1re partie », « Partie 2 ») ou sur plusieurs chaînes : un seul gala.
        .groupBy { (_, promotion) -> promotion }
        .values.flatMap { group -> mergeParts(group) }
    cards += guideOnly
    // Rien dans le guide (gala trop lointain, ou non annoncé) : la chaîne habituelle de l'organisation.
    for (index in cards.indices) {
        val card = cards[index]
        if (card.channels.isNotEmpty()) continue
        val channel = card.promotion.usualGuideIds.firstNotNullOfOrNull { usual[it] } ?: continue
        cards[index] = card.copy(channels = listOf(channel), guideChannel = null, usualChannel = true)
    }
    return cards.filter { it.endMillis > now - MmaViewModel.HISTORY_MILLIS && (it.event == null || !it.finished || it.endMillis > now - MmaViewModel.HISTORY_MILLIS) }
        .sortedWith(compareBy({ it.startMillis }, { it.promotion.ordinal }))
}

/** Début entre 17 h et 6 h (heure de l'appareil) : moment habituel de la diffusion d'un gala. */
private fun eveningOrNight(millis: Long): Boolean {
    val hour = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).hour
    return hour >= EVENING_HOUR || hour < NIGHT_END_HOUR
}

private const val EVENING_HOUR = 17
private const val NIGHT_END_HOUR = 6

/** « ARES 45. 1re partie » → « ARES 45 ». */
private val PART = Regex("""[.,:-]?\s*(1re|1ère|premiere|première|2e|deuxieme|deuxième)?\s*partie\s*\d*\s*$""", RegexOption.IGNORE_CASE)

/** Programmes d'un même gala du guide : regroupés quand ils se suivent (moins de 2 h d'écart), sinon galas distincts. */
private fun mergeParts(group: List<Pair<BroadcastRow, MmaPromotion>>): List<MmaCard> {
    val sorted = group.sortedBy { it.first.startMillis }
    val result = ArrayList<MmaCard>()
    var current = ArrayList<BroadcastRow>()
    var promotion = sorted.first().second
    fun flush() {
        if (current.isEmpty()) return
        val first = current.first()
        val name = MmaGuide.eventName(first.title).replace(PART, "").trim().ifBlank { promotion.label }
        result += withChannels(
            MmaCard(promotion, name, current.minOf { it.startMillis }, null, maxOf(current.maxOf { it.stopMillis }, first.startMillis + GUIDE_EVENT_MILLIS / 2)),
            current,
        )
        current = ArrayList()
    }
    for ((row, rowPromotion) in sorted) {
        val last = current.lastOrNull()
        if (last != null && row.startMillis - current.maxOf { it.stopMillis } > 2 * 60 * 60_000L) flush()
        promotion = rowPromotion
        current += row
    }
    flush()
    return result
}

/** Chaînes de la playlist qui diffusent le gala (françaises d'abord, puis la première à commencer). */
private fun withChannels(card: MmaCard, shows: List<BroadcastRow>): MmaCard {
    // Grille française d'abord (« CANAL+ SPORT 3 » relié au guide de Canal+ Sport Afrique passe après Canal+ Sport 360),
    // puis chaîne française, puis la première à commencer.
    val ranked = shows.sortedWith(compareBy({ !it.epgChannelId.endsWith(".fr") }, { BroadcastLanguage.rank(it.channelName) }, { it.startMillis }))
    val inPlaylist = ranked.filter { it.channelId != null }.distinctBy { it.channelId }
    val preferred = inPlaylist.firstOrNull() ?: ranked.firstOrNull()
    return card.copy(
        channels = inPlaylist.map { GuideChannel(it.channelId!!, it.channelName, null, it.logoUrl, emptyList()) },
        // Chaînes de la playlist d'abord (dans l'ordre proposé), chaque partie à son heure.
        airings = ranked.sortedWith(compareBy({ it.channelId == null }, { row -> inPlaylist.indexOfFirst { it.channelKey == row.channelKey }.let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.startMillis }))
            .distinctBy { it.channelKey to it.startMillis }
            .map { MmaAiring(it.channelName, it.startMillis, it.stopMillis, it.title) },
        programme = preferred?.let { GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year) },
        guideChannel = if (inPlaylist.isEmpty()) ranked.firstOrNull()?.channelName?.takeIf { it.isNotBlank() } else null,
    )
}
