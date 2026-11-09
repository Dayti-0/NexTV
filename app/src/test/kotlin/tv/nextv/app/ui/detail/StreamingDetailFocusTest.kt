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
import tv.nextv.app.ui.home.TrailerSettings
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
                    trailers = TrailerSettings(),
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
                    trailers = TrailerSettings(),
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
}
