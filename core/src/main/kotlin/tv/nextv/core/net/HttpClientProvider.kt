package tv.nextv.core.net

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import kotlinx.coroutines.suspendCancellableCoroutine
import tv.nextv.core.log.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fournit un [OkHttpClient] configuré par type de requête : délais, redirections, proxy
 * (selon [ProxySettings.scopes]), résolution des noms ([NetworkSettings.dns]) et
 * authentification par origine.
 *
 * Tous les clients partagent le pool de connexions et le dispatcher de [baseClient].
 * Les clients sont recréés quand les réglages changent ([update]).
 */
class HttpClientProvider(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val logger: Logger = Logger.None,
    proxySettings: ProxySettings = ProxySettings(),
    networkSettings: NetworkSettings = NetworkSettings(),
) {

    @Volatile
    var proxySettings: ProxySettings = proxySettings
        private set

    @Volatile
    var networkSettings: NetworkSettings = networkSettings
        private set

    private val clients = ConcurrentHashMap<RequestPurpose, OkHttpClient>()

    @Volatile
    private var resolver: Dns? = null

    fun update(proxySettings: ProxySettings = this.proxySettings, networkSettings: NetworkSettings = this.networkSettings) {
        this.proxySettings = proxySettings
        this.networkSettings = networkSettings
        resolver = null
        clients.clear()
    }

    /** Vrai si les requêtes de ce type passent par le proxy configuré. */
    fun usesProxy(purpose: RequestPurpose): Boolean = proxySettings.appliesTo(purpose)

    fun client(purpose: RequestPurpose): OkHttpClient = clients.getOrPut(purpose) { build(purpose) }

    private fun build(purpose: RequestPurpose): OkHttpClient {
        val network = networkSettings
        val proxy = proxySettings
        val readTimeout = if (purpose == RequestPurpose.STREAM) network.streamReadTimeoutMillis else network.readTimeoutMillis
        val builder = baseClient.newBuilder()
            .connectTimeout(network.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(network.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            // Les redirections sont suivies manuellement pour en limiter le nombre.
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(ScopedCredentialsAuthenticator())
            .addInterceptor(RedirectInterceptor(network.maxRedirects))
            .dns(resolver())

        if (proxy.appliesTo(purpose)) {
            builder.proxySelector(ConfiguredProxySelector.from(proxy))
            val credentials = proxy.credentials
            if (credentials != null && proxy.type == ProxyType.HTTP) {
                builder.proxyAuthenticator(BasicProxyAuthenticator(credentials))
            }
            logger.debug(TAG, "Proxy ${proxy.type} actif pour $purpose")
        } else {
            builder.proxy(Proxy.NO_PROXY)
        }
        return builder.build()
    }

    /** Résolveur partagé par tous les clients, recréé quand le réglage change. */
    private fun resolver(): Dns = resolver ?: buildResolver(networkSettings.dns).also { resolver = it }

    private fun buildResolver(provider: DnsProvider): Dns {
        val url = provider.dohUrl ?: return Dns.SYSTEM
        logger.debug(TAG, "DNS sur HTTPS : $provider")
        return DnsOverHttps.Builder()
            // Le résolveur est joint directement par ses adresses IP, sans proxy.
            .client(baseClient.newBuilder().proxy(Proxy.NO_PROXY).build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(provider.addresses.map(InetAddress::getByName))
            .build()
    }

    private companion object {
        const val TAG = "HttpClientProvider"
    }
}

/**
 * Suit les redirections HTTP (301, 302, 303, 307, 308) en limitant leur nombre et en
 * refusant les schémas autres que HTTP(S). Le `Authorization` explicite n'est jamais
 * transmis à une autre origine ; l'[AuthScope] attaché à la requête reste valable,
 * mais [AuthScope.matches] empêche son usage hors de son origine.
 */
internal class RedirectInterceptor(private val maxRedirects: Int) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var redirects = 0
        while (response.isRedirect) {
            val location = response.header("Location") ?: return response
            val target = response.request.url.resolve(location) ?: return response // schéma non HTTP(S)
            if (++redirects > maxRedirects) {
                response.close()
                throw TooManyRedirectsException(maxRedirects)
            }
            val builder = request.newBuilder().url(target)
            if (response.code == 303 || (response.code in 301..302 && request.method == "POST")) {
                builder.method("GET", null)
            }
            if (!sameOrigin(request.url, target)) {
                builder.removeHeader("Authorization")
                builder.removeHeader("Cookie")
            }
            response.close()
            request = builder.build()
            response = chain.proceed(request)
        }
        return response
    }

    private fun sameOrigin(a: okhttp3.HttpUrl, b: okhttp3.HttpUrl) =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
}

/** Levée quand une chaîne de redirections dépasse la limite (boucle probable). */
class TooManyRedirectsException(val limit: Int) : IOException("Too many redirects (limit $limit)")

/** Exécute l'appel de façon suspendue ; l'annulation de la coroutine annule la requête. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
