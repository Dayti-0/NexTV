package tv.nextv.core.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/** Playlist envoyée à une autre télé (une seule). */
data class RemotePlaylist(
    val name: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val userAgent: String? = null,
    val mirrors: List<String> = emptyList(),
) {
    /** Jamais l'adresse ni les identifiants dans un journal. */
    override fun toString(): String = "RemotePlaylist($name)"
}

/** Catégorie masquée sur la télé configurée : type de contenu (`LIVE`, `MOVIE`, `SERIES`) et nom. */
data class RemoteHiddenGroup(val kind: String, val title: String)

/**
 * Configuration complète envoyée à une autre télé (Réglages › Configuration à distance) : sa playlist, et en option les
 * réglages et les clés des services de la télé qui l'envoie, et les catégories à masquer.
 *
 * @param settings réglages codés comme pour les télés reliées (nom → « b:true », « s:texte »…) ; vide : réglages
 *   laissés tels quels.
 * @param serviceKeys clés des services (`tmdb`, `football`, `audd`) ; vide : aucune.
 * @param hidden catégories masquées (les autres sont affichées) ; `null` : catégories laissées telles quelles.
 * @param from nom de la télé qui envoie.
 */
data class RemoteConfig(
    val playlist: RemotePlaylist,
    val settings: Map<String, String> = emptyMap(),
    val serviceKeys: Map<String, String> = emptyMap(),
    val hidden: List<RemoteHiddenGroup>? = null,
    val from: String = "",
) {
    override fun toString(): String = "RemoteConfig(${playlist.name}, ${settings.size} réglages, ${hidden?.size} catégories)"
}

/** Code affiché sur la télé à configurer, valable jusqu'à [expiresAt]. */
data class RemoteSetupOffer(val code: String, val expiresAt: Long)

/** Réponse de la télé configurée. */
data class RemoteSetupReply(val name: String, val installed: Boolean)

sealed interface RemoteSetupFind {
    /** Télé trouvée sous ce code : son nom. */
    data class Found(val name: String) : RemoteSetupFind

    /** Aucune télé n'affiche ce code (faute de frappe, code expiré ou déjà servi). */
    data object UnknownCode : RemoteSetupFind
    data object Offline : RemoteSetupFind
}

/**
 * Configuration à distance d'une télé par un code à 6 chiffres, sans lien durable entre les deux télés.
 *
 * La télé à configurer tire un code et dépose `attente.bin` (son nom, chiffré avec une clé tirée du code) sur une page
 * filebin dont le nom vient aussi du code. Celle qui configure, avec le même code, retrouve la télé, y dépose
 * `configuration.bin` (la [RemoteConfig] chiffrée), et attend `recu.bin`. La page est supprimée par celle qui
 * configure dès la réponse lue, par la télé configurée [CLEANUP_MILLIS] après sa réponse, et quand le code expire.
 */
class RemoteSetupLink(
    private val relay: SyncRelay,
    private val deviceName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val pollMillis: Long = POLL_MILLIS,
) {
    /**
     * Tire un code libre et le dépose (télé à configurer).
     * @throws IOException sans réseau.
     */
    suspend fun open(): RemoteSetupOffer {
        repeat(MAX_CODE_TRIES) {
            val code = SyncPairing.newCode(random)
            val bin = binOf(code)
            // Page d'un code déjà servi (supprimée, fermée par filebin) ou en cours d'usage ailleurs : autre code.
            if (relay.list(bin).isNotEmpty()) return@repeat
            try {
                relay.write(bin, WAITING_FILE, encode(JSONObject().put("name", deviceName()), code))
                return RemoteSetupOffer(code, clock() + VALIDITY_MILLIS)
            } catch (e: SyncBinClosedException) {
                // Code suivant.
            }
        }
        throw IOException("Aucune page libre pour le code")
    }

    /**
     * Attend la configuration (télé à configurer). @return `null` si le code a expiré sans rien recevoir. Une
     * configuration illisible avec ce code est ignorée.
     */
    suspend fun awaitConfig(offer: RemoteSetupOffer): RemoteConfig? {
        val bin = binOf(offer.code)
        while (clock() < offer.expiresAt) {
            delay(pollMillis)
            val files = try {
                relay.list(bin)
            } catch (e: IOException) {
                continue
            }
            if (CONFIG_FILE !in files) continue
            val bytes = try {
                relay.read(bin, CONFIG_FILE)
            } catch (e: IOException) {
                continue
            } ?: continue
            return decodeConfig(bytes, offer.code) ?: continue
        }
        return null
    }

    /** Réponse à celle qui configure (télé configurée). */
    suspend fun reply(offer: RemoteSetupOffer, installed: Boolean) {
        relay.write(binOf(offer.code), REPLY_FILE, encode(JSONObject().put("name", deviceName()).put("installed", installed), offer.code))
    }

    /** Code retiré : page supprimée (filebin n'y laisse plus rien écrire). */
    suspend fun close(offer: RemoteSetupOffer) {
        withContext(NonCancellable) { runCatching { relay.deleteBin(binOf(offer.code)) } }
    }

    /** Cherche la télé qui affiche [code] (télé qui configure). */
    suspend fun find(code: String): RemoteSetupFind {
        val bytes = try {
            relay.read(binOf(code), WAITING_FILE)
        } catch (e: IOException) {
            return RemoteSetupFind.Offline
        } ?: return RemoteSetupFind.UnknownCode
        val json = decode(bytes, code) ?: return RemoteSetupFind.UnknownCode
        return RemoteSetupFind.Found(json.optString("name").ifBlank { "?" })
    }

    /**
     * Dépose la configuration pour la télé qui affiche [code].
     * @throws IOException sans réseau, ou page fermée (code expiré entre-temps).
     */
    suspend fun send(code: String, config: RemoteConfig) {
        try {
            relay.write(binOf(code), CONFIG_FILE, SyncCodec.encrypt(configJson(config.copy(from = config.from.ifBlank { deviceName() })).toString().toByteArray(Charsets.UTF_8), keyOf(code)))
        } catch (e: SyncBinClosedException) {
            throw IOException("Code expiré", e)
        }
    }

    /**
     * Attend la réponse de la télé configurée pendant [timeoutMillis]. @return `null` sans réponse. La page est
     * supprimée dans tous les cas : la configuration (chiffrée) n'y reste pas.
     */
    suspend fun awaitReply(code: String, timeoutMillis: Long = REPLY_TIMEOUT_MILLIS): RemoteSetupReply? {
        val bin = binOf(code)
        val until = clock() + timeoutMillis
        try {
            while (clock() < until) {
                delay(pollMillis)
                val files = try {
                    relay.list(bin)
                } catch (e: IOException) {
                    continue
                }
                if (REPLY_FILE !in files) continue
                val json = runCatching { relay.read(bin, REPLY_FILE) }.getOrNull()?.let { decode(it, code) } ?: continue
                return RemoteSetupReply(json.optString("name").ifBlank { "?" }, json.optBoolean("installed"))
            }
            return null
        } finally {
            withContext(NonCancellable) { runCatching { relay.deleteBin(bin) } }
        }
    }

    private fun encode(json: JSONObject, code: String): ByteArray = SyncCodec.encrypt(json.toString().toByteArray(Charsets.UTF_8), keyOf(code))

    private fun decode(bytes: ByteArray, code: String): JSONObject? = try {
        JSONObject(String(SyncCodec.decrypt(bytes, keyOf(code)), Charsets.UTF_8))
    } catch (e: SyncFormatException) {
        null
    } catch (e: JSONException) {
        null
    }

    private fun decodeConfig(bytes: ByteArray, code: String): RemoteConfig? = decode(bytes, code)?.let(::parseConfig)

    companion object {
        const val VALIDITY_MILLIS = 15 * 60_000L
        const val POLL_MILLIS = 3_000L
        const val REPLY_TIMEOUT_MILLIS = 90_000L

        /** La télé configurée supprime la page ce délai après sa réponse (si celle qui configure ne l'a pas fait). */
        const val CLEANUP_MILLIS = 2 * 60_000L
        private const val MAX_CODE_TRIES = 5
        const val WAITING_FILE = "attente.bin"
        const val CONFIG_FILE = "configuration.bin"
        const val REPLY_FILE = "recu.bin"

        fun binOf(code: String): String = "nixtv-setup-" + "nixtv-setup|$code".encodeUtf8().sha256().hex().take(20)

        fun keyOf(code: String): ByteArray = "nixtv-setup-key|$code".encodeUtf8().sha256().toByteArray()

        fun configJson(config: RemoteConfig): JSONObject {
            val playlist = config.playlist
            return JSONObject()
                .put("v", 1)
                .put("from", config.from)
                .put(
                    "playlist",
                    JSONObject()
                        .put("name", playlist.name)
                        .put("url", playlist.url)
                        .put("user", playlist.username ?: JSONObject.NULL)
                        .put("pass", playlist.password ?: JSONObject.NULL)
                        .put("ua", playlist.userAgent ?: JSONObject.NULL)
                        .put("mirrors", JSONArray(playlist.mirrors)),
                )
                .put("settings", JSONObject(config.settings as Map<*, *>))
                .put("keys", JSONObject(config.serviceKeys as Map<*, *>))
                .put("hidden", config.hidden?.let { groups -> JSONArray(groups.map { JSONArray().put(it.kind).put(it.title) }) } ?: JSONObject.NULL)
        }

        /** `null` si le texte n'est pas une configuration (ou sans adresse de playlist). */
        fun parseConfig(json: JSONObject): RemoteConfig? {
            val playlist = json.optJSONObject("playlist") ?: return null
            val url = playlist.optString("url").takeIf { it.isNotBlank() } ?: return null
            fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { has(name) }
            fun JSONObject?.strings(): Map<String, String> = this?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } }.orEmpty()
            val hidden = json.optJSONArray("hidden")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val pair = array.optJSONArray(i) ?: return@mapNotNull null
                    RemoteHiddenGroup(pair.optString(0).takeIf { it.isNotEmpty() } ?: return@mapNotNull null, pair.optString(1))
                }
            }
            return RemoteConfig(
                playlist = RemotePlaylist(
                    name = playlist.optString("name"),
                    url = url,
                    username = playlist.text("user"),
                    password = playlist.text("pass"),
                    userAgent = playlist.text("ua"),
                    mirrors = playlist.optJSONArray("mirrors")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                ),
                settings = json.optJSONObject("settings").strings(),
                serviceKeys = json.optJSONObject("keys").strings(),
                hidden = hidden,
                from = json.optString("from"),
            )
        }
    }
}
