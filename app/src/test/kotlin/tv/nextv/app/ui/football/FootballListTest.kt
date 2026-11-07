package tv.nextv.app.ui.football

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.football.Competition
import java.util.Calendar

/**
 * Tous les matchs ont leur carte : avec la chaîne du guide, la chaîne « événement » probable de la
 * playlist (« FR| DAZN LALIGA 1 (LIVE MATCH) »), ou « Pas trouvé dans le guide ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class FootballListTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val noon = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun match(home: String, away: String, at: Long, channel: GuideChannel? = null, probable: Boolean = false) = FootballMatch(
        competition = Competition.LA_LIGA,
        home = home,
        away = away,
        kickoffMillis = at,
        endMillis = at + 115 * minute,
        channel = channel,
        probableChannels = if (probable && channel != null) setOf(channel.id) else emptySet(),
    )

    @Test
    fun `every match keeps its card`() {
        val laliga1 = GuideChannel(101, "FR| DAZN LALIGA 1 (LIVE MATCH)", null, null, emptyList())
        val matches = listOf(
            match("Málaga", "Racing", noon + 120 * minute, laliga1, probable = true),
            match("Espanyol", "Deportivo", noon + 255 * minute),
        )
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                FootballContent(FootballUiState(loading = false, now = noon, matches = matches), competition = Competition.LA_LIGA, onCompetition = {}, onPlay = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Málaga – Racing").assertIsDisplayed()
        compose.onNodeWithText("FR| DAZN LALIGA 1 (LIVE MATCH)").assertIsDisplayed()
        compose.onNodeWithText("chaîne probable").assertIsDisplayed()
        compose.onNodeWithText("Espanyol – Deportivo").assertIsDisplayed()
        compose.onNodeWithText("Pas trouvé dans le guide").assertIsDisplayed()
    }
}
