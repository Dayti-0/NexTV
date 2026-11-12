package tv.nextv.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.source.Mirrors
import tv.nextv.core.source.XtreamAccount

/**
 * Fournisseur King : ses serveurs (principal puis secours) sont intégrés à l'application à la fabrication
 * (secret de la CI, jamais dans le dépôt public). Un compte King ne demande que l'identifiant et le mot de
 * passe, comme l'application d'origine.
 */
object King {

    /** Serveurs King, principal d'abord ; vide si l'application a été fabriquée sans eux (pas d'onglet King). */
    @Volatile
    var servers: List<String> = parse(BuildConfig.KING_SERVERS)
        internal set

    val available: Boolean get() = servers.isNotEmpty()

    const val DEFAULT_NAME = "King 365"

    fun parse(text: String): List<String> =
        text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.mapNotNull(Mirrors::normalize).distinct()

    /** Lien de playlist sur l'un des serveurs King (compte Xtream de ce fournisseur). */
    fun isKing(url: String): Boolean {
        val server = XtreamAccount.parse(url)?.server ?: return false
        return servers.any { sameServer(it, server) }
    }

    /** Lien de la playlist du compte, sur le serveur principal ; `null` sans serveur King. */
    fun playlistUrl(username: String, password: String): String? =
        servers.firstOrNull()?.let { XtreamAccount(it, username, password).playlistUrl() }

    /** Serveurs de secours d'une playlist King : tous les autres que celui de [url]. */
    fun mirrorsFor(url: String): List<String> {
        val server = XtreamAccount.parse(url)?.server
        return servers.filterNot { server != null && sameServer(it, server) }
    }

    private fun sameServer(a: String, b: String): Boolean {
        val x = a.toHttpUrlOrNull() ?: return false
        val y = b.toHttpUrlOrNull() ?: return false
        return x.host.equals(y.host, ignoreCase = true) && x.port == y.port
    }
}
