package tv.nextv.core.football

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
)

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
    private val cached = HashMap<Competition, Pair<Long, List<LiveScore>>>()

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

    private suspend fun scores(competition: Competition): List<LiveScore> = mutex.withLock {
        val now = clock()
        cached[competition]?.takeIf { now - it.first < cacheMillis }?.let { return@withLock it.second }
        val league = LEAGUES[competition] ?: return@withLock emptyList()
        val url = baseUrl.newBuilder().addPathSegment(league).addPathSegment("scoreboard").build()
        val scores = try {
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
        // Échec : la réponse précédente reste valable un moment ; sinon, aucun score en direct.
        if (scores == null) return@withLock cached[competition]?.second.orEmpty()
        cached[competition] = now to scores
        scores
    }

    companion object {
        private const val TAG = "LiveScores"

        /** Championnats du tableau des scores d'ESPN (les sélections nationales n'en ont pas un seul). */
        private val LEAGUES = mapOf(
            Competition.LIGUE_1 to "fra.1",
            Competition.PREMIER_LEAGUE to "eng.1",
            Competition.LA_LIGA to "esp.1",
            Competition.SERIE_A to "ita.1",
            Competition.BUNDESLIGA to "ger.1",
            Competition.CHAMPIONS_LEAGUE to "uefa.champions",
            Competition.EUROPA_LEAGUE to "uefa.europa",
            Competition.CONFERENCE_LEAGUE to "uefa.europa.conf",
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
