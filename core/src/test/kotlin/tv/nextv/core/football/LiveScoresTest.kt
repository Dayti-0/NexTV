package tv.nextv.core.football

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScoresTest {

    private fun event(date: String, state: String, name: String, shortDetail: String, clock: String, home: String, homeScore: String, away: String, awayScore: String, homeCode: String = "", awayCode: String = "") = """
        {"date":"$date","status":{"displayClock":"$clock","period":2,"type":{"name":"$name","state":"$state","shortDetail":"$shortDetail"}},
         "competitions":[{"competitors":[
           {"homeAway":"home","score":"$homeScore","team":{"displayName":"$home","shortDisplayName":"$home","abbreviation":"$homeCode"}},
           {"homeAway":"away","score":"$awayScore","team":{"displayName":"$away","shortDisplayName":"$away","abbreviation":"$awayCode"}}]}]}
    """.trimIndent()

    private val board = JSONObject(
        """{"events":[
        ${event("2026-10-31T20:00Z", "in", "STATUS_SECOND_HALF", "83'", "83'", "Barcelona", "6", "Alavés", "0", "BAR", "ALA")},
        ${event("2026-10-31T17:30Z", "in", "STATUS_HALFTIME", "HT", "45'+2'", "Real Betis", "1", "Málaga", "1")},
        ${event("2026-10-31T15:15Z", "post", "STATUS_FULL_TIME", "FT", "90'+5'", "Rayo Vallecano", "3", "Celta Vigo", "0")},
        ${event("2026-10-31T15:15Z", "in", "STATUS_FIRST_HALF", "45'+2'", "45'+2'", "Girona", "0", "Villarreal", "2")}
        ]}""",
    )

    private val kickoff = 1_793_476_800_000L // 2026-10-31T20:00Z

    private fun team(name: String, short: String?, code: String?) = Team(name, short, code, null)

    @Test
    fun `reads score, minute and half-time`() {
        val scores = LiveScoreClient.parse(board, Competition.LA_LIGA)
        assertEquals(4, scores.size)
        val barca = scores[0]
        assertEquals(kickoff, barca.kickoffMillis)
        assertEquals(6, barca.homeScore)
        assertEquals(0, barca.awayScore)
        assertEquals("83'", barca.clock)
        assertTrue(barca.inPlay)
        assertFalse(barca.halfTime)
        assertTrue(scores[1].halfTime)
        assertNull(scores[1].clock)
        assertTrue(scores[2].finished)
        assertEquals("45+2'", scores[3].clock)
    }

    @Test
    fun `delayed football-data score is replaced by the live one`() {
        val scores = LiveScoreClient.parse(board, Competition.LA_LIGA)
        val delayed = ScheduledMatch(
            1, Competition.LA_LIGA, kickoff, MatchStatus.LIVE,
            team("FC Barcelona", "Barça", "FCB"), team("Deportivo Alavés", "Alavés", "ALA"), homeScore = 4, awayScore = 0,
        )
        val updated = LiveScoreClient.overlay(listOf(delayed), scores).single()
        assertEquals(6, updated.homeScore)
        assertEquals("83'", updated.clock)
        assertEquals(MatchStatus.LIVE, updated.status)
    }

    @Test
    fun `matches at the same time are told apart by their teams`() {
        val scores = LiveScoreClient.parse(board, Competition.LA_LIGA)
        val start = kickoff - (4 * 60 + 45) * 60_000L // 15:15Z
        val rayo = ScheduledMatch(2, Competition.LA_LIGA, start, MatchStatus.LIVE, team("Rayo Vallecano de Madrid", "Rayo Vallecano", "RAY"), team("RC Celta de Vigo", "Celta", "CEL"), 2, 0)
        val girona = ScheduledMatch(3, Competition.LA_LIGA, start, MatchStatus.SCHEDULED, team("Girona FC", "Girona", "GIR"), team("Villarreal CF", "Villarreal", "VIL"))
        val (finished, inPlay) = LiveScoreClient.overlay(listOf(rayo, girona), scores)
        assertEquals(MatchStatus.FINISHED, finished.status)
        assertEquals(3, finished.homeScore)
        assertEquals(MatchStatus.LIVE, inPlay.status)
        assertEquals(2, inPlay.awayScore)
        assertEquals("45+2'", inPlay.clock)
    }

    @Test
    fun `unknown matches are left as they are`() {
        val scores = LiveScoreClient.parse(board, Competition.LA_LIGA)
        val other = ScheduledMatch(4, Competition.LIGUE_1, kickoff, MatchStatus.LIVE, team("Paris Saint-Germain FC", "PSG", "PSG"), team("Olympique de Marseille", "Marseille", "MAR"), 1, 0)
        assertEquals(other, LiveScoreClient.overlay(listOf(other), scores).single())
    }
}
