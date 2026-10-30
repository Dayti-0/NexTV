package tv.nextv.core.epg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.ContentSniffer
import tv.nextv.core.io.DetectedContent
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.io.ProgressListener
import tv.nextv.core.io.Streams
import tv.nextv.core.io.TextCharsets
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.source.ContentLimits
import tv.nextv.core.source.ContentPipeline
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * Importe un guide XMLTV — `.xml` ou `.xml.gz`, distant, local ou mis en cache — via [EpgImporter].
 * La compression gzip est détectée sur le contenu lui-même.
 */
class EpgLoader(
    private val opener: ContentOpener,
    private val importer: EpgImporter,
    private val limits: ContentLimits = ContentLimits.EPG,
    private val usesProxy: (RequestPurpose) -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** @throws SourceException */
    suspend fun load(
        location: SourceLocation,
        request: OpenRequest,
        channels: List<EpgChannelRef>,
        sink: ProgrammeSink,
        excludedEpgIds: Set<String> = emptySet(),
        progress: ProgressListener? = null,
    ): EpgImportResult = withContext(ioDispatcher) {
        opener.open(location, request.copy(purpose = RequestPurpose.EPG)).use { content ->
            ContentPipeline.mapReadErrors(usesProxy(RequestPurpose.EPG)) {
                import(content, channels, sink, excludedEpgIds, progress)
            }
        }
    }

    /** Importe un guide déjà téléchargé (cache local). @throws SourceException */
    suspend fun importFile(
        file: File,
        channels: List<EpgChannelRef>,
        sink: ProgrammeSink,
        excludedEpgIds: Set<String> = emptySet(),
        progress: ProgressListener? = null,
    ): EpgImportResult = withContext(ioDispatcher) {
        val stream = try {
            FileInputStream(file)
        } catch (e: FileNotFoundException) {
            throw SourceException(SourceError.FileNotFound, e)
        }
        OpenedContent(stream, contentLength = file.length(), displayName = file.name).use { content ->
            ContentPipeline.mapReadErrors(viaProxy = false) {
                import(content, channels, sink, excludedEpgIds, progress)
            }
        }
    }

    private suspend fun import(
        content: OpenedContent,
        channels: List<EpgChannelRef>,
        sink: ProgrammeSink,
        excludedEpgIds: Set<String>,
        progress: ProgressListener?,
    ): EpgImportResult {
        val context = kotlin.coroutines.coroutineContext
        val total = content.contentLength
        progress?.onProgress(LoadProgress(LoadStep.EPG_IMPORT, 0, total))
        val input = ContentPipeline.prepare(content, limits, progress?.let { listener ->
            { bytes: Long -> listener.onProgress(LoadProgress(LoadStep.EPG_IMPORT, bytes, total)) }
        })
        val head = Streams.peek(input, ContentSniffer.SNIFF_SIZE)
        when (ContentSniffer.sniff(head, TextCharsets.detect(head, content.contentType))) {
            DetectedContent.EMPTY -> throw SourceException(SourceError.EmptyContent)
            DetectedContent.HTML -> throw SourceException(SourceError.HtmlInsteadOfContent)
            DetectedContent.XML -> Unit
            else -> throw SourceException(SourceError.InvalidXmltv)
        }
        val result = importer.import(input, channels, sink, excludedEpgIds) { context.ensureActive() }
        progress?.onProgress(LoadProgress(LoadStep.EPG_IMPORT, total ?: 1, total ?: 1))
        return result
    }
}
