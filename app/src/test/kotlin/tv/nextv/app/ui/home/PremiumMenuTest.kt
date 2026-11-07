package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Menu d'accueil de Cinéma Premium : une tuile par section, parcourue à la télécommande. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class PremiumMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    @Test
    fun `a tile opens its section`() {
        var opened: HomeSection? = null
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                PremiumMenu(
                    current = HomeSection.MOVIES,
                    onSection = { opened = it },
                    onFavorites = {},
                    onContinue = {},
                    onSettings = {},
                    counts = mapOf(HomeSection.TV to 1131, HomeSection.MOVIES to 2400, HomeSection.SERIES to 380),
                    subscription = "Abonnement jusqu’au 10 novembre 2026",
                )
            }
        }
        compose.waitForIdle()
        // Sur la télé, la tuile de la dernière section (Films) a le focus d'office (ici, au toucher, on la choisit).
        compose.onNodeWithText("1131 chaînes").assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Films") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // À droite, les séries.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(HomeSection.SERIES, opened)
    }
}
