package tv.nextv.core.source

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Ouvre des « documents locaux » en mémoire, comme le ferait le ContentResolver Android. */
class FakeDocumentOpener(private val documents: Map<String, ByteArray>) : ContentOpener {
    override fun supports(location: SourceLocation) = location is SourceLocation.LocalDocument

    override suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent {
        val bytes = documents[(location as SourceLocation.LocalDocument).uri]
            ?: throw SourceException(SourceError.FileNotFound)
        return OpenedContent(bytes.inputStream(), displayName = location.displayName)
    }
}

fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
    GZIPOutputStream(out).use { it.write(text.toByteArray()) }
}.toByteArray()

class M3uPlaylistLoaderTest {

    private val server = MockWebServer()
    private val network = NetworkSettings(connectTimeoutMillis = 2_000, readTimeoutMillis = 1_000)
    private val documents = mutableMapOf<String, ByteArray>()
    private lateinit var loader: M3uPlaylistLoader

    @Before
    fun setUp() {
        server.start()
        val opener = CompositeContentOpener(
            listOf(RemoteContentOpener(HttpClientProvider(networkSettings = network)), FakeDocumentOpener(documents)),
        )
        loader = M3uPlaylistLoader(opener)
    }

    @After
    fun tearDown() = server.close()

    private fun remote(path: String) = PlaylistSource(name = "Test", location = SourceLocation.Remote(server.url(path).toString()))

    private fun load(source: PlaylistSource) = runBlocking { loader.load(source, credentials = null) }

    private fun errorOf(source: PlaylistSource): SourceError = try {
        load(source)
        throw AssertionError("SourceException attendue")
    } catch (e: SourceException) {
        e.error
    }

    private fun respond(body: String, contentType: String = "application/x-mpegurl") {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", contentType).body(body).build())
    }

    @Test
    fun `relative stream urls are resolved against the redirected playlist url`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/lists/fr/playlist.m3u").build())
        respond(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="tf1.fr" tvg-logo="logos/tf1.png" group-title="FR",TF1
            tf1.m3u8
            #EXTINF:-1 tvg-chno="2",France 2
            /live/2.ts|User-Agent=VLC
            #EXTINF:-1,Radio RTMP
            rtmp://radio.example/live
            """.trimIndent(),
        )

        val result = load(remote("/short"))

        val (tf1, f2, radio) = result.channels
        assertEquals(server.url("/lists/fr/tf1.m3u8").toString(), tf1.stream.url)
        assertEquals(StreamFormat.HLS, tf1.stream.format)
        assertEquals(server.url("/lists/fr/logos/tf1.png").toString(), tf1.logoUrl)
        assertEquals("tf1.fr", tf1.tvgId)
        assertEquals("FR", tf1.groupTitle)
        assertEquals(server.url("/live/2.ts").toString(), f2.stream.url)
        assertEquals(mapOf("User-Agent" to "VLC"), f2.stream.headers)
        assertEquals("2", f2.channelNumber)
        assertEquals(StreamSupport.UNSUPPORTED, radio.stream.support)
        assertEquals(listOf("http://epg.example/guide.xml.gz"), result.epgUrls)
        assertEquals(setOf(SourceWarning.UNSUPPORTED_STREAMS), result.warnings)
    }

    @Test
    fun `gzip playlists are detected from their content`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/octet-stream")
                .body(Buffer().write(gzip("#EXTM3U\n#EXTINF:-1,A\nhttp://s/a\n")))
                .build(),
        )

        assertEquals(listOf("A"), load(remote("/list.m3u.gz")).channels.map { it.name })
    }

    @Test
    fun `html pages are rejected explicitly`() {
        respond("<!DOCTYPE html><html><body>Abonnement expiré</body></html>", "text/html")

        assertEquals(SourceError.HtmlInsteadOfContent, errorOf(remote("/list.m3u")))
    }

    @Test
    fun `json and arbitrary text are not playlists`() {
        respond("""{"user_info":{"auth":0}}""", "application/json")
        respond("Bonjour,\nceci n'est pas une playlist\n", "text/plain")

        assertEquals(SourceError.NotAPlaylist, errorOf(remote("/player_api.php")))
        assertEquals(SourceError.NotAPlaylist, errorOf(remote("/notes.txt")))
    }

    @Test
    fun `empty content and playlists without entries`() {
        respond("")
        respond("#EXTM3U\n# rien\n")

        assertEquals(SourceError.EmptyContent, errorOf(remote("/empty.m3u")))
        assertEquals(SourceError.NoChannels, errorOf(remote("/header-only.m3u")))
    }

    @Test
    fun `hls manifest url becomes a single stream channel`() {
        respond("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n", "application/vnd.apple.mpegurl")
        val source = remote("/live/master")

        val result = load(source)

        val channel = result.channels.single()
        assertEquals(source.name, channel.name)
        assertEquals(server.url("/live/master").toString(), channel.stream.url)
        assertEquals(StreamFormat.HLS, channel.stream.format)
        assertEquals(setOf(SourceWarning.SINGLE_STREAM), result.warnings)
    }

    @Test
    fun `video content type becomes a single stream without reading the body`() {
        respond("x".repeat(10), "video/mp2t")

        val result = load(remote("/live/42"))

        assertEquals(StreamFormat.PROGRESSIVE, result.channels.single().stream.format)
        assertEquals(setOf(SourceWarning.SINGLE_STREAM), result.warnings)
    }

    @Test
    fun `oversized content is rejected`() {
        val small = M3uPlaylistLoader(
            RemoteContentOpener(HttpClientProvider(networkSettings = network)),
            limits = ContentLimits(maxDownloadBytes = 1_000, maxDecodedBytes = 1_000),
        )
        respond("#EXTM3U\n" + "#EXTINF:-1,X\nhttp://s/x\n".repeat(200))

        val error = try {
            runBlocking { small.load(remote("/big.m3u"), null) }
            null
        } catch (e: SourceException) {
            e.error
        }

        assertEquals(SourceError.TooLarge(1_000), error)
    }

    @Test
    fun `stalled downloads time out while reading`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("#EXTM3U\n" + "#EXTINF:-1,X\nhttp://s/x\n".repeat(100))
                .throttleBody(64, 2, TimeUnit.SECONDS)
                .build(),
        )

        assertEquals(SourceError.Timeout, errorOf(remote("/slow.m3u")))
    }

    @Test
    fun `local playlists are read and relative urls are flagged`() {
        documents["content://docs/list.m3u"] = "#EXTM3U\n#EXTINF:-1,Absolu\nhttps://s/a.m3u8\n#EXTINF:-1,Relatif\nvideo.mp4\n".toByteArray()
        val source = PlaylistSource(name = "Local", location = SourceLocation.LocalDocument("content://docs/list.m3u", "list.m3u"))

        val (absolute, relative) = load(source).channels

        assertEquals(StreamSupport.SUPPORTED, absolute.stream.support)
        assertEquals(setOf(StreamIssue.UNRESOLVED_RELATIVE_URL), relative.stream.issues)
    }

    @Test
    fun `local files that are hls manifests are rejected`() {
        documents["content://docs/stream.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg.ts\n".toByteArray()
        val source = PlaylistSource(name = "Local", location = SourceLocation.LocalDocument("content://docs/stream.m3u8"))

        assertEquals(SourceError.NotAPlaylist, errorOf(source))
    }

    @Test
    fun `missing local documents are reported`() {
        val source = PlaylistSource(name = "Local", location = SourceLocation.LocalDocument("content://docs/absent.m3u"))

        assertEquals(SourceError.FileNotFound, errorOf(source))
    }

    @Test
    fun `latin1 playlists declared by content type are decoded`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "audio/x-mpegurl; charset=ISO-8859-1")
                .body(Buffer().write("#EXTM3U\n#EXTINF:-1,Télé\nhttp://s/a\n".toByteArray(Charsets.ISO_8859_1)))
                .build(),
        )

        assertTrue(load(remote("/latin1.m3u")).channels.single().name == "Télé")
    }
}
