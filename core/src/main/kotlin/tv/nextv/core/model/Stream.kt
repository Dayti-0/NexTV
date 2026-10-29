package tv.nextv.core.model

/** Format de flux déduit de l'URL ; sert d'indice au lecteur, jamais de garantie. */
enum class StreamFormat {
    HLS,
    DASH,
    SMOOTH_STREAMING,
    PROGRESSIVE,
    RTSP,
    UDP,
    UNKNOWN,
}

/** Raisons pour lesquelles un flux est limité ou non pris en charge. */
enum class StreamIssue {
    /** Schéma d'URL que le lecteur ne sait pas ouvrir (rtmp, mms, srt, file…). */
    UNSUPPORTED_SCHEME,

    /** URL relative dans une playlist locale : aucune base pour la résoudre. */
    UNRESOLVED_RELATIVE_URL,

    /** URL syntaxiquement invalide. */
    MALFORMED_URL,

    /** Flux protégé par DRM (licence Widevine/ClearKey déclarée dans la playlist). */
    DRM_PROTECTED,

    /** Le proxy configuré ne peut pas s'appliquer à ce protocole (RTSP, UDP). */
    PROXY_NOT_APPLICABLE,

    /** Diffusion UDP/multicast : ne fonctionne que sur un réseau qui la route. */
    MULTICAST_NETWORK_REQUIRED,
}

enum class StreamSupport {
    /** Lecture HTTP/HTTPS gérée par Media3, proxy et en-têtes compris. */
    SUPPORTED,

    /** Lecture tentée, avec des limites connues (voir [StreamDescriptor.issues]). */
    LIMITED,

    /** Lecture impossible avec ce lecteur. */
    UNSUPPORTED,
}

/**
 * Flux d'une chaîne tel qu'il sera transmis au lecteur.
 *
 * @property url URL absolue (résolue par rapport à la playlist) sans le suffixe d'en-têtes Kodi.
 * @property headers en-têtes HTTP spécifiques déclarés dans la playlist (User-Agent, Referer…).
 */
data class StreamDescriptor(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val support: StreamSupport = StreamSupport.SUPPORTED,
    val issues: Set<StreamIssue> = emptySet(),
)
