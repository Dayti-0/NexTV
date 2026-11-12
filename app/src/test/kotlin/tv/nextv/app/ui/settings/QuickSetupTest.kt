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
import tv.nextv.app.ui.home.LAYOUT_DEMO_TAG
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        // Fenêtre de dialogue ouverte : les touches vont à elle (la dernière racine).
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)

    // Fenêtre de dialogue ouverte : elle a son propre focus, au-dessus de l'écran (le dernier trouvé).
    private fun focusedText(): String = texts(compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().last()).joinToString("\n")

    private val backDispatcher = OnBackPressedDispatcher()
    private fun backOwner(lifecycleOwner: LifecycleOwner) = object : OnBackPressedDispatcherOwner {
        override val lifecycle get() = lifecycleOwner.lifecycle
        override val onBackPressedDispatcher get() = backDispatcher
    }

    private fun back() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun `each step is chosen with the remote, then finish closes it`() {
        var step by mutableStateOf(QuickSetupStep.WELCOME)
        var layout by mutableStateOf(HomeLayout.LIVE_CENTER)
        var quality by mutableStateOf(QualityTarget.BEST)
        var done = false
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner(LocalLifecycleOwner.current)) {
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
        }
        compose.waitForIdle()
        // Une touche pour quitter le mode tactile, puis « Commencer ».
        press(Key.DirectionDown)
        if (!focusedText().startsWith("Commencer")) press(Key.DirectionRight)
        assertEquals("Commencer", focusedText())
        press(Key.DirectionCenter)
        assertEquals(QuickSetupStep.INTERFACE, step)

        // L'interface choisie a le focus ; → puis OK : démonstration de Classique, en plein écran.
        assertTrue(focusedText(), focusedText().startsWith("Live Center"))
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LAYOUT_DEMO_TAG).assertExists()
        // Menu de Classique, puis une section ; Retour ramène au menu, puis propose de garder l'interface.
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        back()
        back()
        assertEquals("Garder celle-ci", focusedText())
        // « Revenir au choix » : rien n'est changé, les cartes reviennent.
        press(Key.DirectionRight)
        if (!focusedText().startsWith("Revenir")) press(Key.DirectionLeft)
        assertEquals("Revenir au choix", focusedText())
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LAYOUT_DEMO_TAG).assertDoesNotExist()
        assertEquals(HomeLayout.LIVE_CENTER, layout)
        assertEquals(QuickSetupStep.INTERFACE, step)

        // Streaming : on y entre, on parcourt, Retour puis « Garder celle-ci » : choisie, étape suivante.
        press(Key.DirectionRight)
        assertTrue(focusedText(), focusedText().contains("Streaming"))
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LAYOUT_DEMO_TAG).assertExists()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        back()
        assertEquals("Garder celle-ci", focusedText())
        press(Key.DirectionCenter)
        assertEquals(HomeLayout.STREAMING, layout)
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
