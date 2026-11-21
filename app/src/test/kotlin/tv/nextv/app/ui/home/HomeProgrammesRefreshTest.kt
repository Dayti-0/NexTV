package tv.nextv.app.ui.home

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.ChannelListRow

/**
 * Liste des chaînes de l'accueil gardée pendant la lecture : au retour du lecteur, les cartes montraient les programmes
 * d'avant la lecture (« Tennis : Nitto ATP Finals » à 23 h 14, bandeau du haut sur le saut à ski). Un programme affiché
 * fini doit faire relire la liste aussitôt, sans attendre les 5 minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class HomeProgrammesRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(id: Long, programme: String, start: Long, stop: Long) = ChannelListRow(
        id = id, name = "Eurosport 1", title = "Eurosport 1", groupTitle = "Sport", logoUrl = null, channelNumber = null,
        streamSupport = "", streamIssues = "", quality = "FHD", variantCount = 1, itemKey = "live:$id",
        nowTitle = programme, nowStart = start, nowStop = stop,
    )

    private class Source(private val rows: List<ChannelListRow>) : PagingSource<Int, ChannelListRow>() {
        override fun getRefreshKey(state: PagingState<Int, ChannelListRow>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelListRow> =
            LoadResult.Page(rows, prevKey = null, nextKey = null)
    }

    @Test
    fun `an ended programme is replaced quickly by the current one`() {
        var loads = 0
        val pager = Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            loads++
            val now = System.currentTimeMillis()
            Source(
                listOf(
                    if (loads == 1) {
                        // Liste lue avant d'ouvrir le lecteur : programme fini depuis longtemps.
                        row(1, "Tennis : Nitto ATP Finals", now - 6 * 3_600_000L, now - 3 * 3_600_000L)
                    } else {
                        row(1, "Saut à ski : Coupe du monde", now - 600_000L, now + 3_600_000L)
                    },
                ),
            )
        }.flow
        compose.setContent {
            val channels = pager.collectAsLazyPagingItems()
            KeepProgrammesCurrent(channels)
            Text(if (channels.itemCount > 0) channels[0]?.nowTitle.orEmpty() else "")
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Saut à ski : Coupe du monde")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Tennis : Nitto ATP Finals")).fetchSemanticsNodes().isEmpty())
        assertTrue("relue une seule fois de plus ($loads)", loads == 2)
    }
}
