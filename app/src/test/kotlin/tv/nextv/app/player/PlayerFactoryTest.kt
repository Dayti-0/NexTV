package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.net.HttpClientProvider

/** Un même lecteur pour plusieurs flux : chacun avec sa source réseau (User-Agent, en-têtes, identifiants). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerFactoryTest {

    private val server = MockWebServer()
    private lateinit var factory: PlayerFactory
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        server.start()
        factory = PlayerFactory(RuntimeEnvironment.getApplication(), HttpClientProvider())
        player = factory.create()
    }

    @After
    fun tearDown() {
        player.release()
        server.close()
    }

    @Test
    fun eachStreamUsesItsOwnNetworkSource() {
        // Premier flux : User-Agent et en-tête de sa playlist, identifiants présentés au défi du serveur.
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"iptv\"").build())
        server.enqueue(MockResponse.Builder().body("pas une vidéo").build())
        val origin = server.url("/").toString()
        player.setMediaSource(
            factory.mediaSource(
                PlaybackRequest(
                    stream = stream("/a.ts", headers = mapOf("X-Stream" to "a")),
                    userAgent = "Alpha/1",
                    credentials = Credentials("abonne", "secret"),
                    credentialsOrigin = origin,
                ),
            ),
        )
        player.prepare()
        val challenged = take()
        assertEquals("/a.ts", challenged.url.encodedPath)
        assertEquals("Alpha/1", challenged.headers["User-Agent"])
        assertEquals("a", challenged.headers["X-Stream"])
        assertNull(challenged.headers["Authorization"])
        val authorized = take()
        assertEquals("Alpha/1", authorized.headers["User-Agent"])
        assertNotNull(authorized.headers["Authorization"])

        // Flux suivant dans le même lecteur : ni l'en-tête, ni le User-Agent, ni les identifiants du précédent.
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"iptv\"").build())
        player.stop()
        player.setMediaSource(
            factory.mediaSource(
                PlaybackRequest(
                    stream = stream("/b.ts", headers = mapOf("User-Agent" to "Beta/2")),
                    userAgent = "Alpha/1",
                    credentials = null,
                    credentialsOrigin = null,
                ),
            ),
        )
        player.prepare()
        val next = take()
        assertEquals("/b.ts", next.url.encodedPath)
        // User-Agent de l'en-tête du flux, prioritaire sur celui de la playlist.
        assertEquals("Beta/2", next.headers["User-Agent"])
        assertNull(next.headers["X-Stream"])
        assertNull(next.headers["Authorization"])
    }

    private fun stream(path: String, headers: Map<String, String>) = StreamDescriptor(
        url = server.url(path).toString(),
        headers = headers,
        format = StreamFormat.PROGRESSIVE,
    )

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) { "aucune requête reçue" }
}
