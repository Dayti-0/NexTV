package tv.nextv.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.stream.QualityTarget

/** Configuration rapide à la télécommande : une étape après l'autre, chaque choix enregistré. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class QuickSetupTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)

    private fun focusedText(): String = texts(compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()).joinToString("\n")

    @Test
    fun `each step is chosen with the remote, then finish closes it`() {
        var step by mutableStateOf(QuickSetupStep.WELCOME)
        var layout by mutableStateOf(HomeLayout.LIVE_CENTER)
        var quality by mutableStateOf(QualityTarget.BEST)
        var done = false
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                QuickSetupContent(
                    step = step,
                    onStep = { step = it },
                    onDone = { done = true },
                    layout = layout,
                    onLayout = { layout = it },
                    theme = ThemeStyle.NIGHT,
                    onTheme = {},
                    quality = quality,
                    onQuality = { quality = it },
                    preview = true,
                    onPreview = {},
                    trailer = true,
                    onTrailer = {},
                    lastChannel = false,
                    onLastChannel = {},
                )
            }
        }
        compose.waitForIdle()
        // Une touche pour quitter le mode tactile, puis « Commencer ».
        press(Key.DirectionDown)
        if (!focusedText().startsWith("Commencer")) press(Key.DirectionRight)
        assertEquals("Commencer", focusedText())
        press(Key.DirectionCenter)
        assertEquals(QuickSetupStep.INTERFACE, step)

        // L'interface choisie a le focus ; → puis OK en choisit une autre.
        assertTrue(focusedText(), focusedText().startsWith("Live Center"))
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(HomeLayout.STREAMING, layout)
        press(Key.DirectionDown)
        if (!focusedText().startsWith("Suivant")) press(Key.DirectionRight)
        assertEquals("Suivant", focusedText())
        press(Key.DirectionCenter)
        assertEquals(QuickSetupStep.COLORS, step)

        // Lecture : la qualité choisie a le focus, → puis OK en choisit une autre.
        compose.runOnIdle { step = QuickSetupStep.PLAYBACK }
        compose.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(QualityTarget.HD, quality)

        compose.runOnIdle { step = QuickSetupStep.DONE }
        compose.waitForIdle()
        assertEquals("Terminer", focusedText())
        press(Key.DirectionCenter)
        assertTrue(done)
    }
}
