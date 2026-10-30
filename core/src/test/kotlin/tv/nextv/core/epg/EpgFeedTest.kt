package tv.nextv.core.epg

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.source.gzip
import java.util.concurrent.TimeUnit

class EpgFeedTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var now = 1_000_000L
    private lateinit var provider: HttpEpgFeedProvider
    private val guide = gzip("<tv><channel id=\"a\"><display-name>A</display-name></channel></tv>")

    @Before
    fun setUp() {
        server.start()
        provider = HttpEpgFeedProvider(
            clients = HttpClientProvider(networkSettings = NetworkSettings(readTimeoutMillis = 2_000)),
            url = server.url("/xmltv/xmltv.xml.gz"),
            directory = folder.newFolder("epg"),
            maxAgeMillis = 60_000,
            clock = { now },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun guideResponse() = MockResponse.Builder().code(200)
        .addHeader("ETag", "\"v1\"")
        .addHeader("Last-Modified", "Wed, 28 Oct 2026 04:00:00 GMT")
        .body(Buffer().write(guide))
        .build()

    @Test
    fun `downloads once, serves the cache, then revalidates with 304`() = runBlocking {
        server.enqueue(guideResponse())
        val progress = mutableListOf<LoadProgress>()

        val first = provider.obtain { progress += it }
        assertEquals(guide.size.toLong(), first.file.length())
        assertEquals(now, first.updatedAtMillis)
        assertTrue(progress.any { it.step == LoadStep.EPG_DOWNLOAD && it.fraction == 1f })

        now += 30_000
        provider.obtain() // encore frais : aucune requête
        assertEquals(1, server.requestCount)

        now += 60_000
        server.enqueue(MockResponse.Builder().code(304).build())
        val revalidated = provider.obtain()
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val conditional = server.takeRequest()
        assertEquals("\"v1\"", conditional.headers["If-None-Match"])
        assertEquals("Wed, 28 Oct 2026 04:00:00 GMT", conditional.headers["If-Modified-Since"])
        assertEquals(first.updatedAtMillis, revalidated.updatedAtMillis)
        assertEquals(now, revalidated.fetchedAtMillis)
    }

    @Test
    fun `keeps the cached guide when the server is unreachable`() = runBlocking {
        server.enqueue(guideResponse())
        provider.obtain()

        now += 120_000
        server.enqueue(MockResponse.Builder().code(503).build())
        val stale = provider.obtain()

        assertTrue(stale.stale)
        assertEquals(SourceError.HttpStatus(503), stale.error)
        assertEquals(guide.size.toLong(), stale.file.length())
    }

    @Test
    fun `fails without cache and leaves no partial file`() = runBlocking {
        server.enqueue(guideResponse().newBuilder().throttleBody(16, 3, TimeUnit.SECONDS).build())

        val error = runCatching { provider.obtain() }.exceptionOrNull()

        assertEquals(SourceError.Timeout, (error as SourceException).error)
        assertNull(provider.cached())
        assertFalse(folder.root.walk().any { it.name == "guide.download" })
    }
}
