package tv.nextv.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException

/** Détection, sans résolution DNS, des hôtes du réseau local. */
object LocalAddresses {

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".localdomain", ".internal")
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isLocal(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        if (!host.contains('.') && !host.contains(':')) return true // nom court (NetBIOS / mDNS)
        IPV4.matchEntire(host)?.let { match ->
            val octets = match.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return false
            val (a, b) = octets
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in host) {
            return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        }
        return false
    }
}

/**
 * Sélecteur renvoyant toujours le proxy configuré, sauf pour les hôtes locaux si demandé :
 * un proxy distant ne peut généralement pas joindre un serveur du réseau domestique.
 */
class ConfiguredProxySelector(
    private val proxy: Proxy,
    private val bypassLocalAddresses: Boolean,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        if (bypassLocalAddresses && host != null && LocalAddresses.isLocal(host)) return listOf(Proxy.NO_PROXY)
        return listOf(proxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    companion object {
        fun from(settings: ProxySettings): ConfiguredProxySelector {
            val type = when (settings.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS -> Proxy.Type.SOCKS
            }
            val address = InetSocketAddress.createUnresolved(settings.host.trim(), settings.port)
            return ConfiguredProxySelector(Proxy(type, address), settings.bypassLocalAddresses)
        }
    }
}
