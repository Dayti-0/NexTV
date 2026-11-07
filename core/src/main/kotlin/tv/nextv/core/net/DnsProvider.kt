package tv.nextv.core.net

/**
 * Résolution des noms de serveurs.
 *
 * - [SYSTEM] : celle de l'appareil, en général le DNS du fournisseur d'accès, qui peut bloquer
 *   certains serveurs (une playlist qui charge en 4G mais pas sur la box).
 * - Les autres : résolveur public interrogé en DNS sur HTTPS. Chiffré, il ne peut être ni lu ni
 *   détourné par le réseau. Ses adresses IP fixes ([addresses]) évitent toute résolution préalable.
 */
enum class DnsProvider(val dohUrl: String?, val addresses: List<String>) {
    SYSTEM(null, emptyList()),
    QUAD9("https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")),
    CLOUDFLARE("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")),
}
