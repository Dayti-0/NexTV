package tv.nextv.core.model

/**
 * Chaîne issue d'une source.
 *
 * @property position ordre d'apparition dans la source.
 * @property tvgId identifiant EPG déclaré (`tvg-id`), utilisé pour associer le guide.
 */
data class Channel(
    val position: Int,
    val name: String,
    val stream: StreamDescriptor,
    val groupTitle: String? = null,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val channelNumber: String? = null,
)

/** Programme d'un guide EPG, horaires en millisecondes epoch UTC. */
data class Programme(
    val epgChannelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)
