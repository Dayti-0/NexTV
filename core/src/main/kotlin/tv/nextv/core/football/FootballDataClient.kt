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
data class Team(val name: String, val shortName: String?, val code: String?, val crestUrl: String?) {
    /** Nom affiché : le nom court s'il existe (« PSG », « Marseille »). */
    val displayName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

enum class MatchStatus { SCHEDULED, LIVE, FINISHED, POSTPONED, CANCELLED }

/** Match programmé, d'après football-data.org. */
data class ScheduledMatch(
    val id: Long,
    val competition: Competition,
    val kickoffMillis: Long,
    val status: MatchStatus,
    val home: Team,
    val away: Team,
)

enum class FootballDataError { MISSING_KEY, INVALID_KEY, RATE_LIMITED, NETWORK, SERVER }

sealed interface FootballDataResult {
    data class Success(val matches: List<ScheduledMatch>) : FootballDataResult
    data class Failed(val error: FootballDataError) : FootballDataResult
}

/**
 * Matchs des compétitions couvertes par l'offre gratuite de football-data.org (API v4).
 * La clé (« jeton », en-tête `X-Auth-Token`) n'apparaît jamais dans les journaux.
 * L'offre gratuite limite le nombre d'appels : les résultats sont gardés [cacheMillis].
 */
class FootballDataClient(
    private val clients: HttpClientProvider,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = "https://api.football-data.org/v4/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30 * 60_000L,
    /** Au-delà, la section Foot n'attend plus le service et se rabat sur le guide. */
    private val callTimeoutMillis: Long = 10_000,
) {
    private class FootballDataException(val error: FootballDataError) : Exception(error.name)

    private val mutex = Mutex()
    private var cached: Cached? = null

    private class Cached(val key: String, val from: Long, val to: Long, val at: Long, val matches: List<ScheduledMatch>)

    /**
     * Matchs entre [fromMillis] et [toMillis] (10 jours au plus). Une réponse récente pour la même
     * période est réutilisée ; en cas d'échec, la dernière réponse obtenue reste servie.
     */
    suspend fun matches(fromMillis: Long, toMillis: Long): FootballDataResult = mutex.withLock {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withLock FootballDataResult.Failed(FootballDataError.MISSING_KEY)
        val from = day(fromMillis)
        val to = day(toMillis)
        cached?.takeIf { it.key == key && it.from == from && it.to == to && clock() - it.at < cacheMillis }?.let {
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

    private fun stale(key: String): FootballDataResult? = cached?.takeIf { it.key == key }?.let { FootballDataResult.Success(it.matches) }

    private suspend fun fetch(key: String, from: Long, to: Long): List<ScheduledMatch> {
        val url = baseUrl.newBuilder().addPathSegment("matches")
            .addQueryParameter("competitions", Competition.entries.mapNotNull { it.apiId }.joinToString(","))
            .addQueryParameter("dateFrom", isoDay(from))
            .addQueryParameter("dateTo", isoDay(to))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", key)
            .header("Accept", "application/json")
            .build()
        val call = clients.client(RequestPurpose.METADATA).newCall(request)
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        val json = call.await().use { response ->
            when {
                // Jeton refusé (400 « invalid token », 401) ou compétition hors de l'offre (403).
                response.code == 400 || response.code == 401 || response.code == 403 -> throw FootballDataException(FootballDataError.INVALID_KEY)
                response.code == 429 -> throw FootballDataException(FootballDataError.RATE_LIMITED)
                !response.isSuccessful -> throw FootballDataException(FootballDataError.SERVER)
            }
            JSONObject(response.body.string())
        }
        val items = json.optJSONArray("matches") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index -> items.optJSONObject(index)?.let(::parseMatch) }
    }

    private fun parseMatch(json: JSONObject): ScheduledMatch? {
        val competition = json.optJSONObject("competition")?.optInt("id")?.let(Competition::fromApiId) ?: return null
        val kickoff = parseUtc(json.optString("utcDate")) ?: return null
        val home = json.optJSONObject("homeTeam")?.let(::parseTeam) ?: return null
        val away = json.optJSONObject("awayTeam")?.let(::parseTeam) ?: return null
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
        )
    }

    /** Équipe connue ; `null` tant qu'elle n'est pas désignée (tirage à venir). */
    private fun parseTeam(json: JSONObject): Team? {
        val name = json.text("name") ?: return null
        return Team(
            name = name,
            shortName = json.text("shortName"),
            code = json.text("tla"),
            // Écussons au format SVG ou PNG : la version PNG s'affiche sans décodeur supplémentaire.
            crestUrl = json.text("crest")?.replace(Regex("""\.svg$""", RegexOption.IGNORE_CASE), ".png"),
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
    }
}
