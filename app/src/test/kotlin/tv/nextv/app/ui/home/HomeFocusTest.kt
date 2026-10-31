package tv.nextv.app.ui.home

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Navigation à la télécommande dans les interfaces de l'accueil, avec beaucoup de catégories (rendu
 * simplifié de Robolectric : en rendu natif, le focus initial ne s'y pose pas, quelle que soit l'interface).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class HomeFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val groups = (1..30).map { GroupRow("Groupe $it", 4) }

    /** Chaînes d'une catégorie, lues avec le délai d'une base de données. */
    private val rows = HomeRows(
        channels = { group ->
            delay(150)
            val number = group.removePrefix("Groupe ").toInt()
            SampleData.channels.take(4).mapIndexed { index, channel ->
                channel.copy(id = number * 100L + index, itemKey = "g$number-$index", title = "$group · $index", groupTitle = group)
            }
        },
    )

    @Test
    fun streamingRowsCanBeReachedWithDown() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        repeat(14) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = focused.lastOrNull()?.groupTitle
        println("Focus après 14 × bas : $last (${focused.map { it.title }})")
        assertTrue("Focus : $last", last != null && last.removePrefix("Groupe ").toInt() >= 8)
    }

    @Test
    fun boxCategoriesCanBeReachedWithDown() {
        val categories = mutableListOf<Category>()
        compose.setContent { Home(HomeLayout.BOX, HomeActions(onCategory = { categories += it })) }
        compose.waitForIdle()
        // Onglets → recherche → catégories.
        repeat(20) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        println("Catégorie choisie : $categories")
        val chosen = categories.lastOrNull() as? Category.Group
        assertTrue("Catégorie : $categories", chosen != null && chosen.title.removePrefix("Groupe ").toInt() >= 10)
    }

    @Test
    fun boxChannelListCanBeScrolledWithDown() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.BOX, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        repeat(12) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        println("Liste Box : ${focused.map { it.title }}")
        assertTrue("Chaînes : ${focused.map { it.title }}", focused.map { it.id }.distinct().size >= 10)
    }

    @Composable
    private fun Home(layout: HomeLayout, actions: HomeActions) {
        NexTvTheme(ThemeStyle.NIGHT) {
            val channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
            val movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems()
            val series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems()
            HomeContent(
                section = HomeSection.TV,
                sourceName = "Bouquet",
                groups = groups,
                category = Category.All,
                query = "",
                channels = channels,
                favoriteChannels = emptyList(),
                movies = movies,
                favoriteMovies = emptyList(),
                series = series,
                favoriteSeries = emptyList(),
                favoriteKeys = emptySet(),
                reorder = ReorderState(),
                now = SampleData.NOW,
                snackbarHostState = remember { SnackbarHostState() },
                actions = actions,
                guide = {},
                football = {},
                layout = layout,
                rows = rows,
            )
        }
    }
}
