package tv.nextv.core.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.catalog.QualityParser
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.DetectedContent
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.ProgressListener
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.ContentSniffer
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.io.Streams
import tv.nextv.core.io.TextCharsets
import tv.nextv.core.m3u.LineTooLongException
import tv.nextv.core.m3u.M3uEntry
import tv.nextv.core.m3u.M3uParser
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind as ModelContentKind
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceType
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.stream.StreamUrlInspector
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

/**
 * Chargeur des playlists M3U / M3U8, distantes ou locales.
 *
 * Le contenu reçu est vérifié avant analyse : une page HTML, une réponse JSON ou un fichier
 * binaire produisent une erreur explicite. Si une URL distante désigne directement un flux
 * (manifeste HLS, `video/mp2t`…), la source est traitée comme une chaîne unique.
 */
class M3uPlaylistLoader(
    private val opener: ContentOpener,
    private val parser: M3uParser = M3uParser(),
    private val limits: ContentLimits = ContentLimits.PLAYLIST,
    private val usesProxy: (RequestPurpose) -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistLoader {

    override val type = SourceType.M3U

    /**
     * Charge la playlist depuis son adresse, puis, si ce serveur ne répond pas, depuis chacun de ses
     * serveurs de secours ([PlaylistSource.mirrors]). En cas d'échec partout, l'erreur du premier
     * serveur est signalée.
     */
    override suspend fun load(source: PlaylistSource, credentials: Credentials?, progress: ProgressListener?): LoadedPlaylist =
        withContext(ioDispatcher) {
            var firstError: SourceException? = null
            for (location in Mirrors.locations(source.location, source.mirrors)) {
                try {
                    return@withContext loadFrom(source, location, credentials, progress)
                } catch (e: SourceException) {
                    if (!Mirrors.isServerProblem(e.error)) throw e
                    if (firstError == null) firstError = e
                }
            }
            throw checkNotNull(firstError)
        }

    private suspend fun loadFrom(
        source: PlaylistSource,
        location: SourceLocation,
        credentials: Credentials?,
        progress: ProgressListener?,
    ): LoadedPlaylist {
        val context = coroutineContext
        val request = OpenRequest(RequestPurpose.PLAYLIST, credentials, userAgent = source.userAgent)
        progress?.onProgress(LoadProgress(LoadStep.PLAYLIST, 0, null))
        return opener.open(location, request).use { content ->
            ContentPipeline.mapReadErrors(usesProxy(RequestPurpose.PLAYLIST)) {
                read(source, location, content, progress) { context.ensureActive() }
            }.copy(userAgent = content.userAgent)
        }
    }

    private fun read(
        source: PlaylistSource,
        location: SourceLocation,
        content: OpenedContent,
        progress: ProgressListener?,
        checkCancelled: () -> Unit,
    ): LoadedPlaylist {
        val remote = location as? SourceLocation.Remote
        if (remote != null && ContentSniffer.isMediaContentType(content.contentType)) {
            return singleStream(source, remote, formatFromContentType(content.contentType))
        }

        val total = content.contentLength
        val input = ContentPipeline.prepare(content, limits, progress?.let { listener ->
            { bytes: Long -> listener.onProgress(LoadProgress(LoadStep.PLAYLIST, bytes, total)) }
        })
        val head = Streams.peek(input, ContentSniffer.SNIFF_SIZE)
        val charset = TextCharsets.detect(head, content.contentType)
        when (ContentSniffer.sniff(head, charset)) {
            DetectedContent.EMPTY -> throw SourceException(SourceError.EmptyContent)
            DetectedContent.HTML -> throw SourceException(SourceError.HtmlInsteadOfContent)
            DetectedContent.XML, DetectedContent.JSON, DetectedContent.BINARY -> throw SourceException(SourceError.NotAPlaylist)
            DetectedContent.MEDIA -> return singleStream(source, remote ?: throw SourceException(SourceError.NotAPlaylist), StreamFormat.PROGRESSIVE)
            DetectedContent.TEXT -> Unit
        }

        val decoder = charset.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        // Chaque entrée est convertie dès sa lecture : la liste des entrées brutes n'existe jamais.
        val base = content.effectiveUrl
        val channels = ArrayList<Channel>()
        val strings = HashMap<String, String>()
        var unsupported = false
        val playlist = try {
            parser.parse(InputStreamReader(input, decoder), checkCancelled) { entry, headerSeen ->
                // Sans en-tête ni #EXTINF, seule une liste d'URL absolues est acceptée : un fichier texte
                // quelconque ne doit pas devenir une liste de « chaînes ».
                if (!headerSeen && !entry.hasExtInf && "://" !in entry.url) return@parse
                val channel = toChannel(channels.size, entry, base, strings)
                if (channel.stream.support == StreamSupport.UNSUPPORTED) unsupported = true
                channels += channel
            }
        } catch (e: LineTooLongException) {
            throw SourceException(SourceError.NotAPlaylist, e)
        }

        if (playlist.isHlsMedia) {
            return singleStream(source, remote ?: throw SourceException(SourceError.NotAPlaylist), StreamFormat.HLS)
        }
        if (channels.isEmpty()) {
            throw SourceException(if (playlist.hasHeader) SourceError.NoChannels else SourceError.NotAPlaylist)
        }
        channels.trimToSize()

        val warnings = buildSet {
            if (playlist.truncated) add(SourceWarning.TRUNCATED)
            if (unsupported) add(SourceWarning.UNSUPPORTED_STREAMS)
        }
        return LoadedPlaylist(channels, playlist.epgUrls, warnings)
    }

    /** @param strings textes déjà rencontrés (groupes…) : une seule copie en mémoire pour des milliers d'entrées. */
    private fun toChannel(position: Int, entry: M3uEntry, baseUrl: String?, strings: MutableMap<String, String>): Channel {
        val stream = StreamUrlInspector.inspect(entry.url, baseUrl, entry.headers, entry.drmProtected)
        val attributes = entry.attributes
        val name = entry.title
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: stream.url.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "#${position + 1}"
        val classification = ContentClassifier.classify(name, stream.url, entry.group, attributes)
        val quality = if (classification.kind == ModelContentKind.LIVE) QualityParser.parse(name) else null
        return Channel(
            position = position,
            name = name,
            stream = stream,
            groupTitle = entry.group?.let { strings.getOrPut(it) { it } },
            logoUrl = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }?.let { resolveLogo(it, baseUrl) },
            tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
            channelNumber = attributes["tvg-chno"]?.takeIf { it.isNotBlank() },
            kind = classification.kind,
            title = quality?.baseName ?: classification.title,
            variantKey = quality?.variantKey.orEmpty(),
            quality = quality?.quality,
            year = classification.year,
            episode = classification.episode,
        )
    }

    private fun resolveLogo(logo: String, baseUrl: String?): String? {
        // Cas courant : URL absolue HTTP(S) sans espace, utilisable telle quelle (analyse complète évitée).
        if ((logo.startsWith("https://", ignoreCase = true) || logo.startsWith("http://", ignoreCase = true)) && logo.none { it.isWhitespace() }) {
            return logo
        }
        val inspected = StreamUrlInspector.inspect(logo, baseUrl)
        return inspected.url.takeIf { inspected.support == StreamSupport.SUPPORTED }
    }

    private fun singleStream(source: PlaylistSource, location: SourceLocation.Remote, format: StreamFormat): LoadedPlaylist {
        val stream = StreamUrlInspector.inspect(location.url, baseUrl = null)
        val channel = Channel(
            position = 0,
            name = source.name,
            stream = if (stream.format == StreamFormat.UNKNOWN) stream.copy(format = format) else stream,
            variantKey = QualityParser.parse(source.name).variantKey,
        )
        return LoadedPlaylist(listOf(channel), warnings = setOf(SourceWarning.SINGLE_STREAM))
    }

    private fun formatFromContentType(contentType: String?): StreamFormat {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (mime) {
            "application/dash+xml" -> StreamFormat.DASH
            "application/vnd.ms-sstr+xml" -> StreamFormat.SMOOTH_STREAMING
            else -> StreamFormat.PROGRESSIVE
        }
    }
}
