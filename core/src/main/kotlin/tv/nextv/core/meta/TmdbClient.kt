package tv.nextv.core.meta

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.text.Normalizer

enum class MediaType { MOVIE, SERIES }

/**
 * Fiche d'un film ou d'une série (The Movie Database).
 *
 * @property rating note moyenne sur 10 ([stars] : sur 5).
 * @property trailerYoutubeKey identifiant YouTube de la bande-annonce, si TMDB en connaît une.
 */
data class MediaDetails(
    val tmdbId: Long,
    val type: MediaType,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val voteCount: Int = 0,
    val genres: List<String> = emptyList(),
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val trailerYoutubeKey: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonCount: Int? = null,
) {
    val stars: Double? get() = rating?.takeIf { voteCount > 0 }?.let { it / 2 }
}

enum class TmdbError { MISSING_KEY, INVALID_KEY, RATE_LIMITED, NETWORK, SERVER }

sealed interface TmdbResult {
    data class Found(val details: MediaDetails) : TmdbResult
    data object NotFound : TmdbResult
    data class Failed(val error: TmdbError) : TmdbResult
}

/**
 * Client TMDB (API v3). La clé peut être une clé d'API v3 (paramètre `api_key`) ou un jeton
 * de lecture v4 (« eyJ… », en-tête `Authorization: Bearer`). Elle n'apparaît jamais dans les
 * journaux (paramètre masqué par le [tv.nextv.core.log.Redactor]).
 */
class TmdbClient(
    private val clients: HttpClientProvider,
    private val apiKey: () -> String?,
    private val language: String = "fr-FR",
    private val baseUrl: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl(),
    private val imageBaseUrl: String = "https://image.tmdb.org/t/p/",
    private val logger: Logger = Logger.None,
) {

    private class TmdbException(val error: TmdbError) : Exception(error.name)
    private class NotFoundException : Exception()

    /** Recherche un film ou une série par titre (et année si connue), puis en lit la fiche. */
    suspend fun lookup(type: MediaType, title: String, year: Int?): TmdbResult {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return TmdbResult.Failed(TmdbError.MISSING_KEY)
        return try {
            val id = search(key, type, title, year) ?: year?.let { search(key, type, title, null) }
                ?: return TmdbResult.NotFound
            TmdbResult.Found(details(key, type, id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbException) {
            TmdbResult.Failed(e.error)
        } catch (e: NotFoundException) {
            TmdbResult.NotFound
        } catch (e: IOException) {
            logger.warn(TAG, "TMDB injoignable pour « $title »", e)
            TmdbResult.Failed(TmdbError.NETWORK)
        } catch (e: JSONException) {
            logger.warn(TAG, "Réponse TMDB illisible pour « $title »", e)
            TmdbResult.Failed(TmdbError.SERVER)
        }
    }

    private suspend fun search(key: String, type: MediaType, title: String, year: Int?): Long? {
        val path = if (type == MediaType.MOVIE) "search/movie" else "search/tv"
        val url = baseUrl.newBuilder().addPathSegments(path)
            .addQueryParameter("query", title)
            .addQueryParameter("language", language)
            .addQueryParameter("include_adult", "false")
            .apply {
                if (year != null) addQueryParameter(if (type == MediaType.MOVIE) "year" else "first_air_date_year", year.toString())
            }
            .build()
        val results = get(key, url).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val wanted = normalize(title)
        var best: JSONObject = results.getJSONObject(0)
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            val names = listOf("title", "original_title", "name", "original_name").map { normalize(candidate.optString(it)) }
            if (wanted in names) {
                best = candidate
                break
            }
        }
        return best.optLong("id").takeIf { it > 0 }
    }

    private suspend fun details(key: String, type: MediaType, id: Long): MediaDetails {
        val path = if (type == MediaType.MOVIE) "movie/$id" else "tv/$id"
        val url = baseUrl.newBuilder().addPathSegments(path)
            .addQueryParameter("language", language)
            .addQueryParameter("append_to_response", "videos")
            .addQueryParameter("include_video_language", "${language.substringBefore('-')},en,null")
            .build()
        val json = get(key, url)
        var overview = json.optStringOrNull("overview")
        if (overview == null && !language.startsWith("en")) {
            // Pas de résumé traduit : résumé original anglais plutôt que rien.
            val english = baseUrl.newBuilder().addPathSegments(path).addQueryParameter("language", "en-US").build()
            overview = runCatching { get(key, english).optStringOrNull("overview") }.getOrNull()
        }
        val date = json.optStringOrNull(if (type == MediaType.MOVIE) "release_date" else "first_air_date")
        return MediaDetails(
            tmdbId = id,
            type = type,
            title = json.optStringOrNull(if (type == MediaType.MOVIE) "title" else "name") ?: "",
            originalTitle = json.optStringOrNull(if (type == MediaType.MOVIE) "original_title" else "original_name"),
            overview = overview,
            tagline = json.optStringOrNull("tagline"),
            year = date?.take(4)?.toIntOrNull(),
            rating = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            voteCount = json.optInt("vote_count"),
            genres = json.optJSONArray("genres").objects().mapNotNull { it.optStringOrNull("name") },
            posterUrl = json.optStringOrNull("poster_path")?.let { "${imageBaseUrl}w500$it" },
            backdropUrl = json.optStringOrNull("backdrop_path")?.let { "${imageBaseUrl}w1280$it" },
            trailerYoutubeKey = trailerKey(json.optJSONObject("videos")?.optJSONArray("results")),
            runtimeMinutes = if (type == MediaType.MOVIE) {
                json.optInt("runtime").takeIf { it > 0 }
            } else {
                json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            },
            seasonCount = if (type == MediaType.SERIES) json.optInt("number_of_seasons").takeIf { it > 0 } else null,
        )
    }

    /** Bande-annonce YouTube, de préférence dans la langue de l'interface. */
    private fun trailerKey(videos: JSONArray?): String? {
        val candidates = videos.objects().filter { it.optString("site").equals("YouTube", ignoreCase = true) && it.optStringOrNull("key") != null }
        val lang = language.substringBefore('-')
        val ranked = candidates.sortedWith(
            compareByDescending<JSONObject> { it.optString("type") == "Trailer" }
                .thenByDescending { it.optString("iso_639_1") == lang }
                .thenByDescending { it.optBoolean("official") },
        )
        return ranked.firstOrNull { it.optString("type") == "Trailer" || it.optString("type") == "Teaser" }?.optString("key")
    }

    private suspend fun get(key: String, url: HttpUrl): JSONObject {
        val bearer = key.startsWith("eyJ")
        val request = Request.Builder()
            .url(if (bearer) url else url.newBuilder().addQueryParameter("api_key", key).build())
            .header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()
        clients.client(RequestPurpose.METADATA).newCall(request).await().use { response ->
            when {
                response.code == 401 -> throw TmdbException(TmdbError.INVALID_KEY)
                response.code == 404 -> throw NotFoundException()
                response.code == 429 -> throw TmdbException(TmdbError.RATE_LIMITED)
                !response.isSuccessful -> throw TmdbException(TmdbError.SERVER)
            }
            return JSONObject(response.body.string())
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "")

    private companion object {
        const val TAG = "Tmdb"
    }
}
