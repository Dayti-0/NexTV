package tv.nextv.core.net

import okhttp3.HttpUrl

/**
 * Port écrit par l'utilisateur. OkHttp retire le port par défaut du schéma (« :80 » en http,
 * « :443 » en https) quand il réécrit une adresse : l'adresse marche pareil, mais le port saisi
 * disparaît du réglage. On le remet dans le texte enregistré pour que l'utilisateur le retrouve.
 */
object ExplicitPort {

    /** Port écrit dans [typed] (« http://hôte:80/… », « hôte:80 »), `null` s'il n'y en a pas. */
    fun of(typed: String): Int? {
        val authority = typed.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        val afterHost = if (authority.startsWith("[")) authority.substringAfter(']', "") else authority
        if (':' !in afterHost) return null
        return afterHost.substringAfterLast(':').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }

    /** [url] en texte, avec le port par défaut remis s'il était écrit dans [typed]. */
    fun text(url: HttpUrl, typed: String): String {
        val text = url.toString()
        val port = of(typed) ?: return text
        if (port != url.port || port != HttpUrl.defaultPort(url.scheme)) return text
        val start = text.indexOf("://") + 3
        val authorityEnd = text.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) text.length else it }
        val hostStart = text.lastIndexOf('@', authorityEnd - 1).let { if (it >= start) it + 1 else start }
        val hostEnd = if (text.startsWith("[", hostStart)) text.indexOf(']', hostStart) + 1 else authorityEnd
        if (text.indexOf(':', hostEnd).let { it in hostEnd until authorityEnd }) return text
        return text.substring(0, hostEnd) + ":" + port + text.substring(hostEnd)
    }
}
