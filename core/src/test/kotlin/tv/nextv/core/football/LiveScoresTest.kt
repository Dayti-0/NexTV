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

    private val europa = JSONObject(
        """{"events":[
        ${event("2026-11-05T20:00Z", "in", "STATUS_SECOND_HALF", "67'", "67'", "Bayer Leverkusen", "0", "Marseille", "1", "LEV", "MAR")},
        ${event("2026-11-05T20:00Z", "in", "STATUS_SECOND_HALF", "67'", "67'", "Hapoel Be'er", "0", "OFI Crete", "2")},
        ${event("2026-11-05T20:00Z", "in", "STATUS_SECOND_HALF", "67'", "67'", "Torreense", "3", "Ararat-Armenia", "2")},
        ${event("2026-11-05T17:45Z", "post", "STATUS_FULL_TIME", "FT", "90'+5'", "AC Milan", "0", "Ferencvaros", "2")}
        ]}""",
    )

    private val evening = 1_793_908_800_000L - 45 * 60_000L // émission de 20:15 (heure de Paris), coup d'envoi 21:00

    @Test
    fun `guide match gets the live score although the broadcast starts before kickoff`() {
        val scores = LiveScoreClient.parse(europa, Competition.EUROPA_LEAGUE)
        val live = LiveScoreClient.match(scores, "Bayer Leverkusen", "Marseille", evening, evening + 3 * 60 * 60_000L)!!
        assertEquals(0, live.homeScore)
        assertEquals(1, live.awayScore)
        assertEquals("67'", live.clock)
    }

    @Test
    fun `guide names that differ from the score source are still recognised`() {
        val scores = LiveScoreClient.parse(europa, Competition.EUROPA_LEAGUE)
        val start = evening + 40 * 60_000L
        assertEquals(2, LiveScoreClient.match(scores, "Hapoel Beer-Sheva", "OFI Crète", start, start + 2 * 60 * 60_000L)!!.awayScore)
        assertEquals(3, LiveScoreClient.match(scores, "União Torreense", "FC Ararat-Armenia", start, start + 2 * 60 * 60_000L)!!.homeScore)
    }

    @Test
    fun `reversed guide match keeps the guide order`() {
        val scores = LiveScoreClient.parse(europa, Competition.EUROPA_LEAGUE)
        val live = LiveScoreClient.match(scores, "Marseille", "Leverkusen", evening, evening + 3 * 60 * 60_000L)!!
        assertEquals(1, live.homeScore)
        assertEquals(0, live.awayScore)
    }

    @Test
    fun `guide match outside the broadcast or with other teams is not given a score`() {
        val scores = LiveScoreClient.parse(europa, Competition.EUROPA_LEAGUE)
        assertNull(LiveScoreClient.match(scores, "Bayer Leverkusen", "Marseille", evening + 3 * 60 * 60_000L, evening + 5 * 60 * 60_000L))
        assertNull(LiveScoreClient.match(scores, "Lyon", "Real Sociedad", evening, evening + 3 * 60 * 60_000L))
        // Un seul mot commun et répandu : aucun score plutôt qu'un faux.
        assertNull(LiveScoreClient.match(scores, "Hapoel Tel-Aviv", "Real Betis", evening, evening + 3 * 60 * 60_000L))
    }

    private fun scheduled(id: Int, date: String, state: String, name: String, clock: String, home: String, homeScore: String, away: String, awayScore: String, homeCode: String = "") = """
        {"id":"$id","date":"$date","status":{"displayClock":"$clock","type":{"name":"$name","state":"$state"}},
         "competitions":[{"competitors":[
           {"homeAway":"home","score":"$homeScore","team":{"id":"1$id","displayName":"$home","shortDisplayName":"$home","abbreviation":"$homeCode","logo":"https://a.espncdn.com/i/teamlogos/soccer/500/1$id.png"}},
           {"homeAway":"away","score":"$awayScore","team":{"id":"2$id","displayName":"$away","logo":"https://a.espncdn.com/i/teamlogos/soccer/500/2$id.png"}}]}]}
    """.trimIndent()

    @Test
    fun `schedule gives every match with its state, score and crests`() {
        val json = JSONObject(
            """{"events":[
            ${scheduled(1, "2026-11-05T20:00Z", "post", "STATUS_FULL_TIME", "90'+4'", "Bayer Leverkusen", "0", "Marseille", "1")},
            ${scheduled(2, "2026-11-05T17:45Z", "in", "STATUS_SECOND_HALF", "67'", "Crystal Palace", "2", "TSG Hoffenheim", "1")},
            ${scheduled(3, "2026-11-06T19:30Z", "pre", "STATUS_SCHEDULED", "0'", "Hamburg SV", "0", "Borussia Mönchengladbach", "0", "HSV")},
            ${scheduled(4, "2026-11-06T20:00Z", "post", "STATUS_POSTPONED", "0'", "Celtic", "0", "Besiktas", "0")}
            ]}""",
        )
        val matches = LiveScoreClient.parseSchedule(json, Competition.EUROPA_LEAGUE)
        assertEquals(4, matches.size)
        val (leverkusen, palace, hamburg, celtic) = matches
        assertEquals(MatchStatus.FINISHED, leverkusen.status)
        assertEquals(0, leverkusen.homeScore)
        assertEquals(1, leverkusen.awayScore)
        assertEquals("Bayer Leverkusen", leverkusen.home.name)
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/21.png", leverkusen.away.crestUrl)
        assertEquals(MatchStatus.LIVE, palace.status)
        assertEquals("67'", palace.clock)
        assertEquals(MatchStatus.SCHEDULED, hamburg.status)
        assertEquals(1_793_993_400_000L, hamburg.kickoffMillis)
        assertNull(hamburg.homeScore)
        assertEquals("HSV", hamburg.home.code)
        assertEquals(MatchStatus.POSTPONED, celtic.status)
    }

    @Test
    fun `calendar lists the match days`() {
        val json = JSONObject("""{"type":"ondays","eventDate":{"type":"ondays","dates":["2026-11-06T08:00Z","2026-11-07T08:00Z","2026-11-07T08:00Z","bad"]}}""")
        assertEquals(listOf("20261106", "20261107"), LiveScoreClient.parseCalendar(json))
        assertNull(LiveScoreClient.parseCalendar(JSONObject("{}")))
    }
}
