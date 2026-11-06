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
    /** Match reporté ou annulé : sa date n'est pas celle où il se jouera. */
    val cancelled: Boolean = false,
) {
    /** Le même match vu de l'autre équipe (affiche du guide inversée). */
    fun swapped(): LiveScore = copy(home = away, away = home, homeScore = awayScore, awayScore = homeScore)
}

/**
 * Scores et minute de jeu en direct, d'après le tableau des scores public d'ESPN (sans clé) :
 * l'offre gratuite de football-data.org donne les scores avec plusieurs minutes de retard et sans
 * la minute de jeu. Chaque championnat est gardé [cacheMillis] ; en cas d'échec, rien n'est changé
 * (les scores de football-data.org restent affichés).
 */
class LiveScoreClient(
    private val clients: HttpClientProvider,
    private val baseUrl: HttpUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 45_000,
    private val callTimeoutMillis: Long = 8_000,
) {
    private val mutex = Mutex()
    private val cached = HashMap<String, Pair<Long, List<LiveScore>>>()
    private val boardMutex = Mutex()
    private val boards = HashMap<String, Pair<Long, List<LiveScore>>>()

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
     * La diffusion de l'affiche [home] – [away] qui commence à [startMillis] n'est pas un direct,
     * d'après la source des scores : c'est une rediffusion quand
     * - le match a été joué (ou est prévu) au moins [REPLAY_GAP_MILLIS] plus tôt dans les deux jours
     *   précédents (match de la veille au soir rediffusé le midi) ;
     * - ou, pour les coupes d'Europe (calendrier complet chez la source), quand aucune des deux équipes
     *   ne joue autour de l'heure de diffusion (vieille affiche rediffusée).
     * `false` quand rien ne le montre (tableaux injoignables, équipes inconnues…).
     */
    suspend fun isReplay(competition: Competition?, home: String, away: String, startMillis: Long): Boolean {
        val competitions = competition?.let(::related) ?: (Competition.entries - Competition.NATIONAL_TEAMS)
        val from = startMillis - REPLAY_LOOKBACK_MILLIS
        val until = startMillis + LATE_KICKOFF_MILLIS
        val wanted = competitions.flatMap { c -> LEAGUES[c].orEmpty().flatMap { league -> days(from, until).map { day -> Triple(c, league, day) } } }
        // Tableaux demandés ensemble : un appareil lent n'attend pas chacun à son tour.
        val boards = coroutineScope { wanted.map { (c, league, day) -> async { board(c, league, day) } }.awaitAll() }
        val scores = boards.flatMap { it.orEmpty() }
        if (earlier(scores, home, away, from, startMillis - REPLAY_GAP_MILLIS) != null) return true
        return competition in EUROPEAN_CUPS && boards.none { it == null } &&
            absent(scores, home, away, startMillis - REPLAY_GAP_MILLIS, until)
    }

    /**
     * Tableau des scores d'une journée ([day] : « 20261105 »), gardé plus longtemps pour les jours
     * passés ; `null` quand il n'a jamais pu être lu.
     */
    private suspend fun board(competition: Competition, league: String, day: String): List<LiveScore>? {
        val key = "$league|$day"
        val now = clock()
        val maxAge = if (day < dayOf(now)) PAST_BOARD_MILLIS else TODAY_BOARD_MILLIS
        boardMutex.withLock { boards[key]?.takeIf { now - it.first < maxAge }?.let { return it.second } }
        val url = baseUrl.newBuilder().addPathSegments(league).addPathSegment("scoreboard").addQueryParameter("dates", day).build()
        val scores = fetch(url, competition)
        return boardMutex.withLock {
            if (scores != null) {
                boards[key] = now to scores
                // Seuls les jours encore utiles restent en mémoire.
                boards.entries.removeAll { now - it.value.first > PAST_BOARD_MILLIS }
            }
            scores ?: boards[key]?.second
        }
    }

    /** Matchs d'un tableau des scores, `null` en cas d'échec (réseau, réponse illisible). */
    private suspend fun fetch(url: HttpUrl, competition: Competition): List<LiveScore>? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        // Lecture hors du fil principal (le corps se lit sur le réseau).
        withContext(Dispatchers.IO) {
            call.await().use { response -> if (response.isSuccessful) parse(JSONObject(response.body.string()), competition) else null }
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

        /**
         * Écart entre le coup d'envoi et le début d'une diffusion à partir duquel c'est une
         * rediffusion : un direct commence avant le coup d'envoi, ou au plus tard à la seconde
         * mi-temps ; marge en plus pour un guide décalé d'une heure ou deux.
         */
        const val REPLAY_GAP_MILLIS = 3 * 60 * 60_000L + 30 * 60_000L

        /** Un direct commence au plus tôt 3 h avant le coup d'envoi (avant-match compris). */
        private const val LATE_KICKOFF_MILLIS = 3 * 60 * 60_000L

        /** Les rediffusions reprennent les matchs des deux derniers jours. */
        private const val REPLAY_LOOKBACK_MILLIS = 2 * 24 * 60 * 60_000L

        /** Tableau d'un jour passé (résultats définitifs) ou du jour même (horaires qui peuvent changer). */
        private const val PAST_BOARD_MILLIS = 6 * 60 * 60_000L
        private const val TODAY_BOARD_MILLIS = 10 * 60_000L

        /** Jours des tableaux des scores, à l'heure de New York comme la source (« 20261105 »). */
        private val BOARD_ZONE: TimeZone = TimeZone.getTimeZone("America/New_York")
        private const val DAY_MILLIS = 24 * 60 * 60_000L

        private fun dayOf(millis: Long): String =
            SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = BOARD_ZONE }.format(millis)

        /** Jours qui couvrent [from]..[to] (bornes comprises). */
        internal fun days(from: Long, to: Long): List<String> {
            if (to < from) return emptyList()
            val days = LinkedHashSet<String>()
            var t = from
            while (t < to) {
                days += dayOf(t)
                t += DAY_MILLIS
            }
            days += dayOf(to)
            return days.toList()
        }

        /**
         * Match de [scores] entre [home] et [away] (les deux équipes reconnues, dans un sens ou dans
         * l'autre) dont le coup d'envoi tombe entre [from] et [to].
         */
        fun earlier(scores: List<LiveScore>, home: String, away: String, from: Long, to: Long): LiveScore? {
            val homeWords = FootballMatches.teamWords(home)
            val awayWords = FootballMatches.teamWords(away)
            return scores.firstOrNull { score ->
                score.kickoffMillis in from..to && !score.cancelled &&
                    FootballMatches.sameTeams(homeWords, awayWords, score.home.words(), score.away.words())
            }
        }

        /**
         * Aucune des deux équipes ([home], [away]) n'a de match dont le coup d'envoi tombe entre [from]
         * et [to]. `false` quand une équipe n'a aucun mot reconnaissable (rien ne peut être affirmé).
         */
        fun absent(scores: List<LiveScore>, home: String, away: String, from: Long, to: Long): Boolean {
            val homeWords = FootballMatches.teamWords(home)
            val awayWords = FootballMatches.teamWords(away)
            if (homeWords.isEmpty() || awayWords.isEmpty()) return false
            return scores.none { score ->
                score.kickoffMillis in from..to && listOf(score.home.words(), score.away.words()).any { team ->
                    FootballMatches.sameTeam(homeWords, team) || FootballMatches.sameTeam(awayWords, team)
                }
            }
        }

        private fun LiveTeam.words(): Set<String> = names.flatMapTo(HashSet()) { FootballMatches.teamWords(it) }

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
                    cancelled = type.optString("name") in CANCELLED,
                )
            }
        }

        private val CANCELLED = setOf("STATUS_POSTPONED", "STATUS_CANCELED", "STATUS_CANCELLED", "STATUS_DELAYED")

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
