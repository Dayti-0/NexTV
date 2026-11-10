package tv.nextv.app.ui.home

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
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Fenêtre « Favoris » de Classique, à la télécommande : ouverte sur les films, onglets Films / Séries / Chaînes TV
 * (la liste suit l'onglet choisi), OK ouvre la fiche d'un film, et lance une chaîne en mode favoris.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class PremiumFavoritesTest {

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
    fun tabsMoviesSeriesAndChannels() {
        val openedMovies = mutableListOf<Long>()
        val played = mutableListOf<Long>()
        val movies = SampleData.movies.take(2)
        val series = listOf(SeriesRow(5_000, "Les Gardiens", 2021, null, "Séries", "series:les gardiens", 8))
        val channels = SampleData.channels.take(2)
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                PremiumFavorites(
                    movies = movies,
                    series = series,
                    channels = channels,
                    now = SampleData.NOW,
                    actions = HomeActions(onOpenMovie = { openedMovies += it }, onPlayFavoriteChannel = { played += it }),
                    onBack = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        println("Départ : ${focusedText()}")
        assertTrue("Départ : ${focusedText()}", focusedText()?.contains(movies[0].title) == true)
        press(Key.DirectionCenter)
        assertEquals(listOf(movies[0].id), openedMovies)
        // ↑ : onglet Films ; → : Séries (la liste suit) ; → : Chaînes TV.
        press(Key.DirectionUp)
        println("↑ : ${focusedText()}")
        press(Key.DirectionRight)
        println("→ : ${focusedText()}")
        assertTrue("Onglet Séries : ${focusedText()}", focusedText()?.contains("Séries") == true)
        assertTrue("Série absente", compose.onAllNodes(androidx.compose.ui.test.hasText("Les Gardiens")).fetchSemanticsNodes().isNotEmpty())
        press(Key.DirectionRight)
        assertTrue("Onglet Chaînes : ${focusedText()}", focusedText()?.contains("Chaînes TV") == true)
        // ↓ : la première chaîne ; OK : lancée en mode favoris.
        press(Key.DirectionDown)
        println("↓ : ${focusedText()}")
        press(Key.DirectionCenter)
        assertEquals(listOf(channels[0].id), played)
    }
}
