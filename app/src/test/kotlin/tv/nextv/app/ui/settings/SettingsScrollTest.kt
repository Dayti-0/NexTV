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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import tv.nextv.app.data.update.UpdateState
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.update.AppRelease

/**
 * Réglages à la télécommande : la liste des pages à gauche, la page choisie à droite. Chaque page
 * défile d'un bout à l'autre ; les nouveautés d'une mise à jour s'y lisent en entier.
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

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)

    private fun focusedText(): String = texts(compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()).joinToString("\n")

    /** Page affichée : la liste qui défile à droite. */
    private fun page(): SemanticsNode = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.left }

    private fun scroll() = page().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun setContent(update: UpdateState = UpdateState.Idle) {
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
                    update = update,
                    now = SampleData.NOW,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Descend la liste jusqu'à la page [title]. */
    private fun selectPage(title: String) {
        press(Key.DirectionDown)
        repeat(12) {
            if (focusedText().startsWith(title)) return
            press(Key.DirectionDown)
        }
        throw AssertionError("Page « $title » introuvable (sur « ${focusedText()} »)")
    }

    @Test
    fun `moving down the list shows each page`() {
        setContent()
        selectPage("Lecture")
        assertTrue(shown("Qualité de l’image, son et ouverture des chaînes"))
        assertTrue(shown("Qualité visée"))
        press(Key.DirectionDown)
        assertTrue(focusedText(), focusedText().startsWith("Affichage"))
        assertTrue(shown("Aperçu et bande-annonce"))
        // La page précédente n'est plus affichée.
        assertTrue(!shown("Qualité visée"))
    }

    @Test
    fun `a page reaches both of its ends, then left goes back to its name`() {
        setContent()
        selectPage("Lecture")
        press(Key.DirectionRight)
        assertTrue("Dans la page", !focusedText().startsWith("Lecture"))
        assertTrue("Page plus longue que l'écran", scroll().maxValue() > 0f)
        press(Key.DirectionDown, 40)
        assertEquals("Tout en bas", scroll().maxValue(), scroll().value(), 1f)
        press(Key.DirectionUp, 40)
        assertEquals("Tout en haut", 0f, scroll().value(), 1f)
        press(Key.DirectionLeft)
        assertTrue(focusedText(), focusedText().startsWith("Lecture"))
        assertTrue(shown("Qualité visée"))
    }

    @Test
    fun `every part of the update notes is shown whole`() {
        val sections = (44 downTo 38).map { version -> "Version 0.2.$version\n" + (1..3).joinToString("\n") { "- Changement $it de la version 0.2.$version, assez long pour tenir sur deux lignes à l'écran de la télé" } }
        val release = AppRelease("0.2.44", null, "https://example.invalid/NexTV.apk", 1, null, changes = sections.joinToString("\n\n"))
        setContent(UpdateState.Available(release))
        selectPage("Mise à jour")
        press(Key.DirectionRight)
        val seen = HashSet<String>()
        repeat(40) {
            val text = focusedText()
            val version = Regex("Version 0\\.2\\.(\\d+)\\n").find(text)?.groupValues?.get(1)
            if (version != null) {
                seen += version
                val focused = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val list = page().boundsInRoot
                assertTrue("Version 0.2.$version coupée", focused.top >= list.top - 1 && focused.bottom <= list.bottom + 1)
            }
            press(Key.DirectionDown)
        }
        assertEquals((38..44).map { it.toString() }.toSet(), seen)
    }
}
