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

        /**
         * Qualité d'une image de [width] × [height] pixels (fichier lu, ou décrit par le serveur). La largeur
         * compte autant que la hauteur : un film en 1920 × 800 (format cinéma) est en Full HD.
         */
        fun fromSize(width: Int?, height: Int?): StreamQuality? {
            val w = width ?: 0
            val h = height ?: 0
            if (w <= 0 && h <= 0) return null
            return when {
                w >= 3200 || h >= 1800 -> UHD
                w >= 1800 || h >= 1000 -> FHD
                w >= 1200 || h >= 700 -> HD
                w >= 600 || h >= 400 -> SD
                else -> LOW
            }
        }
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
 * @property catchupDays jours de programmes passés que le serveur garde pour le replay (0 : pas de replay).
 * @property audio son annoncé par le nom d'une chaîne (double audio, Dolby : voir QualityParser.AUDIO_MULTI), 0 sinon.
 * @property lineup rang de la chaîne dans l'ordre « rangé » (TNT, Canal+, cinéma… voir ChannelLineup) ; `null` :
 *   celui de la playlist.
 * @property theme catégorie par thème de la chaîne quand les chaînes sont rassemblées (« Cinéma »), `null` : celle de
 *   la playlist.
 * @property niceTitle nom propre de la chaîne quand les chaînes sont rassemblées (« Ciné+ Frisson »), `null` : [title].
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
    val catchupDays: Int = 0,
    val audio: Int = 0,
    val lineup: Int? = null,
    val theme: String? = null,
    val niceTitle: String? = null,
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
    /** Année de production, si le guide l'indique. */
    val year: Int? = null,
    /** Image du programme, si le guide en donne une. */
    val imageUrl: String? = null,
)
