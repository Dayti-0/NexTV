package tv.nextv.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.MediaType

/** Année de sortie de la fiche programme prise sur TMDB quand le guide ne la donne pas. */
class ProgrammeYearTest {
    private fun film(title: String, original: String? = null, year: Int? = 1997) =
        MediaDetails(tmdbId = 1, type = MediaType.MOVIE, title = title, originalTitle = original, year = year)

    @Test
    fun theYearOfTheSameTitleIsShown() {
        assertEquals(1997, sameTitleYear("Le Cinquième Élément", film("Le Cinquième élément")))
        assertEquals(1997, sameTitleYear("The Fifth Element", film("Le Cinquième élément", original = "The Fifth Element")))
    }

    @Test
    fun anotherFilmGivesNoYear() {
        assertNull(sameTitleYear("Le Cinquième Élément", film("Le Sixième Sens")))
        assertNull(sameTitleYear("Le Cinquième Élément", null))
    }
}
