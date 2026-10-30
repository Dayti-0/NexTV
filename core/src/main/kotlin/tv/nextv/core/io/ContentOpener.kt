package tv.nextv.core.io

import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.RequestPurpose
import java.io.Closeable
import java.io.InputStream

/**
 * Ouvre le contenu désigné par un [SourceLocation].
 *
 * Une implémentation par origine : HTTP(S) dans le cœur ([RemoteContentOpener]), documents
 * Android via `ContentResolver` dans l'application. Les erreurs sont toujours signalées par
 * une [SourceException] typée.
 */
interface ContentOpener {
    fun supports(location: SourceLocation): Boolean

    /** @throws SourceException */
    suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent
}

data class OpenRequest(
    val purpose: RequestPurpose,
    val credentials: Credentials? = null,
    /** Origine à laquelle les identifiants sont liés ; par défaut, celle de la ressource ouverte. */
    val credentialsOrigin: String? = null,
    val userAgent: String? = null,
)

/**
 * Contenu ouvert. Le flux doit être fermé par l'appelant ([close]).
 *
 * @property effectiveUrl URL finale après redirections (base de résolution des URL relatives),
 * `null` pour un contenu local.
 */
class OpenedContent(
    val stream: InputStream,
    val effectiveUrl: String? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val displayName: String? = null,
    /** User-Agent de repli accepté par le serveur, qui refusait celui de l'application (HTTP 403). */
    val userAgent: String? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            stream.close()
        } finally {
            onClose()
        }
    }
}

/** Délègue au premier [ContentOpener] qui prend en charge l'emplacement. */
class CompositeContentOpener(private val openers: List<ContentOpener>) : ContentOpener {

    override fun supports(location: SourceLocation) = openers.any { it.supports(location) }

    override suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent {
        val opener = openers.firstOrNull { it.supports(location) }
            ?: throw SourceException(SourceError.UnsupportedSourceType(location.javaClass.simpleName))
        return opener.open(location, request)
    }
}
