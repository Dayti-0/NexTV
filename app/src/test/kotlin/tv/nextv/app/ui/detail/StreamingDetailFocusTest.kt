package tv.nextv.app.ui.detail

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
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
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.model.ContentKind

/** Fiche Streaming à la télécommande : départ sur « Reprendre », épisodes, retour aux boutons. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class StreamingDetailFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun television() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun focusedText() = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.toString().let { Regex("Text : \\[[^]]*]").find(it)?.value }
    }.getOrNull()

    @Test
    fun seriesSheetWithTheRemote() {
        val played = mutableListOf<Long>()
        val resumed = mutableListOf<Long>()
        val series = SampleData.seriesDetail.copy(selectedSeason = 0)
        val progress = SampleData.continueSeries.first().copy(channelId = series.seasons.first().episodes[1].id)
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                StreamingDetailContent(
                    state = series.copy(progress = progress),
                    kind = ContentKind.SERIES,
                    window = rememberWindowInfo(),
                    onPlay = { played += it },
                    onResume = { resumed += it },
                    onRestart = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onOpenItem = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Départ : ${focusedText()}")
        assertTrue("Départ : ${focusedText()}", focusedText()?.contains("Reprendre") == true)
        // ↓ : saisons ; ↓ : épisodes ; → : l'épisode en cours (2e) ; OK : il reprend.
        press(Key.DirectionDown)
        println("↓ : ${focusedText()}")
        assertTrue("↓ : ${focusedText()}", focusedText()?.contains("Saison 1") == true)
        press(Key.DirectionDown)
        println("↓↓ : ${focusedText()}")
        press(Key.DirectionRight)
        println("→ : ${focusedText()}")
        press(Key.DirectionCenter)
        println("OK : reprise $resumed, lecture $played")
        assertEquals(listOf(progress.channelId), resumed)
        // ↑ ↑ : retour aux boutons.
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        println("↑↑ : ${focusedText()}")
        assertTrue("↑↑ : ${focusedText()}", focusedText()?.contains("Reprendre") == true || focusedText()?.contains("Lire") == true)
    }

    @Test
    fun favoriteButtonWithTheRemote() {
        var toggles = 0
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                StreamingDetailContent(
                    state = SampleData.movieDetail,
                    kind = ContentKind.MOVIE,
                    window = rememberWindowInfo(),
                    onPlay = {},
                    onResume = {},
                    onRestart = {},
                    onToggleFavorite = { toggles++ },
                    onSelectSeason = {},
                    onOpenItem = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Départ : ${focusedText()}")
        repeat(4) {
            if (focusedText()?.contains("favoris") != true) press(Key.DirectionRight)
        }
        println("Bouton : ${focusedText()}")
        assertTrue("Bouton : ${focusedText()}", focusedText()?.contains("favoris") == true)
        press(Key.DirectionCenter)
        assertEquals(1, toggles)
    }

    @Test
    fun backToTheButtonsShowsTheWholeTop() {
        val movie = SampleData.movieDetail.copy(
            similar = SampleData.movies.take(8).map { tv.nextv.app.ui.home.PosterItem(it.id, ContentKind.MOVIE, it.itemKey, it.title, it.year, it.logoUrl) },
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit", "Louis Moreau", "Jeanne Laurent").map { tv.nextv.core.meta.CastMember(it, null, null) },
        )
        val title = (movie.metadata as tv.nextv.app.data.catalog.MetadataState.Available).details.title
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                StreamingDetailContent(
                    state = movie,
                    kind = ContentKind.MOVIE,
                    window = rememberWindowInfo(),
                    onPlay = {},
                    onResume = {},
                    onRestart = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onOpenItem = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Haut de la fiche : le plus haut des éléments qui portent le titre (titre, affiche).
        fun titleTop() = compose.onAllNodes(androidx.compose.ui.test.hasText(title), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        val start = titleTop()
        // ↓ ↓ : distribution puis titres similaires ; ↑ ↑ : retour aux boutons.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        println("En bas sur ${focusedText()} : titre à ${titleTop()} (départ $start)")
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Remonté sur ${focusedText()} : titre à ${titleTop()} (départ $start)")
        assertEquals(start, titleTop(), 1f)
        // Et dès l'ouverture, le haut n'est pas coupé.
        assertTrue("Haut coupé : $start", start >= 0f)
    }

    /**
     * Haut de fiche chargé (long titre, long résumé, distribution) : passer d'un bouton à l'autre ne fait pas
     * défiler la fiche, le titre reste entier en haut.
     */
    @Test
    fun movingBetweenTheButtonsKeepsTheTitleInPlace() {
        val base = SampleData.movieDetail
        val available = base.metadata as tv.nextv.app.data.catalog.MetadataState.Available
        val title = "Leaving Neverland 2 : Surviving Michael Jackson"
        val movie = base.copy(
            metadata = available.copy(
                details = available.details.copy(title = title, overview = "Un très long résumé de la fiche. ".repeat(20), tagline = "Une accroche"),
            ),
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit", "Louis Moreau", "Jeanne Laurent").map { tv.nextv.core.meta.CastMember(it, null, null) },
        )
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                StreamingDetailContent(
                    state = movie,
                    kind = ContentKind.MOVIE,
                    window = rememberWindowInfo(),
                    onPlay = {},
                    onResume = {},
                    onRestart = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onOpenItem = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        fun titleTop() = compose.onAllNodes(androidx.compose.ui.test.hasText(title), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        val start = titleTop()
        println("Départ sur ${focusedText()} : titre à $start")
        assertTrue("Haut coupé : $start", start >= 0f)
        repeat(3) {
            press(Key.DirectionRight)
            println("→ ${focusedText()} : titre à ${titleTop()}")
            assertEquals("→ ${focusedText()}", start, titleTop(), 1f)
        }
        repeat(3) {
            press(Key.DirectionLeft)
            assertEquals("← ${focusedText()}", start, titleTop(), 1f)
        }
    }
}
