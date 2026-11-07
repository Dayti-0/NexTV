package tv.nextv.core.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.SourceLocation

/**
 * Serveurs de secours d'une playlist : autres adresses du même fournisseur, avec les mêmes
 * identifiants (« http://serveur2.com:8080 »). Une adresse de la playlist ou d'un flux sur l'un des
 * serveurs connus se décline sur les autres en ne changeant que le schéma, l'hôte et le port.
 */
object Mirrors {

    /** Racine d'un serveur (« http://hôte:port »), `null` si l'adresse est invalide. */
    fun normalize(input: String): String? = XtreamAccount.serverUrl(input)?.toString()?.trimEnd('/')

    /** L'emplacement de la playlist, puis le même sur chaque serveur de secours. */
    fun locations(location: SourceLocation, mirrors: List<String>): List<SourceLocation> {
        val remote = location as? SourceLocation.Remote ?: return listOf(location)
        return listOf(location) + mirrors.mapNotNull { rewrite(remote.url, it) }.distinct().filter { it != remote.url }.map { SourceLocation.Remote(it) }
    }

    /**
     * Même adresse sur les autres serveurs [roots] (playlist et secours), si [url] est sur l'un d'eux ;
     * liste vide sinon (flux hébergé ailleurs).
     */
    fun alternatives(url: String, roots: List<String>): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return emptyList()
        val known = roots.mapNotNull { it.toHttpUrlOrNull() }
        if (known.none { it.host.equals(parsed.host, ignoreCase = true) && it.port == parsed.port }) return emptyList()
        return known.filterNot { it.host.equals(parsed.host, ignoreCase = true) && it.port == parsed.port }
            .map { parsed.newBuilder().scheme(it.scheme).host(it.host).port(it.port).build().toString() }
            .distinct()
    }

    /** [url] sur le serveur [root]. */
    fun rewrite(url: String, root: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val server = root.toHttpUrlOrNull() ?: return null
        return parsed.newBuilder().scheme(server.scheme).host(server.host).port(server.port).build().toString()
    }

    /** Erreur qui peut venir du serveur lui-même (injoignable, bloqué, en panne) : un autre peut répondre. */
    fun isServerProblem(error: SourceError): Boolean = when (error) {
        SourceError.Timeout, SourceError.ConnectionFailed, SourceError.TlsFailure, SourceError.TooManyRedirects,
        SourceError.EmptyContent, SourceError.HtmlInsteadOfContent, SourceError.NotAPlaylist,
        is SourceError.UnknownHost, is SourceError.HttpStatus, is SourceError.Network, is SourceError.Unknown,
        is SourceError.UnsupportedRedirect,
        -> true
        else -> false
    }
}
