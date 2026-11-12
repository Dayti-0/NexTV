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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.model.ContentKind

/**
 * Bande-annonce en bas de la fiche, dans toutes les interfaces : une grande image sur toute la largeur de l'écran, qui
 * dépasse en bas de la fiche (bouton de lecture dans sa partie visible). Rien ne se lit à cet endroit ; à la
 * télécommande, ↓ jusqu'à elle l'ouvre en plein écran. Plus de bouton « Bande-annonce » en haut de la fiche.
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

    /** Image de la bande-annonce (surface cliquable qui porte le bouton de lecture). */
    private fun trailerImage() = compose.onAllNodes(hasContentDescription("Bande-annonce") and hasClickAction()).fetchSemanticsNodes().singleOrNull()

    /** Bouton de lecture : s'il est à l'écran, il est au milieu de la partie visible de l'image (jamais hors écran). */
    private fun assertPlayButtonOnScreen() {
        val image = trailerImage() ?: return
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // Pas encore à l'écran (préparée par la liste plus bas : partie visible vide) : rien à vérifier.
        if (!image.layoutInfo.isPlaced || image.boundsInRoot.height <= 0f) return
        val button = compose.onAllNodes(hasContentDescription("Bande-annonce"), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { !it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
            .minBy { it.boundsInRoot.width }
        val center = button.boundsInRoot.center.y
        println("image ${image.boundsInRoot}, bouton à $center (écran ${screen.bottom})")
        assertTrue("bouton de lecture hors écran : $center", center > image.boundsInRoot.top && center < screen.bottom)
    }

    private fun movie(): DetailUiState {
        val base = SampleData.movieDetail
        val available = base.metadata as MetadataState.Available
        return base.copy(
            progress = null,
            metadata = available.copy(details = available.details.copy(overview = "Un long résumé du film. ".repeat(12))),
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit").map { tv.nextv.core.meta.CastMember(it, "Rôle de $it", null) },
        )
    }

    /** Classique : plein écran tout seul en bas de la fiche, une fois ; Retour, ↑ puis ↓ : de nouveau. */
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
                    trailerPaused = open.value,
                )
            }
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        // Plus de bouton « Bande-annonce » en haut : la bande-annonce est en bas de la fiche.
        assertTrue(compose.onAllNodes(hasText("Bande-annonce") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        repeat(5) {
            if (opened.isEmpty()) {
                press(Key.DirectionDown)
                println("↓ ${focusedText()}")
                if (opened.isEmpty()) assertPlayButtonOnScreen()
            }
        }
        assertEquals(listOf("trailer"), opened)
        // Rien n'est lu en bas de la fiche : seulement le plein écran.
        assertNull("vidéo lue dans la fiche", webView())
        // « Retour » : la fiche revient, sans rouvrir le plein écran.
        compose.runOnUiThread { open.value = false }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals(1, opened.size)
        assertNull("vidéo lue dans la fiche", webView())
        // L'image prend toute la largeur de l'écran.
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val image = checkNotNull(trailerImage()).boundsInRoot
        assertEquals(screen.width, image.width, 1f)
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
        assertTrue(compose.onAllNodes(hasText("Bande-annonce") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        repeat(6) {
            if (compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionDown)
                println("↓ ${focusedText()}")
            }
        }
        assertTrue("pas de plein écran en bas de la fiche", compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isNotEmpty())
    }
}
