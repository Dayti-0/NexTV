package tv.nextv.app.data.local

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.log.Logger
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.DocumentAccess
import java.io.FileNotFoundException

/** Ouvre les documents choisis avec le sélecteur de fichiers (Storage Access Framework). */
class ContentResolverOpener(
    private val resolver: ContentResolver,
    private val logger: Logger,
) : ContentOpener {

    override fun supports(location: SourceLocation) = location is SourceLocation.LocalDocument

    override suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent = withContext(Dispatchers.IO) {
        val document = location as SourceLocation.LocalDocument
        val uri = Uri.parse(document.uri)
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            throw SourceException(SourceError.FileNotFound, e)
        } catch (e: SecurityException) {
            // Autorisation perdue : fichier déplacé, application de stockage désinstallée…
            logger.warn(TAG, "Accès refusé au document ${document.displayName.orEmpty()}", e)
            throw SourceException(SourceError.PermissionDenied, e)
        } catch (e: IllegalArgumentException) {
            throw SourceException(SourceError.FileNotFound, e)
        } ?: throw SourceException(SourceError.FileNotFound)

        OpenedContent(
            stream = stream,
            contentType = runCatching { resolver.getType(uri) }.getOrNull(),
            displayName = document.displayName,
        )
    }

    private companion object {
        const val TAG = "ContentResolverOpener"
    }
}

/** Autorisations persistantes sur les documents importés. */
class AndroidDocumentAccess(
    private val resolver: ContentResolver,
    private val logger: Logger,
) : DocumentAccess {

    /**
     * Conserve l'accès au document après redémarrage, pour pouvoir l'actualiser plus tard.
     * @return faux si le fournisseur ne propose pas d'autorisation persistante (lecture possible
     * pendant la session uniquement).
     */
    fun persist(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        logger.warn(TAG, "Autorisation persistante refusée par le fournisseur du document", e)
        false
    }

    override fun release(location: SourceLocation.LocalDocument) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(location.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Aucune autorisation détenue : rien à rendre.
        }
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        const val TAG = "DocumentAccess"
    }
}
