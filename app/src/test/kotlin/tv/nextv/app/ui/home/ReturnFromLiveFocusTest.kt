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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
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

    /** Catégorie affichée et ses chaînes (l'accueil peut en changer pendant la lecture). */
    private var shownCategory by mutableStateOf<Category>(Category.All)
    private var shownChannels by mutableStateOf(SampleData.channels)
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
                                category = shownCategory,
                                query = "",
                                channels = remember(shownChannels) { flowOf(PagingData.from(shownChannels)) }.collectAsLazyPagingItems(),
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

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /**
     * Comme sur la télé : la chaîne est choisie à la télécommande puis ouverte par OK (la liste retient la chaîne qui
     * avait le focus et la reprendrait d'elle-même au retour), on zappe sur une autre, puis retour.
     */
    private fun openWithRemoteZapAndReturn(layout: HomeLayout) {
        home(layout)
        repeat(6) { if (focusedChannels.isEmpty()) press(Key.DirectionDown) }
        press(Key.DirectionRight)
        val opened = focusedChannels.last()
        press(Key.DirectionCenter)
        assertEquals(opened.id, played.last())
        val watched = SampleData.channels.first { it.id != opened.id && it.id > opened.id + 1 }
        showHome = false
        lastLive = watched.itemKey
        compose.waitForIdle()
        showHome = true
        repeat(10) {
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
        }
        println("$layout : ouverte ${opened.title}, regardée ${watched.title}, focus ${focusedChannels.last().title}")
        assertEquals("$layout : focus au retour", watched.title, focusedChannels.last().title)
    }

    @Test
    fun liveCenterWithTheRemote() = openWithRemoteZapAndReturn(HomeLayout.LIVE_CENTER)

    @Test
    fun streamingWithTheRemote() = openWithRemoteZapAndReturn(HomeLayout.STREAMING)

    @Test
    fun premiumWithTheRemote() = openWithRemoteZapAndReturn(HomeLayout.PREMIUM)

    /**
     * Sur la box Raspberry Pi, au retour du lecteur, l'écran refuse le focus un instant : la chaîne regardée en dernier doit le
     * redemander jusqu'à l'avoir (elle le croyait pris, et la liste reprenait la chaîne ouverte au départ).
     */
    @Test
    fun theLastWatchedChannelWaitsUntilItCanTakeTheFocus() {
        var allowed by mutableStateOf(false)
        val focused = mutableListOf<String>()
        val lastOpened = mutableStateOf<OpenedItem?>(OpenedItem("live:tf1", 0, visit = -1))
        compose.setContent {
            CompositionLocalProvider(LocalLastLiveChannel provides "live:t18") {
                androidx.compose.foundation.layout.Row {
                    for (key in listOf("live:tf1", "live:t18")) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier
                                .restoreFocus(key, lastOpened)
                                .focusProperties { canFocus = key != "live:t18" || allowed }
                                .onFocusChanged { if (it.isFocused) focused += key }
                                .focusable()
                                .size(40.dp),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // Toujours refusée : la chaîne ouverte au départ ne prend pas sa place.
        assertTrue("Focus pendant le refus : $focused", focused.isEmpty())
        allowed = true
        repeat(5) {
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
        }
        assertEquals(listOf("live:t18"), focused)
        assertEquals(null, lastOpened.value)
    }

    @Test
    fun withoutZappingTheOpenedChannelKeepsTheFocus() {
        home(HomeLayout.LIVE_CENTER)
        val opened = SampleData.channels[2]
        openZapAndReturn(opened, watched = null)
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(opened.title) == true)
        assertEquals(opened.id, focusedChannels.last().id)
    }

    /**
     * Pendant la lecture, l'accueil est passé à une autre catégorie, où ni la chaîne ouverte ni la dernière regardée ne
     * sont : au retour, la sélection va sur la première chaîne de la liste. Elle se perdait, et Android la donnait au
     * premier élément en haut à gauche : le menu de gauche de Streaming, qui s'ouvrait.
     */
    @Test
    fun withNeitherChannelInTheListTheFocusGoesToTheFirstChannel() {
        home(HomeLayout.STREAMING)
        val opened = SampleData.channels[2]
        compose.onAllNodesWithText(opened.title, substring = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(opened.id, played.last())
        showHome = false
        lastLive = "live:ailleurs"
        shownCategory = Category.Group("Cinéma")
        shownChannels = SampleData.channels.drop(4)
        compose.waitForIdle()
        showHome = true
        repeat(10) {
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
        }
        val first = shownChannels.first()
        println("Focus au retour : ${focusedText()}")
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(first.title) == true)
        assertEquals(first.id, focusedChannels.last().id)
    }

    @Test
    fun aChannelOutsideTheListLeavesTheFocusOnTheOpenedOne() {
        home(HomeLayout.LIVE_CENTER)
        val opened = SampleData.channels[2]
        openZapAndReturn(opened, SampleData.channels[3].copy(itemKey = "live:ailleurs"))
        assertTrue("Focus au retour : ${focusedText()}", focusedText()?.contains(opened.title) == true)
    }
}
