package tv.nextv.app.ui.common

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.player.RadioTitleService
import tv.nextv.core.log.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Morceau en cours d'une radio sur sa vignette et dans l'aperçu : lu sans qu'elle soit sélectionnée, et jamais celui de
 * la radio précédente après un changement de chaîne (il restait affiché jusqu'à la réponse du service).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RadioSongDisplayTest {
    // Effets sur le fil principal, comme dans l'appli : réponse du service reçue sur un fil d'arrière-plan, l'écran était
    // parfois redessiné depuis ce fil par l'outil de test (« Only the original thread… », échec au hasard sur la CI).
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @get:Rule
    val compose = createComposeRule(effectContext = kotlinx.coroutines.Dispatchers.Main)

    private val server = MockWebServer()
    private val fipAnswer = CountDownLatch(1)
    private val grooveCalls = AtomicInteger()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/livemeta/66" -> {
                    grooveCalls.incrementAndGet()
                    song("Tonight", "Dj Nu-Mark")
                }
                "/livemeta/7" -> {
                    // Service lent : la réponse n'arrive qu'à la fin du test.
                    fipAnswer.await(10, TimeUnit.SECONDS)
                    song("Autumn Leaves", "Chet Baker")
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        fipAnswer.countDown()
        server.close()
    }

    private fun song(title: String, artist: String) = MockResponse.Builder().body(
        """{"steps":{"s":{"title":"$title","authors":"$artist","embedType":"song"}},"levels":[{"items":["s"],"position":0}]}""",
    ).build()

    private fun shown(): String = compose.onNodeWithTag("song").fetchSemanticsNode().config
        .let { config -> config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } }

    @Test
    fun theSongOfThePreviousRadioNeverStaysOnTheNextOne() {
        val service = RadioTitleService({ OkHttpClient() }, Logger.None, radioFranceUrl = { server.url("/livemeta/$it").toString() })
        var name by mutableStateOf("FIP Groove")
        compose.setContent {
            CompositionLocalProvider(LocalRadioTitles provides service) {
                Text(noProgrammeText(name, "Web radios"), Modifier.testTag("song"))
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Tonight", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Autre radio dont le service n'a pas encore répondu : son genre, plus le morceau de la précédente.
        compose.runOnIdle { name = "FIP" }
        compose.waitForIdle()
        assertEquals("Éclectique", shown())

        fipAnswer.countDown()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Autumn Leaves", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Retour sur la première : son morceau, déjà connu, revient tout de suite, sans nouvel appel.
        compose.runOnIdle { name = "FIP Groove" }
        compose.waitForIdle()
        assertEquals(true, shown().contains("Tonight"))
        assertEquals(1, grooveCalls.get())
    }
}
