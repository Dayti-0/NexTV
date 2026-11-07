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

    private fun dayOf(millis: Long) = Calendar.getInstance(paris).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun match(id: Long, home: String, away: String, kickoff: Long, competition: Competition = Competition.LA_LIGA, status: MatchStatus = MatchStatus.SCHEDULED) =
        ScheduledMatch(id, competition, kickoff, status, Team(home, null, null, null), Team(away, null, null, null))

    @Test
    fun `event channels are recognised by their name`() {
        assertEquals(EventChannel(Competition.LA_LIGA, 2), EventChannels.parse("FR| DAZN LALIGA 2 (LIVE MATCH)"))
        assertEquals(EventChannel(Competition.LA_LIGA, 1), EventChannels.parse("FR| DAZN LALIGA 1 (LIVE MATCH) FHD"))
        assertEquals(EventChannel(Competition.SERIE_A, 3), EventChannels.parse("FR| DAZN SERIE A 3 (LIVE MATCH)"))
        // Chaînes ordinaires, ou d'une autre compétition.
        assertNull(EventChannels.parse("FR| DAZN LALIGA TV"))
        assertNull(EventChannels.parse("FR| DAZN 1"))
        assertNull(EventChannels.parse("FR| BEIN SPORTS 2"))
        assertNull(EventChannels.parse("ES| LALIGA HYPERMOTION 1 (LIVE MATCH)"))
        assertNull(EventChannels.parse("LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)"))
    }

    @Test
    fun `the nth liga match of the day is on the nth channel`() {
        // Samedi 7 novembre 2026 : les images d'attente de LALIGA 1, 2 et 4 montrent leurs matchs.
        val malaga = match(1, "Málaga", "Racing Santander", at(7, 14))
        val espanyol = match(2, "Espanyol", "Deportivo", at(7, 16, 15))
        val osasuna = match(3, "Osasuna", "Athletic Club", at(7, 18, 30))
        val valencia = match(4, "Valencia", "Real Madrid", at(7, 21))
        val cagliari = match(5, "Cagliari", "Frosinone", at(7, 15), Competition.SERIE_A)
        // Le lendemain, la numérotation repart de 1.
        val elche = match(6, "Elche", "Real Betis", at(8, 14))
        val channels = (1..4).map { "LALIGA $it" to EventChannel(Competition.LA_LIGA, it) }
        val result = EventChannels.assign(listOf(osasuna, malaga, cagliari, espanyol, valencia, elche), channels, ::dayOf)
        assertEquals(listOf("LALIGA 1"), result[malaga])
        assertEquals(listOf("LALIGA 2"), result[espanyol])
        assertEquals(listOf("LALIGA 3"), result[osasuna])
        assertEquals(listOf("LALIGA 4"), result[valencia])
        assertEquals(listOf("LALIGA 1"), result[elche])
        assertNull(result[cagliari])
    }

    @Test
    fun `matches at the same time share their channels`() {
        // Dimanche 8 novembre : deux matchs à 18:30, rangs 3 et 4.
        val day = listOf(
            match(11, "Elche", "Real Betis", at(8, 14)),
            match(12, "Real Sociedad", "Rayo Vallecano", at(8, 16, 15)),
            match(13, "Celta Vigo", "Levante", at(8, 18, 30)),
            match(14, "Villarreal", "Getafe", at(8, 18, 30)),
            match(15, "Atlético Madrid", "Barcelona", at(8, 21)),
        )
        val channels = (1..5).map { "LALIGA $it" to EventChannel(Competition.LA_LIGA, it) }
        val result = EventChannels.assign(day, channels, ::dayOf)
        assertEquals(listOf("LALIGA 3", "LALIGA 4"), result[day[2]])
        assertEquals(listOf("LALIGA 4", "LALIGA 3"), result[day[3]])
        assertEquals(listOf("LALIGA 5"), result[day[4]])
    }

    @Test
    fun `many matches at once are not guessed`() {
        val tied = (1..4).map { match(20L + it, "A$it", "B$it", at(7, 15, 30), Competition.SERIE_A) }
        val channels = (1..4).map { "SERIE A $it" to EventChannel(Competition.SERIE_A, it) }
        assertEquals(emptyMap<ScheduledMatch, List<String>>(), EventChannels.assign(tied, channels, ::dayOf))
    }
}
