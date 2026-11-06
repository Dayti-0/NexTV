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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import tv.nextv.app.ui.components.BottomFadeLength
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
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

    /** Comme sur une vraie TV : Compose y fait défiler les listes en « pivot » (élément choisi à 30 % de la hauteur). */
    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private val groups = (1..30).map { GroupRow("Groupe $it", 4) }

    /** Chaînes d'une catégorie, lues avec le délai d'une base de données. */
    private val rows = HomeRows(
        channels = { group ->
            delay(150)
            val number = group.removePrefix("Groupe ").toInt()
            List(12) { SampleData.channels[it % SampleData.channels.size] }.mapIndexed { index, channel ->
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
    fun liveCenterCategoriesCanBeReachedWithDown() {
        val categories = mutableListOf<Category>()
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions(onCategory = { categories += it })) }
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
    fun liveCenterChannelListCanBeScrolledWithDown() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions(onChannelFocus = { focused += it })) }
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

    @Test
    fun premiumRowsCanBeReachedWithDown() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.PREMIUM, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        repeat(10) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = focused.lastOrNull()?.groupTitle
        println("Cinéma Premium, focus après 10 × bas : $last")
        assertTrue("Focus : $last", last != null && last.removePrefix("Groupe ").toInt() >= 5)
    }

    /** En descendant dans la liste des chaînes, la chaîne choisie reste au-dessus du fondu du bas. */
    @Test
    fun focusedChannelStaysAboveTheBottomFade() {
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions()) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val screen = compose.onRoot().getBoundsInRoot().bottom
        repeat(8) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            val focused = compose.onNode(isFocused()).getBoundsInRoot().bottom
            assertTrue("Chaîne choisie dans le fondu : $focused / $screen", focused <= screen - BottomFadeLength + 1.dp)
        }
    }

    /** La rangée choisie est calée en haut : rien de la précédente ne reste visible, coupé, au-dessus. */
    @Test
    fun premiumFocusedRowIsSnappedToTheTop() = assertFocusedRowSnapped(HomeLayout.PREMIUM)

    @Test
    fun streamingFocusedRowIsSnappedToTheTop() = assertFocusedRowSnapped(HomeLayout.STREAMING)

    /** Touches enchaînées pendant le défilement (appareil lent, touche maintenue) : même résultat. */
    @Test
    fun premiumRowIsSnappedWhenKeysComeQuickly() = assertFocusedRowSnapped(HomeLayout.PREMIUM, quick = true)

    @Test
    fun streamingRowIsSnappedWhenKeysComeQuickly() = assertFocusedRowSnapped(HomeLayout.STREAMING, quick = true)

    private fun assertFocusedRowSnapped(layout: HomeLayout, quick: Boolean = false) {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(layout, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        if (quick) compose.mainClock.autoAdvance = false
        repeat(6) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            if (quick) compose.mainClock.advanceTimeBy(48) else compose.waitForIdle()
        }
        if (quick) {
            compose.mainClock.advanceTimeBy(2_000)
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val group = focused.last().groupTitle!!.removePrefix("Groupe ").toInt()
        val previous = "Groupe ${group - 1} · 0"
        val visible = runCatching { compose.onNodeWithText(previous).isDisplayed() }.getOrDefault(false)
        println("$layout${if (quick) " (rapide)" else ""} : rangée Groupe $group, « $previous » visible : $visible")
        assertTrue("Groupe $group", group >= 2)
        assertTrue("$layout : « $previous » encore visible au-dessus de la rangée choisie", !visible)
        // Le titre de la rangée choisie reste entier, en haut.
        assertTrue("$layout : titre « Groupe $group » caché", compose.onNodeWithText("Groupe $group").isDisplayed())
    }

    /** Gauche / droite dans une rangée : la liste ne bouge pas verticalement (pas de tremblement). */
    @Test
    fun premiumRowsDoNotShakeWhenMovingSideways() = assertNoShake(HomeLayout.PREMIUM)

    @Test
    fun streamingRowsDoNotShakeWhenMovingSideways() = assertNoShake(HomeLayout.STREAMING)

    private fun assertNoShake(layout: HomeLayout) {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(layout, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        repeat(3) { press(Key.DirectionDown) }
        val title = focused.last().groupTitle!!
        val tops = mutableListOf<Float>()
        val lefts = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft).forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                tops += compose.onNodeWithText(title).getBoundsInRoot().top.value
                lefts += compose.onNode(isFocused()).getBoundsInRoot().left.value
            }
            lefts += Float.NaN
        }
        compose.mainClock.autoAdvance = true
        println("$layout : haut de « $title » = ${tops.distinct()}")
        println("$layout : gauche de la vignette choisie = ${lefts.joinToString(" ") { "%.1f".format(it) }}")
        assertTrue("$layout : la rangée bouge verticalement : ${tops.distinct()}", tops.max() - tops.min() < 0.5f)
    }

    /** Retour du lecteur : la liste revient à sa place et la chaîne ouverte reprend le focus. */
    @Test
    fun openedChannelGetsFocusBackAfterReturning() {
        val tester = StateRestorationTester(compose)
        val opened = mutableListOf<Long>()
        val focused = mutableListOf<ChannelListRow>()
        tester.setContent {
            Home(HomeLayout.LIVE_CENTER, HomeActions(onPlayChannel = { opened += it }, onChannelFocus = { focused += it }))
        }
        compose.waitForIdle()
        // Onglets → recherche → catégories, puis la liste des chaînes et sa 9e chaîne.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        repeat(8) { press(Key.DirectionDown) }
        val chosen = focused.last()
        press(Key.DirectionCenter)
        assertEquals(listOf(chosen.id), opened)
        focused.clear()
        // Écran recréé (comme au retour du lecteur) avec son état enregistré.
        tester.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        println("Focus rendu à : ${focused.map { it.title }} (ouverte : ${chosen.title})")
        assertEquals(chosen.id, focused.lastOrNull()?.id)
    }

    @Test
    fun tabsChangeSectionWithoutOk() {
        var section by mutableStateOf(HomeSection.TV)
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions(onSection = { section = it }), section) }
        compose.waitForIdle()
        // Focus initial sur l'onglet TV : droite, droite → Séries, sans appuyer sur OK.
        press(Key.DirectionRight)
        assertEquals(HomeSection.MOVIES, section)
        press(Key.DirectionRight)
        assertEquals(HomeSection.SERIES, section)
        // Dans le contenu, puis retour vers les onglets : la section affichée reste la même.
        repeat(3) { press(Key.DirectionDown) }
        repeat(4) { press(Key.DirectionUp) }
        assertEquals(HomeSection.SERIES, section)
    }

    /** Appui sur une touche, puis le temps des animations. */
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Composable
    private fun Home(layout: HomeLayout, actions: HomeActions, section: HomeSection = HomeSection.TV) {
        NexTvTheme(ThemeStyle.NIGHT) {
            val channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
            val movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems()
            val series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems()
            HomeContent(
                section = section,
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
