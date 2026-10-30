package tv.nextv.core.model

/** Nature d'une entrée de playlist : chaîne en direct, film ou épisode de série. */
enum class ContentKind {
    LIVE,
    MOVIE,
    SERIES,
}

/**
 * Qualité déclarée par le nom de l'entrée (« TF1 FHD », « TF1 HD »…).
 *
 * @property rank ordre de préférence (le plus élevé est le meilleur).
 */
enum class StreamQuality(val rank: Int, val label: String) {
    LOW(0, "LOW"),
    SD(1, "SD"),
    HD(3, "HD"),
    FHD(4, "FHD"),
    UHD(5, "4K"),
    ;

    companion object {
        /** Rang d'une version sans mention de qualité : entre SD et HD. */
        const val UNKNOWN_RANK = 2

        fun rankOf(quality: StreamQuality?): Int = quality?.rank ?: UNKNOWN_RANK
    }
}

/** Épisode d'une série, d'après le nom de l'entrée (« Série S01 E02 »). */
data class EpisodeInfo(
    val seriesName: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * Chaîne (ou film, ou épisode) issue d'une source.
 *
 * @property position ordre d'apparition dans la source.
 * @property tvgId identifiant EPG déclaré (`tvg-id`), utilisé pour associer le guide.
 * @property variantKey clé commune aux versions d'une même chaîne en différentes qualités.
 * @property title titre nettoyé (sans préfixe de langue, qualité ni année), pour l'affichage et TMDB.
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
    val kind: ContentKind = ContentKind.LIVE,
    val title: String = name,
    val variantKey: String = "",
    val quality: StreamQuality? = null,
    val year: Int? = null,
    val episode: EpisodeInfo? = null,
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
