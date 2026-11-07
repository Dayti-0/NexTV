package tv.nextv.app.ui.player

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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import tv.nextv.app.data.settings.AspectMode
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.stream.QualityTarget

/** Options du lecteur (touche Menu) : la qualité visée se change à la télécommande, comme dans les réglages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class PlayerOptionsTest {

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

    private fun focusedText(): String = texts(compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()).joinToString("\n")

    @Test
    fun `quality target is chosen from the options`() {
        var target by mutableStateOf(QualityTarget.BEST)
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                PlayerOptionsPanel(
                    visible = true,
                    tracks = PlayerTracks(),
                    aspect = AspectMode.FIT,
                    isLive = true,
                    infoVisible = false,
                    onAudio = {},
                    onSubtitles = {},
                    onAspect = {},
                    onPreviousChannel = {},
                    onToggleInfo = {},
                    onDismiss = {},
                    qualityTarget = target,
                    playingQuality = "FHD",
                    onQualityTarget = { target = it },
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("Qualité visée · FHD en cours").assertExists()
        // Format de l'image a le focus ; on descend jusqu'à « Économie ».
        repeat(10) { if (!focusedText().startsWith("Économie")) press(Key.DirectionDown) }
        assertEquals("Économie", focusedText())
        press(Key.DirectionCenter)
        assertEquals(QualityTarget.ECO, target)
        press(Key.DirectionUp)
        assertEquals("HD maximum", focusedText())
        press(Key.DirectionCenter)
        assertEquals(QualityTarget.HD, target)
    }
}
