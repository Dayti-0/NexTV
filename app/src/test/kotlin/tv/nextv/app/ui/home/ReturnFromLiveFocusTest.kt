package tv.nextv.app.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
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
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Chaîne ouverte depuis l'accueil, puis changée dans le lecteur (zapping) : au retour, la sélection est sur la chaîne
 * regardée en dernier, et non sur celle ouverte au départ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class ReturnFromLiveFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    /** Accueil affiché (sinon : le lecteur plein écran), et dernière chaîne lue en plein écran. */
    private var showHome by mutableStateOf(true)
    private var lastLive by mutableStateOf<String?>(null)
    private val focusedChannels = mutableListOf<ChannelListRow>()
    private val played = mutableListOf<Long>()

    private fun home(layout: HomeLayout) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent {
            // Comme la navigation : l'état de l'accueil (dernier élément ouvert) est gardé pendant la lecture.
            val saved = rememberSaveableStateHolder()
            NexTvTheme(ThemeStyle.NIGHT) {
                if (showHome) {
                    saved.SaveableStateProvider("home") {
                        CompositionLocalProvider(LocalLastLiveChannel provides lastLive) {
                            HomeContent(
                                section = HomeSection.TV,
                                groups = SampleData.groups,
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
                                actions = HomeActions(onPlayChannel = { played += it }, onChannelFocus = { focusedChannels += it }),
                                guide = {},
                                layout = layout,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Ouvre [opened], zappe jusqu'à [watched] (null : pas de zapping) et revient à l'accueil. */
    private fun openZapAndReturn(opened: ChannelListRow, watched: ChannelListRow?) {
        compose.onAllNodesWithText(opened.title, substring = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(opened.id, played.last())
        showHome = false
        lastLive = (watched ?: opened).itemKey
        compose.waitForIdle()
        showHome = true
        repeat(10) {
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
        }
    }

    private fun focusedText(): String? = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.toString()
    }.getOrNull()

    @Test
    fun theLastWatchedChannelHasTheFocusAfterZapping() {
        home(HomeLayout.LIVE_CENTER)
        val opened = SampleData.channels[1]
        val watched = SampleData.channels[3]
        openZapAndReturn(opened, watched)
        println("Focus au retour : ${focusedText()}")
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(watched.title) == true)
        assertEquals(watched.id, focusedChannels.last().id)
    }

    @Test
    fun withoutZappingTheOpenedChannelKeepsTheFocus() {
        home(HomeLayout.LIVE_CENTER)
        val opened = SampleData.channels[2]
        openZapAndReturn(opened, watched = null)
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(opened.title) == true)
        assertEquals(opened.id, focusedChannels.last().id)
    }

    @Test
    fun aChannelOutsideTheListLeavesTheFocusOnTheOpenedOne() {
        home(HomeLayout.LIVE_CENTER)
        val opened = SampleData.channels[2]
        openZapAndReturn(opened, SampleData.channels[3].copy(itemKey = "live:ailleurs"))
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(opened.title) == true)
    }
}
