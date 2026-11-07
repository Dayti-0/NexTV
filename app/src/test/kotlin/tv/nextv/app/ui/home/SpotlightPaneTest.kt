package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Volet d'un film ou d'une série (Live Center) : le résumé n'est pas coupé (« … »), et « bas » le fait
 * défiler jusqu'à sa dernière ligne. Rendu natif : le texte y est découpé en lignes comme sur la télé.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class SpotlightPaneTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    @Test
    fun theWholeOverviewCanBeRead() {
        val overview = (1..30).joinToString(" ") { "Phrase $it du résumé, assez longue pour occuper une ligne du volet." } + " Fin du résumé."
        val pane = FocusRequester()
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    SpotlightPane(Spotlight(SampleData.movies[0].toPoster().copy(overview = overview)), Modifier.fillMaxSize().focusRequester(pane))
                }
            }
        }
        compose.waitForIdle()
        val paneHeight = with(compose.density) { 480.dp.toPx() }
        val text = compose.onNodeWithText(overview).fetchSemanticsNode()
        assertTrue("Résumé entier, plus haut que le volet : ${text.size}", text.size.height > paneHeight)
        compose.runOnIdle { pane.requestFocus() }
        repeat(20) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
        val bottom = compose.onNodeWithText(overview).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
        assertTrue("Fin du résumé visible après défilement : bas du texte $bottom, volet $paneHeight", bottom <= paneHeight)
    }
}
