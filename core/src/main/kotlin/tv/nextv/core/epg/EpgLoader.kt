package tv.nextv.core.epg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.ContentKind
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.ContentSniffer
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.Streams
import tv.nextv.core.io.TextCharsets
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.source.ContentLimits
import tv.nextv.core.source.ContentPipeline

/**
 * Télécharge (ou ouvre) un guide XMLTV — `.xml` ou `.xml.gz`, distant ou local — et
 * l'importe via [EpgImporter]. La compression gzip est détectée sur le contenu lui-même.
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
    ): EpgImportResult = withContext(ioDispatcher) {
        val context = coroutineContext
        opener.open(location, request.copy(purpose = RequestPurpose.EPG)).use { content ->
            ContentPipeline.mapReadErrors(usesProxy(RequestPurpose.EPG)) {
                val input = ContentPipeline.prepare(content, limits)
                val head = Streams.peek(input, ContentSniffer.SNIFF_SIZE)
                when (ContentSniffer.sniff(head, TextCharsets.detect(head, content.contentType))) {
                    ContentKind.EMPTY -> throw SourceException(SourceError.EmptyContent)
                    ContentKind.HTML -> throw SourceException(SourceError.HtmlInsteadOfContent)
                    ContentKind.XML -> Unit
                    else -> throw SourceException(SourceError.InvalidXmltv)
                }
                importer.import(input, channels, sink, excludedEpgIds) { context.ensureActive() }
            }
        }
    }
}
