package tv.nextv.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import tv.nextv.core.update.AppRelease
import tv.nextv.app.data.update.UpdateState
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
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Réglages parcourus à la télécommande : tout en haut, le titre de la première carte est entier ; tout
 * en bas, les mentions (licence) aussi, bien qu'aucun des deux ne se choisisse.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class SettingsScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private fun press(key: Key, times: Int) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun scroll() = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    fun `the list reaches both of its ends`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                SettingsContent(
                    state = SampleData.settings,
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onPlaylists = {},
                    onRefreshPlaylist = {},
                    onRefreshGuide = {},
                    onAutoQuality = {},
                    onSaveTmdbKey = {},
                    onClearTmdbKey = {},
                    onProxy = {},
                    now = SampleData.NOW,
                )
            }
        }
        compose.waitForIdle()
        assertTrue("Réglages plus longs que l'écran", scroll().maxValue() > 0f)
        press(Key.DirectionDown, 80)
        assertEquals("Tout en bas", scroll().maxValue(), scroll().value(), 1f)
        press(Key.DirectionUp, 80)
        assertEquals("Tout en haut", 0f, scroll().value(), 1f)
        // Premier élément choisi en descendant à nouveau : la liste reste en haut.
        press(Key.DirectionDown, 2)
        assertEquals("Encore en haut", 0f, scroll().value(), 1f)
    }

    @Test
    fun `every part of the update notes is shown whole`() {
        val sections = (44 downTo 38).map { version -> "Version 0.2.$version\n" + (1..3).joinToString("\n") { "- Changement $it de la version 0.2.$version, assez long pour tenir sur deux lignes à l'écran de la télé" } }
        val release = AppRelease("0.2.44", null, "https://example.invalid/NexTV.apk", 1, null, changes = sections.joinToString("\n\n"))
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                SettingsContent(
                    state = SampleData.settings,
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onPlaylists = {},
                    onRefreshPlaylist = {},
                    onRefreshGuide = {},
                    onAutoQuality = {},
                    onSaveTmdbKey = {},
                    onClearTmdbKey = {},
                    onProxy = {},
                    update = UpdateState.Available(release),
                    now = SampleData.NOW,
                )
            }
        }
        compose.waitForIdle()
        val seen = HashSet<String>()
        repeat(90) {
            press(Key.DirectionDown, 1)
            val focused = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()
            val list = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            fun texts(node: SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
            val text = texts(focused).joinToString("\n")
            val version = Regex("Version 0\\.2\\.(\\d+)\\n").find(text)?.groupValues?.get(1)
            if (version != null) {
                seen += version
                assertTrue("Version 0.2.$version coupée", focused.boundsInRoot.top >= list.top - 1 && focused.boundsInRoot.bottom <= list.bottom + 1)
            }
        }
        assertEquals((38..44).map { it.toString() }.toSet(), seen)
    }
}
