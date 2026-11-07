package tv.nextv.core.source

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.net.RemoteUrlValidator

/**
 * Compte Xtream Codes : adresse du serveur, identifiant et mot de passe donnés par le fournisseur.
 * La playlist du compte est le lien M3U habituel de ces serveurs
 * (`http://serveur/get.php?username=…&password=…&type=m3u_plus&output=ts`), construit à partir
 * de ces trois informations ou relu depuis un tel lien.
 */
class XtreamAccount(val server: String, val username: String, val password: String) {

    /** Lien de la playlist M3U du compte, `null` si l'adresse du serveur est invalide. */
    fun playlistUrl(): String? = serverUrl(server)?.newBuilder()
        ?.addPathSegment("get.php")
        ?.addQueryParameter("username", username)
        ?.addQueryParameter("password", password)
        ?.addQueryParameter("type", "m3u_plus")
        ?.addQueryParameter("output", "ts")
        ?.build()
        ?.toString()

    override fun equals(other: Any?): Boolean =
        other is XtreamAccount && other.server == server && other.username == username && other.password == password

    override fun hashCode(): Int = (server.hashCode() * 31 + username.hashCode()) * 31 + password.hashCode()

    override fun toString(): String = "XtreamAccount($server, ***)"

    companion object {
        /**
         * Racine du serveur : `http://` ajouté s'il manque ; chemin, paramètres et identifiants
         * éventuels (lien complet collé) ignorés.
         */
        fun serverUrl(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isISOControl() || it == ' ' }) return null
            val url = (if ("://" in trimmed) trimmed else "http://$trimmed").toHttpUrlOrNull() ?: return null
            if (url.scheme !in RemoteUrlValidator.SUPPORTED_SCHEMES || url.host.isBlank()) return null
            return url.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build()
        }

        /** Compte d'un lien de playlist Xtream (`…/get.php?username=…&password=…`), sinon `null`. */
        fun parse(url: String): XtreamAccount? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            if (parsed.pathSegments.lastOrNull() != "get.php") return null
            val username = parsed.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
            val password = parsed.queryParameter("password") ?: return null
            val root = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return XtreamAccount(root, username, password)
        }
    }
}
