package tv.nextv.app.ui.common

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.ProgrammeSheetContent
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Fiche d'un programme d'une chaîne flamande : sa description néerlandaise est affichée en français. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class TranslationTest {

    @get:Rule
    val compose = createComposeRule()

    private val dutch = "Liveverslag van de eerste helft van Turkijë - België, vanuit het Gürsel Akselstadion in Izmir."
    private val french = "Reportage en direct de la première mi-temps de Turquie - Belgique, depuis le stade d'Izmir."

    @Test
    fun `foreign descriptions are shown in french`() {
        val now = 1_700_000_000_000L
        val programme = GuideProgramme("Turkije - België", now, now + 3_600_000L, null, dutch, "Voetbal", null)
        val channel = GuideChannel(1, "BE| VTM", null, null, listOf(programme))
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                CompositionLocalProvider(LocalTranslator provides { text -> french.takeIf { text == dutch } }) {
                    ProgrammeSheetContent(channel, programme, now, onWatch = {}, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(french).assertIsDisplayed()
    }
}
