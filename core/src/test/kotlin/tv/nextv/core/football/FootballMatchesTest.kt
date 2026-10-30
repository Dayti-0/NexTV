package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FootballMatchesTest {

    private fun of(title: String, subtitle: String? = null, category: String? = null, description: String? = null) =
        FootballMatches.fixtureOf(title, subtitle, category, description)

    @Test
    fun `matches give the competition and both teams`() {
        assertEquals(
            Fixture(Competition.LIGUE_1, "Paris-SG", "Marseille"),
            of("Football", "Ligue 1 McDonald's - 11e journée : Paris-SG / Marseille", "Sport"),
        )
        assertEquals(Fixture(Competition.LIGUE_1, "Lens", "Lille"), of("Ligue 1+ : Lens - Lille"))
        assertEquals(Fixture(Competition.PREMIER_LEAGUE, "Liverpool", "Arsenal"), of("Premier League : Liverpool - Arsenal", category = "Football"))
        assertEquals(Fixture(Competition.CHAMPIONS_LEAGUE, "Real Madrid", "Liverpool"), of("Football : Ligue des champions", "Real Madrid / Liverpool"))
        assertEquals(Fixture(Competition.CHAMPIONS_LEAGUE, "Bayern Munich", "Inter"), of("UEFA Champions League", "Bayern Munich vs. Inter"))
        assertEquals(Fixture(Competition.CHAMPIONS_LEAGUE, "PSG", "Arsenal"), of("Football : Ligue des champions - PSG / Arsenal"))
        assertEquals(Fixture(Competition.CHAMPIONS_LEAGUE, "Club Bruges", "Paris SG"), of("Ligue des champions : Club Bruges / Paris SG"))
        assertEquals("Paris SG – Marseille", Fixture(Competition.LIGUE_1, "Paris SG", "Marseille").label)
    }

    @Test
    fun `other leagues, european cups and national teams are recognised`() {
        assertEquals(Fixture(Competition.LA_LIGA, "Real Madrid", "FC Barcelone"), of("Football : Liga", "Real Madrid / FC Barcelone"))
        assertEquals(Fixture(Competition.LA_LIGA, "Séville", "Betis"), of("LaLiga EA Sports : Séville - Betis"))
        assertEquals(Fixture(Competition.SERIE_A, "Inter Milan", "Juventus"), of("Football : Serie A", "Inter Milan / Juventus"))
        assertEquals(Fixture(Competition.BUNDESLIGA, "Bayern Munich", "Dortmund"), of("Bundesliga : Bayern Munich - Dortmund"))
        assertEquals(Fixture(Competition.EUROPA_LEAGUE, "Lyon", "Rome"), of("Football : Ligue Europa", "Lyon / Rome"))
        assertEquals(Fixture(Competition.CONFERENCE_LEAGUE, "Strasbourg", "Fiorentina"), of("Football : Ligue Europa Conférence", "Strasbourg / Fiorentina"))
        assertEquals(Fixture(Competition.NATIONAL_TEAMS, "France", "Allemagne"), of("Football : Ligue des nations", "France / Allemagne"))
        assertEquals(Fixture(Competition.NATIONAL_TEAMS, "France", "Espagne"), of("Football : éliminatoires de la Coupe du monde 2030 - France / Espagne"))
        assertNull(of("Football : Liga Portugal", "Benfica / Porto"))
        assertNull(of("Football : 2. Bundesliga", "Hambourg / Hanovre"))
        assertNull(of("Football : Coupe du monde des clubs", "Real Madrid / Al-Hilal"))
        assertNull(of("Rugby : Coupe du monde", "France / Irlande"))
    }

    @Test
    fun `teams may come from the description of a football programme`() {
        assertEquals(
            Fixture(Competition.PREMIER_LEAGUE, "Manchester City", "Chelsea"),
            of("Premier League", category = "Football", description = "11e journée. Manchester City - Chelsea, en direct de l'Etihad Stadium."),
        )
        assertEquals(
            Fixture(Competition.PREMIER_LEAGUE, "Manchester City", "Chelsea"),
            of("Manchester City / Chelsea", category = "Football", description = "Match de Premier League."),
        )
        assertNull(of("Le grand quiz", category = "Divertissement", description = "Questions sur la Ligue 1 : Lens / Lille."))
    }

    @Test
    fun `real guide formats are recognised`() {
        // Canal+ : équipes en titre, compétition en sous-titre, genres « Sport, Football ».
        assertEquals(Fixture(Competition.PREMIER_LEAGUE, "Liverpool", "Arsenal"), of("Liverpool / Arsenal", "Premier League - 9e journée", "Sport, Football"))
        // TV5Monde : barre sans espaces, mi-temps entre parenthèses.
        assertEquals(
            Fixture(Competition.LIGUE_1, "Lyon", "Angers"),
            of("Football - Ligue 1 McDonald's", "Lyon/Angers (1/2) - 31/10/2026 - J9", "Sport"),
        )
        // Pickx : compétition dans la description.
        assertEquals(
            Fixture(Competition.CHAMPIONS_LEAGUE, "Galatasaray", "Stuttgart"),
            of("LIVE: Galatasaray - Stuttgart", category = "Sports, Football", description = "Quatrième journée de la phase de ligue de l'UEFA Champions League : Galatasaray - Stuttgart."),
        )
        assertEquals(
            Fixture(Competition.SERIE_A, "AC Milan", "Bologne"),
            of("AC Milan / Bologne", category = "Football", description = "Retransmission d'un match du championnat d'Italie de football, appelé Serie A."),
        )
        assertNull(of("Paris FC / Paris-SG", "Arkema Première Ligue - 7e journée", "Sport, Football"))
        assertNull(of("Football : Liga F", "Real Madrid / Tenerife", "Football"))
        assertNull(of("Charlton Athletic LFC / Chelsea", category = "Football", description = "Women's Super League action as Charlton Athletic face Chelsea."))
        assertNull(of("Multiplex", "Premier League - 9e journée", "Sport, Football"))
        assertNull(of("Washington Commanders / Philadelphia Eagles", category = "Football US"))
        assertNull(of("Eibar / Almeria", "Liga 2 - 12e journée", "Sport, Football", "Le championnat d'Espagne : Liga, deuxième division."))
        assertNull(of("Liverpool / Chelsea", "Coupe de la Ligue anglaise - 8e de finale", "Sport, Football", "Chelsea, qualifié pour la Ligue Europa…"))
        assertNull(of("Flashpoint", "Shergar/Euro 2016", "Culture Infos"))
        assertNull(of("Football : Ligue Europa", category = "Football", description = "Lyon avait gagné avant de s'incliner au Vélodrome - l'Olympiakos a ensuite..."))
    }

    @Test
    fun `other sports are left out`() {
        assertNull(of("Judo : Ligue des champions", "Paris / Kazan"))
        assertNull(of("Ligue des champions", "Judo - Finale : Paris / Kazan"))
        assertNull(of("Ligue des champions", "Kiel / Paris", "Sport", "Handball : la finale de la compétition."))
        assertNull(of("Handball : Ligue 1", "Nantes / Montpellier"))
        assertNull(of("Rugby : Champions Cup", "Toulouse / Leinster"))
        assertNull(of("Football : Ligue 1 Arkema", "PSG / Lyon"))
        assertNull(of("Premier League Darts", "Littler / Humphries"))
    }

    @Test
    fun `programmes without a match are left out`() {
        assertNull(of("Canal Football Club", category = "Magazine sportif", description = "Ligue 1"))
        assertNull(of("Téléfoot", category = "Magazine de football", description = "Toute l'actualité de la Ligue 1."))
        assertNull(of("Résumés de la Premier League"))
        assertNull(of("Ligue 1 McDonald's", "Multiplex - 11e journée"))
        assertNull(of("Football : Ligue des champions", "Tirage au sort des huitièmes de finale"))
        assertNull(of("Football : Ligue 1", "11e journée"))
        assertNull(of("Football", "Coupe de France : Rennes - Nantes"))
        assertNull(of("Ligue 10 de démonstration", "A / B"))
    }
}
