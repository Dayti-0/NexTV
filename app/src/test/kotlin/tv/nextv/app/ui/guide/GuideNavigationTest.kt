package tv.nextv.app.ui.guide

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /** Texte de la case qui a le focus. */
    private fun focusedText(): String = compose.onNode(isFocused()).fetchSemanticsNode().config.toString()

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun show(shown: GuideUiState = state, describe: suspend (GuideProgramme) -> GuideProgramme = { it }) {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                GuideContent(state = shown, groups = emptyList(), selectedGroup = null, onSelectGroup = {}, onPlay = {}, describe = describe)
            }
        }
        compose.waitForIdle()
    }

    /** Seules les cases proches de l'heure affichée existent ; les autres apparaissent en s'en approchant. */
    @Test
    fun onlyProgrammesNearTheScreenAreDrawnAndLeftRightReachEveryone() {
        show()
        assertTrue(focusedText(), "Une 14h00" in focusedText())
        assertTrue("case de l'heure affichée absente", exists("Une 14h30"))
        assertTrue("case du matin dessinée alors qu'elle est loin de l'écran", !exists("Une 2h00"))
        assertTrue("case de la nuit dessinée alors qu'elle est loin de l'écran", !exists("Une 23h30"))

        // ← jusqu'au matin : chaque appui trouve la case d'avant, sans jamais quitter la ligne.
        repeat(24) { index ->
            press(Key.DirectionLeft)
            val expected = 14 * 60 - (index + 1) * 30
            assertTrue("après ${index + 1} appuis sur ← : ${focusedText()}", "Une ${expected / 60}h${if (expected % 60 == 0) "00" else "30"}" in focusedText())
        }
        assertTrue(exists("Une 2h00"))
        assertTrue("case de l'après-midi encore dessinée, loin de l'écran", !exists("Une 14h00"))

        // → jusqu'au soir.
        repeat(40) { press(Key.DirectionRight) }
        assertTrue(focusedText(), "Une 22h00" in focusedText())
        assertTrue(!exists("Une 2h00"))
    }

    /** Haut / bas vers une ligne sans programme à l'heure affichée : son programme le plus proche, même loin, prend le focus. */
    @Test
    fun upAndDownReachAProgrammeFarFromTheScreen() {
        val withGap = state.copy(
            channels = state.channels + GuideChannel(3, "Trois", "3", null, listOf(GuideProgramme("Nuit", midnight + 2 * hour, midnight + 3 * hour))),
        )
        show(withGap)
        // Ligne sans programme à l'heure affichée : sa case la plus proche reste dessinée, pour rester atteignable.
        assertTrue("case la plus proche absente", exists("Nuit"))
        press(Key.DirectionDown)
        assertTrue(focusedText(), "Long après-midi" in focusedText())
        press(Key.DirectionDown)
        assertTrue(focusedText(), "Nuit" in focusedText())
        // Et retour : la ligne du dessus reprend le focus.
        press(Key.DirectionUp)
        assertTrue(focusedText(), "Matinée" in focusedText() || "Long après-midi" in focusedText())
    }

    /** La description du programme sélectionné est lue à la demande et rejoint son titre. */
    @Test
    fun theDescriptionOfTheFocusedProgrammeIsReadOnDemand() {
        val asked = mutableListOf<String>()
        show(
            describe = { programme ->
                asked += programme.title
                programme.copy(description = "Résumé de ${programme.title}")
            },
        )
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("description absente : $asked", exists("Résumé de Une 14h00"))
        press(Key.DirectionRight)
        assertTrue(exists("Résumé de Une 14h30"))
        assertTrue(!exists("Résumé de Une 14h00"))
        // Seuls les programmes sélectionnés sont lus, pas toute la grille.
        assertEquals(listOf("Une 14h00", "Une 14h30"), asked.distinct())
    }

    @Test
    fun drawnRangeKeepsWhatTouchesTheScreenAndOneNeighbourEachSide() {
        val row = halfHours("X") // 48 cases d'une demi-heure, de minuit à minuit
        // Écran de 14 h à 16 h : cases 28 à 31, plus une voisine de chaque côté.
        assertEquals(27..32, drawnRange(row, midnight + 14 * hour, midnight + 16 * hour))
        // Bords de la journée : pas de voisine au-delà.
        assertEquals(0..2, drawnRange(row, midnight - hour, midnight + hour))
        assertEquals(45..47, drawnRange(row, midnight + 23 * hour, midnight + 30 * hour))
        // Long programme qui dépasse l'écran des deux côtés : lui et ses deux voisines.
        val long = listOf(
            GuideProgramme("Avant", midnight, midnight + 2 * hour),
            GuideProgramme("Long", midnight + 2 * hour, midnight + 20 * hour),
            GuideProgramme("Après", midnight + 20 * hour, midnight + 22 * hour),
            GuideProgramme("Encore après", midnight + 22 * hour, midnight + 24 * hour),
        )
        assertEquals(0..2, drawnRange(long, midnight + 10 * hour, midnight + 12 * hour))
        // Rien à l'heure affichée (trou du guide) : les cases les plus proches de chaque côté.
        val gap = listOf(GuideProgramme("Matin", midnight + hour, midnight + 2 * hour), GuideProgramme("Soir", midnight + 20 * hour, midnight + 21 * hour))
        assertEquals(0..1, drawnRange(gap, midnight + 10 * hour, midnight + 12 * hour))
        assertEquals(0..1, drawnRange(gap, midnight + 22 * hour, midnight + 23 * hour))
        assertEquals(0..0, drawnRange(gap.take(1), midnight + 10 * hour, midnight + 12 * hour))
        assertNotNull(drawnRange(emptyList(), 0, 1).takeIf { it.isEmpty() })
    }

    /** Programme commencé avant le bord gauche de l'écran : son titre suit ce bord, il reste lisible. */
    @Test
    fun titleOfAProgrammeStartedEarlierStaysVisible() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                GuideContent(state = state, groups = emptyList(), selectedGroup = null, onSelectGroup = {}, onPlay = {})
            }
        }
        compose.waitForIdle()
        val title = compose.onAllNodesWithText("Long après-midi").onFirst().getBoundsInRoot()
        val screen = compose.onRoot().getBoundsInRoot()
        println("« Long après-midi » : ${title.left}..${title.right} (écran ${screen.right})")
        // À droite de la colonne des chaînes (230 dp), et dans l'écran.
        assertTrue("Titre hors de l'écran à gauche : ${title.left}", title.left >= 230.dp)
        assertTrue("Titre hors de l'écran à droite : ${title.right}", title.right <= screen.right)
    }
}
