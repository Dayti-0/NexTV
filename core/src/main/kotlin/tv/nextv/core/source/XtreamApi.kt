package tv.nextv.core.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
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

    private suspend fun <T : Any> call(source: PlaylistSource, action: String?, timeoutMillis: Long, read: (Response) -> T?): T? {
        val account = (source.location as? SourceLocation.Remote)?.url?.let(XtreamAccount::parse) ?: return null
        for (root in (listOf(account.server) + source.mirrors).distinct()) {
            val url = root.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("player_api.php")
                ?.addQueryParameter("username", account.username)
                ?.addQueryParameter("password", account.password)
                ?.apply { if (action != null) addQueryParameter("action", action) }
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

        private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

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
