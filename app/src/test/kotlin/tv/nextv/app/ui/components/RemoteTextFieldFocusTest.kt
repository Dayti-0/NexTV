package tv.nextv.app.ui.components

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.nextv.app.ui.home.SearchField
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Le champ de recherche (télécommande) ne retient pas le focus : « bas » passe à l'élément suivant. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class RemoteTextFieldFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun downLeavesTheField() {
        var below by mutableStateOf(false)
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val start = remember { FocusRequester() }
                Column {
                    SearchField("", "Rechercher", {}, Modifier.focusRequester(start))
                    Box(Modifier.size(100.dp).onFocusChanged { below = it.isFocused }.focusable())
                }
                LaunchedEffect(Unit) { start.requestFocus() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(true, below)
    }

    /**
     * OK ouvre le champ, texte tapé, clavier fermé : « bas » descend quand même vers les résultats (sur la box, le
     * champ gardait la touche pour son curseur : impossible d'ouvrir un résultat), et « Retour » rend la main au cadre.
     */
    @Test
    fun downLeavesTheFieldAfterTyping() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        var below by mutableStateOf(false)
        var query by mutableStateOf("")
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val start = remember { FocusRequester() }
                Column {
                    SearchField(query, "Rechercher", { query = it }, Modifier.focusRequester(start))
                    Box(Modifier.size(100.dp).onFocusChanged { below = it.isFocused }.focusable())
                }
                LaunchedEffect(Unit) { start.requestFocus() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("fra")
        compose.waitForIdle()
        assertEquals("fra", query)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(true, below)
        // Retour au champ, rouvert, puis « Retour » : le cadre garde la sélection.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertEquals(false, below)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(true, below)
    }
}
