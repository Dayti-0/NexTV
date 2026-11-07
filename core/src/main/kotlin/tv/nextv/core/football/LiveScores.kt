package tv.nextv.core.football

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Équipe d'un score en direct (noms donnés par la source des scores). */
data class LiveTeam(val names: List<String>)

/**
 * Score en direct d'un match : [clock] « 81' » ou « 45+2' » pendant le jeu, [halfTime] à la pause,
 * [finished] une fois le match terminé.
 */
data class LiveScore(
    val competition: Competition,
    val kickoffMillis: Long,
    val home: LiveTeam,
    val away: LiveTeam,
    val homeScore: Int?,
    val awayScore: Int?,
    val inPlay: Boolean,
    val finished: Boolean,
    val clock: String?,
    val halfTime: Boolean,
) {
    /** Le même match vu de l'autre équipe (affiche du guide inversée). */
    fun swapped(): LiveScore = copy(home = away, away = home, homeScore = awayScore, awayScore = homeScore)
}

/**
 * Calendrier des matchs, scores et minute de jeu en direct, d'après les données publiques d'ESPN
 * (sans clé) : toutes les compétitions de la section Foot, coupes d'Europe et sélections comprises,
 * avec les vrais coups d'envoi. Les tableaux des scores en direct sont gardés [cacheMillis] ; en cas
 * d'échec, la réponse précédente reste utilisée.
 */
class LiveScoreClient(
    private val clients: HttpClientProvider,
    private val baseUrl: HttpUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/".toHttpUrl(),
    /** Jours de match de chaque compétition. */
    private val calendarUrl: HttpUrl = "https://sports.core.api.espn.com/v2/sports/soccer/leagues/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 45_000,
    private val callTimeoutMillis: Long = 8_000,
) {
    private val mutex = Mutex()
    private val cached = HashMap<String, Pair<Long, List<LiveScore>>>()
    private val scheduleMutex = Mutex()
    private val boards = HashMap<String, Pair<Long, List<ScheduledMatch>>>()
    private val calendars = HashMap<String, Pair<Long, List<String>>>()

    /**
     * [matches] avec le score, la minute et l'état en direct des matchs en cours (ou sur le point de
     * commencer) quand la source des scores les connaît.
     */
    suspend fun update(matches: List<ScheduledMatch>): List<ScheduledMatch> {
        val now = clock()
        val competitions = matches.filter { it.isActive(now) }.mapTo(HashSet()) { it.competition }.filter { LEAGUES[it] != null }
        if (competitions.isEmpty()) return matches
        val scores = competitions.flatMap { scores(it) }
        return if (scores.isEmpty()) matches else overlay(matches, scores)
    }

    /**
     * Score en direct d'une affiche trouvée dans le guide seul (« Bayer Leverkusen » – « Marseille »),
     * diffusée de [startMillis] à [endMillis] : les coupes d'Europe et les matchs sans clé
     * football-data.org n'ont pas d'autre score. `null` : match inconnu de la source des scores.
     */
    suspend fun find(competition: Competition?, home: String, away: String, startMillis: Long, endMillis: Long): LiveScore? {
        // Compétition inconnue (titre sans elle) : championnats et coupes d'Europe.
        val competitions = competition?.let(::related) ?: (Competition.entries - Competition.NATIONAL_TEAMS)
        val scores = competitions.flatMap { scores(it) }
        return match(scores, home, away, startMillis, endMillis)
    }

    private suspend fun scores(competition: Competition): List<LiveScore> =
        LEAGUES[competition].orEmpty().flatMap { scores(competition, it) }

    private suspend fun scores(competition: Competition, league: String): List<LiveScore> = mutex.withLock {
        val now = clock()
        cached[league]?.takeIf { now - it.first < cacheMillis }?.let { return@withLock it.second }
        val url = baseUrl.newBuilder().addPathSegments(league).addPathSegment("scoreboard").build()
        val scores = fetch(url, competition)
        // Échec : la réponse précédente reste valable un moment ; sinon, aucun score en direct.
        if (scores == null) return@withLock cached[league]?.second.orEmpty()
        cached[league] = now to scores
        scores
    }

    /**
     * Matchs de toutes les compétitions dont le coup d'envoi tombe entre [fromMillis] et [toMillis],
     * avec leur état, leur score et la minute de jeu. Seuls les jours de match de chaque compétition
     * sont demandés. `null` quand la source ne répond pas du tout.
     */
    suspend fun schedule(fromMillis: Long, toMillis: Long): List<ScheduledMatch>? {
        val first = dayOf(fromMillis)
        val last = dayOf(toMillis)
        val leagues = LEAGUES.flatMap { (competition, slugs) -> slugs.map { competition to it } }
        // Demandes en parallèle : un appareil lent n'attend pas chaque compétition à son tour.
        val results = coroutineScope {
            leagues.map { (competition, league) ->
                async {
                    val days = calendar(league)
                    if (days == null) {
                        // Calendrier injoignable : au moins les matchs du moment (tableau du jour).
                        listOf(board(competition, league, day = null))
                    } else {
                        days.filter { it in first..last }.map { day -> async { board(competition, league, day) } }.awaitAll()
                    }
                }
            }.awaitAll().flatten()
        }
        if (results.isNotEmpty() && results.all { it == null }) return null
        return results.flatMap { it.orEmpty() }
            .filter { it.kickoffMillis in fromMillis..toMillis }
            .distinctBy { it.id }
            .sortedBy { it.kickoffMillis }
    }

    /** Jours de match d'une compétition (« 20261106 »), gardés [CALENDAR_MILLIS] ; `null` s'il n'a jamais pu être lu. */
    private suspend fun calendar(league: String): List<String>? {
        val now = clock()
        scheduleMutex.withLock { calendars[league]?.takeIf { now - it.first < CALENDAR_MILLIS }?.let { return it.second } }
        val url = calendarUrl.newBuilder().addPathSegments(league).addPathSegments("calendar/ondays").build()
        val days = fetchJson(url)?.let(::parseCalendar)
        return scheduleMutex.withLock {
            if (days != null) calendars[league] = now to days
            days ?: calendars[league]?.second
        }
    }

    /**
     * Matchs d'une compétition un jour donné ([day] : « 20261106 » ; `null` : tableau du moment).
     * Gardés 20 s pendant les matchs, plus longtemps sinon ; `null` s'il n'a jamais pu être lu.
     */
    private suspend fun board(competition: Competition, league: String, day: String?): List<ScheduledMatch>? {
        val key = "$league|${day.orEmpty()}"
        val now = clock()
        scheduleMutex.withLock {
            boards[key]?.takeIf { (at, matches) -> now - at < boardMaxAge(day, matches, now) }?.let { return it.second }
        }
        val url = baseUrl.newBuilder().addPathSegments(league).addPathSegment("scoreboard")
            .apply { if (day != null) addQueryParameter("dates", day) }
            .build()
        val matches = fetchJson(url)?.let { parseSchedule(it, competition) }
        return scheduleMutex.withLock {
            if (matches != null) {
                boards[key] = now to matches
                // Jours passés : plus utiles.
                boards.entries.removeAll { now - it.value.first > PAST_BOARD_MILLIS }
            }
            matches ?: boards[key]?.second
        }
    }

    private fun boardMaxAge(day: String?, matches: List<ScheduledMatch>, now: Long): Long = when {
        matches.any { it.isActive(now) } -> LIVE_BOARD_MILLIS
        day == null || day <= dayOf(now) -> TODAY_BOARD_MILLIS
        else -> FUTURE_BOARD_MILLIS
    }

    /** Matchs d'un tableau des scores en direct, `null` en cas d'échec. */
    private suspend fun fetch(url: HttpUrl, competition: Competition): List<LiveScore>? =
        fetchJson(url)?.let { parse(it, competition) }

    /** Réponse JSON d'ESPN, `null` en cas d'échec (réseau, réponse illisible). */
    private suspend fun fetchJson(url: HttpUrl): JSONObject? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        // Lecture hors du fil principal (le corps se lit sur le réseau).
        withContext(Dispatchers.IO) {
            call.await().use { response -> if (response.isSuccessful) JSONObject(response.body.string()) else null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Scores en direct injoignables", e)
        null
    } catch (e: JSONException) {
        logger.warn(TAG, "Scores en direct illisibles", e)
        null
    }

    companion object {
        private const val TAG = "LiveScores"

        /** Tableaux des scores d'ESPN de chaque compétition (les sélections jouent dans plusieurs). */
        private val LEAGUES = mapOf(
            Competition.LIGUE_1 to listOf("fra.1"),
            Competition.PREMIER_LEAGUE to listOf("eng.1"),
            Competition.LA_LIGA to listOf("esp.1"),
            Competition.SERIE_A to listOf("ita.1"),
            Competition.BUNDESLIGA to listOf("ger.1"),
            // Tours préliminaires (juillet, août) : tableaux à part.
            Competition.CHAMPIONS_LEAGUE to listOf("uefa.champions", "uefa.champions_qual"),
            Competition.EUROPA_LEAGUE to listOf("uefa.europa", "uefa.europa_qual"),
            Competition.CONFERENCE_LEAGUE to listOf("uefa.europa.conf", "uefa.europa.conf_qual"),
            Competition.NATIONAL_TEAMS to listOf("uefa.nations", "fifa.worldq.uefa", "fifa.world", "uefa.euro", "fifa.friendly"),
        )

        private val EUROPEAN_CUPS = listOf(Competition.CHAMPIONS_LEAGUE, Competition.EUROPA_LEAGUE, Competition.CONFERENCE_LEAGUE)

        /**
         * Compétitions où chercher une affiche du guide : les trois coupes d'Europe ensemble (le guide
         * annonce parfois l'une pour l'autre), la compétition seule sinon.
         */
        private fun related(competition: Competition): List<Competition> =
            if (competition in EUROPEAN_CUPS) listOf(competition) + (EUROPEAN_CUPS - competition) else listOf(competition)

        /** Jours de match d'une compétition : redemandés deux fois par jour. */
        private const val CALENDAR_MILLIS = 12 * 60 * 60_000L

        /** Tableau d'un jour : pendant les matchs, le jour même, les jours suivants, les jours passés. */
        private const val LIVE_BOARD_MILLIS = 20_000L
        private const val TODAY_BOARD_MILLIS = 10 * 60_000L
        private const val FUTURE_BOARD_MILLIS = 3 * 60 * 60_000L
        private const val PAST_BOARD_MILLIS = 12 * 60 * 60_000L

        /** Jours des tableaux des scores, à l'heure de New York comme la source (« 20261105 »). */
        private val BOARD_ZONE: TimeZone = TimeZone.getTimeZone("America/New_York")

        internal fun dayOf(millis: Long): String =
            SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = BOARD_ZONE }.format(millis)

        /** Jours de match d'une réponse « calendar/ondays » (« 2026-11-06T08:00Z » → « 20261106 »). */
        fun parseCalendar(json: JSONObject): List<String>? {
            val dates = json.optJSONObject("eventDate")?.optJSONArray("dates") ?: return null
            return (0 until dates.length()).mapNotNull { index ->
                dates.optString(index).takeIf { it.length >= 10 }?.substring(0, 10)?.replace("-", "")?.takeIf { day -> day.all(Char::isDigit) }
            }.distinct()
        }

        /** Statuts d'ESPN d'un match qui n'aura pas lieu à la date prévue. */
        private val POSTPONED = setOf("STATUS_POSTPONED", "STATUS_DELAYED", "STATUS_SUSPENDED")
        private val CANCELLED = setOf("STATUS_CANCELED", "STATUS_CANCELLED", "STATUS_ABANDONED", "STATUS_FORFEIT")

        /** Matchs d'un tableau des scores, avec les écussons des équipes. */
        fun parseSchedule(json: JSONObject, competition: Competition): List<ScheduledMatch> {
            val events = json.optJSONArray("events") ?: return emptyList()
            return (0 until events.length()).mapNotNull { index ->
                val event = events.optJSONObject(index) ?: return@mapNotNull null
                val id = event.optString("id").toLongOrNull() ?: return@mapNotNull null
                val kickoff = parseDate(event.optString("date")) ?: return@mapNotNull null
                val contest = event.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
                val competitors = contest.optJSONArray("competitors") ?: return@mapNotNull null
                val sides = (0 until competitors.length()).mapNotNull { competitors.optJSONObject(it) }
                val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
                val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
                val status = event.optJSONObject("status") ?: contest.optJSONObject("status") ?: return@mapNotNull null
                val type = status.optJSONObject("type") ?: return@mapNotNull null
                val name = type.optString("name")
                val state = type.optString("state")
                val halfTime = name == "STATUS_HALFTIME" || type.optString("shortDetail") == "HT"
                val matchStatus = when {
                    name in CANCELLED -> MatchStatus.CANCELLED
                    name in POSTPONED -> MatchStatus.POSTPONED
                    state == "in" -> MatchStatus.LIVE
                    state == "post" -> MatchStatus.FINISHED
                    else -> MatchStatus.SCHEDULED
                }
                val started = matchStatus == MatchStatus.LIVE || matchStatus == MatchStatus.FINISHED
                ScheduledMatch(
                    id = id,
                    competition = competition,
                    kickoffMillis = kickoff,
                    status = matchStatus,
                    home = espnTeam(home) ?: return@mapNotNull null,
                    away = espnTeam(away) ?: return@mapNotNull null,
                    homeScore = home.optString("score").toIntOrNull().takeIf { started },
                    awayScore = away.optString("score").toIntOrNull().takeIf { started },
                    clock = status.optString("displayClock").takeIf { state == "in" && !halfTime }?.let(::minute),
                    halfTime = state == "in" && halfTime,
                )
            }
        }

        private fun espnTeam(competitor: JSONObject): Team? {
            val team = competitor.optJSONObject("team") ?: return null
            val name = team.optString("displayName").trim().ifEmpty { team.optString("name").trim() }.ifEmpty { return null }
            return Team(
                name = name,
                shortName = team.optString("shortDisplayName").trim().takeIf { it.isNotEmpty() && it != name },
                code = team.optString("abbreviation").trim().takeIf { it.isNotEmpty() },
                crestUrl = team.optString("logo").trim().takeIf { it.startsWith("https://") },
                id = team.optString("id").toLongOrNull(),
            )
        }

        /** Avant le coup d'envoi, l'émission commence souvent par l'avant-match. */
        private const val PRESHOW_MILLIS = 30 * 60_000L

        /**
         * Match du guide ([home] – [away], diffusé de [startMillis] à [endMillis]) parmi [scores] :
         * coup d'envoi pendant la diffusion, et les deux équipes reconnues (ou une seule, quand aucun
         * autre match de la fenêtre ne la concerne : une équipe ne joue qu'un match à la fois).
         * Score dans l'ordre de l'affiche du guide.
         */
        fun match(scores: List<LiveScore>, home: String, away: String, startMillis: Long, endMillis: Long): LiveScore? {
            val window = scores.filter { it.kickoffMillis >= startMillis - PRESHOW_MILLIS && it.kickoffMillis < endMillis }
            val homeWords = words(home).toSet()
            val awayWords = words(away).toSet()
            fun LiveTeam.isOne(of: Set<String>) = names.flatMap(::words).any { it in of }
            window.firstOrNull { it.home.isOne(homeWords) && it.away.isOne(awayWords) }?.let { return it }
            window.firstOrNull { it.home.isOne(awayWords) && it.away.isOne(homeWords) }?.let { return it.swapped() }
            // Une seule équipe reconnue : le seul match de la fenêtre qui la concerne.
            val distinctive = (homeWords + awayWords) - GENERIC_WORDS
            val single = window.filter { it.home.isOne(distinctive) || it.away.isOne(distinctive) }.singleOrNull() ?: return null
            return if (single.home.isOne(homeWords) || single.away.isOne(awayWords)) single else single.swapped()
        }

        /** Mots de nom de club trop répandus pour reconnaître seuls une équipe. */
        private val GENERIC_WORDS = setOf(
            "real", "city", "united", "sporting", "athletic", "atletico", "racing", "olympique", "inter", "dinamo",
            "dynamo", "hapoel", "maccabi", "saint", "fc", "union", "red", "star", "young", "boys", "rapid", "royal",
        )

        /** Écart toléré entre les heures de coup d'envoi des deux sources. */
        private const val KICKOFF_TOLERANCE_MILLIS = 20 * 60_000L

        /** Mots trop communs pour reconnaître une équipe (« Club », « Calcio »…). */
        private val COMMON_WORDS = setOf("club", "calcio", "football", "futbol", "the", "del", "ssc", "afc", "cfc")

        fun parse(json: JSONObject, competition: Competition): List<LiveScore> {
            val events = json.optJSONArray("events") ?: return emptyList()
            return (0 until events.length()).mapNotNull { index ->
                val event = events.optJSONObject(index) ?: return@mapNotNull null
                val kickoff = parseDate(event.optString("date")) ?: return@mapNotNull null
                val contest = event.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
                val competitors = contest.optJSONArray("competitors") ?: return@mapNotNull null
                val sides = (0 until competitors.length()).mapNotNull { competitors.optJSONObject(it) }
                val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
                val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
                val status = event.optJSONObject("status") ?: contest.optJSONObject("status") ?: return@mapNotNull null
                val type = status.optJSONObject("type") ?: return@mapNotNull null
                val state = type.optString("state")
                val halfTime = type.optString("name") == "STATUS_HALFTIME" || type.optString("shortDetail") == "HT"
                LiveScore(
                    competition = competition,
                    kickoffMillis = kickoff,
                    home = team(home),
                    away = team(away),
                    homeScore = home.optString("score").toIntOrNull(),
                    awayScore = away.optString("score").toIntOrNull(),
                    inPlay = state == "in",
                    finished = state == "post",
                    clock = status.optString("displayClock").takeIf { state == "in" && !halfTime }?.let(::minute),
                    halfTime = state == "in" && halfTime,
                )
            }
        }

        /** « 45'+2' » → « 45+2' » ; « 81' » tel quel. */
        fun minute(displayClock: String): String? {
            val clean = displayClock.replace("'", "").trim()
            if (clean.isEmpty() || !clean.first().isDigit()) return null
            return "$clean'"
        }

        private fun team(competitor: JSONObject): LiveTeam {
            val team = competitor.optJSONObject("team") ?: return LiveTeam(emptyList())
            return LiveTeam(listOf("displayName", "shortDisplayName", "name", "abbreviation", "location").mapNotNull { key ->
                team.optString(key).trim().takeIf { it.isNotEmpty() }
            }.distinct())
        }

        /** Les matchs connus de la source des scores prennent leur score, leur minute et leur état. */
        fun overlay(matches: List<ScheduledMatch>, scores: List<LiveScore>): List<ScheduledMatch> = matches.map { match ->
            val candidates = scores.filter { it.competition == match.competition && kotlin.math.abs(it.kickoffMillis - match.kickoffMillis) <= KICKOFF_TOLERANCE_MILLIS }
            val live = candidates.singleOrNull { sameTeams(it, match) }
                ?: candidates.singleOrNull()?.takeIf { matches.count { other -> other.competition == match.competition && kotlin.math.abs(other.kickoffMillis - match.kickoffMillis) <= KICKOFF_TOLERANCE_MILLIS } == 1 }
                ?: return@map match
            when {
                live.inPlay -> match.copy(
                    status = MatchStatus.LIVE,
                    homeScore = live.homeScore ?: match.homeScore,
                    awayScore = live.awayScore ?: match.awayScore,
                    clock = live.clock,
                    halfTime = live.halfTime,
                )
                live.finished -> match.copy(
                    status = MatchStatus.FINISHED,
                    homeScore = live.homeScore ?: match.homeScore,
                    awayScore = live.awayScore ?: match.awayScore,
                    clock = null,
                    halfTime = false,
                )
                else -> match
            }
        }

        private fun sameTeams(live: LiveScore, match: ScheduledMatch): Boolean =
            sameTeam(live.home, match.home) && sameTeam(live.away, match.away)

        /** Un mot du nom en commun (« Barcelona » / « FC Barcelona »), ou le même sigle (« INT »). */
        private fun sameTeam(live: LiveTeam, team: Team): Boolean {
            val ours = listOfNotNull(team.name, team.shortName, team.code).flatMap(::words).toSet()
            return live.names.flatMap(::words).any { it in ours }
        }

        private fun words(name: String): List<String> =
            FootballText.fold(name).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in COMMON_WORDS }

        private fun parseDate(value: String): Long? {
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                try {
                    return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time
                } catch (e: ParseException) {
                    // Format suivant.
                }
            }
            return null
        }
    }
}
