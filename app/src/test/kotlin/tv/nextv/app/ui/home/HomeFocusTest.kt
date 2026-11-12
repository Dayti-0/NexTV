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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import tv.nextv.app.ui.components.BottomFadeLength
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.collectAsState
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.flatMapLatest
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
import tv.nextv.app.data.settings.GridColumns
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

    /** Affiche choisie : mise en avant comme dans l'application (bannière, fond). */
    private var spotlight by mutableStateOf<Spotlight?>(null)

    private val groups = (1..30).map { GroupRow("Groupe $it", 60) }

    /** Chaînes d'une catégorie, lues avec le délai d'une base de données. */
    private val rows = HomeRows(
        channels = { group, _ ->
            delay(150)
            val number = group.removePrefix("Groupe ").toInt()
            List(12) { SampleData.channels[it % SampleData.channels.size] }.mapIndexed { index, channel ->
                channel.copy(id = number * 100L + index, itemKey = "g$number-$index", title = "$group · $index", groupTitle = group)
            }
        },
        movies = { group, limit ->
            delay(150)
            val number = group.removePrefix("Groupe ").toInt()
            List(minOf(limit, 60)) { SampleData.movies[it % SampleData.movies.size] }.mapIndexed { index, movie ->
                movie.copy(id = number * 100L + index, itemKey = "m$number-$index", title = "$group · $index", groupTitle = group)
            }
        },
    )

    @Test
    fun streamingRowsCanBeReachedWithDown() {
        val focused = mutableListOf<String>()
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        enterStreamingContent()
        repeat(14) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = focused.lastOrNull()?.substringBefore(" · ")
        println("Focus après 14 × bas : $last")
        assertTrue("Focus : $last", last != null && last.removePrefix("Groupe ").toInt() >= 8)
    }

    /** Streaming et Classique : ← au bord gauche des rangées ouvre le volet de toutes les catégories. */
    @Test
    fun streamingCategoriesOpenWithLeft() = assertDrawerOpensWithLeft(HomeLayout.STREAMING)

    @Test
    fun premiumCategoriesOpenWithLeft() = assertDrawerOpensWithLeft(HomeLayout.PREMIUM)

    private fun assertDrawerOpensWithLeft(layout: HomeLayout) {
        val categories = mutableListOf<Category>()
        val focused = mutableListOf<String>()
        compose.setContent { Home(layout, HomeActions(onCategory = { categories += it }, onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        if (layout == HomeLayout.STREAMING) enterStreamingContent()
        // Dans les rangées (première vignette), puis ← : volet ouvert, focus sur ses catégories.
        // Streaming : ← mène au menu de gauche, dont « Catégories » ouvre le volet.
        repeat(3) { press(Key.DirectionDown) }
        // ← jusqu'au bord gauche de la rangée : le volet des catégories s'ouvre (Streaming comme Classique).
        openDrawerWithLeft()
        compose.onNodeWithText("Groupe 1").assertExists()
        // Volet ouvert sur « Tous les films », puis Groupe 1, 2, 3 ; OK : saut à la rangée « Groupe 3 »,
        // toutes les rangées restent (aucune catégorie ouverte).
        repeat(3) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("$layout, saut : ${focused.lastOrNull()}, catégories ouvertes : $categories")
        assertTrue("Catégorie ouverte au lieu du saut : $categories", categories.isEmpty())
        assertTrue("Sélection : ${focused.lastOrNull()}", focused.lastOrNull()?.startsWith("Groupe 3 · ") == true)
        compose.onNodeWithText("Groupe 4").assertExists()
    }

    /** Volet ouvert : ↑ tout en haut ne fait pas sortir le focus vers les affiches derrière. */
    @Test
    fun streamingDrawerKeepsTheFocus() = assertDrawerKeepsFocus(HomeLayout.STREAMING)

    @Test
    fun premiumDrawerKeepsTheFocus() = assertDrawerKeepsFocus(HomeLayout.PREMIUM)

    private fun assertDrawerKeepsFocus(layout: HomeLayout) {
        val categories = mutableListOf<Category>()
        val focused = mutableListOf<String>()
        compose.setContent { Home(layout, HomeActions(onCategory = { categories += it }, onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        if (layout == HomeLayout.STREAMING) enterStreamingContent()
        repeat(3) { press(Key.DirectionDown) }
        // ← jusqu'au bord gauche de la rangée : le volet des catégories s'ouvre (Streaming comme Classique).
        openDrawerWithLeft()
        compose.onNodeWithText("Groupe 1").assertExists()
        // Tout en haut, puis au-delà : le focus doit rester dans le volet (← le ferme : test à part).
        repeat(12) { press(Key.DirectionUp) }
        compose.onNodeWithText("Groupe 1").assertExists()
        // Recherche, Favoris, Reprendre, Tous les films, Groupe 1.
        repeat(4) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("$layout, volet après ↑ et ← : ${focused.lastOrNull()}")
        assertTrue("Sélection : ${focused.lastOrNull()}", focused.lastOrNull()?.startsWith("Groupe 1 · ") == true)
    }

    /**
     * Chaînes en Streaming : liste avec « Toutes les chaînes » (comme Live Center), le focus d'office
     * sur la liste ; ↓ passe d'une chaîne à l'autre, ← mène aux catégories, OK en ouvre une.
     */
    @Test
    fun streamingChannelsAreAList() {
        val focused = mutableListOf<ChannelListRow>()
        val categories = mutableListOf<Category>()
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(onChannelFocus = { focused += it }, onCategory = { categories += it })) }
        compose.waitForIdle()
        repeat(8) { press(Key.DirectionDown) }
        val seen = focused.map { it.id }.distinct()
        println("Streaming, chaînes parcourues : ${focused.map { it.title }.distinct()}")
        assertTrue("Chaînes parcourues : $seen", seen.size >= 8)
        // ← : le volet des catégories (« Toutes les chaînes » en fait partie) ; ↓ puis OK : une catégorie s'ouvre.
        press(Key.DirectionLeft)
        compose.onNodeWithText("Toutes les chaînes").assertExists()
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        println("Streaming, catégorie choisie : $categories")
        assertTrue("Catégorie : $categories", categories.isNotEmpty())
    }

    @Test
    fun premiumChannelsAreAList() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.PREMIUM, HomeActions(onChannelFocus = { focused += it })) }
        compose.waitForIdle()
        compose.onNodeWithText("Toutes les chaînes").assertExists()
        // Barre du haut → recherche → catégories, puis → dans la liste.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        repeat(8) { press(Key.DirectionDown) }
        val seen = focused.map { it.id }.distinct()
        println("Premium, chaînes parcourues : ${focused.map { it.title }.distinct()}")
        assertTrue("Chaînes parcourues : $seen", seen.size >= 8)
    }

    /** Streaming, volet ouvert : ← le ferme et la sélection passe au menu de gauche. */
    @Test
    fun leftInTheDrawerGoesToTheMenu() {
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(), HomeSection.MOVIES) }
        compose.waitForIdle()
        enterStreamingContent()
        repeat(3) { press(Key.DirectionDown) }
        openDrawerWithLeft()
        compose.onNodeWithTag(CATEGORY_DRAWER_TAG).assertExists()
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionLeft)
        val focusedLeft = compose.onNode(isFocused()).getBoundsInRoot().left
        println("← dans le volet : sélection à $focusedLeft")
        assertTrue("Volet encore ouvert", compose.onAllNodesWithTag(CATEGORY_DRAWER_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue("Sélection hors du menu : $focusedLeft", focusedLeft < 100.dp)
    }

    /** Streaming : ☰ dans le contenu ouvre le menu de gauche ; ☰ dans le menu le referme. */
    @Test
    fun menuKeyOpensTheStreamingMenu() {
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(), HomeSection.MOVIES) }
        compose.waitForIdle()
        enterStreamingContent()
        repeat(2) { press(Key.DirectionDown) }
        val inContent = compose.onNode(isFocused()).getBoundsInRoot().left
        assertTrue("Sélection encore dans le menu : $inContent", inContent > 100.dp)
        press(Key.Menu)
        val inMenu = compose.onNode(isFocused()).getBoundsInRoot().left
        println("☰ : sélection à $inMenu")
        assertTrue("Menu pas ouvert : $inMenu", inMenu < 100.dp)
        press(Key.Menu)
        val back = compose.onNode(isFocused()).getBoundsInRoot().left
        assertTrue("Menu pas refermé : $back", back > 100.dp)
    }

    /**
     * Chaînes en Streaming : une catégorie, puis « Toutes les chaînes » : la liste revient bien à toutes
     * (liste paginée qui suit la catégorie, comme dans l'application).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun streamingChannelsBackToAllChannels() {
        val chosen = mutableListOf<Category>()
        val focusedChannels = mutableListOf<ChannelListRow>()
        val categoryFlow = kotlinx.coroutines.flow.MutableStateFlow<Category>(Category.All)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val paged = categoryFlow.flatMapLatest { c ->
            if (c == Category.Favorites || c == Category.Recent) {
                kotlinx.coroutines.flow.emptyFlow()
            } else {
                val list = SampleData.channels.filter { c !is Category.Group || it.groupTitle.orEmpty() == c.title }
                androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 60, enablePlaceholders = false)) { ListSource(list) }.flow
            }
        }.cachedIn(scope)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent {
            val category by categoryFlow.collectAsState()
            NexTvTheme(ThemeStyle.NIGHT) {
                HomeContent(
                    section = HomeSection.TV,
                    groups = SampleData.groups,
                    category = category,
                    query = "",
                    channels = paged.collectAsLazyPagingItems(),
                    favoriteChannels = emptyList(),
                    movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems(),
                    favoriteMovies = emptyList(),
                    series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems(),
                    favoriteSeries = emptyList(),
                    favoriteKeys = emptySet(),
                    reorder = ReorderState(),
                    now = SampleData.NOW,
                    snackbarHostState = remember { SnackbarHostState() },
                    actions = HomeActions(onCategory = { chosen += it; categoryFlow.value = it }, onChannelFocus = { focusedChannels += it }),
                    guide = {},
                    football = {},
                    layout = HomeLayout.STREAMING,
                )
            }
        }
        compose.waitForIdle()
        // ← : volet des catégories, ouvert sur « Toutes les chaînes » ; Information, Sport : OK sur « Sport ».
        press(Key.DirectionLeft)
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        // Dans la liste de Sport, sur une chaîne plus bas.
        repeat(2) { press(Key.DirectionDown) }
        // ← : le volet s'ouvre sur la catégorie choisie.
        press(Key.DirectionLeft)
        val picked = (chosen.last() as Category.Group).title
        println("Volet ouvert sur : ${focusedText()} (choisie : $picked)")
        assertTrue("← : ${focusedText()} (choisie : $picked)", focusedText()?.contains(picked) == true)
        // ↑ jusqu'à « Toutes les chaînes », OK : la liste repart du haut, sur toutes les chaînes.
        repeat(8) { if (focusedText()?.contains("Toutes les chaînes") != true) press(Key.DirectionUp) }
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Catégories choisies : $chosen")
        assertEquals(Category.All, chosen.lastOrNull())
        assertTrue("Info 24 pas affichée en haut de « Toutes »", compose.onAllNodesWithText("Info 24", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private class ListSource(private val rows: List<ChannelListRow>) : androidx.paging.PagingSource<Int, ChannelListRow>() {
        override fun getRefreshKey(state: androidx.paging.PagingState<Int, ChannelListRow>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelListRow> = LoadResult.Page(rows, null, null)
    }

    private fun focusedText() = runCatching { compose.onNode(isFocused()).fetchSemanticsNode().config.toString().let { Regex("Text : \\[[^]]*]").find(it)?.value } }.getOrNull()

    /**
     * Bandeau de Streaming, sans boutons : à l'ouverture, la sélection est sur la première affiche du Top 10, dont
     * la rangée est entière (titre compris : le bandeau ne change plus de hauteur sous elle).
     */
    @Test
    fun streamingHeroStartsOnTheFirstPoster() {
        val focused = mutableListOf<String>()
        // Télécommande : pas de mode tactile (où Android refuse de poser la sélection d'office).
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val trending = Discover(trending = SampleData.movies.take(3).map { it.toPoster() })
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES, discover = trending) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Sélection de départ : ${focusedText()}")
        assertTrue("Départ : ${focusedText()}", focusedText()?.contains("Le Grand Voyage") == true)
        assertTrue("Boutons encore là", compose.onAllNodesWithText("Plus d’infos").fetchSemanticsNodes().isEmpty())
        assertTrue("Boutons encore là", compose.onAllNodesWithText("Regarder").fetchSemanticsNodes().isEmpty())
        // Titre de la première rangée visible en entier, sous le bandeau (244 dp).
        val heroBottom = with(compose.density) { 244.dp.toPx() }
        fun titleTop() = compose.onAllNodesWithText("Tendances", substring = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        println("Titre de la rangée : ${titleTop()} (bandeau : $heroBottom)")
        assertTrue("Titre de la rangée coupé : ${titleTop()}", (titleTop() ?: -1f) >= heroBottom - 1f)
        // → puis ← : retour sur la première affiche, la rangée toujours entière.
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertTrue("← : ${focusedText()}", focusedText()?.contains("Le Grand Voyage") == true)
        assertTrue("Titre de la rangée coupé : ${titleTop()}", (titleTop() ?: -1f) >= heroBottom - 1f)
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
        val focused = mutableListOf<String>()
        compose.setContent { Home(HomeLayout.PREMIUM, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        repeat(10) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = focused.lastOrNull()?.substringBefore(" · ")
        println("Classique, focus après 10 × bas : $last")
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
        val focused = mutableListOf<String>()
        compose.setContent { Home(layout, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        if (layout == HomeLayout.STREAMING) enterStreamingContent()
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
        val group = focused.last().substringBefore(" · ").removePrefix("Groupe ").toInt()
        val previous = "Groupe ${group - 1} · 0"
        val visible = runCatching { compose.onNodeWithText(previous).isDisplayed() }.getOrDefault(false)
        println("$layout${if (quick) " (rapide)" else ""} : rangée Groupe $group, « $previous » visible : $visible")
        assertTrue("Groupe $group", group >= 2)
        if (layout == HomeLayout.STREAMING) {
            assertNextRowPeeks(group)
        } else {
            assertTrue("$layout : « $previous » encore visible au-dessus de la rangée choisie", !visible)
        }
        // Le titre de la rangée choisie reste entier, en haut.
        assertTrue("$layout : titre « Groupe $group » caché", compose.onNodeWithText("Groupe $group").isDisplayed())
    }

    /** Gauche / droite dans une rangée : la liste ne bouge pas verticalement (pas de tremblement). */
    @Test
    fun premiumRowsDoNotShakeWhenMovingSideways() = assertNoShake(HomeLayout.PREMIUM)

    @Test
    fun streamingRowsDoNotShakeWhenMovingSideways() = assertNoShake(HomeLayout.STREAMING)

    private fun assertNoShake(layout: HomeLayout) {
        val focused = mutableListOf<String>()
        compose.setContent { Home(layout, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        if (layout == HomeLayout.STREAMING) enterStreamingContent()
        repeat(3) { press(Key.DirectionDown) }
        val title = focused.last().substringBefore(" · ")
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

    /**
     * Streaming : sous la rangée choisie, le titre de la suivante en entier (et pas ses affiches) ; au-dessus,
     * rien de la rangée précédente (ni affiches, ni titres écrits sous les affiches).
     */
    @Test
    fun streamingRowShowsTheNextTitleAndNothingAbove() {
        val focused = mutableListOf<String>()
        compose.setContent { Home(HomeLayout.STREAMING, HomeActions(onPosterFocus = { focused += it.title }), HomeSection.MOVIES) }
        compose.waitForIdle()
        enterStreamingContent()
        var checked = 0
        repeat(5) {
            press(Key.DirectionDown)
            val group = focused.lastOrNull()?.substringBefore(" · ")?.removePrefix("Groupe ")?.toIntOrNull() ?: return@repeat
            val list = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(isFocused())).fetchSemanticsNodes()
                .map { Rect(it.positionInRoot, it.size.toSize()) }.maxBy { it.height }
            fun rects(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
                .map { Rect(it.positionInRoot, it.size.toSize()) }
            val title = rects("Groupe $group").single()
            val next = rects("Groupe ${group + 1}").singleOrNull()
            // Tout ce qui porte le nom d'une affiche de la rangée précédente (image, titre dessous).
            val above = (0..5).flatMap { rects("Groupe ${group - 1} · $it") }.filter { it.bottom > list.top + 1f && it.top < list.bottom }
            println("Streaming, Groupe $group : liste ${list.top}..${list.bottom}, titre ${title.top}..${title.bottom}, suivante ${next?.top}..${next?.bottom}, précédente visible : ${above.map { "${it.top}..${it.bottom}" }}")
            assertTrue("Groupe $group : titre coupé en haut (${title.top} < ${list.top})", title.top >= list.top - 0.5f)
            if (next != null) assertTrue("Groupe $group : titre de la rangée suivante coupé (${next.bottom} > ${list.bottom})", next.bottom <= list.bottom + 0.5f)
            assertTrue("Groupe $group : la rangée précédente se voit au-dessus : $above", above.isEmpty())
            checked++
        }
        assertTrue("rangées vérifiées : $checked", checked >= 3)
    }

    /**
     * Rangée choisie entière : titre et vignettes (texte compris) au-dessus de la marge de sécurité
     * du bas des TV, rien de la rangée précédente au-dessus.
     */
    @Test
    fun streamingMovieRowsAreEntirelyVisible() = assertRowsEntirelyVisible(HomeLayout.STREAMING, HomeSection.MOVIES)

    @Test
    fun premiumMovieRowsAreEntirelyVisible() = assertRowsEntirelyVisible(HomeLayout.PREMIUM, HomeSection.MOVIES)

    private fun assertRowsEntirelyVisible(layout: HomeLayout, section: HomeSection) {
        val focused = mutableListOf<String>()
        compose.setContent { Home(layout, HomeActions(onChannelFocus = { focused += it.title }, onPosterFocus = { focused += it.title }), section) }
        compose.waitForIdle()
        if (layout == HomeLayout.STREAMING) enterStreamingContent()
        val safeBottom = compose.onRoot().getBoundsInRoot().bottom - 27.dp
        var checked = 0
        repeat(6) {
            press(Key.DirectionDown)
            val group = focused.lastOrNull()?.substringBefore(" · ")?.removePrefix("Groupe ")?.toIntOrNull() ?: return@repeat
            val card = compose.onNode(isFocused()).getBoundsInRoot()
            val previous = runCatching { compose.onNodeWithText("Groupe ${group - 1} · 0").isDisplayed() }.getOrDefault(false)
            println("$layout $section, Groupe $group : vignette ${card.top}..${card.bottom} (limite $safeBottom), précédente visible : $previous")
            assertTrue("$layout $section, Groupe $group : vignette coupée en bas (${card.bottom} > $safeBottom)", card.bottom <= safeBottom)
            assertTrue("$layout $section, Groupe $group : titre caché", compose.onNodeWithText("Groupe $group").isDisplayed())
            if (layout == HomeLayout.STREAMING) {
                assertNextRowPeeks(group)
            } else {
                assertTrue("$layout $section, Groupe $group : rangée précédente visible au-dessus", !previous)
            }
            checked++
        }
        assertTrue("$layout $section : rangées vérifiées : $checked", checked >= 4)
    }

    /**
     * Volet de droite du Live Center : « droite » depuis la liste y entre, « bas » le fait défiler
     * jusqu'à « Plus tard », « gauche » revient à la chaîne d'où l'on venait.
     */
    @Test
    fun liveCenterPaneCanBeScrolledWithTheRemote() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions(onChannelFocus = { focused += it }), preview = SampleData.channelPreview) }
        compose.waitForIdle()
        // Onglets → recherche → catégories, puis la liste des chaînes et sa 4e chaîne.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        repeat(3) { press(Key.DirectionDown) }
        val chosen = focused.last()
        val list = compose.onNode(isFocused()).getBoundsInRoot()
        assertTrue("« Plus tard » déjà visible : le volet ne dépasse pas", !compose.onNodeWithText("Journal de la nuit").isDisplayed())
        press(Key.DirectionRight)
        val pane = compose.onNode(isFocused()).getBoundsInRoot()
        assertTrue("Focus dans le volet : $pane (liste : $list)", pane.left >= list.right)
        repeat(4) { press(Key.DirectionDown) }
        assertTrue("« Plus tard » visible après défilement", compose.onNodeWithText("Journal de la nuit").isDisplayed())
        press(Key.DirectionLeft)
        println("Volet : retour sur ${focused.last().title} (choisie : ${chosen.title})")
        assertEquals(chosen.id, focused.last().id)
        assertTrue("Focus revenu dans la liste", compose.onNode(isFocused()).getBoundsInRoot().right <= pane.left)
    }

    /**
     * L'élément choisi s'agrandit un peu (onglet ×1,06, affiche ×1,07 ; une catégorie garde sa taille,
     * son texte reste net, mais son ombre déborde de [RAIL_FOCUS_ROOM]) : sa liste lui laisse cette place,
     * sinon elle le coupe à son bord (cadre de sélection rogné). [vertical] : une rangée qui défile de
     * gauche à droite ne coupe pas en hauteur (Compose y garde une marge).
     */
    private fun assertRoomToGrow(scale: Float, what: String, vertical: Boolean = true) {
        val item = focusedRect()
        val list = focusedListRect()
        val grownX = item.width * (scale - 1) / 2
        val grownY = item.height * (scale - 1) / 2
        println("$what : $item dans $list (+$grownX, +$grownY)")
        assertTrue("$what coupé à gauche", item.left - grownX >= list.left)
        assertTrue("$what coupé à droite", item.right + grownX <= list.right)
        if (vertical) assertTrue("$what coupé en haut", item.top - grownY >= list.top)
    }

    private fun focusedRect() = compose.onNode(isFocused()).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    /** Liste (ou grille, rangée d'onglets) qui défile et contient l'élément choisi : la plus proche. */
    private fun focusedListRect() = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(isFocused())).fetchSemanticsNodes()
        .map { Rect(it.positionInRoot, it.size.toSize()) }
        .minBy { it.width * it.height }

    @Test
    fun focusedTabsAndCategoriesAreNotCut() {
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions()) }
        compose.waitForIdle()
        // Premier onglet (TV), puis le dernier (Foot).
        assertRoomToGrow(1.06f, "Onglet TV", vertical = false)
        repeat(HomeSection.entries.size - 1) { press(Key.DirectionRight) }
        assertRoomToGrow(1.06f, "Onglet Foot", vertical = false)
        // Onglets → recherche → première catégorie.
        repeat(HomeSection.entries.size - 1) { press(Key.DirectionLeft) }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        // Ombre de la catégorie : RAIL_FOCUS_ROOM tout autour.
        val item = focusedRect()
        val list = focusedListRect()
        val room = with(compose.density) { RAIL_FOCUS_ROOM.toPx() }
        assertTrue("Ombre de la catégorie coupée à gauche", item.left - room >= list.left - 0.5f)
        assertTrue("Ombre de la catégorie coupée à droite", item.right + room <= list.right + 0.5f)
        assertTrue("Ombre de la catégorie coupée en haut", item.top - room >= list.top - 0.5f)
    }

    @Test
    fun focusedPosterOfTheFirstRowIsNotCut() {
        compose.setContent { Home(HomeLayout.LIVE_CENTER, HomeActions(), HomeSection.MOVIES) }
        compose.waitForIdle()
        // Onglets → recherche → catégories, puis la grille, et haut jusqu'à sa première ligne.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        repeat(4) {
            val item = focusedRect()
            if (item.top - focusedListRect().top > item.height / 2) press(Key.DirectionUp)
        }
        val item = focusedRect()
        assertTrue("Affiche de la première ligne : $item", item.top - focusedListRect().top < item.height / 2)
        assertRoomToGrow(1.07f, "Affiche")
    }

    /**
     * Classique, films : à droite dans une rangée (l'affiche choisie change la mise en avant),
     * la rangée reste calée en haut, sans que la précédente revienne au-dessus.
     */
    @Test
    fun premiumMovieRowStaysAtTheTopWhenMovingRight() {
        val focused = mutableListOf<String>()
        compose.setContent { Home(HomeLayout.PREMIUM, HomeActions(onPosterFocus = { focused += it.title; spotlight = Spotlight(it) }), HomeSection.MOVIES) }
        compose.waitForIdle()
        repeat(3) { press(Key.DirectionDown) }
        val group = focused.last().substringBefore(" · ")
        val previous = "Groupe ${group.removePrefix("Groupe ").toInt() - 1} · 0"
        val tops = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        repeat(5) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                tops += compose.onNodeWithText(group).getBoundsInRoot().top.value
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        println("Premium films, $group : haut = ${tops.distinct()} (${focused.last()})")
        assertTrue("$group bouge en allant à droite : ${tops.distinct()}", tops.max() - tops.min() < 0.5f)
        assertTrue("$previous revenu au-dessus", !compose.onNodeWithText(previous).isDisplayed())
    }

    /**
     * Bout d'une rangée : la suite se charge d'elle-même (plus de « Voir plus »), dans la même interface,
     * et la sélection continue sans s'arrêter.
     */
    @Test
    fun rowLoadsMoreByItself() {
        val focused = mutableListOf<String>()
        val categories = mutableListOf<Category>()
        compose.setContent { Home(HomeLayout.PREMIUM, HomeActions(onPosterFocus = { focused += it.title }, onCategory = { categories += it }), HomeSection.MOVIES) }
        compose.waitForIdle()
        // Onglets, puis la première rangée.
        repeat(3) { if (focused.isEmpty()) press(Key.DirectionDown) }
        val group = focused.last().substringBefore(" · ")
        repeat(HomeRows.ROW_SIZE + 4) { press(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Rangée : ${focused.last()}, catégories ouvertes : $categories")
        assertTrue("« Voir plus » encore là", compose.onAllNodesWithText("Voir plus").fetchSemanticsNodes().isEmpty())
        val index = focused.last().substringAfter(" · ").toInt()
        assertTrue("Sélection arrêtée à ${focused.last()}", focused.last().startsWith("$group · ") && index > HomeRows.ROW_SIZE)
        assertTrue("Affichage classique ouvert : $categories", categories.isEmpty())
    }

    /**
     * Mosaïque du Live Center : l'aperçu est en bandeau au-dessus (plus de volet à droite), les vignettes prennent
     * toute la largeur, assez larges pour lire le nom des chaînes ; droite passe à la chaîne suivante.
     */
    @Test
    fun liveCenterMosaicHasTheBannerOnTop() {
        val focused = mutableListOf<ChannelListRow>()
        compose.setContent {
            Home(HomeLayout.LIVE_CENTER, HomeActions(onChannelFocus = { focused += it }), preview = SampleData.channelPreview, grid = GridColumns(liveCenterMosaic = true, perRow = 5))
        }
        compose.waitForIdle()
        // Onglets → recherche → catégories, puis la mosaïque (première colonne).
        // → depuis « Favoris » : dans la mosaïque (à cette hauteur, il n'y a à droite que le bandeau, puis les onglets).
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        val first = focused.last()
        val card = compose.onNode(isFocused()).getBoundsInRoot()
        // Droite : la chaîne suivante, sur la même ligne.
        press(Key.DirectionRight)
        val second = focused.last()
        val secondCard = compose.onNode(isFocused()).getBoundsInRoot()
        assertTrue("Droite : chaîne suivante (${first.title} → ${second.title})", second.id != first.id)
        assertTrue("Même ligne", secondCard.top == card.top)
        // Bandeau au-dessus de la mosaïque, vignettes assez larges.
        // Nom de la chaîne en grand dans le bandeau (le premier à l'écran ; il est aussi sur sa vignette).
        val banner = compose.onAllNodesWithText(SampleData.channelPreview.channel.title, useUnmergedTree = true)[0].getBoundsInRoot()
        println("Vignette : $card, bandeau : $banner")
        assertTrue("Bandeau au-dessus des vignettes", banner.bottom < card.top)
        assertTrue("Vignettes larges : ${card.right - card.left}", card.right - card.left > 110.dp)
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
    /**
     * Streaming : au premier appui (télécommande), le focus se pose dans le menu de gauche ; → entre dans
     * le contenu (sur la télé, le contenu a le focus d'office).
     */
    /**
     * Streaming : la rangée choisie est au milieu ; en dessous, seul le titre de la suivante se voit (pas ses
     * affiches). Rien à vérifier pour la dernière rangée.
     */
    private fun assertNextRowPeeks(group: Int) {
        if (compose.onAllNodesWithText("Groupe ${group + 1}").fetchSemanticsNodes().isEmpty()) return
        assertTrue("Titre « Groupe ${group + 1} » caché sous la rangée choisie", compose.onNodeWithText("Groupe ${group + 1}").isDisplayed())
        val card = runCatching { compose.onNodeWithText("Groupe ${group + 1} · 0").isDisplayed() }.getOrDefault(false)
        assertTrue("Affiches de « Groupe ${group + 1} » visibles sous la rangée choisie", !card)
    }

    /** ← jusqu'à l'ouverture du volet des catégories (bord gauche de la rangée ou de la liste). */
    private fun openDrawerWithLeft() {
        repeat(10) {
            if (compose.onAllNodesWithTag(CATEGORY_DRAWER_TAG).fetchSemanticsNodes().isNotEmpty()) return
            press(Key.DirectionLeft)
        }
    }

    private fun enterStreamingContent() {
        press(Key.DirectionDown)
        press(Key.DirectionRight)
    }

    /**
     * Réorganisation des favoris (Live Center, liste) : la dernière chaîne, soulevée, monte jusqu'en haut ; la liste
     * la suit, elle reste à l'écran et sélectionnée.
     */
    @Test
    fun aMovedFavoriteStaysOnScreen() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val favorites = androidx.compose.runtime.mutableStateListOf(*SampleData.channels.take(20).toTypedArray())
        val moving = favorites.last()
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                HomeContent(
                    section = HomeSection.TV,
                    groups = groups,
                    category = Category.Favorites,
                    query = "",
                    channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems(),
                    favoriteChannels = favorites.toList(),
                    movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems(),
                    favoriteMovies = emptyList(),
                    series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems(),
                    favoriteSeries = emptyList(),
                    favoriteKeys = favorites.map { it.itemKey }.toSet(),
                    reorder = ReorderState(active = true, movingKey = moving.itemKey),
                    now = SampleData.NOW,
                    snackbarHostState = remember { SnackbarHostState() },
                    actions = HomeActions(onMove = { _, key, offset ->
                        val from = favorites.indexOfFirst { it.itemKey == key }
                        val to = (from + offset).coerceIn(0, favorites.lastIndex)
                        favorites.add(to, favorites.removeAt(from))
                    }),
                    guide = {},
                    football = {},
                    layout = HomeLayout.LIVE_CENTER,
                    gridColumns = GridColumns(),
                )
            }
        }
        compose.waitForIdle()
        // Sélection sur la chaîne soulevée (la dernière) : → dans la liste, ↓ jusqu'en bas.
        repeat(3) { if (focusedText()?.contains(favorites.first().title) != true) press(Key.DirectionRight) }
        println("Liste : ${focusedText()}")
        repeat(40) { if (focusedText()?.contains(moving.title) != true) press(Key.DirectionDown) }
        println("Soulevée : ${focusedText()}")
        assertTrue("Pas sur la dernière : ${focusedText()}", focusedText()?.contains(moving.title) == true)
        repeat(favorites.size) { press(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(moving.itemKey, favorites.first().itemKey)
        println("En haut : ${focusedText()}")
        assertTrue("Plus sélectionnée : ${focusedText()}", focusedText()?.contains(moving.title) == true)
        compose.onNode(isFocused()).assertIsDisplayed()
        val bounds = compose.onNode(isFocused()).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("Hors de l'écran : $bounds", bounds.top >= root.top && bounds.bottom <= root.bottom)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Composable
    private fun Home(
        layout: HomeLayout,
        actions: HomeActions,
        section: HomeSection = HomeSection.TV,
        preview: ChannelPreview? = null,
        grid: GridColumns = GridColumns(),
        category: Category = Category.All,
        discover: Discover = Discover(),
    ) {
        NexTvTheme(ThemeStyle.NIGHT) {

            val channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
            val movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems()
            val series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems()
            HomeContent(
                section = section,
                groups = groups,
                category = category,
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
                preview = preview,
                spotlight = spotlight,
                gridColumns = grid,
                discover = discover,
            )
        }
    }
}
