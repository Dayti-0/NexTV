package tv.nextv.app.ui.detail

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
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
 * Fiche de l'interface Classique, à la télécommande : long résumé, série (« Lire le premier épisode », favoris ; la
 * bande-annonce est en bas de la fiche). Les boutons tiennent sur une ligne, et passer de l'un à l'autre ne fait pas
 * défiler.
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
        fun center(description: String) = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(description), useUnmergedTree = true)
            .fetchSemanticsNodes().minOf { it.boundsInRoot.center.y }
        val play = compose.onAllNodes(hasText("Lire le premier", substring = true), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.boundsInRoot.center.y }
        val favorite = center("Ajouter aux favoris")
        println("Boutons : lecture $play, favoris $favorite ; sélection ${focusedText()}")
        assertEquals("Boutons sur deux lignes", play, favorite, 4f)
        // Plus de bouton « Bande-annonce » en haut : elle est tout en bas de la fiche, sur toute la largeur.
        assertTrue(compose.onAllNodes(hasText("Bande-annonce") and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val start = top(title)
        repeat(1) {
            press(Key.DirectionRight)
            println("→ ${focusedText()} : titre à ${top(title)} (départ $start)")
            assertEquals("→ ${focusedText()}", start, top(title), 1f)
        }
        press(Key.DirectionLeft)
        assertEquals(start, top(title), 1f)
        assertTrue("Haut coupé : $start", start >= 0f)
    }

    /**
     * Film en plusieurs versions : → depuis « Lecture » mène au bouton « Version », OK passe à la suivante (la
     * sélection reste dessus), et « Lecture » lit alors la version affichée.
     */
    @Test
    fun versionButtonCyclesWithTheRemote() {
        val versions = listOf(MovieVersion(1_000, "TRUEFRENCH", "UHD"), MovieVersion(1_001, "FR", null))
        var played: Long? = null
        compose.setContent {
            var state by remember { androidx.compose.runtime.mutableStateOf(SampleData.movieDetail.copy(progress = null, versions = versions)) }
            NexTvTheme(ThemeStyle.NIGHT) {
                DetailContent(
                    state = state,
                    kind = ContentKind.MOVIE,
                    snackbarHostState = remember { SnackbarHostState() },
                    onPlay = { played = it },
                    onTrailer = {},
                    onToggleFavorite = {},
                    onSelectSeason = {},
                    onBack = {},
                    onNextVersion = {
                        val next = (state.selectedVersion + 1) % versions.size
                        state = state.copy(selectedVersion = next, item = state.item?.copy(firstId = versions[next].id, quality = versions[next].quality))
                    },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Sans reprise en cours, les boutons tiennent sur une ligne.
        val favorite = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("favoris", substring = true), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.boundsInRoot.center.y }
        val play = compose.onAllNodes(hasText("Lecture", substring = true), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.boundsInRoot.center.y }
        assertEquals("Boutons sur deux lignes", play, favorite, 4f)
        press(Key.DirectionRight)
        assertTrue("Sélection : ${focusedText()}", focusedText().orEmpty().contains("Version : TRUEFRENCH (1/2)"))
        press(Key.DirectionCenter)
        assertTrue("Sélection : ${focusedText()}", focusedText().orEmpty().contains("Version : FR (2/2)"))
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(1_001L, played)
    }

    /** Distribution : en y descendant, le nom de l'acteur choisi est à l'écran (et pas seulement sa photo). */
    @Test
    fun castNamesAreShownWhenSelected() {
        val base = SampleData.movieDetail
        val available = base.metadata as MetadataState.Available
        val movie = base.copy(
            progress = null,
            metadata = available.copy(details = available.details.copy(overview = "Un long résumé du film. ".repeat(12))),
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit", "Louis Moreau").map { tv.nextv.core.meta.CastMember(it, "Rôle de $it", null) },
        )
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                DetailContent(
                    state = movie,
                    kind = ContentKind.MOVIE,
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
        // ↓ depuis les boutons : la première photo de la distribution.
        press(Key.DirectionDown)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val name = compose.onAllNodes(hasText("Camille Martin"), useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        val role = compose.onAllNodes(hasText("Rôle de Camille Martin"), useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        println("Distribution : nom en bas à $name, rôle à $role, écran ${root.bottom}")
        assertTrue("Nom coupé : $name > ${root.bottom}", name <= root.bottom)
        assertTrue("Rôle coupé : $role > ${root.bottom}", role <= root.bottom)
    }

    /** Tout en bas de la fiche, puis tout en haut : le bouton Retour choisi n'est pas coupé (marge au-dessus). */
    @Test
    fun backButtonIsNotCutAtTheTop() {
        val base = SampleData.movieDetail
        val available = base.metadata as MetadataState.Available
        val movie = base.copy(
            progress = null,
            metadata = available.copy(details = available.details.copy(overview = "Un long résumé du film. ".repeat(12))),
            cast = listOf("Camille Martin", "Hugo Bernard", "Inès Petit", "Louis Moreau").map { tv.nextv.core.meta.CastMember(it, "Rôle de $it", null) },
        )
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                DetailContent(
                    state = movie,
                    kind = ContentKind.MOVIE,
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
        repeat(4) { press(Key.DirectionDown) }
        val back = androidx.compose.ui.test.hasContentDescription("Retour")
        repeat(8) { if (compose.onAllNodes(isFocused() and back).fetchSemanticsNodes().isEmpty()) press(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val top = compose.onNode(isFocused() and back).fetchSemanticsNode().boundsInRoot.top
        val margin = with(compose.density) { 12.dp.toPx() }
        println("Bouton Retour choisi : haut à $top (marge voulue $margin)")
        assertTrue("Bouton Retour coupé : haut à $top", top >= margin)
    }
}
