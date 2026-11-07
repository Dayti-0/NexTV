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

    /** Minuit à Paris. */
    private fun midnight(day: Int) = at(day, 0)

    @Test
    fun `the matches of the day are numbered in kickoff order, the next day's after them`() {
        // Samedi 7 novembre 2026 au matin, d'après les images d'attente : LALIGA 1 à 4 pour les matchs du
        // samedi, LALIGA 5 pour le premier du dimanche. Celui de vendredi n'est plus compté.
        val alaves = match(1, "Sevilla", "Alavés", at(6, 21), status = MatchStatus.FINISHED)
        val malaga = match(2, "Málaga", "Racing Santander", at(7, 14))
        val espanyol = match(3, "Espanyol", "Deportivo", at(7, 16, 15))
        val osasuna = match(4, "Osasuna", "Athletic Club", at(7, 18, 30))
        val valencia = match(5, "Valencia", "Real Madrid", at(7, 21))
        val elche = match(6, "Elche", "Real Betis", at(8, 14))
        val sociedad = match(7, "Real Sociedad", "Rayo Vallecano", at(8, 16, 15))
        val cagliari = match(8, "Cagliari", "Frosinone", at(7, 15), Competition.SERIE_A)
        val matches = listOf(osasuna, sociedad, malaga, alaves, cagliari, espanyol, elche, valencia)
        val result = EventChannels.assign(matches, laliga, midnight(7))
        assertEquals(listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)"), result[malaga])
        assertEquals(listOf("FR| DAZN LALIGA 2 (LIVE MATCH ONLY)"), result[espanyol])
        assertEquals(listOf("FR| DAZN LALIGA 3 (LIVE MATCH ONLY)"), result[osasuna])
        assertEquals(listOf("FR| DAZN LALIGA 4 (LIVE MATCH ONLY)"), result[valencia])
        assertEquals(listOf("FR| DAZN LALIGA 5 (LIVE MATCH ONLY)"), result[elche])
        // Pas de 6e chaîne ; pas de chaîne pour le match de la veille ni pour une autre compétition.
        assertNull(result[sociedad])
        assertNull(result[alaves])
        assertNull(result[cagliari])
    }

    @Test
    fun `a match keeps its channel when the one before is over`() {
        // Samedi après-midi : Málaga – Racing terminé, Espanyol – Deportivo toujours sur LALIGA 2.
        val malaga = match(2, "Málaga", "Racing Santander", at(7, 14), status = MatchStatus.FINISHED)
        val espanyol = match(3, "Espanyol", "Deportivo", at(7, 16, 15), status = MatchStatus.LIVE)
        val result = EventChannels.assign(listOf(malaga, espanyol), laliga, midnight(7))
        assertEquals(listOf("FR| DAZN LALIGA 2 (LIVE MATCH ONLY)"), result[espanyol])
    }

    @Test
    fun `the numbering starts again with the next day`() {
        // Dimanche : Elche – Betis premier du jour ; deux matchs à 18:30 (rangs 3 et 4).
        val day = listOf(
            match(11, "Elche", "Real Betis", at(8, 14)),
            match(12, "Real Sociedad", "Rayo Vallecano", at(8, 16, 15)),
            match(13, "Celta Vigo", "Levante", at(8, 18, 30)),
            match(14, "Villarreal", "Getafe", at(8, 18, 30)),
            match(15, "Atlético Madrid", "Barcelona", at(8, 21)),
        )
        val result = EventChannels.assign(day, laliga, midnight(8))
        assertEquals(listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)"), result[day[0]])
        assertEquals(listOf("FR| DAZN LALIGA 3 (LIVE MATCH ONLY)", "FR| DAZN LALIGA 4 (LIVE MATCH ONLY)"), result[day[2]])
        assertEquals(listOf("FR| DAZN LALIGA 4 (LIVE MATCH ONLY)", "FR| DAZN LALIGA 3 (LIVE MATCH ONLY)"), result[day[3]])
        assertEquals(listOf("FR| DAZN LALIGA 5 (LIVE MATCH ONLY)"), result[day[4]])
    }

    @Test
    fun `matches at the same time share their channels`() {
        // Serie A, samedi : deux matchs à 15:00 sur DAZN 1 et 2 SERIE A (dans un ordre inconnu), Parme à 18:00 sur la 3.
        val serieA = channels(Competition.SERIE_A, 3) { "FR| DAZN $it SERIE A (LIVE MATCH ONLY)" }
        val cagliari = match(11, "Cagliari", "Frosinone", at(7, 15), Competition.SERIE_A)
        val torino = match(12, "Torino", "Lecce", at(7, 15), Competition.SERIE_A)
        val parma = match(13, "Parma", "Bologna", at(7, 18), Competition.SERIE_A)
        val roma = match(14, "AS Roma", "Sassuolo", at(7, 20, 45), Competition.SERIE_A)
        val result = EventChannels.assign(listOf(cagliari, torino, parma, roma), serieA, midnight(7))
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
        val result = EventChannels.assign(tied + later, serieA, midnight(7))
        tied.forEach { assertNull(result[it]) }
        // Le suivant garde son rang.
        assertEquals(listOf("FR| DAZN SERIE A 5 (LIVE MATCH)"), result[later])
    }

    @Test
    fun `only the numbering of the best family is used`() {
        // Chaînes françaises d'abord : celles d'un autre pays ont leur propre numérotation.
        val spanish = channels(Competition.LA_LIGA, 6) { "ES| LALIGA $it (LIVE MATCH)" }
        val matches = (1..6).map { match(40L + it, "A$it", "B$it", at(7, 10 + 2 * it)) }
        val result = EventChannels.assign(matches, laliga + spanish, midnight(7))
        assertEquals(listOf("FR| DAZN LALIGA 5 (LIVE MATCH ONLY)"), result[matches[4]])
        assertNull(result[matches[5]])
        // Une seule chaîne par numéro, de la meilleure famille.
        val best = EventChannels.primaries(laliga + listOf("FR| DAZN LALIGA 1 (LIVE MATCH ONLY) HD" to laliga[0].second) + spanish)
        assertEquals(laliga.map { it.first }, best.map { it.first })
    }

    @Test
    fun `a known channel gives the turn of the others`() {
        // Espanyol – Deportivo annoncé sur LALIGA 2 : les matchs suivants prennent les chaînes à tour de rôle,
        // Elche – Betis reste sur la 5, Real Sociedad – Rayo revient sur la 1 (la 1 est libre depuis samedi).
        val malaga = match(2, "Málaga", "Racing Santander", at(7, 14), status = MatchStatus.FINISHED)
        val espanyol = match(3, "Espanyol", "Deportivo", at(7, 16, 15), status = MatchStatus.LIVE)
        val osasuna = match(4, "Osasuna", "Athletic Club", at(7, 18, 30))
        val valencia = match(5, "Valencia", "Real Madrid", at(7, 21))
        val elche = match(6, "Elche", "Real Betis", at(8, 14))
        val sociedad = match(7, "Real Sociedad", "Rayo Vallecano", at(8, 16, 15))
        val celta = match(8, "Celta Vigo", "Levante", at(8, 18, 30))
        val villarreal = match(9, "Villarreal", "Getafe", at(8, 18, 30))
        val atletico = match(10, "Atlético Madrid", "Barcelona", at(8, 21))
        val matches = listOf(malaga, espanyol, osasuna, valencia, elche, sociedad, celta, villarreal, atletico)
        // Dimanche : le match de samedi encore dans le calendrier sert de référence.
        val result = EventChannels.assign(matches, laliga, midnight(8), anchors = mapOf(espanyol to 2))
        fun channel(n: Int) = "FR| DAZN LALIGA $n (LIVE MATCH ONLY)"
        assertEquals(listOf(channel(1)), result[malaga])
        assertEquals(listOf(channel(3)), result[osasuna])
        assertEquals(listOf(channel(5)), result[elche])
        assertEquals(listOf(channel(1)), result[sociedad])
        assertEquals(listOf(channel(2), channel(3)), result[celta])
        assertEquals(listOf(channel(3), channel(2)), result[villarreal])
        assertEquals(listOf(channel(4)), result[atletico])
    }
}
