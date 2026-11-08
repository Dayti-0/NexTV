package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Menu de gauche de Streaming sur un écran de télé : « Réglages », tout en bas, reste entier et lisible. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class StreamingNavTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)

    private fun focused(): SemanticsNode = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun `settings stays whole at the bottom of the open menu`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                Row(Modifier.fillMaxSize()) {
                    StreamingNav(
                        section = HomeSection.TV,
                        window = rememberWindowInfo(),
                        onSection = {},
                        onSearch = {},
                        onCategories = {},
                        onSettings = {},
                    )
                    Box(Modifier.fillMaxSize()) {
                        FocusSurface(onClick = {}) { Text("Contenu", Modifier.size(120.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        repeat(12) { if (!texts(focused()).contains("Réglages")) press(Key.DirectionDown) }
        assertTrue(texts(focused()).toString(), texts(focused()).contains("Réglages"))
        val item = focused().boundsInRoot
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertTrue("Hauteur ${item.height / density} dp", item.height / density >= 38f)
        assertTrue("Dans l'écran", item.bottom / density <= 540f)
        assertEquals(1, texts(focused()).count { it == "Réglages" })
    }
}
