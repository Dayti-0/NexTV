package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
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
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Streaming sur la télé : rubrique choisie dans le menu de gauche (Films → Séries). Vu en vidéo sur l'appareil : la
 * nouvelle page s'affichait d'abord sur son Top 10, puis sautait à « Reprendre la lecture » (la sélection, passée du
 * menu au contenu par → , tombait sur la reprise, plus proche du menu). La page doit s'ouvrir sur sa première rangée
 * et y rester.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class StreamingSectionSwitchTest {

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

    private fun focusedText(): String? = runCatching {
        texts(compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()).joinToString(" / ")
    }.getOrNull()

    private fun top(text: String): Float? =
        compose.onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    private var section by mutableStateOf(HomeSection.MOVIES)
    private val movieTop = Discover(trending = List(10) { i -> SampleData.movies[i % SampleData.movies.size].toPoster().copy(itemKey = "movie:top$i", title = "Film tendance $i") })
    private val seriesTop = Discover(trending = List(10) { i -> SampleData.series[i % SampleData.series.size].toPoster().copy(itemKey = "series:top$i", title = "Série tendance $i") })
    private val continueMovies = listOf(ProgressEntity(1, "movie:repris", "MOVIE", 501, "Film repris", null, null, 600_000, 3_600_000, 2))
    private val continueSeries = listOf(ProgressEntity(1, "series:reprise", "SERIES", 502, "Série reprise", "S01E01", null, 600_000, 3_000_000, 2))
    private val resumed = mutableListOf<Long>()

    @Composable
    private fun Home() {
        NexTvTheme(ThemeStyle.NIGHT) {
            HomeContent(
                section = section,
                groups = (1..6).map { GroupRow("Groupe $it", 20) },
                category = Category.All,
                query = "",
                channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems(),
                favoriteChannels = emptyList(),
                movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems(),
                favoriteMovies = emptyList(),
                series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems(),
                favoriteSeries = emptyList(),
                favoriteKeys = emptySet(),
                reorder = ReorderState(),
                now = SampleData.NOW,
                snackbarHostState = remember { SnackbarHostState() },
                actions = HomeActions(onSection = { section = it }, onResume = { resumed += it }),
                guide = {},
                football = {},
                layout = HomeLayout.STREAMING,
                continueMovies = continueMovies,
                continueSeries = continueSeries,
                discover = if (section == HomeSection.SERIES) seriesTop else movieTop,
            )
        }
    }

    /** ☰ puis la rubrique [label] dans le menu, OK ; renvoie la place du Top 10 sur chaque image (1,5 s). */
    private fun pickInMenu(label: String): List<String> {
        press(Key.Menu)
        repeat(6) { if (focusedText()?.contains(label) != true) press(Key.DirectionDown) }
        repeat(6) { if (focusedText()?.contains(label) != true) press(Key.DirectionUp) }
        assertTrue("$label pas sélectionnée dans le menu : ${focusedText()}", focusedText()?.contains(label) == true)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionCenter)
        val seen = mutableListOf<String>()
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            seen += "top10=${top("Top 10 de la semaine")?.toInt()} reprise=${top("Reprendre la lecture")?.toInt()}"
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return seen
    }

    private fun assertStill(seen: List<String>, expectedFocus: String) {
        val steps = seen.fold(mutableListOf<String>()) { acc, it -> if (acc.lastOrNull() != it) acc += it; acc }
        val focus = focusedText()
        println("Images : $steps ; sélection : $focus")
        // Calage de la rangée au bord du bandeau : quelques pixels, pas un saut d'une rangée à l'autre.
        val tops = seen.mapNotNull { Regex("top10=(-?\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals("Top 10 absent sur des images : $steps", seen.size, tops.size)
        assertTrue("La page a changé de place : $steps", tops.max() - tops.min() <= 16)
        assertTrue("Sélection : $focus", focus?.contains(expectedFocus) == true)
    }

    @Test
    fun sectionPickedInTheMenuOpensOnItsFirstRowWithoutJumping() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent { Home() }
        compose.waitForIdle()
        // Dans les rangées, sur la reprise (2e rangée), puis ☰ : le menu ; Séries, OK.
        repeat(3) { if (focusedText()?.contains("Film repris") != true) press(Key.DirectionDown) }
        println("Avant le menu : ${focusedText()}")
        assertStill(pickInMenu("Séries"), "Série tendance 0")
    }

    /**
     * Vu sur la box (0.2.215) : film repris depuis « Reprendre la lecture », retour du lecteur sur une autre rubrique ;
     * Films choisi ensuite dans le menu : la sélection allait sur la n°1 du Top 10, puis le film ouvert avant la reprenait
     * (rendu de la sélection au retour, resté en attente) et la page glissait jusqu'à sa rangée.
     */
    @Test
    fun anItemOpenedEarlierDoesNotPullTheFocusAfterAMenuPick() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val tester = StateRestorationTester(compose)
        tester.setContent { Home() }
        compose.waitForIdle()
        repeat(3) { if (focusedText()?.contains("Film repris") != true) press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        assertEquals(listOf(501L), resumed)
        // Pendant la lecture, l'accueil passe aux chaînes ; retour dans l'appli (accueil recréé).
        section = HomeSection.TV
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Sélection dans la liste des chaînes (après la recréation, rien n'a encore la sélection).
        repeat(2) { if (focusedText() == null || focusedText() == "TV") press(Key.DirectionRight) }
        println("Chaînes : ${focusedText()}")
        assertStill(pickInMenu("Films"), "Film tendance 0")
    }
}
