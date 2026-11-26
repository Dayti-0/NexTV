package tv.nextv.app.ui.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Chaîne « événement » (« FR| DAZN 3 SERIE A (LIVE MATCH ONLY) ») : sans programme du guide, le bandeau montre le match et son score. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class EventChannelBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the score shows without a guide programme`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                PlayerOverlay(
                    state = SampleData.player.copy(
                        channelName = "FR| DAZN 3 SERIE A (LIVE MATCH ONLY)",
                        now = null,
                        next = null,
                        match = PlayerMatch("Parma", "Bologna", null, null, "0 – 0", live = true, clock = "45+3'"),
                    ),
                    now = SampleData.NOW,
                    visible = true,
                    showTouchControls = false,
                    onBack = {},
                    onZap = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Parma").assertIsDisplayed()
        compose.onNodeWithText("0 – 0").assertIsDisplayed()
        compose.onNodeWithText("45+3'").assertIsDisplayed()
    }

    @Test
    fun `half-time shows the time left before the restart`() {
        var now by mutableLongStateOf(SampleData.NOW)
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                PlayerOverlay(
                    state = SampleData.player.copy(
                        match = PlayerMatch("Parma", "Bologna", null, null, "1 – 0", live = true, halfTime = true, resumeAtMillis = SampleData.NOW + 8 * 60_000L + 30_000),
                    ),
                    now = now,
                    visible = true,
                    showTouchControls = false,
                    onBack = {},
                    onZap = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Mi-temps").assertIsDisplayed()
        // Minute entamée comptée : 8 min 30 s → 9 min.
        compose.onNodeWithText("Reprise dans 9 min").assertIsDisplayed()
        // Heure de la reprise passée (pause prolongée) : plus de temps restant, le badge reste.
        now = SampleData.NOW + 9 * 60_000L
        compose.onNodeWithText("Mi-temps").assertIsDisplayed()
        compose.onNodeWithText("Reprise dans", substring = true).assertDoesNotExist()
    }
}
