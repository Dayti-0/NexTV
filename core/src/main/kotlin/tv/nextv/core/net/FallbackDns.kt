package tv.nextv.core.net

import okhttp3.Dns
import tv.nextv.core.log.Logger
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Résolution avec secours : le résolveur choisi d'abord, puis les autres quand il ne trouve pas
 * le serveur.
 *
 * Certains résolveurs bloquent des serveurs de playlist : Quad9 répond « nom inconnu » pour les
 * adresses qu'il juge suspectes (playlist qui ne charge plus, 24/11/2026), un fournisseur d'accès
 * ou un filtre (Pi-hole, AdGuard) renvoie 0.0.0.0 ou 127.0.0.1. Une réponse de ce genre compte
 * comme un échec ; elle n'est rendue que si aucun autre résolveur ne fait mieux (jamais pire
 * qu'avant).
 *
 * Le résolveur qui a trouvé un serveur est retenu pour lui jusqu'au prochain changement de réglage
 * (le résolveur entier est alors recréé) : pas de nouvel essai du premier à chaque requête.
 */
class FallbackDns(
    val resolvers: List<Pair<DnsProvider, Dns>>,
    private val logger: Logger = Logger.None,
) : Dns {

    private val chosen = ConcurrentHashMap<String, Int>()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        val first = chosen[host] ?: 0
        val order = listOf(first) + resolvers.indices.filter { it != first }
        var suspect: List<InetAddress>? = null
        var error: UnknownHostException? = null
        for (index in order) {
            val (provider, dns) = resolvers[index]
            val found = try {
                dns.lookup(hostname)
            } catch (e: UnknownHostException) {
                if (error == null) error = e
                continue
            }
            if (found.isEmpty()) continue
            if (looksBlocked(host, found)) {
                if (suspect == null) suspect = found
                continue
            }
            if (index != first) {
                chosen[host] = index
                // Sans le nom du serveur : le journal ne doit pas révéler l'adresse de la playlist.
                logger.info(TAG, "Serveur introuvable par ${resolvers[first].first}, trouvé par $provider")
            }
            return found
        }
        return suspect ?: throw (error ?: UnknownHostException(hostname))
    }

    private companion object {
        const val TAG = "FallbackDns"

        /** Réponse de blocage : seulement 0.0.0.0 / 127.x / ::, pour un nom qui n'est pas local. */
        fun looksBlocked(host: String, found: List<InetAddress>): Boolean =
            host != "localhost" && !host.endsWith(".localhost") &&
                found.all { it.isAnyLocalAddress || it.isLoopbackAddress }
    }
}

/**
 * Ordre d'essai : le résolveur choisi, puis celui de l'appareil, puis un résolveur public qui ne
 * filtre pas (Cloudflare, ou Google si Cloudflare est déjà le choix). Trois au plus, pour qu'un
 * appareil hors ligne n'attende pas trop longtemps son message d'erreur. Quad9, qui filtre, n'est
 * jamais pris en secours.
 */
fun DnsProvider.withFallbacks(): List<DnsProvider> =
    (listOf(this) + listOf(DnsProvider.SYSTEM, DnsProvider.CLOUDFLARE, DnsProvider.GOOGLE).filter { it != this }).take(3)
