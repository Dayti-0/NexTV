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
}
