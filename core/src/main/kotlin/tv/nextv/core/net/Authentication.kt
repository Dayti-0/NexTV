package tv.nextv.core.net

import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import tv.nextv.core.model.Credentials

/**
 * Identifiants valables pour une origine donnée (schéma + hôte + port).
 *
 * Ils ne sont présentés qu'en réponse à un défi `401` de cette même origine (ou de sa
 * version HTTPS) : après une redirection vers un autre hôte ou vers HTTP en clair,
 * ils ne sont jamais envoyés.
 */
class AuthScope(
    val scheme: String,
    val host: String,
    val port: Int,
    val credentials: Credentials,
) {
    fun matches(url: HttpUrl): Boolean {
        if (!url.host.equals(host, ignoreCase = true)) return false
        if (url.scheme == scheme && url.port == port) return true
        return scheme == "http" && url.scheme == "https" && url.port == HttpUrl.defaultPort("https")
    }

    override fun toString(): String = "AuthScope($scheme://$host:$port)"

    companion object {
        fun forUrl(url: HttpUrl, credentials: Credentials) = AuthScope(url.scheme, url.host, url.port, credentials)
    }
}

/**
 * Répond aux défis `WWW-Authenticate: Basic` avec les identifiants de l'[AuthScope]
 * attaché à la requête (tag OkHttp) ou, à défaut, de [fixedScope].
 *
 * Digest, NTLM, Bearer… ne sont pas pris en charge : le `401` remonte alors tel quel.
 */
class ScopedCredentialsAuthenticator(private val fixedScope: AuthScope? = null) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val scope = request.tag(AuthScope::class.java) ?: fixedScope ?: return null
        if (!scope.matches(request.url)) return null
        // Déjà tenté : identifiants refusés, inutile de boucler.
        if (request.header(HEADER_AUTHORIZATION) != null) return null
        val basic = response.challenges().firstOrNull { it.scheme.equals("Basic", ignoreCase = true) } ?: return null
        val value = okhttp3.Credentials.basic(scope.credentials.username, scope.credentials.password, basic.charset)
        return request.newBuilder().header(HEADER_AUTHORIZATION, value).build()
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

/**
 * Authentification Basic auprès d'un proxy HTTP, y compris de manière préemptive
 * pour les tunnels CONNECT (OkHttp appelle l'authentificateur avant d'ouvrir le tunnel).
 */
class BasicProxyAuthenticator(private val credentials: Credentials) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.header(HEADER_PROXY_AUTHORIZATION) != null) return null
        val challenges = response.challenges()
        val acceptsBasic = challenges.isEmpty() || challenges.any {
            it.scheme.equals("Basic", ignoreCase = true) || it.scheme.equals("OkHttp-Preemptive", ignoreCase = true)
        }
        if (!acceptsBasic) return null
        val value = okhttp3.Credentials.basic(credentials.username, credentials.password)
        return request.newBuilder().header(HEADER_PROXY_AUTHORIZATION, value).build()
    }

    companion object {
        const val HEADER_PROXY_AUTHORIZATION = "Proxy-Authorization"
    }
}
