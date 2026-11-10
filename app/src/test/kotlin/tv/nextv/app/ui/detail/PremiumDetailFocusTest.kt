package tv.nextv.app.ui.detail

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.screenshots.SampleData
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.model.ContentKind

/**
 * Fiche de l'interface Classique, à la télécommande : long résumé, série (« Lire le premier épisode »,
 * « Bande-annonce », favoris). Les boutons tiennent sur une ligne, et passer de l'un à l'autre ne fait pas défiler.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class PremiumDetailFocusTest {

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
    fun buttonsOnOneLineAndNoJump() {
        val base = SampleData.seriesDetail
        // Fiche TMDB d'exemple (celle du film), adaptée à la série de la photo.
        val available = SampleData.movieDetail.metadata as MetadataState.Available
        val title = "L'impératrice remariée"
        val series = base.copy(
            progress = null,
            metadata = available.copy(
                details = available.details.copy(
                    title = title,
                    overview = "Impératrice de l'Empire de l'Est, la brillante Navier règne en parfaite harmonie. ".repeat(8),
                    tagline = "J'accepte ce divorce et je demande l'approbation de mon remariage.",
                    trailerYoutubeKey = "abc",
                ),
            ),
            cast = listOf("Shin Min-a", "Ju Ji-hoon", "Lee Jong-suk").map { tv.nextv.core.meta.CastMember(it, null, null) },
        )
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                DetailContent(
                    state = series,
                    kind = ContentKind.SERIES,
                    snackbarHostState = remember { SnackbarHostState() },
                    onPlay = {},
                    onTrailer = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onBack = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        fun top(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        val play = top("Lire le premier")
        val trailer = top("Bande-annonce")
        println("Boutons : lecture $play, bande-annonce $trailer ; sélection ${focusedText()}")
        assertEquals("Boutons sur deux lignes", play, trailer, 2f)
        val start = top(title)
        repeat(2) {
            press(Key.DirectionRight)
            println("→ ${focusedText()} : titre à ${top(title)} (départ $start)")
            assertEquals("→ ${focusedText()}", start, top(title), 1f)
        }
        press(Key.DirectionLeft)
        assertEquals(start, top(title), 1f)
        assertTrue("Haut coupé : $start", start >= 0f)
    }
}
