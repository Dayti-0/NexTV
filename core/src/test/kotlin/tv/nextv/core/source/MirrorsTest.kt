package tv.nextv.core.source

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.nextv.core.error.SourceError
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings

class MirrorsTest {

    private val primary = MockWebServer()
    private val backup = MockWebServer()

    @Before
    fun setUp() {
        primary.start()
        backup.start()
    }

    @After
    fun tearDown() {
        primary.close()
        backup.close()
    }

    @Test
    fun `server addresses are normalised`() {
        assertEquals("http://r56mail.top", Mirrors.normalize("r56mail.top"))
        assertEquals("http://serveur.example:8080", Mirrors.normalize("http://serveur.example:8080/get.php?username=a"))
        assertNull(Mirrors.normalize("pas une adresse"))
    }

    @Test
    fun `streams on a known server have alternatives on the others`() {
        val roots = listOf("http://line.example", "http://backup.example:8080")
        assertEquals(
            listOf("http://backup.example:8080/live/u/p/123.ts"),
            Mirrors.alternatives("http://line.example:80/live/u/p/123.ts", roots),
        )
        assertEquals(emptyList<String>(), Mirrors.alternatives("http://cdn.other.example/live/123.ts", roots))
    }

    @Test
    fun `the playlist is loaded from a backup server when the main one fails`() = runBlocking {
        primary.enqueue(MockResponse.Builder().code(503).build())
        backup.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-mpegurl")
                .body("#EXTM3U\n#EXTINF:-1,TF1\n/live/u/p/1.ts\n").build(),
        )
        val loader = M3uPlaylistLoader(
            CompositeContentOpener(listOf(RemoteContentOpener(HttpClientProvider(networkSettings = NetworkSettings(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000))))),
        )
        val source = PlaylistSource(
            name = "Test",
            location = SourceLocation.Remote(primary.url("/get.php?username=u&password=p").toString()),
            mirrors = listOf(backup.url("/").toString().trimEnd('/')),
        )

        val result = loader.load(source, credentials = null)

        assertEquals(backup.url("/live/u/p/1.ts").toString(), result.channels.single().stream.url)
        val request = backup.takeRequest()
        assertEquals("/get.php", request.url.encodedPath)
        assertEquals("u", request.url.queryParameter("username"))
    }

    @Test
    fun `wrong credentials are not retried elsewhere`() {
        assertEquals(false, Mirrors.isServerProblem(SourceError.AuthenticationFailed))
        assertEquals(true, Mirrors.isServerProblem(SourceError.HttpStatus(503)))
    }
}
