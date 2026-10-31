package tv.nextv.core.football

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Équipe d'un match (football-data.org). [crestUrl] : écusson. */
data class Team(val name: String, val shortName: String?, val code: String?, val crestUrl: String?, val id: Long? = null) {
    /** Nom affiché : le nom court s'il existe (« PSG », « Marseille »). */
    val displayName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

enum class MatchStatus { SCHEDULED, LIVE, FINISHED, POSTPONED, CANCELLED }

/** Match programmé, d'après football-data.org. Score : en cours de match ou final, `null` avant le coup d'envoi. */
data class ScheduledMatch(
    val id: Long,
    val competition: Competition,
    val kickoffMillis: Long,
    val status: MatchStatus,
    val home: Team,
    val away: Team,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
) {
    /** En cours ou sur le point de commencer : le score change, il est redemandé plus souvent. */
    fun isActive(now: Long): Boolean =
        status == MatchStatus.LIVE || (status == MatchStatus.SCHEDULED && kickoffMillis <= now + 5 * 60_000L && kickoffMillis >= now - 3 * 60 * 60_000L)
}

/** Ligne du classement d'un championnat. */
data class Standing(
    val position: Int,
    val team: Team,
    val played: Int,
    val won: Int,
    val draw: Int,
    val lost: Int,
    val goalDifference: Int,
    val points: Int,
)

enum class FootballDataError { MISSING_KEY, INVALID_KEY, RATE_LIMITED, NETWORK, SERVER }

sealed interface FootballDataResult {
    data class Success(val matches: List<ScheduledMatch>) : FootballDataResult
    data class Failed(val error: FootballDataError) : FootballDataResult
}

/** Derniers matchs joués (le plus récent en premier) et prochains matchs d'une équipe. */
data class TeamMatches(val last: List<ScheduledMatch>, val next: List<ScheduledMatch>)

sealed interface TeamMatchesResult {
    data class Success(val matches: TeamMatches) : TeamMatchesResult
    data class Failed(val error: FootballDataError) : TeamMatchesResult
}

sealed interface StandingsResult {
    data class Success(val table: List<Standing>) : StandingsResult
    data class Failed(val error: FootballDataError) : StandingsResult
}

/**
 * Matchs et classements des compétitions couvertes par l'offre gratuite de football-data.org (API v4).
 * La clé (« jeton », en-tête `X-Auth-Token`) n'apparaît jamais dans les journaux.
 * L'offre gratuite limite le nombre d'appels : les résultats sont gardés [cacheMillis], ou
 * [liveCacheMillis] pendant les matchs (scores en direct).
 */
class FootballDataClient(
    private val clients: HttpClientProvider,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = "https://api.football-data.org/v4/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30 * 60_000L,
    private val liveCacheMillis: Long = 2 * 60_000L,
    private val standingsCacheMillis: Long = 60 * 60_000L,
    /** Au-delà, la section Foot n'attend plus le service et se rabat sur le guide. */
    private val callTimeoutMillis: Long = 10_000,
) {
    private class FootballDataException(val error: FootballDataError) : Exception(error.name)

    private val mutex = Mutex()
    private var cached: Cached? = null
    private val cachedStandings = HashMap<Competition, CachedStandings>()

    private class Cached(val key: String, val from: Long, val to: Long, val at: Long, val matches: List<ScheduledMatch>)

    private class CachedStandings(val key: String, val at: Long, val table: List<Standing>)

    private val cachedTeams = HashMap<Long, CachedTeam>()

    private class CachedTeam(val key: String, val at: Long, val matches: TeamMatches)

    /**
     * Matchs entre [fromMillis] et [toMillis] (10 jours au plus). Une réponse récente pour la même
     * période est réutilisée ; en cas d'échec, la dernière réponse obtenue reste servie.
     */
    suspend fun matches(fromMillis: Long, toMillis: Long): FootballDataResult = mutex.withLock {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withLock FootballDataResult.Failed(FootballDataError.MISSING_KEY)
        val from = day(fromMillis)
        val to = day(toMillis)
        cached?.takeIf { it.key == key && it.from == from && it.to == to && clock() - it.at < freshness(it.matches) }?.let {
            return@withLock FootballDataResult.Success(it.matches)
        }
        try {
            val matches = fetch(key, from, to)
            cached = Cached(key, from, to, clock(), matches)
            FootballDataResult.Success(matches)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FootballDataException) {
            stale(key) ?: FootballDataResult.Failed(e.error)
        } catch (e: IOException) {
            logger.warn(TAG, "football-data.org injoignable", e)
            stale(key) ?: FootballDataResult.Failed(FootballDataError.NETWORK)
        } catch (e: JSONException) {
            logger.warn(TAG, "Réponse football-data.org illisible", e)
            stale(key) ?: FootballDataResult.Failed(FootballDataError.SERVER)
        }
    }

    /** Durée de validité d'une réponse : courte pendant les matchs, pour suivre le score. */
    private fun freshness(matches: List<ScheduledMatch>): Long {
        val now = clock()
        return if (matches.any { it.isActive(now) }) liveCacheMillis else cacheMillis
    }

    private fun stale(key: String): FootballDataResult? = cached?.takeIf { it.key == key }?.let { FootballDataResult.Success(it.matches) }

    /**
     * Cinq derniers matchs et cinq prochains matchs d'une équipe (compétitions suivies), sur deux
     * mois de part et d'autre d'aujourd'hui ; gardés [cacheMillis].
     */
    suspend fun teamMatches(teamId: Long): TeamMatchesResult = mutex.withLock {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withLock TeamMatchesResult.Failed(FootballDataError.MISSING_KEY)
        val previous = cachedTeams[teamId]?.takeIf { it.key == key }
        previous?.takeIf { clock() - it.at < freshness(it.matches.last + it.matches.next) }?.let { return@withLock TeamMatchesResult.Success(it.matches) }
        try {
            val today = day(clock())
            val path = baseUrl.newBuilder().addPathSegment("teams").addPathSegment(teamId.toString()).addPathSegment("matches")
            val past = get(
                key,
                path.addQueryParameter("dateFrom", isoDay(today - TEAM_RANGE_MILLIS)).addQueryParameter("dateTo", isoDay(today))
                    .addQueryParameter("status", "FINISHED").build(),
            )
            val future = get(
                key,
                baseUrl.newBuilder().addPathSegment("teams").addPathSegment(teamId.toString()).addPathSegment("matches")
                    .addQueryParameter("dateFrom", isoDay(today)).addQueryParameter("dateTo", isoDay(today + TEAM_RANGE_MILLIS))
                    .addQueryParameter("status", "SCHEDULED,TIMED,IN_PLAY,PAUSED").build(),
            )
            val matches = TeamMatches(
                last = parseMatches(past).sortedByDescending { it.kickoffMillis }.take(TEAM_MATCHES),
                next = parseMatches(future).sortedBy { it.kickoffMillis }.take(TEAM_MATCHES),
            )
            cachedTeams[teamId] = CachedTeam(key, clock(), matches)
            TeamMatchesResult.Success(matches)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FootballDataException) {
            previous?.let { TeamMatchesResult.Success(it.matches) } ?: TeamMatchesResult.Failed(e.error)
        } catch (e: IOException) {
            logger.warn(TAG, "football-data.org injoignable", e)
            previous?.let { TeamMatchesResult.Success(it.matches) } ?: TeamMatchesResult.Failed(FootballDataError.NETWORK)
        } catch (e: JSONException) {
            logger.warn(TAG, "Matchs d'équipe football-data.org illisibles", e)
            previous?.let { TeamMatchesResult.Success(it.matches) } ?: TeamMatchesResult.Failed(FootballDataError.SERVER)
        }
    }

    private fun parseMatches(json: JSONObject): List<ScheduledMatch> {
        val items = json.optJSONArray("matches") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index -> items.optJSONObject(index)?.let(::parseMatch) }
    }

    /** Classement d'un championnat ([Competition.apiId] requis), gardé [standingsCacheMillis]. */
    suspend fun standings(competition: Competition): StandingsResult = mutex.withLock {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withLock StandingsResult.Failed(FootballDataError.MISSING_KEY)
        val apiId = competition.apiId ?: return@withLock StandingsResult.Success(emptyList())
        val previous = cachedStandings[competition]?.takeIf { it.key == key }
        previous?.takeIf { clock() - it.at < standingsCacheMillis }?.let { return@withLock StandingsResult.Success(it.table) }
        try {
            val json = get(key, baseUrl.newBuilder().addPathSegment("competitions").addPathSegment(apiId.toString()).addPathSegment("standings").build())
            val table = parseStandings(json)
            cachedStandings[competition] = CachedStandings(key, clock(), table)
            StandingsResult.Success(table)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FootballDataException) {
            previous?.let { StandingsResult.Success(it.table) } ?: StandingsResult.Failed(e.error)
        } catch (e: IOException) {
            logger.warn(TAG, "football-data.org injoignable", e)
            previous?.let { StandingsResult.Success(it.table) } ?: StandingsResult.Failed(FootballDataError.NETWORK)
        } catch (e: JSONException) {
            logger.warn(TAG, "Classement football-data.org illisible", e)
            previous?.let { StandingsResult.Success(it.table) } ?: StandingsResult.Failed(FootballDataError.SERVER)
        }
    }

    private suspend fun fetch(key: String, from: Long, to: Long): List<ScheduledMatch> {
        val url = baseUrl.newBuilder().addPathSegment("matches")
            .addQueryParameter("competitions", Competition.entries.mapNotNull { it.apiId }.joinToString(","))
            .addQueryParameter("dateFrom", isoDay(from))
            .addQueryParameter("dateTo", isoDay(to))
            .build()
        return parseMatches(get(key, url))
    }

    private suspend fun get(key: String, url: HttpUrl): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", key)
            .header("Accept", "application/json")
            .build()
        val call = clients.client(RequestPurpose.METADATA).newCall(request)
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        return call.await().use { response ->
            when {
                // Jeton refusé (400 « invalid token », 401) ou compétition hors de l'offre (403).
                response.code == 400 || response.code == 401 || response.code == 403 -> throw FootballDataException(FootballDataError.INVALID_KEY)
                response.code == 429 -> throw FootballDataException(FootballDataError.RATE_LIMITED)
                !response.isSuccessful -> throw FootballDataException(FootballDataError.SERVER)
            }
            JSONObject(response.body.string())
        }
    }

    /** Classement général (« TOTAL ») ; pour une phase de groupes, les groupes à la suite. */
    private fun parseStandings(json: JSONObject): List<Standing> {
        val standings = json.optJSONArray("standings") ?: return emptyList()
        val total = (0 until standings.length()).mapNotNull { standings.optJSONObject(it) }
            .filter { it.optString("type", "TOTAL") == "TOTAL" }
        return total.flatMap { standing ->
            val table = standing.optJSONArray("table") ?: return@flatMap emptyList()
            (0 until table.length()).mapNotNull { index ->
                val row = table.optJSONObject(index) ?: return@mapNotNull null
                val team = row.optJSONObject("team")?.let(::parseTeam) ?: return@mapNotNull null
                Standing(
                    position = row.optInt("position"),
                    team = team,
                    played = row.optInt("playedGames"),
                    won = row.optInt("won"),
                    draw = row.optInt("draw"),
                    lost = row.optInt("lost"),
                    goalDifference = row.optInt("goalDifference"),
                    points = row.optInt("points"),
                )
            }
        }
    }

    private fun parseMatch(json: JSONObject): ScheduledMatch? {
        val competition = json.optJSONObject("competition")?.optInt("id")?.let(Competition::fromApiId) ?: return null
        val kickoff = parseUtc(json.optString("utcDate")) ?: return null
        val home = json.optJSONObject("homeTeam")?.let(::parseTeam) ?: return null
        val away = json.optJSONObject("awayTeam")?.let(::parseTeam) ?: return null
        // Score du moment pendant le match, score final ensuite.
        val score = json.optJSONObject("score")?.optJSONObject("fullTime")
        return ScheduledMatch(
            id = json.optLong("id"),
            competition = competition,
            kickoffMillis = kickoff,
            status = when (json.optString("status")) {
                "IN_PLAY", "PAUSED", "LIVE" -> MatchStatus.LIVE
                "FINISHED", "AWARDED" -> MatchStatus.FINISHED
                "POSTPONED", "SUSPENDED" -> MatchStatus.POSTPONED
                "CANCELLED" -> MatchStatus.CANCELLED
                else -> MatchStatus.SCHEDULED
            },
            home = home,
            away = away,
            homeScore = score?.score("home"),
            awayScore = score?.score("away"),
        )
    }

    private fun JSONObject.score(side: String): Int? = if (isNull(side) || !has(side)) null else optInt(side)

    /** Équipe connue ; `null` tant qu'elle n'est pas désignée (tirage à venir). */
    private fun parseTeam(json: JSONObject): Team? {
        val name = json.text("name") ?: return null
        return Team(
            name = name,
            shortName = json.text("shortName"),
            code = json.text("tla"),
            // Écussons au format SVG ou PNG : la version PNG s'affiche sans décodeur supplémentaire.
            crestUrl = json.text("crest")?.replace(SVG_EXTENSION, ".png"),
            id = json.text("id")?.toLongOrNull(),
        )
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun day(millis: Long): Long = millis - Math.floorMod(millis, DAY_MILLIS)

    private fun isoDay(millis: Long): String = utcFormat("yyyy-MM-dd").format(Date(millis))

    private fun parseUtc(value: String): Long? = try {
        utcFormat("yyyy-MM-dd'T'HH:mm:ss").parse(value.take(19))?.time
    } catch (e: ParseException) {
        null
    }

    private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private companion object {
        const val TAG = "FootballData"
        const val DAY_MILLIS = 86_400_000L
        const val TEAM_RANGE_MILLIS = 60 * DAY_MILLIS
        const val TEAM_MATCHES = 5
        val SVG_EXTENSION = Regex("""\.svg$""", RegexOption.IGNORE_CASE)
    }
}
