package tv.nextv.core.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.model.Credentials

/**
 * Sauvegarde de l'application : playlists (avec leurs identifiants), favoris, catégories masquées
 * et réglages. Le fichier contient des mots de passe : il reste à l'utilisateur.
 */
data class Backup(
    val exportedAtMillis: Long,
    val playlists: List<PlaylistBackup>,
    val settings: List<SettingBackup>,
)

/** Playlist distante (les fichiers de l'appareil ne se transportent pas d'un appareil à l'autre). */
data class PlaylistBackup(
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val mirrors: List<String> = emptyList(),
    val credentials: Credentials? = null,
    /** Playlist affichée à l'accueil. */
    val active: Boolean = false,
    val favorites: List<FavoriteBackup> = emptyList(),
    val hiddenGroups: List<HiddenGroupBackup> = emptyList(),
)

data class FavoriteBackup(val kind: String, val itemKey: String, val title: String, val logoUrl: String?, val position: Int)

data class HiddenGroupBackup(val kind: String, val title: String)

/** Réglage : nom de la préférence et sa valeur ([Boolean], [Int], [Long], [String] ou ensemble de [String]). */
data class SettingBackup(val key: String, val value: Any)

class BackupFormatException(message: String) : Exception(message)

/** Fichier JSON de sauvegarde (format « nextv-backup », version 1). */
object BackupCodec {
    private const val FORMAT = "nextv-backup"
    private const val VERSION = 1

    fun encode(backup: Backup): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exportedAt", backup.exportedAtMillis)
        .put("playlists", JSONArray(backup.playlists.map(::playlist)))
        .put("settings", JSONArray(backup.settings.mapNotNull(::setting)))
        .toString(2)

    /** @throws BackupFormatException si le texte n'est pas une sauvegarde NexTV lisible. */
    fun decode(text: String): Backup {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("JSON illisible")
        }
        if (json.optString("format") != FORMAT) throw BackupFormatException("Pas une sauvegarde NexTV")
        if (json.optInt("version") > VERSION) throw BackupFormatException("Sauvegarde d'une version plus récente")
        return Backup(
            exportedAtMillis = json.optLong("exportedAt"),
            playlists = json.optJSONArray("playlists").objects().mapNotNull(::playlistOf),
            settings = json.optJSONArray("settings").objects().mapNotNull(::settingOf),
        )
    }

    private fun playlist(playlist: PlaylistBackup) = JSONObject()
        .put("name", playlist.name)
        .put("url", playlist.url)
        .putOpt("userAgent", playlist.userAgent)
        .put("mirrors", JSONArray(playlist.mirrors))
        .putOpt("credentials", playlist.credentials?.let { JSONObject().put("username", it.username).put("password", it.password) })
        .put("active", playlist.active)
        .put(
            "favorites",
            JSONArray(
                playlist.favorites.map {
                    JSONObject().put("kind", it.kind).put("itemKey", it.itemKey).put("title", it.title).putOpt("logoUrl", it.logoUrl).put("position", it.position)
                },
            ),
        )
        .put("hiddenGroups", JSONArray(playlist.hiddenGroups.map { JSONObject().put("kind", it.kind).put("title", it.title) }))

    private fun playlistOf(json: JSONObject): PlaylistBackup? {
        val url = json.text("url") ?: return null
        return PlaylistBackup(
            name = json.text("name").orEmpty(),
            url = url,
            userAgent = json.text("userAgent"),
            mirrors = json.optJSONArray("mirrors").strings(),
            credentials = json.optJSONObject("credentials")?.let { credentials ->
                credentials.text("username")?.let { Credentials(it, credentials.optString("password")) }
            },
            active = json.optBoolean("active"),
            favorites = json.optJSONArray("favorites").objects().mapNotNull { favorite ->
                FavoriteBackup(
                    kind = favorite.text("kind") ?: return@mapNotNull null,
                    itemKey = favorite.text("itemKey") ?: return@mapNotNull null,
                    title = favorite.text("title").orEmpty(),
                    logoUrl = favorite.text("logoUrl"),
                    position = favorite.optInt("position"),
                )
            },
            hiddenGroups = json.optJSONArray("hiddenGroups").objects().mapNotNull { group ->
                // Titre vide : éléments sans groupe.
                HiddenGroupBackup(group.text("kind") ?: return@mapNotNull null, if (group.isNull("title")) "" else group.optString("title"))
            },
        )
    }

    private fun setting(setting: SettingBackup): JSONObject? {
        val (type, value) = when (val value = setting.value) {
            is Boolean -> "boolean" to value
            is Int -> "int" to value
            is Long -> "long" to value
            is String -> "string" to value
            is Set<*> -> "strings" to JSONArray(value.filterIsInstance<String>())
            else -> return null
        }
        return JSONObject().put("key", setting.key).put("type", type).put("value", value)
    }

    private fun settingOf(json: JSONObject): SettingBackup? {
        val key = json.text("key") ?: return null
        if (!json.has("value")) return null
        val value: Any = when (json.optString("type")) {
            "boolean" -> json.optBoolean("value")
            "int" -> json.optInt("value")
            "long" -> json.optLong("value")
            "string" -> json.optString("value")
            "strings" -> json.optJSONArray("value").strings().toSet()
            else -> return null
        }
        return SettingBackup(key, value)
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotEmpty() } }
}
