package tv.nextv.core.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit

/** Abonnement d'un compte Xtream Codes, tel que le serveur le décrit (`player_api.php`). */
data class XtreamAccountInfo(
    /** Identifiants acceptés par le serveur. */
    val authenticated: Boolean,
    /** État annoncé par le serveur (« Active », « Expired », « Banned », « Disabled »…). */
    val status: String?,
    /** Fin de l'abonnement, `null` : sans date de fin. */
    val expiresAtMillis: Long?,
    val trial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    /** Fuseau horaire du serveur (« Europe/Paris ») : heures de début des programmes en replay. */
    val serverTimeZone: String?,
) {
    val active: Boolean get() = authenticated && (status == null || status.equals("Active", ignoreCase = true))
}

/** Image d'un film décrite par le serveur Xtream (`get_vod_info`) ; tailles `null` si le serveur ne les donne pas. */
data class VodVideo(val width: Int?, val height: Int?) {
    val unknown: Boolean get() = width == null && height == null
}

/** Série du catalogue Xtream (`get_series`) : son identifiant et son nom tel que le serveur l'écrit. */
data class XtreamSeries(val id: String, val name: String)

/** Programme d'une chaîne d'après le guide du compte Xtream (`get_short_epg`). */
data class XtreamProgramme(val title: String, val description: String?, val startMillis: Long, val stopMillis: Long)

/**
 * Interface `player_api.php` des serveurs Xtream Codes, interrogée sur le serveur de la playlist
 * puis sur ses serveurs de secours. Les identifiants font partie de l'adresse appelée : elle n'est
 * jamais écrite dans les journaux.
 */
class XtreamApi(
    private val clients: HttpClientProvider,
    private val logger: Logger = Logger.None,
    private val callTimeoutMillis: Long = 15_000,
    /** Liste des chaînes : plusieurs mégaoctets pour les grosses playlists. */
    private val listTimeoutMillis: Long = 45_000,
) {

    /** Abonnement du compte d'une playlist Xtream ; `null` si ce n'en est pas une ou si aucun serveur ne répond. */
    suspend fun accountInfo(source: PlaylistSource): XtreamAccountInfo? =
        call(source, action = null, callTimeoutMillis) { parseAccountInfo(it.body.string()) }

    /**
     * Jours de replay des chaînes d'une playlist Xtream, par identifiant de flux (`get_live_streams`) ;
     * `null` si ce n'est pas un compte Xtream ou si aucun serveur ne répond.
     */
    suspend fun archiveDays(source: PlaylistSource): Map<String, Int>? =
        call(source, action = "get_live_streams", listTimeoutMillis) { response -> response.body.charStream().use(::readArchiveDays) }

    /**
     * Prochains programmes ([limit]) d'une chaîne d'après le guide du fournisseur, par l'identifiant de
     * son flux : celui des chaînes « événement » (« FR| DAZN LALIGA 2 (LIVE MATCH ONLY) ») peut annoncer le
     * match qu'elles diffusent. `null` si ce n'est pas un compte Xtream ou si aucun serveur ne répond.
     */
    suspend fun shortEpg(source: PlaylistSource, streamId: String, limit: Int = 8): List<XtreamProgramme>? =
        call(source, action = "get_short_epg", callTimeoutMillis, mapOf("stream_id" to streamId, "limit" to limit.toString())) {
            parseShortEpg(it.body.string())
        }

    /**
     * Taille de l'image d'un film d'après sa fiche sur le serveur (`get_vod_info`, `info.video`), par l'identifiant
     * de son flux. [VodVideo.unknown] : le serveur répond sans la donner ; `null` : pas de compte Xtream ou pas de réponse.
     */
    suspend fun vodVideo(source: PlaylistSource, streamId: String): VodVideo? =
        call(source, action = "get_vod_info", callTimeoutMillis, mapOf("vod_id" to streamId)) { parseVodVideo(it.body.string()) }

    /** Catégories de séries du compte (identifiant → nom) ; `null` : pas un compte Xtream ou pas de réponse. */
    suspend fun seriesCategories(source: PlaylistSource): Map<String, String>? =
        call(source, action = "get_series_categories", callTimeoutMillis) { parseCategories(it.body.string()) }

    /**
     * Séries d'une catégorie ([categoryId]), ou de tout le catalogue (`null` : plusieurs mégaoctets pour un gros
     * catalogue) ; `null` : pas un compte Xtream ou pas de réponse.
     */
    suspend fun series(source: PlaylistSource, categoryId: String?): List<XtreamSeries>? =
        call(source, action = "get_series", listTimeoutMillis, categoryId?.let { mapOf("category_id" to it) }.orEmpty()) {
            parseSeries(it.body.string())
        }

    /**
     * Durée des épisodes d'une série (`get_series_info`), en secondes, par identifiant de flux de l'épisode (celui de
     * son adresse, « …/series/…/12345.mkv ») ; `null` : pas de compte Xtream ou pas de réponse.
     */
    suspend fun episodeDurations(source: PlaylistSource, seriesId: String): Map<String, Int>? =
        call(source, action = "get_series_info", callTimeoutMillis, mapOf("series_id" to seriesId)) { parseEpisodeDurations(it.body.string()) }

    private suspend fun <T : Any> call(
        source: PlaylistSource,
        action: String?,
        timeoutMillis: Long,
        parameters: Map<String, String> = emptyMap(),
        read: (Response) -> T?,
    ): T? {
        val account = (source.location as? SourceLocation.Remote)?.url?.let(XtreamAccount::parse) ?: return null
        for (root in (listOf(account.server) + source.mirrors).distinct()) {
            val url = root.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("player_api.php")
                ?.addQueryParameter("username", account.username)
                ?.addQueryParameter("password", account.password)
                ?.apply { if (action != null) addQueryParameter("action", action) }
                ?.apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
                ?.build() ?: continue
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .apply { if (!source.userAgent.isNullOrBlank()) header("User-Agent", source.userAgent) }
                .build()
            try {
                val call = clients.client(RequestPurpose.PLAYLIST).newCall(request)
                call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
                val result = withContext(Dispatchers.IO) {
                    call.await().use { response -> if (response.isSuccessful) read(response) else null }
                }
                if (result != null) return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn(TAG, "Serveur Xtream injoignable", e)
            }
        }
        return null
    }

    internal companion object {
        private const val TAG = "XtreamApi"

        fun parseAccountInfo(body: String): XtreamAccountInfo? = try {
            val json = JSONObject(body)
            json.optJSONObject("user_info")?.let { user ->
                val server = json.optJSONObject("server_info")
                XtreamAccountInfo(
                    authenticated = user.text("auth").let { it == "1" || it.equals("true", ignoreCase = true) },
                    status = user.text("status"),
                    expiresAtMillis = user.number("exp_date")?.takeIf { it > 0 }?.times(1000),
                    trial = user.number("is_trial") == 1L,
                    activeConnections = user.number("active_cons")?.toInt(),
                    maxConnections = user.number("max_connections")?.toInt(),
                    serverTimeZone = server?.text("timezone"),
                )
            }
        } catch (e: JSONException) {
            null
        }

        /** Réponse de `get_vod_info` : `{"info":{"video":{"width":1920,"height":1080,…},…},…}`. `null` : illisible. */
        fun parseVodVideo(body: String): VodVideo? = try {
            val info = JSONObject(body).optJSONObject("info")
            // Sans fiche, ou fiche sans image décrite (« video »: [] chez certains serveurs).
            val video = info?.optJSONObject("video")
            VodVideo(video?.number("width")?.toInt()?.takeIf { it > 0 }, video?.number("height")?.toInt()?.takeIf { it > 0 })
        } catch (e: JSONException) {
            null
        }

        /** Réponse de `get_series_categories` : `[{"category_id":"12","category_name":"Séries VF"},…]`. */
        fun parseCategories(body: String): Map<String, String>? = try {
            val array = org.json.JSONArray(body)
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.text("category_id") ?: continue
                    put(id, item.text("category_name") ?: continue)
                }
            }
        } catch (e: JSONException) {
            null
        }

        /** Réponse de `get_series` : `[{"series_id":345,"name":"…",…},…]`. */
        fun parseSeries(body: String): List<XtreamSeries>? = try {
            val array = org.json.JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val id = item.text("series_id") ?: return@mapNotNull null
                XtreamSeries(id, item.text("name") ?: return@mapNotNull null)
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse de `get_series_info` : `{"episodes":{"1":[{"id":"12345","info":{"duration_secs":3120,"duration":"00:52:00"}}]}}`
         * (les épisodes sont parfois un tableau de tableaux). Épisodes sans durée : absents. `null` : illisible.
         */
        fun parseEpisodeDurations(body: String): Map<String, Int>? = try {
            val episodes = JSONObject(body).opt("episodes")
            val seasons: List<org.json.JSONArray> = when (episodes) {
                is JSONObject -> episodes.keys().asSequence().mapNotNull { episodes.optJSONArray(it) }.toList()
                is org.json.JSONArray -> (0 until episodes.length()).mapNotNull { episodes.optJSONArray(it) }
                else -> emptyList()
            }
            buildMap {
                for (season in seasons) {
                    for (i in 0 until season.length()) {
                        val episode = season.optJSONObject(i) ?: continue
                        val id = episode.text("id") ?: continue
                        val info = episode.optJSONObject("info") ?: continue
                        val seconds = info.number("duration_secs")?.toInt()?.takeIf { it > 0 }
                            ?: info.text("duration")?.let(::clockSeconds)
                        if (seconds != null && seconds > 0) put(id, seconds)
                    }
                }
            }
        } catch (e: JSONException) {
            null
        }

        /** « 00:52:10 » ou « 52:10 » → secondes ; `null` si illisible. */
        private fun clockSeconds(text: String): Int? {
            val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> null
            }
        }

        private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

        /**
         * Réponse de `get_short_epg` : `{"epg_listings":[{"title":"…","start_timestamp":"…",…}]}`, titres et
         * descriptions en base64 (ou en clair selon les serveurs). `null` si la réponse est illisible.
         */
        fun parseShortEpg(body: String): List<XtreamProgramme>? = try {
            val listings = JSONObject(body).optJSONArray("epg_listings")
            if (listings == null) {
                emptyList()
            } else {
                (0 until listings.length()).mapNotNull { index ->
                    val listing = listings.optJSONObject(index) ?: return@mapNotNull null
                    val title = listing.text("title")?.let(::plainText) ?: return@mapNotNull null
                    val start = listing.number("start_timestamp")?.times(1000) ?: return@mapNotNull null
                    val stop = listing.number("stop_timestamp")?.times(1000) ?: return@mapNotNull null
                    if (stop <= start) return@mapNotNull null
                    XtreamProgramme(title, listing.text("description")?.let(::plainText), start, stop)
                }
            }
        } catch (e: JSONException) {
            null
        }

        private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

        /** Texte en base64 décodé ; tel quel s'il n'en est pas (serveurs qui l'envoient en clair). */
        fun plainText(value: String): String? {
            val decoded = value.takeIf { it.length % 4 == 0 && BASE64.matches(it) }
                ?.decodeBase64()
                ?.utf8()
                ?.takeIf { text -> '\uFFFD' !in text && text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } }
            return (decoded ?: value).trim().takeIf { it.isNotEmpty() }
        }

        /** Nombre donné tel quel ou entre guillemets (« "1" »), selon les serveurs. */
        private fun JSONObject.number(name: String): Long? = text(name)?.toLongOrNull()

        /**
         * Jours de replay par flux, lus au fil de la réponse de `get_live_streams` (tableau d'objets
         * dont seuls `stream_id`, `tv_archive` et `tv_archive_duration` sont retenus) : la liste
         * entière n'est jamais en mémoire. `null` si la réponse n'est pas un tableau.
         */
        fun readArchiveDays(reader: Reader): Map<String, Int>? {
            val json = JsonScanner(reader)
            if (json.nextToken() != '[') return null
            val days = HashMap<String, Int>()
            while (true) {
                when (json.nextToken()) {
                    ',' -> continue
                    '{' -> {
                        var id: String? = null
                        var archive: String? = null
                        var duration: String? = null
                        while (true) {
                            val token = json.nextToken()
                            if (token == '}' || token == null) break
                            if (token != '"') continue
                            val key = json.readString()
                            if (json.nextToken() != ':') break
                            val value = json.readScalar()
                            when (key) {
                                "stream_id" -> id = value
                                "tv_archive" -> archive = value
                                "tv_archive_duration" -> duration = value
                            }
                        }
                        val count = duration?.toDoubleOrNull()?.toInt() ?: 0
                        val recorded = archive?.toDoubleOrNull()?.toInt() == 1 || archive.equals("true", ignoreCase = true)
                        if (id != null && recorded && count > 0) days[id] = count
                    }
                    else -> break
                }
            }
            return days
        }
    }
}

/** Lecture minimale de JSON caractère par caractère (sans tout charger en mémoire). */
private class JsonScanner(reader: Reader) {
    private val input = reader.buffered()
    private var pending = -1

    private fun read(): Int = if (pending >= 0) pending.also { pending = -1 } else input.read()

    /** Prochain caractère significatif (hors espaces) ; `null` en fin de texte. */
    fun nextToken(): Char? {
        while (true) {
            val c = read()
            if (c < 0) return null
            if (!c.toChar().isWhitespace()) return c.toChar()
        }
    }

    /** Chaîne dont le guillemet ouvrant vient d'être lu. */
    fun readString(): String {
        val text = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0 || c == '"'.code) return text.toString()
            if (c != '\\'.code) {
                text.append(c.toChar())
                continue
            }
            when (val escaped = read()) {
                'n'.code -> text.append('\n')
                't'.code -> text.append('\t')
                'r'.code -> text.append('\r')
                'b'.code -> text.append('\b')
                'f'.code -> text.append('\u000C')
                'u'.code -> {
                    val hex = CharArray(4) { read().coerceAtLeast(0).toChar() }
                    String(hex).toIntOrNull(16)?.let { text.append(it.toChar()) }
                }
                -1 -> return text.toString()
                else -> text.append(escaped.toChar())
            }
        }
    }

    /** Valeur simple (texte, nombre, booléen) ; un objet ou un tableau est sauté et donne `null`. */
    fun readScalar(): String? {
        val first = nextToken() ?: return null
        return when (first) {
            '"' -> readString()
            '{', '[' -> {
                skipNested()
                null
            }
            else -> {
                val text = StringBuilder().append(first)
                while (true) {
                    val c = read()
                    if (c < 0) break
                    val char = c.toChar()
                    if (char == ',' || char == '}' || char == ']' || char.isWhitespace()) {
                        pending = c
                        break
                    }
                    text.append(char)
                }
                text.toString().takeUnless { it == "null" }
            }
        }
    }

    /** Saute la fin d'un objet ou d'un tableau dont le premier caractère vient d'être lu. */
    private fun skipNested() {
        var depth = 1
        while (depth > 0) {
            when (read()) {
                -1 -> return
                '"'.code -> readString()
                '{'.code, '['.code -> depth++
                '}'.code, ']'.code -> depth--
            }
        }
    }
}
