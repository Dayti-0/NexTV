package tv.nextv.app.ui.home

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bande-annonce du bandeau (page YouTube) quand la mémoire manque : la page fermée par le système ne fait plus
 * fermer l'application (elle est retirée, l'image revient), et les bandes-annonces automatiques attendent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class TrailerMemoryTest {

    @get:Rule
    val compose = createComposeRule()

    private var now = 1_000_000L

    @Before
    fun setUp() {
        TrailerMemory.clock = { now }
        TrailerMemory.reset()
    }

    @After
    fun tearDown() {
        TrailerMemory.reset()
        TrailerMemory.clock = android.os.SystemClock::elapsedRealtime
    }

    private var root: View? = null

    private fun webView(): WebView? = compose.runOnUiThread { root?.rootView?.let(::find) }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            root = androidx.compose.ui.platform.LocalView.current
            content()
        }
    }

    private fun find(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    @Test
    fun `page stopped by the system is removed and trailers wait`() {
        var finished = 0
        compose.mainClock.autoAdvance = false
        show {
            TrailerBackdrop("abc", "fr", Modifier.size(320.dp, 180.dp), delayMillis = 3_000L, onFinished = { finished++ })
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val web = webView()
        assertNotNull("la page se prépare pendant le délai", web)
        web!!
        val client = shadowOf(web).webViewClient
        val handled = compose.runOnUiThread {
            client.onRenderProcessGone(web, object : RenderProcessGoneDetail() {
                override fun didCrash() = false
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
            })
        }
        assertTrue("l'application ne doit pas être fermée avec la page", handled)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertTrue("la page arrêtée est retirée et détruite", shadowOf(web).wasDestroyCalled())
        assertEquals(1, finished)
        assertTrue(TrailerMemory.paused)
    }

    @Test
    fun `no automatic trailer while memory is short, then it comes back`() {
        compose.runOnUiThread { TrailerMemory.pause() }
        compose.mainClock.autoAdvance = false
        val key = androidx.compose.runtime.mutableStateOf("abc")
        show {
            TrailerBackdrop(key.value, "fr", Modifier.size(320.dp, 180.dp), delayMillis = 3_000L)
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertNull(webView())
        // Suspension écoulée : la bande-annonce du contenu suivant se prépare.
        now += TRAILER_MEMORY_PAUSE_MILLIS + 1
        assertFalse(TrailerMemory.paused)
        compose.runOnUiThread { key.value = "def" }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        assertNotNull(webView())
    }

    @Test
    fun `button trailer is still allowed`() {
        compose.runOnUiThread { TrailerMemory.pause() }
        show {
            TrailerBackdrop("abc", "fr", Modifier.size(320.dp, 180.dp), delayMillis = 0L)
        }
        compose.waitForIdle()
        assertNotNull(webView())
    }
}
