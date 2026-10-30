package tv.nextv.core.source

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
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
    /** Fuseau horaire du serveur (« Europe/Paris »). */
    val serverTimeZone: String?,
) {
    val active: Boolean get() = authenticated && (status == null || status.equals("Active", ignoreCase = true))
}

/**
 * Interface `player_api.php` des serveurs Xtream Codes. Les identifiants font partie de l'adresse
 * appelée : elle n'est jamais écrite dans les journaux.
 */
class XtreamApi(
    private val clients: HttpClientProvider,
    private val logger: Logger = Logger.None,
    private val callTimeoutMillis: Long = 15_000,
) {

    /**
     * Abonnement du compte d'une playlist Xtream (lien `get.php`), demandé à son serveur puis à ses
     * serveurs de secours ; `null` si la playlist n'est pas un compte Xtream ou si aucun serveur ne répond.
     */
    suspend fun accountInfo(source: PlaylistSource): XtreamAccountInfo? {
        val account = (source.location as? SourceLocation.Remote)?.url?.let(XtreamAccount::parse) ?: return null
        for (root in (listOf(account.server) + source.mirrors).distinct()) {
            try {
                fetch(root, account, source.userAgent)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn(TAG, "Serveur Xtream injoignable", e)
            }
        }
        return null
    }

    private suspend fun fetch(root: String, account: XtreamAccount, userAgent: String?): XtreamAccountInfo? {
        val url = root.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("player_api.php")
            ?.addQueryParameter("username", account.username)
            ?.addQueryParameter("password", account.password)
            ?.build() ?: return null
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }
            .build()
        val call = clients.client(RequestPurpose.PLAYLIST).newCall(request)
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        val body = call.await().use { response -> if (response.isSuccessful) response.body.string() else null } ?: return null
        return parseAccountInfo(body)
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
    }
}
