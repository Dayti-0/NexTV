package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EventChannelsTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")

    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance(paris).apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis

    private fun match(id: Long, home: String, away: String, kickoff: Long, competition: Competition = Competition.LA_LIGA, status: MatchStatus = MatchStatus.SCHEDULED) =
        ScheduledMatch(id, competition, kickoff, status, Team(home, null, null, null), Team(away, null, null, null))

    private fun channels(competition: Competition, count: Int, name: (Int) -> String) =
        (1..count).map { name(it) to checkNotNull(EventChannels.parse(name(it))) }.also { list ->
            list.forEach { assertEquals(competition, it.second.competition) }
        }

    private val laliga = channels(Competition.LA_LIGA, 5) { "FR| DAZN LALIGA $it (LIVE MATCH ONLY)" }

    @Test
    fun `event channels are recognised by their name`() {
        assertEquals(EventChannel(Competition.LA_LIGA, 2, "fr dazn"), EventChannels.parse("FR| DAZN LALIGA 2 (LIVE MATCH ONLY)"))
        assertEquals(EventChannel(Competition.LA_LIGA, 1, "fr dazn"), EventChannels.parse("FR| DAZN LALIGA 1 (LIVE MATCH) FHD"))
        // Numéro avant le championnat.
        assertEquals(EventChannel(Competition.SERIE_A, 1, "fr dazn"), EventChannels.parse("FR| DAZN 1 SERIE A (LIVE MATCH ONLY)"))
        assertEquals(EventChannel(Competition.SERIE_A, 3, "fr dazn"), EventChannels.parse("FR| DAZN SERIE A 3 (LIVE MATCH)"))
        assertEquals(EventChannel(Competition.LA_LIGA, 4, "es"), EventChannels.parse("ES| LALIGA 4 (LIVE MATCH)"))
        // Chaînes ordinaires, ou d'une autre compétition.
        assertNull(EventChannels.parse("FR| DAZN LALIGA TV"))
        assertNull(EventChannels.parse("FR| DAZN 1"))
        assertNull(EventChannels.parse("FR| DAZN 1 (LIVE MATCH)"))
        assertNull(EventChannels.parse("FR| DAZN 1 SERIE A"))
        assertNull(EventChannels.parse("FR| BEIN SPORTS 2"))
        assertNull(EventChannels.parse("ES| LALIGA HYPERMOTION 1 (LIVE MATCH)"))
        assertNull(EventChannels.parse("LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)"))
    }

    @Test
    fun `the next matches are on the channels in kickoff order, days after included`() {
        // Samedi 7 novembre 2026 au matin, d'après les images d'attente : LALIGA 1 à 4 pour les matchs du
        // samedi, LALIGA 5 pour le premier du dimanche. Celui de vendredi, terminé, n'a plus de chaîne.
        val alaves = match(1, "Sevilla", "Alavés", at(6, 21), status = MatchStatus.FINISHED)
        val malaga = match(2, "Málaga", "Racing Santander", at(7, 14))
        val espanyol = match(3, "Espanyol", "Deportivo", at(7, 16, 15))
        val osasuna = match(4, "Osasuna", "Athletic Club", at(7, 18, 30))
        val valencia = match(5, "Valencia", "Real Madrid", at(7, 21))
        val elche = match(6, "Elche", "Real Betis", at(8, 14))
        val sociedad = match(7, "Real Sociedad", "Rayo Vallecano", at(8, 16, 15))
        val cagliari = match(8, "Cagliari", "Frosinone", at(7, 15), Competition.SERIE_A)
        val result = EventChannels.assign(listOf(osasuna, sociedad, malaga, alaves, cagliari, espanyol, elche, valencia), laliga)
        assertEquals(listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)"), result[malaga])
        assertEquals(listOf("FR| DAZN LALIGA 2 (LIVE MATCH ONLY)"), result[espanyol])
        assertEquals(listOf("FR| DAZN LALIGA 3 (LIVE MATCH ONLY)"), result[osasuna])
        assertEquals(listOf("FR| DAZN LALIGA 4 (LIVE MATCH ONLY)"), result[valencia])
        assertEquals(listOf("FR| DAZN LALIGA 5 (LIVE MATCH ONLY)"), result[elche])
        // Pas de 6e chaîne ; pas de chaîne pour un match terminé ni pour une autre compétition.
        assertNull(result[sociedad])
        assertNull(result[alaves])
        assertNull(result[cagliari])
    }

    @Test
    fun `a match in progress keeps its rank, a finished one frees its channel`() {
        val malaga = match(2, "Málaga", "Racing Santander", at(7, 14), status = MatchStatus.LIVE)
        val espanyol = match(3, "Espanyol", "Deportivo", at(7, 16, 15))
        assertEquals(listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)"), EventChannels.assign(listOf(malaga, espanyol), laliga)[malaga])
        val over = malaga.copy(status = MatchStatus.FINISHED)
        val result = EventChannels.assign(listOf(over, espanyol), laliga)
        assertNull(result[over])
        assertEquals(listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)"), result[espanyol])
    }

    @Test
    fun `matches at the same time share their channels`() {
        // Serie A, samedi : deux matchs à 15:00 sur DAZN 1 et 2 SERIE A (dans un ordre inconnu), Parme à 18:00 sur la 3.
        val serieA = channels(Competition.SERIE_A, 3) { "FR| DAZN $it SERIE A (LIVE MATCH ONLY)" }
        val venezia = match(10, "Venezia", "Udinese", at(6, 20, 45), Competition.SERIE_A, MatchStatus.FINISHED)
        val cagliari = match(11, "Cagliari", "Frosinone", at(7, 15), Competition.SERIE_A)
        val torino = match(12, "Torino", "Lecce", at(7, 15), Competition.SERIE_A)
        val parma = match(13, "Parma", "Bologna", at(7, 18), Competition.SERIE_A)
        val roma = match(14, "AS Roma", "Sassuolo", at(7, 20, 45), Competition.SERIE_A)
        val result = EventChannels.assign(listOf(venezia, cagliari, torino, parma, roma), serieA)
        assertEquals(listOf("FR| DAZN 1 SERIE A (LIVE MATCH ONLY)", "FR| DAZN 2 SERIE A (LIVE MATCH ONLY)"), result[cagliari])
        assertEquals(listOf("FR| DAZN 2 SERIE A (LIVE MATCH ONLY)", "FR| DAZN 1 SERIE A (LIVE MATCH ONLY)"), result[torino])
        assertEquals(listOf("FR| DAZN 3 SERIE A (LIVE MATCH ONLY)"), result[parma])
        assertNull(result[roma])
    }

    @Test
    fun `many matches at once are not guessed`() {
        val tied = (1..4).map { match(20L + it, "A$it", "B$it", at(7, 15, 30), Competition.SERIE_A) }
        val later = match(30, "C", "D", at(7, 18, 30), Competition.SERIE_A)
        val serieA = channels(Competition.SERIE_A, 5) { "FR| DAZN SERIE A $it (LIVE MATCH)" }
        val result = EventChannels.assign(tied + later, serieA)
        tied.forEach { assertNull(result[it]) }
        // Le suivant garde son rang.
        assertEquals(listOf("FR| DAZN SERIE A 5 (LIVE MATCH)"), result[later])
    }

    @Test
    fun `only the numbering of the best family is used`() {
        // Chaînes françaises d'abord : celles d'un autre pays ont leur propre numérotation.
        val spanish = channels(Competition.LA_LIGA, 6) { "ES| LALIGA $it (LIVE MATCH)" }
        val matches = (1..6).map { match(40L + it, "A$it", "B$it", at(7, 10 + 2 * it)) }
        val result = EventChannels.assign(matches, laliga + spanish)
        assertEquals(listOf("FR| DAZN LALIGA 5 (LIVE MATCH ONLY)"), result[matches[4]])
        assertNull(result[matches[5]])
    }
}
