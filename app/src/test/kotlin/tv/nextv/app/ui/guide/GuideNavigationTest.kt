package tv.nextv.app.ui.guide

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Haut / bas dans le guide : la frise horaire reste sur la même heure. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class GuideNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val hour = 60 * minute
    private val midnight = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val now = midnight + 14 * hour + 5 * minute

    private fun halfHours(name: String) = (0 until 48).map { i ->
        GuideProgramme("$name ${i / 2}h${if (i % 2 == 0) "00" else "30"}", midnight + i * 30 * minute, midnight + (i + 1) * 30 * minute)
    }

    // Deuxième chaîne : un long programme commencé bien avant l'heure affichée.
    private val state = GuideUiState(
        loading = false,
        windowStart = midnight,
        windowEnd = midnight + 24 * hour,
        openAt = midnight + 14 * hour,
        now = now,
        channels = listOf(
            GuideChannel(1, "Une", "1", null, halfHours("Une")),
            GuideChannel(
                2,
                "Deux",
                "2",
                null,
                listOf(
                    GuideProgramme("Matinée", midnight, midnight + 12 * hour),
                    GuideProgramme("Long après-midi", midnight + 12 * hour, midnight + 16 * hour + 15 * minute),
                    GuideProgramme("Soirée", midnight + 16 * hour + 15 * minute, midnight + 24 * hour),
                ),
            ),
        ),
    )

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun upAndDownKeepTheTimeline() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                GuideContent(state = state, groups = emptyList(), selectedGroup = null, onSelectGroup = {}, onPlay = {})
            }
        }
        compose.waitForIdle()
        // 14 h → 16 h sur la première chaîne.
        repeat(4) { press(Key.DirectionRight) }
        val label = SimpleDateFormat("HH:mm", Locale.FRANCE).format(midnight + 16 * hour)
        fun labelX() = compose.onAllNodesWithText(label).onFirst().getBoundsInRoot().left
        val before = labelX()
        press(Key.DirectionDown)
        val after = labelX()
        println("Repère $label : $before → $after")
        assertEquals(before.value, after.value, 0.5f)
        press(Key.DirectionUp)
        assertEquals(before.value, labelX().value, 0.5f)
    }
}
