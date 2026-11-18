package tv.nextv.app.ui.player

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import tv.nextv.core.audio.AudioSnippet
import tv.nextv.core.log.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Morceau en cours d'une radio qui ne l'annonce pas : service de Radio France pour les siennes, reconnaissance à l'écoute
 * pour les autres ; rien de demandé quand la radio annonce elle-même le morceau.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RadioNowPlayingTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(PlayerUiState())
    private val snippet = AudioSnippet()
    private val radioFrance = AtomicInteger()
    private val recognitions = AtomicInteger()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/livemeta/66" -> {
                    radioFrance.incrementAndGet()
                    MockResponse.Builder().body(
                        """{"steps":{"s":{"title":"Tonight","authors":"Dj Nu-Mark","embedType":"song","visual":"https://example.com/c.jpg"}},
                        "levels":[{"items":["s"],"position":0}]}""",
                    ).build()
                }
                "/skyrock" -> MockResponse.Builder().body(
                    """{"schedule":[{"type":"record","info":{"title":"Sicario","start_ts":"0","end_ts":"9999999999",
                    "cover_mid_uri":"https://media.skyrock.fm/b.jpg"}}]}""",
                ).build()
                "/indes/www.jazzradio.fr" -> MockResponse.Builder().body(
                    """{"1":{"artist":"MADELEINE PEYROUX","title":"Walkin' after midnight","cover":"https://covers.eg-ad.fr/m.jpg"}}""",
                ).build()
                "/audd" -> {
                    recognitions.incrementAndGet()
                    MockResponse.Builder().body(
                        """{"status":"success","result":{"artist":"Tonic","title":"Basement Ska","timecode":"00:10",
                        "apple_music":{"durationInMillis":200000}}}""",
                    ).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun nowPlaying() = RadioNowPlaying(
        scope = scope,
        state = state,
        client = { OkHttpClient() },
        snippet = snippet,
        auddKey = { null },
        logger = Logger.None,
        radioFranceUrl = { server.url("/livemeta/$it").toString() },
        skyrockUrl = server.url("/skyrock").toString(),
        indesUrl = { site -> server.url("/indes/$site").toString() },
        recognitionUrl = server.url("/audd").toString(),
    )

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test
    fun radioFranceStationsShowTheirSongWithItsCover() {
        nowPlaying().follow(active = true, channelName = "FIP Groove")
        waitFor { state.value.nowPlaying != null }
        assertEquals("Dj Nu-Mark – Tonight", state.value.nowPlaying)
        assertEquals("https://example.com/c.jpg", state.value.nowPlayingCover)
        assertEquals(0, recognitions.get())
    }

    /** Radios qui publient leur morceau en cours : Skyrock (radio de la playlist, par son nom), Jazz Radio (Les Indés). */
    @Test
    fun skyrockAndLesIndesShowTheirSongWithoutRecognition() {
        val follower = nowPlaying()
        follower.follow(active = true, channelName = "|FR| SkyRock FM")
        waitFor { state.value.nowPlaying != null }
        assertEquals("Sicario", state.value.nowPlaying)
        follower.follow(active = true, channelName = "Jazz Radio")
        waitFor { state.value.nowPlaying?.contains("PEYROUX") == true }
        assertEquals("MADELEINE PEYROUX – Walkin' after midnight", state.value.nowPlaying)
        assertEquals("https://covers.eg-ad.fr/m.jpg", state.value.nowPlayingCover)
        assertEquals(0, recognitions.get())
    }

    @Test
    fun otherRadiosAreRecognizedByEar() {
        val follower = nowPlaying()
        follower.follow(active = true, channelName = "Tonic Ska Radio")
        val samples = FloatArray(44_100) { i -> (0.4 * sin(2 * PI * 440 * i / 44_100)).toFloat() }
        repeat(9) { snippet.write(samples, samples.size, 44_100) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        waitFor { state.value.nowPlaying != null }
        assertEquals("Tonic – Basement Ska", state.value.nowPlaying)
        assertEquals(1, recognitions.get())
        // Radio fermée : plus rien d'affiché.
        follower.follow(active = false, channelName = "Tonic Ska Radio")
        assertNull(state.value.nowPlaying)
    }

    @Test
    fun nothingIsAskedWhenTheRadioAnnouncesTheSong() {
        state.update { it.copy(radioTitle = "Yellowman - Rub A Dub") }
        nowPlaying().follow(active = true, channelName = "Alpha Boys School Radio")
        val samples = FloatArray(44_100) { i -> (0.4 * sin(2 * PI * 440 * i / 44_100)).toFloat() }
        repeat(9) { snippet.write(samples, samples.size, 44_100) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        Thread.sleep(2_500)
        assertEquals(0, recognitions.get())
        assertTrue(state.value.nowPlaying == null)
    }
}
