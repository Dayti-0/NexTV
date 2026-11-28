package tv.nextv.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.nextv.app.data.db.EpisodeRow

/** Fiche d'une série : un seul épisode par numéro quand le fournisseur en a deux copies. */
class DistinctEpisodesTest {

    private fun episode(id: Long, name: String, season: Int?, number: Int?, url: String = "http://serveur/series/u/p/$id.mkv") =
        EpisodeRow(id, name, season, number, null, "SUPPORTED", url)

    @Test
    fun twoCopiesOfEachEpisodeShowOnce() {
        val rows = listOf(
            episode(1, "Série S01 E01", 1, 1),
            episode(2, "Série S01 E01", 1, 1),
            episode(3, "Série S01 E02", 1, 2),
            episode(4, "Série S01 E02", 1, 2),
        )
        assertEquals(listOf(1L, 3L), distinctEpisodes(rows).map { it.id })
    }

    @Test
    fun frenchCopyBeforeSubtitledOne() {
        val rows = listOf(
            episode(1, "Série (VOSTFR) S01 E01", 1, 1),
            episode(2, "Série S01 E01", 1, 1),
        )
        assertEquals(listOf(2L), distinctEpisodes(rows).map { it.id })
    }

    @Test
    fun sameCopyListedTwiceCountsOnce() {
        val rows = listOf(
            episode(1, "Série S01 E01", 1, 1, url = "http://serveur/series/u/p/77.mkv"),
            episode(2, "Série S01 E01", 1, 1, url = "http://serveur/series/u/p/77.mkv"),
        )
        assertEquals(listOf(1L), distinctEpisodes(rows).map { it.id })
    }

    @Test
    fun sameNumberInAnotherSeasonIsKept() {
        val rows = listOf(episode(1, "Série S01 E01", 1, 1), episode(2, "Série S02 E01", 2, 1))
        assertEquals(listOf(1L, 2L), distinctEpisodes(rows).map { it.id })
    }

    @Test
    fun episodesWithoutNumberAreAllKept() {
        val rows = listOf(episode(1, "Série bonus", 1, null), episode(2, "Série making-of", 1, null))
        assertEquals(listOf(1L, 2L), distinctEpisodes(rows).map { it.id })
    }
}
