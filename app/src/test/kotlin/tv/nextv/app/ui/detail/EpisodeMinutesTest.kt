package tv.nextv.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.MediaType

/** Durée affichée sur la vignette d'un épisode : mesurée, sinon celle de TMDB, sinon la durée habituelle de la série. */
class EpisodeMinutesTest {

    private fun episode(id: Long, season: Int?, number: Int?) = EpisodeRow(id, "Série S${season}E$number", season, number, null, "SUPPORTED")

    private val series = MediaDetails(tmdbId = 42, type = MediaType.SERIES, title = "Série", runtimeMinutes = 24)

    @Test
    fun durationOfEachEpisode() {
        val state = DetailUiState(
            metadata = MetadataState.Available(series),
            seasonRuntimes = mapOf(1 to mapOf(1 to 25, 2 to 23)),
            progress = ProgressEntity(1, "series:serie", "SERIES", 102, "Série", "S01E02", null, 300_000, 1_412_000, 1_000),
        )
        assertEquals(25, state.episodeMinutes(episode(101, 1, 1)))
        // En cours de lecture : la durée mesurée du fichier (23 min 32 s).
        assertEquals(24, state.episodeMinutes(episode(102, 1, 2)))
        // Numéro que TMDB ne connaît pas (numérotation de la playlist différente) : la durée habituelle.
        assertEquals(24, state.episodeMinutes(episode(150, 1, 50)))
        // Saison pas encore lue : rien (pas une durée approximative remplacée un instant après).
        assertNull(state.episodeMinutes(episode(201, 2, 1)))
        // Épisode sans saison : la durée habituelle.
        assertEquals(24, state.episodeMinutes(episode(301, null, 3)))
    }

    @Test
    fun withoutTmdbOnlyTheMeasuredDuration() {
        val state = DetailUiState(
            metadata = MetadataState.NotFound,
            progress = ProgressEntity(1, "series:serie", "SERIES", 102, "Série", "S01E02", null, 300_000, 2_700_000, 1_000),
        )
        assertEquals(45, state.episodeMinutes(episode(102, 1, 2)))
        assertNull(state.episodeMinutes(episode(101, 1, 1)))
        assertNull(state.episodeMinutes(episode(301, null, 3)))
    }
}
