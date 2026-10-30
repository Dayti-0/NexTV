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
}
