package tv.nextv.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.sync.SyncDevice
import tv.nextv.core.sync.SyncInvite
import tv.nextv.core.sync.SyncStatus

/**
 * Page « Télés reliées » à la télécommande : un bouton qui disparaît sous la sélection (« Ne plus relier », « Fermer le
 * code ») la laisse dans la page, sur « Afficher le code », au lieu de la perdre (elle partirait en haut à gauche).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class TvLinkFocusTest {

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

    /** Avance dans la page (vers la droite, sinon vers le bas) jusqu'au bouton [label]. */
    private fun moveTo(label: String) {
        repeat(16) {
            if (focusedText() == label) return
            val before = focusedText()
            press(Key.DirectionRight)
            if (focusedText() == before) press(Key.DirectionDown)
        }
        throw AssertionError("« $label » introuvable (sur « ${focusedText()} »)")
    }

    @Test
    fun `buttons that vanish leave the focus on the code button`() {
        var link by mutableStateOf(
            TvLinkUi(
                status = SyncStatus(linked = true, peers = listOf(SyncDevice("a", "Salon", at = SampleData.NOW)), lastSyncAt = SampleData.NOW),
                invite = SyncInvite("12345678"),
            ),
        )
        var unlinked = 0
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
                    tvLink = link,
                    tvLinkActions = TvLinkActions(
                        onHideCode = { link = link.copy(invite = null) },
                        onUnlink = {
                            unlinked++
                            link = TvLinkUi()
                        },
                    ),
                    now = SampleData.NOW,
                    initialPage = SettingsPage.LINK,
                )
            }
        }
        compose.waitForIdle()
        press(Key.DirectionRight)

        moveTo("Fermer le code")
        press(Key.Enter)
        assertEquals("Afficher le code", focusedText())

        moveTo("Ne plus relier cette télé")
        press(Key.Enter)
        assertEquals(1, unlinked)
        assertEquals("Afficher le code", focusedText())
        assertTrue(texts(compose.onRoot().fetchSemanticsNode()).any { it.startsWith("Cette télé n'est reliée") })
    }
}
