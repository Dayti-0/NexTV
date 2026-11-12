package tv.nextv.core.meta

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * @property trailerLanguage langue de cette bande-annonce (« fr », « en »…), vide si TMDB ne la donne pas.
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
    val trailerLanguage: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonCount: Int? = null,
) {
    val stars: Double? get() = rating?.takeIf { voteCount > 0 }?.let { it / 2 }
}

/** Acteur ou actrice de la distribution : nom, rôle et photo (TMDB). */
data class CastMember(val name: String, val character: String?, val photoUrl: String?)

/** Film ou série de la semaine sur TMDB (« Tendances »), à retrouver dans la playlist. */
data class TrendingTitle(
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val rating: Double?,
    val posterUrl: String?,
    val backdropUrl: String?,
)

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
    /** Clé de secours (fournie avec l'application), essayée si [apiKey] est refusée. */
    private val fallbackApiKey: () -> String? = { null },
    /** Traduction des résumés que TMDB n'a pas dans la langue demandée. */
    private val translator: Translator? = null,
) {

    private class TmdbException(val error: TmdbError) : Exception(error.name)
    private class NotFoundException : Exception()

    /** Bande-annonce dans la langue de l'interface (une VF, ou une VOSTFR qui a ses sous-titres dans l'image). */
    fun isInterfaceLanguage(trailerLanguage: String?): Boolean =
        !trailerLanguage.isNullOrEmpty() && trailerLanguage.substringBefore('-') == language.substringBefore('-')

    /** Recherche un film ou une série par titre (et année si connue), puis en lit la fiche. */
    /**
     * Fiche du film ou de la série [title]. [exact] : seulement un résultat portant exactement ce titre
     * (programmes du guide hors films et séries : pas de fiche approchante, donc pas de fausse image).
     */
    suspend fun lookup(type: MediaType, title: String, year: Int?, exact: Boolean = false): TmdbResult {
        val fallback = fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() }
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallback ?: return TmdbResult.Failed(TmdbError.MISSING_KEY)
        val result = lookupWith(key, type, title, year, exact)
        // Clé des réglages refusée (ancienne, mal recopiée…) : celle fournie avec l'application.
        if (result == TmdbResult.Failed(TmdbError.INVALID_KEY) && fallback != null && fallback != key) {
            logger.warn(TAG, "Clé TMDB des réglages refusée : clé fournie avec l'application")
            return lookupWith(fallback, type, title, year, exact)
        }
        return result
    }

    /**
     * Distribution du film ou de la série [tmdbId] (fiche déjà trouvée), par ordre d'importance, au plus
     * [limit] personnes. Série : toutes les saisons (rôle principal de chacun). Vide sans clé ou en cas
     * d'échec (la fiche s'affiche sans).
     */
    suspend fun credits(type: MediaType, tmdbId: Long, limit: Int = 15): List<CastMember> {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val path = if (type == MediaType.MOVIE) "movie/$tmdbId/credits" else "tv/$tmdbId/aggregate_credits"
        val url = baseUrl.newBuilder().addPathSegments(path).addQueryParameter("language", language).build()
        val json = try {
            get(key, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Distribution TMDB indisponible", e)
            return emptyList()
        }
        return json.optJSONArray("cast").objects()
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .mapNotNull { person ->
                val name = person.optStringOrNull("name") ?: return@mapNotNull null
                // Série (aggregate_credits) : le rôle le plus joué ; film : le rôle.
                val character = person.optStringOrNull("character")
                    ?: person.optJSONArray("roles").objects().maxByOrNull { it.optInt("episode_count") }?.optStringOrNull("character")
                // Mentions anglaises du rôle (« (voice) », « Self ») : en français.
                val role = if (language.startsWith("fr")) character?.let { Roles.toFrench(it, person.optInt("gender")) } else character
                CastMember(name, role, person.optStringOrNull("profile_path")?.let { "${imageBaseUrl}w185$it" })
            }
            .take(limit)
    }

    /**
     * Bande-annonce d'une saison de série (TMDB en a une par saison pour beaucoup de séries) : identifiant
     * YouTube et langue, ou `null` (pas de clé, saison sans bande-annonce, échec).
     */
    suspend fun seasonTrailer(tmdbId: Long, season: Int): Pair<String, String>? {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = baseUrl.newBuilder().addPathSegments("tv/$tmdbId/season/$season/videos")
            .addQueryParameter("language", language)
            .addQueryParameter("include_video_language", "${language.substringBefore('-')},en,null")
            .build()
        val json = try {
            get(key, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Saison inconnue de TMDB (404) ou réseau : la bande-annonce de la série reste.
            return null
        }
        val trailer = trailer(json.optJSONArray("results")) ?: return null
        return trailer.optString("key") to trailer.optStringOrNull("iso_639_1").orEmpty()
    }

    /**
     * Bandes-annonces et teasers YouTube d'un film ou d'une série, la meilleure d'abord (même ordre que
     * [MediaDetails.trailerYoutubeKey]) : identifiant et langue. Pour en essayer une autre quand la première ne se lit
     * pas (vidéo retirée, réservée à un pays ou aux adultes, lecture intégrée refusée). Liste vide sans clé ou en cas d'échec.
     */
    suspend fun trailers(type: MediaType, tmdbId: Long, limit: Int = 8): List<Pair<String, String>> {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val path = if (type == MediaType.MOVIE) "movie/$tmdbId/videos" else "tv/$tmdbId/videos"
        val url = baseUrl.newBuilder().addPathSegments(path)
            .addQueryParameter("language", language)
            .addQueryParameter("include_video_language", "${language.substringBefore('-')},en,null")
            .build()
        val json = try {
            get(key, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        return rankedTrailers(json.optJSONArray("results"))
            .map { it.optString("key") to it.optStringOrNull("iso_639_1").orEmpty() }
            .distinctBy { it.first }
            .take(limit)
    }

    /**
     * Films ou séries les plus regardés de la semaine (deux pages, une quarantaine de titres) ;
     * liste vide sans clé ou en cas d'échec.
     */
    suspend fun trending(type: MediaType, pages: Int = 2): List<TrendingTitle> {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val path = if (type == MediaType.MOVIE) "trending/movie/week" else "trending/tv/week"
        val titles = mutableListOf<TrendingTitle>()
        for (page in 1..pages) {
            val url = baseUrl.newBuilder().addPathSegments(path)
                .addQueryParameter("language", language)
                .addQueryParameter("page", page.toString())
                .build()
            val json = try {
                get(key, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Tendances TMDB indisponibles", e)
                break
            }
            json.optJSONArray("results").objects().mapNotNullTo(titles) { item -> titleOf(type, item) }
        }
        return titles
    }

    /**
     * Titres recommandés par TMDB pour le film ou la série [tmdbId] (proches par le genre, le ton, le public :
     * ceux que regardent les mêmes spectateurs), deux pages au plus. Vide sans clé ou en cas d'échec.
     */
    suspend fun recommendations(type: MediaType, tmdbId: Long, pages: Int = 2): List<TrendingTitle> {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val path = if (type == MediaType.MOVIE) "movie/$tmdbId/recommendations" else "tv/$tmdbId/recommendations"
        val titles = mutableListOf<TrendingTitle>()
        for (page in 1..pages) {
            val url = baseUrl.newBuilder().addPathSegments(path)
                .addQueryParameter("language", language)
                .addQueryParameter("page", page.toString())
                .build()
            val json = try {
                get(key, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Recommandations TMDB indisponibles", e)
                break
            }
            json.optJSONArray("results").objects().mapNotNullTo(titles) { item -> titleOf(type, item) }
            if (page >= json.optInt("total_pages", 1)) break
        }
        return titles
    }

    private fun titleOf(type: MediaType, item: JSONObject): TrendingTitle? {
        val title = item.optStringOrNull(if (type == MediaType.MOVIE) "title" else "name") ?: return null
        return TrendingTitle(
            title = title,
            originalTitle = item.optStringOrNull(if (type == MediaType.MOVIE) "original_title" else "original_name"),
            year = item.optStringOrNull(if (type == MediaType.MOVIE) "release_date" else "first_air_date")?.take(4)?.toIntOrNull(),
            overview = item.optStringOrNull("overview"),
            rating = item.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            posterUrl = item.optStringOrNull("poster_path")?.let { "${imageBaseUrl}w500$it" },
            backdropUrl = item.optStringOrNull("backdrop_path")?.let { "${imageBaseUrl}w1280$it" },
        )
    }

    private suspend fun lookupWith(key: String, type: MediaType, title: String, year: Int?, exact: Boolean = false): TmdbResult {
        return try {
            // Titre tel quel, puis sans ses parties entre parenthèses ou crochets ; avec l'année, puis sans.
            val queries = listOf(title, title.replace(BRACKETED, " ").replace(SPACES, " ").trim())
                .filter { it.isNotBlank() }
                .distinct()
            var id: Long? = null
            for (query in queries) {
                id = search(key, type, query, year, exact) ?: year?.let { search(key, type, query, null, exact) }
                if (id != null) break
            }
            if (id == null) return TmdbResult.NotFound
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

    private suspend fun search(key: String, type: MediaType, title: String, year: Int?, exact: Boolean = false): Long? {
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
        var best: JSONObject? = if (exact) null else results.getJSONObject(0)
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            val names = listOf("title", "original_title", "name", "original_name").map { normalize(candidate.optString(it)) }
            if (wanted in names) {
                best = candidate
                break
            }
        }
        return best?.optLong("id")?.takeIf { it > 0 }
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
            // Pas de résumé traduit : résumé anglais, traduit automatiquement (l'anglais s'il n'y a pas de traduction).
            val english = baseUrl.newBuilder().addPathSegments(path).addQueryParameter("language", "en-US").build()
            overview = runCatching { get(key, english).optStringOrNull("overview") }.getOrNull()
                ?.let { text -> translator?.translate(text, from = "en", to = language.substringBefore('-')) ?: text }
        }
        val date = json.optStringOrNull(if (type == MediaType.MOVIE) "release_date" else "first_air_date")
        val trailer = trailer(json.optJSONObject("videos")?.optJSONArray("results"))
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
            trailerYoutubeKey = trailer?.optString("key"),
            trailerLanguage = trailer?.let { it.optStringOrNull("iso_639_1").orEmpty() },
            runtimeMinutes = if (type == MediaType.MOVIE) {
                json.optInt("runtime").takeIf { it > 0 }
            } else {
                json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            },
            seasonCount = if (type == MediaType.SERIES) json.optInt("number_of_seasons").takeIf { it > 0 } else null,
        )
    }

    /**
     * Bande-annonce YouTube, de préférence dans la langue de l'interface, et doublée (VF) plutôt
     * que sous-titrée (VOSTFR).
     */
    private fun trailer(videos: JSONArray?): JSONObject? = rankedTrailers(videos).firstOrNull()

    /**
     * Bandes-annonces et teasers YouTube, dans la langue de l'interface d'abord (un teaser en français passe avant
     * une bande-annonce en anglais), puis les bandes-annonces avant les teasers, doublés plutôt que sous-titrés.
     */
    private fun rankedTrailers(videos: JSONArray?): List<JSONObject> {
        val candidates = videos.objects().filter { it.optString("site").equals("YouTube", ignoreCase = true) && it.optStringOrNull("key") != null }
        val lang = language.substringBefore('-')
        val ranked = candidates.sortedWith(
            compareByDescending<JSONObject> { it.optString("iso_639_1") == lang }
                .thenByDescending { it.optString("type") == "Trailer" }
                .thenByDescending { !SUBTITLED.containsMatchIn(it.optString("name")) }
                .thenByDescending { it.optBoolean("official") },
        )
        return ranked.filter { it.optString("type") == "Trailer" || it.optString("type") == "Teaser" }
    }

    private suspend fun get(key: String, url: HttpUrl): JSONObject {
        val bearer = key.startsWith("eyJ")
        val request = Request.Builder()
            .url(if (bearer) url else url.newBuilder().addQueryParameter("api_key", key).build())
            .header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()
        // Réponse lue hors du fil principal (le corps se lit sur le réseau, interdit sur le fil principal).
        return withContext(Dispatchers.IO) {
            clients.client(RequestPurpose.METADATA).newCall(request).await().use { response ->
                when {
                    response.code == 401 -> throw TmdbException(TmdbError.INVALID_KEY)
                    response.code == 404 -> throw NotFoundException()
                    response.code == 429 -> throw TmdbException(TmdbError.RATE_LIMITED)
                    !response.isSuccessful -> throw TmdbException(TmdbError.SERVER)
                }
                JSONObject(response.body.string())
            }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(NON_ALPHANUMERIC, "")

    private companion object {
        const val TAG = "Tmdb"

        /** Bande-annonce sous-titrée : « VOSTFR », « VOST », « sous-titré(e) », « subtitled ». */
        val SUBTITLED = Regex("""\bvost|sous[- ]titr|subtitled""", RegexOption.IGNORE_CASE)
        val BRACKETED = Regex("""\s*[(\[][^)\]]*[)\]]?""")
        val SPACES = Regex("""\s{2,}""")
        val MARKS = Regex("\\p{Mn}+")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    }
}
