package tv.nextv.app.ui.detail

import android.app.Application
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.home.TrailerMemory
import tv.nextv.app.ui.home.TrailerSettings
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.model.ContentKind

/**
 * Fiche Classique / Live Center avec « Bande-annonce en fond » : la bande-annonce est en bas de la fiche. Rien ne se
 * lit tant qu'on n'y est pas descendu ; à la télécommande, ↓ y mène, le cadre est entier à l'écran et la lecture démarre.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class DetailTrailerSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var root: View? = null

    @Before
    fun television() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        TrailerMemory.reset()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun webView(): WebView? = compose.runOnUiThread { root?.rootView?.let(::find) }

    private fun find(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    private fun focusedText() = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.toString().let { Regex("Text : \\[[^]]*]").find(it)?.value }
    }.getOrNull()

    private fun movie(): DetailUiState {
        val base = SampleData.movieDetail
        val available = base.metadata as MetadataState.Available
        return base.copy(
            progress = null,
            metadata = available.copy(details = available.details.copy(overview = "Un long résumé du film. ".repeat(12))),
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit").map { tv.nextv.core.meta.CastMember(it, "Rôle de $it", null) },
        )
    }

    /** Classique : rien n'est lu hors écran ; tout en bas, plein écran tout seul, une fois ; Retour, ↑ puis ↓ : de nouveau. */
    @Test
    fun reachingTheBottomOpensTheTrailerFullScreen() {
        val opened = mutableListOf<String>()
        val open = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            root = LocalView.current
            NexTvTheme(ThemeStyle.NIGHT) {
                DetailContent(
                    state = movie(),
                    kind = ContentKind.MOVIE,
                    snackbarHostState = remember { SnackbarHostState() },
                    onPlay = {},
                    onTrailer = { opened += it; open.value = true },
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onBack = {},
                    trailers = TrailerSettings(autoplay = true, delayMillis = 2_000L),
                    trailerPaused = open.value,
                )
            }
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        assertNull("bande-annonce lue hors de l'écran", webView())
        // ↓ : la distribution (le cadre arrive à l'écran et se lit), puis le cadre : plein écran.
        press(Key.DirectionDown)
        println("↓ ${focusedText()}")
        assertTrue("plein écran trop tôt", opened.isEmpty())
        repeat(3) { if (opened.isEmpty()) press(Key.DirectionDown) }
        assertEquals(listOf("trailer"), opened)
        // « Retour » : la fiche revient, sans rouvrir le plein écran.
        compose.runOnUiThread { open.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, opened.size)
        // Cadre entier à l'écran (sélectionné au retour).
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onAllNodes(hasText("Bande-annonce"), useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        assertTrue("titre de la section hors écran : $title", title >= 0f && title < screen.bottom)
        // La vidéo du cadre reprend à l'écran, en petit.
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertNotNull("bande-annonce du cadre non lue", webView())
        // ↑ puis ↓ : de nouveau en plein écran.
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertEquals(2, opened.size)
    }

    /** Fiche Streaming : la même chose, le plein écran s'ouvre (fenêtre de la bande-annonce). */
    @Test
    fun streamingSheetOpensTheTrailerFullScreenAtTheBottom() {
        compose.setContent {
            root = LocalView.current
            NexTvTheme(ThemeStyle.NIGHT) {
                StreamingDetailContent(
                    state = movie(),
                    kind = ContentKind.MOVIE,
                    window = tv.nextv.app.ui.components.rememberWindowInfo(),
                    trailers = TrailerSettings(autoplay = true, delayMillis = 2_000L),
                    onPlay = {},
                    onResume = {},
                    onRestart = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onOpenItem = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isEmpty())
        repeat(5) {
            if (compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionDown)
                println("↓ ${focusedText()}")
            }
        }
        assertTrue("pas de plein écran en bas de la fiche", compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isNotEmpty())
    }
}
