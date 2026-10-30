package tv.nextv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FootballMatchesTest {

    private fun of(title: String, subtitle: String? = null, category: String? = null, description: String? = null) =
        FootballMatches.competitionOf(title, subtitle, category, description)

    @Test
    fun `matches are found in the title or subtitle`() {
        assertEquals(Competition.LIGUE_1, of("Football", "Ligue 1 McDonald's - 11e journée : Paris-SG / Marseille", "Sport"))
        assertEquals(Competition.LIGUE_1, of("Ligue 1+ : Lens - Lille"))
        assertEquals(Competition.PREMIER_LEAGUE, of("Premier League : Liverpool - Arsenal", category = "Football"))
        assertEquals(Competition.CHAMPIONS_LEAGUE, of("Football : Ligue des champions", "Real Madrid / Liverpool"))
        assertEquals(Competition.CHAMPIONS_LEAGUE, of("UEFA Champions League", "Bayern Munich vs. Inter"))
        assertEquals(Competition.CHAMPIONS_LEAGUE, of("Ligue des champions : Club Bruges / Paris SG"))
    }

    @Test
    fun `description is read for football programmes only`() {
        assertEquals(Competition.PREMIER_LEAGUE, of("Manchester City / Chelsea", category = "Football", description = "Match de Premier League."))
        assertNull(of("Le grand quiz", category = "Divertissement", description = "Questions sur la Ligue 1."))
    }

    @Test
    fun `magazines, summaries and other sports are left out`() {
        assertNull(of("Canal Football Club", category = "Magazine sportif", description = "Ligue 1"))
        assertNull(of("Téléfoot", category = "Magazine de football", description = "Toute l'actualité de la Ligue 1."))
        assertNull(of("Résumés de la Premier League"))
        assertNull(of("Rugby : Top 14"))
        assertNull(of("Handball : Ligue 1"))
        assertNull(of("Football : Ligue 1 Arkema", "PSG / Lyon"))
        assertNull(of("Ligue 10 de démonstration"))
        assertNull(of("Football", "Coupe de France : Rennes - Nantes"))
    }
}
