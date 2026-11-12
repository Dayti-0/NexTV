package tv.nextv.app.ui.home

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Recherche de partout : une rangée par type de contenu trouvé (chaînes, films), rien pour un type sans résultat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class GlobalSearchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `channels and films found are shown in their own row`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val channels = remember { flowOf(PagingData.from(SampleData.channels.take(3))) }.collectAsLazyPagingItems()
                val movies = remember { flowOf(PagingData.from(SampleData.movies.take(2))) }.collectAsLazyPagingItems()
                val series = remember { flowOf(PagingData.from(emptyList<SeriesRow>())) }.collectAsLazyPagingItems()
                GlobalSearch(
                    query = "a",
                    onQuery = {},
                    onClose = {},
                    channels = channels,
                    movies = movies,
                    series = series,
                    content = ShelfContent(HomeSection.TV, emptyList(), HomeRows(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Discover(), emptySet(), SampleData.NOW),
                    lastOpened = remember { mutableStateOf(null) },
                    actions = HomeActions(),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Recherche Globale").assertIsDisplayed()
        compose.onNodeWithText("Chaînes").assertIsDisplayed()
        compose.onNodeWithText("Films").assertIsDisplayed()
        compose.onNodeWithText("Séries").assertDoesNotExist()
    }

    /** Film déjà vu trouvé par la recherche : même pastille « Déjà vu » que dans les rangées. */
    @Test
    fun `watched film shows its badge`() {
        val watched = SampleData.movies.first()
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val channels = remember { flowOf(PagingData.from(emptyList<tv.nextv.app.data.db.ChannelListRow>())) }.collectAsLazyPagingItems()
                val movies = remember { flowOf(PagingData.from(listOf(watched))) }.collectAsLazyPagingItems()
                val series = remember { flowOf(PagingData.from(emptyList<SeriesRow>())) }.collectAsLazyPagingItems()
                androidx.compose.runtime.CompositionLocalProvider(LocalWatchedKeys provides setOf(watched.toPoster().itemKey)) {
                    GlobalSearch(
                        query = "a",
                        onQuery = {},
                        onClose = {},
                        channels = channels,
                        movies = movies,
                        series = series,
                        content = ShelfContent(HomeSection.TV, emptyList(), HomeRows(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Discover(), emptySet(), SampleData.NOW),
                        lastOpened = remember { mutableStateOf(null) },
                        actions = HomeActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Déjà vu", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Film ouvert depuis la recherche, puis retour de sa fiche : la sélection revient sur ce film (pas sur « Retour »). */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `back from a sheet selects the opened item again`() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val opened = mutableListOf<Long>()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val channels = remember { flowOf(PagingData.from(SampleData.channels.take(3))) }.collectAsLazyPagingItems()
                val movies = remember { flowOf(PagingData.from(SampleData.movies.take(6))) }.collectAsLazyPagingItems()
                val series = remember { flowOf(PagingData.from(emptyList<SeriesRow>())) }.collectAsLazyPagingItems()
                GlobalSearch(
                    query = "a",
                    onQuery = {},
                    onClose = {},
                    channels = channels,
                    movies = movies,
                    series = series,
                    content = ShelfContent(HomeSection.TV, emptyList(), HomeRows(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Discover(), emptySet(), SampleData.NOW),
                    lastOpened = rememberLastOpened(),
                    actions = HomeActions(onOpenMovie = { opened += it }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        fun press(key: androidx.compose.ui.input.key.Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
        fun focusedText() = runCatching {
            compose.onNode(androidx.compose.ui.test.isFocused()).fetchSemanticsNode().config.toString().let { Regex("Text : \\[[^]]*]").find(it)?.value }
        }.getOrNull()
        val target = SampleData.movies[1].title
        // Champ → chaînes → films ; au début de la rangée, puis le 2e film.
        val titles = SampleData.movies.take(6).map { it.title }
        repeat(4) { if (titles.none { focusedText()?.contains(it) == true }) press(androidx.compose.ui.input.key.Key.DirectionDown) }
        repeat(6) { if (focusedText()?.contains(titles[0]) != true) press(androidx.compose.ui.input.key.Key.DirectionLeft) }
        press(androidx.compose.ui.input.key.Key.DirectionRight)
        println("Avant la fiche : ${focusedText()}")
        assertTrue("Pas sur le film : ${focusedText()}", focusedText()?.contains(target) == true)
        press(androidx.compose.ui.input.key.Key.DirectionCenter)
        assertEquals(listOf(SampleData.movies[1].id), opened)
        // Fiche ouverte puis refermée : l'écran est recréé.
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        println("Au retour : ${focusedText()}")
        assertTrue("Au retour : ${focusedText()}", focusedText()?.contains(target) == true)
    }
}
