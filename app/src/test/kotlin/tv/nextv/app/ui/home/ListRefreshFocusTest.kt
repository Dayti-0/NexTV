package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
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
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Liste des chaînes relue pendant que la sélection est loin dedans (guide ou playlist actualisés juste après le retour
 * du lecteur, programmes relus toutes les 5 minutes) : comme la base, elle recharge à partir de la chaîne affichée, et
 * sa première chaîne change. La grille remontait alors en haut, la chaîne sélectionnée hors de l'écran : la sélection
 * se perdait, et Android la donnait au menu de gauche de Streaming, qui s'ouvrait.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class ListRefreshFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    /** Comme la base (Room) : pages lues par rang, et relecture à partir de la place affichée (moins une demi-page). */
    private class OffsetSource(private val rows: List<ChannelListRow>) : PagingSource<Int, ChannelListRow>() {
        override fun getRefreshKey(state: PagingState<Int, ChannelListRow>): Int? =
            state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelListRow> {
            val key = params.key ?: 0
            val start = if (params is LoadParams.Prepend) maxOf(0, key - params.loadSize) else key.coerceIn(0, rows.size)
            val end = if (params is LoadParams.Prepend) key else minOf(rows.size, start + params.loadSize)
            return LoadResult.Page(
                data = rows.subList(start, end),
                prevKey = start.takeIf { it > 0 },
                nextKey = end.takeIf { it < rows.size },
                itemsBefore = start,
                itemsAfter = rows.size - end,
            )
        }
    }

    private val rows = (1..CHANNELS).map { number ->
        SampleData.channels[number % SampleData.channels.size].copy(
            id = number.toLong(), name = "Chaîne $number", title = "Chaîne $number", itemKey = "live:chaine $number", channelNumber = null,
        )
    }

    private var source: OffsetSource? = null

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    /** Chaîne sélectionnée (« Chaîne 121 »), `null` : aucune. */
    private fun focusedChannel(): String? = runCatching {
        Regex("Chaîne \\d+").find(compose.onNode(isFocused()).fetchSemanticsNode().config.toString())?.value
    }.getOrNull()

    private fun reloadFarDown(layout: HomeLayout) {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                val pager = remember { Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) { OffsetSource(rows).also { source = it } }.flow }
                HomeContent(
                    section = HomeSection.TV,
                    groups = SampleData.groups,
                    category = Category.All,
                    query = "",
                    channels = pager.collectAsLazyPagingItems(),
                    favoriteChannels = emptyList(),
                    movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems(),
                    favoriteMovies = emptyList(),
                    series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems(),
                    favoriteSeries = emptyList(),
                    favoriteKeys = emptySet(),
                    reorder = ReorderState(),
                    now = SampleData.NOW,
                    snackbarHostState = remember { SnackbarHostState() },
                    actions = HomeActions(),
                    guide = {},
                    layout = layout,
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Dans la liste (Live Center : des onglets, ↓ vers les catégories puis → ; Streaming : elle a la sélection).
        if (focusedChannel() == null) {
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionRight)
        }
        val start = focusedChannel()
        // Loin dans la liste : au-delà d'une demi-page lue d'un coup (la relecture repart d'ailleurs que du début).
        repeat(DEPTH) { press(Key.DirectionDown) }
        val selected = focusedChannel()
        println("$layout, avant la relecture : $selected (départ : $start)")
        assertEquals("Chaîne ${(start?.substringAfter(' ')?.toInt() ?: 0) + DEPTH}", selected)
        // Actualisation (programmes, guide ou playlist) : la liste est relue.
        checkNotNull(source).invalidate()
        repeat(10) {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        val shown = compose.onAllNodes(androidx.compose.ui.test.hasText("Chaîne", substring = true)).fetchSemanticsNodes()
            .mapNotNull { n -> Regex("Chaîne \\d+").find(n.config.toString())?.value }.distinct()
        println("$layout, après la relecture : ${focusedChannel()} ; affichées : ${shown.take(3)} … ${shown.takeLast(2)}")
        assertEquals("$layout : sélection après la relecture", selected, focusedChannel())
        // La liste est restée sur elle : ses voisines sont à l'écran, pas le haut de la liste (la chaîne sélectionnée,
        // gardée par la liste même hors de l'écran, y figure toujours).
        val number = checkNotNull(selected).substringAfter(' ').toInt()
        assertTrue("$layout : liste remontée en haut ($shown)", "Chaîne 1" !in shown && "Chaîne ${number - 1}" in shown)
    }

    @Test
    fun streaming() = reloadFarDown(HomeLayout.STREAMING)

    @Test
    fun liveCenter() = reloadFarDown(HomeLayout.LIVE_CENTER)

    private companion object {
        const val CHANNELS = 400
        const val DEPTH = 130
    }
}
