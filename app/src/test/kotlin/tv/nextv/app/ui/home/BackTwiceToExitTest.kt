package tv.nextv.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * « Retour » sur l'accueil : pas de fenêtre « Quitter NixTV ? », mais « Appuyer à nouveau pour quitter » pendant 3 s ;
 * seul un second appui pendant ce temps ferme l'application, sinon le message revient au prochain appui.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR")
class BackTwiceToExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun hintShown() = compose.onAllNodesWithTextCompat("Appuyer à nouveau pour quitter").isNotEmpty()

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCompat(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun aSecondBackWithinThreeSecondsLeaves() {
        var exits = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { NexTvTheme(ThemeStyle.NIGHT) { BackTwiceToExit(onExit = { exits++ }) } }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(!hintShown())
        // Premier appui : le message, l'application reste ouverte.
        back()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(hintShown())
        assertEquals(0, exits)
        // Second appui dans les 3 s : elle se ferme.
        back()
        assertEquals(1, exits)
    }

    @Test
    fun afterThreeSecondsTheMessageGoesAndBackShowsItAgain() {
        var exits = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { NexTvTheme(ThemeStyle.NIGHT) { BackTwiceToExit(onExit = { exits++ }) } }
        compose.mainClock.advanceTimeByFrame()
        back()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(hintShown())
        // 3 s plus tard : le message a disparu, l'application est toujours là.
        compose.mainClock.advanceTimeBy(3_100)
        assertTrue(!hintShown())
        // Nouvel appui : le message revient, sans fermer.
        back()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(hintShown())
        assertEquals(0, exits)
    }
}
