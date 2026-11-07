package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastFinderTest {

    private val minute = 60_000L
    private val kickoff = 1_800_000_000_000L

    private fun match(id: Long, competition: Competition, home: Team, away: Team, at: Long = kickoff) =
        ScheduledMatch(id, competition, at, MatchStatus.SCHEDULED, home, away)

    private fun broadcast(channel: String, title: String, subtitle: String? = null, category: String? = "Sport", start: Long = kickoff - 15 * minute, stop: Long = kickoff + 120 * minute) =
        Broadcast(channel, title, subtitle, category, null, start, stop)

    private val psg = Team("Paris Saint-Germain FC", "PSG", "PSG", null)
    private val om = Team("Olympique de Marseille", "Marseille", "MAR", null)
    private val bayern = Team("FC Bayern München", "Bayern", "FCB", null)
    private val inter = Team("FC Internazionale Milano", "Inter", "INT", null)
    private val milan = Team("AC Milan", "Milan", "MIL", null)
    private val barcelona = Team("FC Barcelona", "Barça", "FCB", null)
    private val lens = Team("Racing Club de Lens", "Lens", "RCL", null)
    private val lille = Team("Lille OSC", "Lille", "LIL", null)

    @Test
    fun `french guide names are recognised`() {
        assertTrue("paris sg" in BroadcastFinder.aliases(psg))
        assertTrue("om" in BroadcastFinder.aliases(om))
        assertTrue("bayern munich" in BroadcastFinder.aliases(bayern))
        assertTrue("inter" in BroadcastFinder.aliases(inter))
        assertTrue("barcelone" in BroadcastFinder.aliases(barcelona))
        assertTrue("losc" in BroadcastFinder.aliases(lille))
        assertTrue("paris" !in BroadcastFinder.aliases(psg))
    }

    @Test
    fun `crests are found from the names written in the guide`() {
        val lyon = Team("Olympique Lyonnais", "Lyon", "OL", "https://crests/523.png")
        val roma = Team("AS Roma", "Roma", "ROM", "https://crests/100.png")
        val find = BroadcastFinder.crests(listOf(lyon, roma, psg.copy(crestUrl = "https://crests/524.png")))

        assertEquals("https://crests/523.png", find("Lyon"))
        assertEquals("https://crests/100.png", find("AS Rome"))
        assertEquals("https://crests/524.png", find("Paris SG"))
        assertEquals(null, find("Fenerbahçe"))
    }

    @Test
    fun `each programme goes to the match it shows`() {
        val classico = match(1, Competition.LIGUE_1, psg, om)
        val derby = match(2, Competition.LIGUE_1, lens, lille)
        val result = BroadcastFinder.assign(
            listOf(classico, derby),
            listOf(
                broadcast("Canal+", "Football : Ligue 1", "Paris-SG / OM"),
                broadcast("Ligue 1+", "Lens - Lille", category = "Football"),
                broadcast("TF1", "Journal de 20h", category = "Information"),
            ),
        )
        assertEquals(listOf("Canal+"), result[classico])
        assertEquals(listOf("Ligue 1+"), result[derby])
    }

    @Test
    fun `the programme must cover the kick-off`() {
        val classico = match(1, Competition.LIGUE_1, psg, om)
        val result = BroadcastFinder.assign(
            listOf(classico),
            listOf(
                broadcast("Canal+", "Ligue 1 : PSG / OM", start = kickoff - 26 * 60 * minute, stop = kickoff - 24 * 60 * minute),
                broadcast("Canal+ Sport", "Avant-match : PSG / OM", start = kickoff - 60 * minute, stop = kickoff),
                broadcast("Canal+ Foot", "Ligue 1 : PSG / OM", start = kickoff + 3 * 60 * minute, stop = kickoff + 5 * 60 * minute),
            ),
        )
        assertEquals(emptyMap<ScheduledMatch, List<String>>(), result)
    }

    @Test
    fun `both teams cited beat a single shared name`() {
        val interMatch = match(1, Competition.CHAMPIONS_LEAGUE, inter, barcelona)
        val milanMatch = match(2, Competition.CHAMPIONS_LEAGUE, milan, bayern)
        val result = BroadcastFinder.assign(
            listOf(interMatch, milanMatch),
            listOf(
                broadcast("Canal+", "Ligue des champions", "Inter Milan / Barcelone"),
                broadcast("beIN Sports 1", "UEFA Champions League", "AC Milan vs. Bayern Munich"),
            ),
        )
        assertEquals(listOf("Canal+"), result[interMatch])
        assertEquals(listOf("beIN Sports 1"), result[milanMatch])
    }

    // Cas réels du guide xmltvfr.fr (week-end du 7 novembre 2026).
    private val sevilla = Team("Sevilla", null, "SEV", null)
    private val alaves = Team("Alavés", null, "ALA", null)
    private val atletico = Team("Atlético Madrid", "Atlético", "ATM", null)
    private val valencia = Team("Valencia", null, "VAL", null)
    private val realMadrid = Team("Real Madrid", null, "RMA", null)
    private val napoli = Team("Napoli", null, "NAP", null)
    private val lazio = Team("Lazio", null, "LAZ", null)
    private val roma = Team("AS Roma", null, "ROM", null)
    private val sassuolo = Team("Sassuolo", null, "SAS", null)
    private val torino = Team("Torino", null, "TOR", null)
    private val lecce = Team("Lecce", null, "LEC", null)
    private val juventus = Team("Juventus", null, "JUV", null)
    private val atalanta = Team("Atalanta", null, "ATA", null)
    private val italy = Team("Italy", null, "ITA", null)
    private val france = Team("France", null, "FRA", null)

    private val serieA = "Retransmission d'un match du championnat d'Italie de football, appelé Serie A."

    /** Programme de DAZN : genre « Émission sportive », compétition en sous-titre. */
    private fun dazn(title: String, subtitle: String, description: String? = null, start: Long = kickoff - 10 * minute) =
        Broadcast("DAZN", title, subtitle, "Football, Emission sportive", description, start, start + 140 * minute)

    @Test
    fun `dazn live matches keep their competition`() {
        val valenceReal = match(1, Competition.LA_LIGA, valencia, realMadrid)
        val result = BroadcastFinder.assign(listOf(valenceReal), listOf(dazn("Valence / Real Madrid", "Football. Liga 12e journée.")))
        assertEquals(listOf("DAZN"), result[valenceReal])
        assertTrue("valence" in BroadcastFinder.aliases(valencia))
    }

    @Test
    fun `a programme naming another fixture does not show a match sharing one team`() {
        val sevillaAlaves = match(1, Competition.LA_LIGA, sevilla, alaves)
        // Barcelone joue aussi dans la fenêtre du calendrier : « FC Barcelone » est un autre club.
        val atleticoBarca = match(2, Competition.LA_LIGA, atletico, barcelona, at = kickoff + 48 * 60 * minute)
        val result = BroadcastFinder.assign(
            listOf(sevillaAlaves, atleticoBarca),
            listOf(
                dazn("FC Barcelone / Deportivo Alavés", "Football. Liga 11e journée."),
                Broadcast("Eleven Sports 1", "FC Barcelone / Deportivo Alavés", null, "Football", "Diffusion d'un match de LaLiga EA Sports.", kickoff - 15 * minute, kickoff + 2 * 60 * minute),
                broadcast("Canal+ Sport 4", "FC Séville / Deportivo Alavés", "Liga - 12e journée", "Sport, Football"),
            ),
        )
        assertEquals(listOf("Canal+ Sport 4"), result[sevillaAlaves])
    }

    @Test
    fun `an unknown name in the fixture does not hide the match`() {
        val interComo = match(1, Competition.SERIE_A, inter, Team("Como", null, "COM", null))
        val result = BroadcastFinder.assign(listOf(interComo), listOf(dazn("Inter Milan / Côme", "Football. Serie A 11e journée.", serieA)))
        assertEquals(listOf("DAZN"), result[interComo])
    }

    @Test
    fun `a country named in a club match description is not a national team broadcast`() {
        val italyFrance = match(1, Competition.NATIONAL_TEAMS, italy, france)
        val result = BroadcastFinder.assign(
            listOf(italyFrance),
            listOf(
                dazn("Naples / Lazio Rome", "Football. Serie A 11e journée.", serieA),
                Broadcast("Sport 24", "Football", null, "Football", "Un match en direct d'Italie.", kickoff - 15 * minute, kickoff + 2 * 60 * minute),
                broadcast("TF1", "Italie / France", category = "Football"),
            ),
        )
        assertEquals(listOf("TF1"), result[italyFrance])
    }

    @Test
    fun `rome and turin alone do not name a club`() {
        val romaSassuolo = match(1, Competition.SERIE_A, roma, sassuolo)
        val torinoLecce = match(2, Competition.SERIE_A, torino, lecce)
        // La Lazio et la Juventus jouent aussi pendant la semaine du calendrier.
        val later = kickoff + 24 * 60 * minute
        val result = BroadcastFinder.assign(
            listOf(romaSassuolo, torinoLecce, match(3, Competition.SERIE_A, napoli, lazio, at = later), match(4, Competition.SERIE_A, juventus, atalanta, at = later)),
            listOf(
                Broadcast("Eleven Sports 1", "Lazio Rome / Sassuolo", null, "Football", serieA, kickoff - 15 * minute, kickoff + 2 * 60 * minute),
                Broadcast("Eleven Sports 2", "Juventus Turin / Lecce", null, "Football", serieA, kickoff - 15 * minute, kickoff + 2 * 60 * minute),
                broadcast("DAZN 2", "AS Rome / Sassuolo", "Serie A - 11e journée", "Sport, Football"),
            ),
        )
        assertEquals(listOf("DAZN 2"), result[romaSassuolo])
        assertEquals(null, result[torinoLecce])
        assertTrue("turin" !in BroadcastFinder.aliases(torino))
        assertTrue("juve" in BroadcastFinder.aliases(juventus))
    }

    @Test
    fun `other sports and magazines are ignored`() {
        val classico = match(1, Competition.LIGUE_1, psg, om)
        val result = BroadcastFinder.assign(
            listOf(classico),
            listOf(
                broadcast("beIN Sports 2", "Handball : Paris / Marseille"),
                broadcast("Canal+", "Canal Football Club", "Avant PSG / OM"),
            ),
        )
        assertEquals(emptyMap<ScheduledMatch, List<String>>(), result)
    }

    /** Titres du guide du fournisseur sur ses chaînes « LIVE MATCH » (images d'attente du 7 novembre 2026), noms d'ESPN. */
    @Test
    fun `provider titles of event channels find their matches`() {
        fun team(name: String, short: String = name) = Team(name, short, null, null)
        val fixtures = listOf(
            "Málaga vs. Racing de Santander" to (team("Málaga") to team("Racing Santander", "Racing")),
            "Espanyol vs. Deportivo de La Coruña" to (team("Espanyol") to team("Deportivo")),
            "Osasuna vs. Athletic Club" to (team("Osasuna") to team("Athletic Club", "Athletic")),
            "Valencia vs. Real Madrid" to (team("Valencia") to team("Real Madrid")),
            "Elche vs. Real Betis" to (team("Elche") to team("Real Betis", "Betis")),
        )
        val matches = fixtures.mapIndexed { index, (_, teams) ->
            match(index.toLong(), Competition.LA_LIGA, teams.first, teams.second, kickoff + index * 135 * minute)
        }
        val broadcasts = fixtures.mapIndexed { index, (title, _) ->
            val start = kickoff + index * 135 * minute
            broadcast("LALIGA ${index + 1}", title, category = null, start = start, stop = start + 120 * minute)
        }
        val found = BroadcastFinder.assign(matches, broadcasts)
        matches.forEachIndexed { index, match -> assertEquals(fixtures[index].first, listOf("LALIGA ${index + 1}"), found[match]) }
    }
}
