package tv.nextv.app.ui.player

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Menu des chaînes du lecteur : les programmes en cours sont lus avec l'heure du chargement de la liste.
 * Un programme fini depuis (passage de minuit) laissait la ligne sans programme alors que le volet de
 * droite trouvait le suivant : la liste doit être relue.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class ChannelMenuRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private fun row(id: Long, title: String, programme: String, start: Long, stop: Long) = ChannelListRow(
        id = id, name = title, title = title, groupTitle = "Sport", logoUrl = null, channelNumber = null,
        streamSupport = "", streamIssues = "", quality = "FHD", variantCount = 1, itemKey = "live:$id",
        nowTitle = programme, nowStart = start, nowStop = stop,
    )

    /** Liste d'une page ; chaque relecture (nouvelle source) donne les programmes de l'heure actuelle. */
    private inner class Source(private val rows: List<ChannelListRow>) : PagingSource<Int, ChannelListRow>() {
        override fun getRefreshKey(state: PagingState<Int, ChannelListRow>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelListRow> =
            LoadResult.Page(rows, prevKey = null, nextKey = null)
    }

    @Test
    fun `a programme that ended is replaced by the current one`() {
        val category = Category.Group("Sport")
        var loads = 0
        val pager = Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            loads++
            val now = System.currentTimeMillis()
            val programme = if (loads == 1) {
                // Liste chargée avant minuit : programme fini depuis.
                row(1, "Eurosport 1", "Snooker : fin de soirée", now - 3 * 3_600_000L, now - 60_000L)
            } else {
                row(1, "Eurosport 1", "Endurance : 6 Heures de Barcelone", now - 60_000L, now + 3_600_000L)
            }
            Source(listOf(programme, row(2, "Infosport+", "Club Week-end", now - 60_000L, now + 3_600_000L)))
        }
        val menuChannels = pager.flow.map { data -> data.map { MenuChannel(category, it) } }
        compose.setContent {
            val channels = menuChannels.collectAsLazyPagingItems()
            NexTvTheme(ThemeStyle.NIGHT) {
                ChannelMenu(
                    menu = ChannelMenuState(open = true, category = category, currentChannelId = 2),
                    groups = emptyList(),
                    channels = channels,
                    favorites = emptyList(),
                    now = System.currentTimeMillis(),
                    onCategory = {},
                    onPlay = {},
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Endurance : 6 Heures de Barcelone")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Snooker : fin de soirée")).fetchSemanticsNodes().isEmpty())
    }
}
