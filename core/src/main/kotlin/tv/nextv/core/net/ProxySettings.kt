package tv.nextv.core.net

import tv.nextv.core.model.Credentials

/** Nature d'une requête distante ; détermine notamment si le proxy s'applique. */
enum class RequestPurpose {
    PLAYLIST,
    EPG,
    STREAM,
    IMAGE,

    /** Informations complémentaires (TMDB : synopsis, affiches…). */
    METADATA,
}

enum class ProxyType {
    /** Proxy HTTP (CONNECT pour HTTPS), avec authentification Basic optionnelle. */
    HTTP,

    /** Proxy SOCKS v4/v5 sans authentification. */
    SOCKS,
}

/** Catégories de requêtes auxquelles le proxy peut être appliqué. */
enum class ProxyScope {
    /** Playlists et logos des chaînes. */
    PLAYLISTS,
    EPG,

    /** Flux vidéo HTTP/HTTPS (manifestes et segments HLS/DASH compris). */
    STREAMS,
}

/**
 * Proxy configuré par l'utilisateur.
 *
 * Limites de l'implémentation (OkHttp) : l'authentification n'est possible qu'avec un proxy
 * HTTP (schéma Basic) ; un proxy SOCKS doit être ouvert. Les flux RTSP et UDP ne passent
 * jamais par le proxy.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val credentials: Credentials? = null,
    val scopes: Set<ProxyScope> = ProxyScope.entries.toSet(),
    /** Ne pas passer par le proxy pour `localhost`, les adresses privées et les noms `.local`. */
    val bypassLocalAddresses: Boolean = true,
) {

    /** Vrai si le proxy est actif et valide pour ce type de requête. */
    fun appliesTo(purpose: RequestPurpose): Boolean {
        if (!enabled || validate().isNotEmpty()) return false
        val scope = when (purpose) {
            RequestPurpose.PLAYLIST, RequestPurpose.IMAGE, RequestPurpose.METADATA -> ProxyScope.PLAYLISTS
            RequestPurpose.EPG -> ProxyScope.EPG
            RequestPurpose.STREAM -> ProxyScope.STREAMS
        }
        return scope in scopes
    }

    fun validate(): List<ProxyProblem> {
        if (!enabled) return emptyList()
        val problems = mutableListOf<ProxyProblem>()
        val trimmedHost = host.trim()
        when {
            trimmedHost.isEmpty() -> problems += ProxyProblem.MISSING_HOST
            "://" in trimmedHost || trimmedHost.any { it.isWhitespace() || it == '/' || it == '@' } ->
                problems += ProxyProblem.INVALID_HOST
        }
        if (port !in 1..65535) problems += ProxyProblem.INVALID_PORT
        if (type == ProxyType.SOCKS && credentials != null) problems += ProxyProblem.SOCKS_AUTH_UNSUPPORTED
        if (credentials != null && ':' in credentials.username) problems += ProxyProblem.INVALID_USERNAME
        if (scopes.isEmpty()) problems += ProxyProblem.NO_SCOPE
        return problems
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

enum class ProxyProblem {
    MISSING_HOST,
    INVALID_HOST,
    INVALID_PORT,
    SOCKS_AUTH_UNSUPPORTED,
    INVALID_USERNAME,
    NO_SCOPE,
}

/**
 * Paramètres réseau communs.
 *
 * Le délai de lecture est un délai d'inactivité : un gros guide EPG peut mettre plusieurs
 * minutes à se télécharger sans dépasser ce délai, tant que des données arrivent.
 */
data class NetworkSettings(
    val connectTimeoutMillis: Long = 15_000,
    val readTimeoutMillis: Long = 30_000,
    val streamReadTimeoutMillis: Long = 20_000,
    val maxRedirects: Int = 10,
    val defaultUserAgent: String = "NexTV/1.0",
    /** Résolution des noms de toutes les requêtes (playlists, guide, flux, images…). */
    val dns: DnsProvider = DnsProvider.SYSTEM,
)
