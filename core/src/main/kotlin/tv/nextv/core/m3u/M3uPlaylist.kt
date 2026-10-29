package tv.nextv.core.m3u

/**
 * Entrée brute d'une playlist M3U, avant résolution de l'URL du flux.
 *
 * @property attributes attributs de `#EXTINF` (`tvg-id`, `tvg-logo`, `group-title`…), clés en minuscules.
 * @property headers en-têtes HTTP déclarés par `#EXTVLCOPT`, `#EXTHTTP` ou `#KODIPROP`.
 * @property drmProtected une licence DRM est déclarée (`inputstream.adaptive.license_type`…).
 */
data class M3uEntry(
    val title: String?,
    val url: String,
    val duration: Double? = null,
    val attributes: Map<String, String> = emptyMap(),
    val group: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmProtected: Boolean = false,
    /** L'entrée était précédée d'une ligne `#EXTINF`. */
    val hasExtInf: Boolean = false,
)

/**
 * @property hasHeader le contenu commence par `#EXTM3U`.
 * @property truncated la limite d'entrées a été atteinte.
 * @property isHlsMedia le contenu est un manifeste HLS (flux vidéo) et non une liste de chaînes.
 */
data class M3uPlaylist(
    val headerAttributes: Map<String, String>,
    val entries: List<M3uEntry>,
    val hasHeader: Boolean = false,
    val truncated: Boolean = false,
    val isHlsMedia: Boolean = false,
) {
    /** Guides XMLTV annoncés par l'en-tête (`url-tvg`, `x-tvg-url`, `tvg-url`), séparés par des virgules. */
    val epgUrls: List<String>
        get() = EPG_HEADER_KEYS
            .mapNotNull { headerAttributes[it] }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private companion object {
        val EPG_HEADER_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")
    }
}
