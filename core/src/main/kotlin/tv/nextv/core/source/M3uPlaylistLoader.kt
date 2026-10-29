package tv.nextv.core.source

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
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.io.Streams
import tv.nextv.core.io.TextCharsets
import tv.nextv.core.m3u.LineTooLongException
import tv.nextv.core.m3u.M3uEntry
import tv.nextv.core.m3u.M3uParser
import tv.nextv.core.model.Channel
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

    override suspend fun load(source: PlaylistSource, credentials: Credentials?): LoadedPlaylist =
        withContext(ioDispatcher) {
            val context = coroutineContext
            val request = OpenRequest(RequestPurpose.PLAYLIST, credentials, userAgent = source.userAgent)
            opener.open(source.location, request).use { content ->
                ContentPipeline.mapReadErrors(usesProxy(RequestPurpose.PLAYLIST)) {
                    read(source, content) { context.ensureActive() }
                }
            }
        }

    private fun read(source: PlaylistSource, content: OpenedContent, checkCancelled: () -> Unit): LoadedPlaylist {
        val remote = source.location as? SourceLocation.Remote
        if (remote != null && ContentSniffer.isMediaContentType(content.contentType)) {
            return singleStream(source, remote, formatFromContentType(content.contentType))
        }

        val input = ContentPipeline.prepare(content, limits)
        val head = Streams.peek(input, ContentSniffer.SNIFF_SIZE)
        val charset = TextCharsets.detect(head, content.contentType)
        when (ContentSniffer.sniff(head, charset)) {
            ContentKind.EMPTY -> throw SourceException(SourceError.EmptyContent)
            ContentKind.HTML -> throw SourceException(SourceError.HtmlInsteadOfContent)
            ContentKind.XML, ContentKind.JSON, ContentKind.BINARY -> throw SourceException(SourceError.NotAPlaylist)
            ContentKind.MEDIA -> return singleStream(source, remote ?: throw SourceException(SourceError.NotAPlaylist), StreamFormat.PROGRESSIVE)
            ContentKind.TEXT -> Unit
        }

        val decoder = charset.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val playlist = try {
            parser.parse(InputStreamReader(input, decoder), checkCancelled)
        } catch (e: LineTooLongException) {
            throw SourceException(SourceError.NotAPlaylist, e)
        }

        if (playlist.isHlsMedia) {
            return singleStream(source, remote ?: throw SourceException(SourceError.NotAPlaylist), StreamFormat.HLS)
        }
        // Sans en-tête ni #EXTINF, seule une liste d'URL absolues est acceptée : un fichier texte
        // quelconque ne doit pas devenir une liste de « chaînes ».
        val entries = if (playlist.hasHeader) {
            playlist.entries
        } else {
            playlist.entries.filter { it.hasExtInf || "://" in it.url }
        }
        if (entries.isEmpty()) {
            throw SourceException(if (playlist.hasHeader) SourceError.NoChannels else SourceError.NotAPlaylist)
        }

        val base = content.effectiveUrl
        val channels = entries.mapIndexed { index, entry -> toChannel(index, entry, base) }
        val warnings = buildSet {
            if (playlist.truncated) add(SourceWarning.TRUNCATED)
            if (channels.any { it.stream.support == StreamSupport.UNSUPPORTED }) add(SourceWarning.UNSUPPORTED_STREAMS)
        }
        return LoadedPlaylist(channels, playlist.epgUrls, warnings)
    }

    private fun toChannel(position: Int, entry: M3uEntry, baseUrl: String?): Channel {
        val stream = StreamUrlInspector.inspect(entry.url, baseUrl, entry.headers, entry.drmProtected)
        val attributes = entry.attributes
        val name = entry.title
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: stream.url.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "#${position + 1}"
        return Channel(
            position = position,
            name = name,
            stream = stream,
            groupTitle = entry.group,
            logoUrl = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }?.let { resolveLogo(it, baseUrl) },
            tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
            channelNumber = attributes["tvg-chno"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun resolveLogo(logo: String, baseUrl: String?): String? {
        val inspected = StreamUrlInspector.inspect(logo, baseUrl)
        return inspected.url.takeIf { inspected.support == StreamSupport.SUPPORTED }
    }

    private fun singleStream(source: PlaylistSource, location: SourceLocation.Remote, format: StreamFormat): LoadedPlaylist {
        val stream = StreamUrlInspector.inspect(location.url, baseUrl = null)
        val channel = Channel(
            position = 0,
            name = source.name,
            stream = if (stream.format == StreamFormat.UNKNOWN) stream.copy(format = format) else stream,
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
