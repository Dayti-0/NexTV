package tv.nextv.core.epg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.CountingInputStream
import tv.nextv.core.io.LimitedInputStream
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.ProgressListener
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkErrorMapper
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import tv.nextv.core.source.ContentLimits
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Guide disponible localement.
 *
 * @property updatedAtMillis date à laquelle son contenu a changé pour la dernière fois.
 * @property fetchedAtMillis date de la dernière vérification auprès du serveur.
 * @property stale le serveur n'a pas pu être joint : c'est la copie précédente.
 */
data class EpgFeed(
    val file: File,
    val updatedAtMillis: Long,
    val fetchedAtMillis: Long,
    val stale: Boolean = false,
    val error: SourceError? = null,
)

/** Fournit le guide des programmes utilisé par toutes les playlists. */
interface EpgFeedProvider {
    /**
     * Renvoie le guide local, téléchargé ou revalidé s'il est trop ancien (ou si [force]).
     * @throws SourceException si aucun guide n'est disponible, même en cache.
     */
    suspend fun obtain(force: Boolean = false, progress: ProgressListener? = null): EpgFeed

    /** Guide en cache, sans accès réseau. */
    fun cached(): EpgFeed?
}

/**
 * Guide XMLTV distant mis en cache sur le disque.
 *
 * - Revalidation au plus tard toutes les [maxAgeMillis] avec `If-None-Match` / `If-Modified-Since` :
 *   un `304` évite de retélécharger le fichier.
 * - Téléchargement dans un fichier temporaire puis remplacement atomique : un téléchargement
 *   interrompu ne corrompt jamais la copie existante.
 * - En cas d'échec réseau, la copie précédente reste utilisée (marquée [EpgFeed.stale]).
 */
class HttpEpgFeedProvider(
    private val clients: HttpClientProvider,
    private val url: HttpUrl,
    private val directory: File,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val limits: ContentLimits = ContentLimits.EPG,
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EpgFeedProvider {

    private val dataFile = File(directory, "guide.xmltv")
    private val metaFile = File(directory, "guide.properties")
    private val lock = Mutex()

    override fun cached(): EpgFeed? {
        if (!dataFile.isFile) return null
        val meta = readMeta()
        return EpgFeed(
            file = dataFile,
            updatedAtMillis = meta.getProperty(KEY_UPDATED)?.toLongOrNull() ?: dataFile.lastModified(),
            fetchedAtMillis = meta.getProperty(KEY_FETCHED)?.toLongOrNull() ?: dataFile.lastModified(),
        )
    }

    override suspend fun obtain(force: Boolean, progress: ProgressListener?): EpgFeed = lock.withLock {
        withContext(ioDispatcher) {
            val cached = cached()
            val now = clock()
            if (!force && cached != null && now - cached.fetchedAtMillis in 0 until maxAgeMillis) {
                return@withContext cached
            }
            try {
                download(cached, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? SourceException)?.error ?: NetworkErrorMapper.map(e, url.host, clients.usesProxy(RequestPurpose.EPG))
                logger.warn(TAG, "Guide $url indisponible : ${error.code}", e)
                cached?.copy(stale = true, error = error) ?: throw (e as? SourceException ?: SourceException(error, e))
            }
        }
    }

    private suspend fun download(cached: EpgFeed?, progress: ProgressListener?): EpgFeed {
        val meta = readMeta()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", clients.networkSettings.defaultUserAgent)
            .apply {
                if (cached != null) {
                    meta.getProperty(KEY_ETAG)?.let { header("If-None-Match", it) }
                    meta.getProperty(KEY_LAST_MODIFIED)?.let { header("If-Modified-Since", it) }
                }
            }
            .build()
        progress?.onProgress(LoadProgress(LoadStep.EPG_DOWNLOAD, 0, null))
        val response = try {
            clients.client(RequestPurpose.EPG).newCall(request).await()
        } catch (e: IOException) {
            throw SourceException(NetworkErrorMapper.map(e, url.host, clients.usesProxy(RequestPurpose.EPG)), e)
        }
        response.use {
            val now = clock()
            if (response.code == 304 && cached != null) {
                meta.setProperty(KEY_FETCHED, now.toString())
                writeMeta(meta)
                logger.info(TAG, "Guide inchangé (304)")
                return cached.copy(fetchedAtMillis = now)
            }
            if (!response.isSuccessful) throw SourceException(NetworkErrorMapper.map(response, credentialsProvided = false))

            directory.mkdirs()
            val total = response.body.contentLength().takeIf { it > 0 }
            val temp = File(directory, "guide.download")
            val context = kotlin.coroutines.coroutineContext
            try {
                CountingInputStream(response.body.byteStream()) { bytes ->
                    progress?.onProgress(LoadProgress(LoadStep.EPG_DOWNLOAD, bytes, total))
                }.let { LimitedInputStream(it, limits.maxDownloadBytes) }.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (temp.length() == 0L) throw SourceException(SourceError.EmptyContent)
                if (!temp.renameTo(dataFile)) {
                    dataFile.delete()
                    if (!temp.renameTo(dataFile)) throw IOException("Cannot replace cached guide")
                }
            } finally {
                temp.delete()
            }
            meta.setProperty(KEY_FETCHED, now.toString())
            meta.setProperty(KEY_UPDATED, now.toString())
            meta.putOrRemove(KEY_ETAG, response.header("ETag"))
            meta.putOrRemove(KEY_LAST_MODIFIED, response.header("Last-Modified"))
            writeMeta(meta)
            progress?.onProgress(LoadProgress(LoadStep.EPG_DOWNLOAD, total ?: dataFile.length(), total ?: dataFile.length()))
            logger.info(TAG, "Guide téléchargé : ${dataFile.length()} octets")
            return EpgFeed(dataFile, updatedAtMillis = now, fetchedAtMillis = now)
        }
    }

    private fun readMeta(): Properties = Properties().apply {
        if (metaFile.isFile) runCatching { metaFile.inputStream().use { load(it) } }
    }

    private fun Properties.putOrRemove(key: String, value: String?) {
        if (value != null) setProperty(key, value) else remove(key)
    }

    private fun writeMeta(meta: Properties) {
        directory.mkdirs()
        metaFile.outputStream().use { meta.store(it, null) }
    }

    companion object {
        /** Guide XMLTV français utilisé automatiquement. */
        const val XMLTV_FR_URL = "https://xmltvfr.fr/xmltv/xmltv.xml.gz"
        const val DEFAULT_MAX_AGE_MILLIS = 12 * 60 * 60_000L

        private const val TAG = "EpgFeed"
        private const val BUFFER_SIZE = 64 * 1024
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MODIFIED = "lastModified"
        private const val KEY_FETCHED = "fetchedAt"
        private const val KEY_UPDATED = "updatedAt"
    }
}
