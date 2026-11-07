package tv.nextv.app.ui.player

import android.app.Application
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
}
