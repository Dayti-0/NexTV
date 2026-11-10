package tv.nextv.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Réglages › Interface et couleurs : à l'ouverture, la sélection est sur l'interface choisie. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class AppearanceFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        // Télécommande : pas de mode tactile (où Android refuse de poser la sélection d'office).
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun selectedInterfaceHasTheFocus() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                AppearanceContent(theme = ThemeStyle.NIGHT, previous = null, layout = HomeLayout.PREMIUM, onBack = {}, onTheme = {}, onLayout = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(isFocused() and hasText("Classique")).assertExists()
    }
}
