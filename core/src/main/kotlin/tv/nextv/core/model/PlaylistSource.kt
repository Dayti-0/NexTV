package tv.nextv.core.model

import tv.nextv.core.error.SourceError

/**
 * Source de chaînes enregistrée par l'utilisateur.
 *
 * Les identifiants ne font pas partie du modèle : ils sont conservés chiffrés à part
 * ([tv.nextv.core.repository.CredentialStore]) et [hasCredentials] indique seulement leur présence.
 */
data class PlaylistSource(
    val id: Long = 0,
    val name: String,
    val type: SourceType = SourceType.M3U,
    val location: SourceLocation,
    val epg: EpgSettings = EpgSettings(),
    val userAgent: String? = null,
    val hasCredentials: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val status: SourceStatus = SourceStatus(),
)

/**
 * Configuration du guide des programmes.
 *
 * @property location guide explicite (URL XMLTV ou fichier XML / XML.GZ importé).
 * @property useHeaderUrls utiliser aussi les guides annoncés par l'en-tête de la playlist (`url-tvg`).
 */
data class EpgSettings(
    val location: SourceLocation? = null,
    val useHeaderUrls: Boolean = true,
)

/** Particularités constatées lors du dernier chargement réussi. */
enum class SourceWarning {
    /** La playlist dépassait le nombre maximal d'entrées et a été tronquée. */
    TRUNCATED,

    /** L'URL désignait directement un flux vidéo : la source contient une seule chaîne. */
    SINGLE_STREAM,

    /** Certaines entrées pointent vers des flux non pris en charge par le lecteur. */
    UNSUPPORTED_STREAMS,
}

data class SourceStatus(
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val channelCount: Int = 0,
    val error: SourceError? = null,
    val warnings: Set<SourceWarning> = emptySet(),
    /** Guides EPG annoncés par l'en-tête de la playlist lors du dernier chargement. */
    val headerEpgUrls: List<String> = emptyList(),
    val epg: EpgStatus = EpgStatus(),
)

data class EpgStatus(
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val programmeCount: Int = 0,
    val matchedChannelCount: Int = 0,
    val error: SourceError? = null,
)
