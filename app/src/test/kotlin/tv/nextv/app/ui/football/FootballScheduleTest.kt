package tv.nextv.app.ui.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.core.football.Competition
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Team

class FootballScheduleTest {

    private val hour = 60 * 60_000L
    private val thursdayEvening = 1_793_908_800_000L // 2026-11-05T20:00Z
    private val fridayNoon = 1_793_963_880_000L // 2026-11-06T11:18Z
    private val now = fridayNoon + 2 * hour

    private val event = EpgChannelRow("canalplusevent.fr", 1, "FR| CANAL+ ÉVÉNEMENT", "FR| CANAL+ ÉVÉNEMENT", null)
    private val foot = EpgChannelRow("canalplusfoot.fr", 2, "FR| CANAL+ FOOT", "FR| CANAL+ FOOT", null)
    private val voo = EpgChannelRow("voo.be", 3, "BE| VOO WORLD 2", "BE| VOO WORLD 2", null)

    private fun row(channel: EpgChannelRow, title: String, start: Long, minutes: Int) = BroadcastRow(
        channel,
        null,
        FootballProgrammeRow(channel.epgChannelId!!, title, null, null, "Football", null, start, start + minutes * 60_000L),
    )

    private fun match(id: Long, competition: Competition, home: String, away: String, kickoff: Long, status: MatchStatus = MatchStatus.SCHEDULED) =
        ScheduledMatch(id, competition, kickoff, status, Team(home, null, null, "https://crest/$home.png"), Team(away, null, null, null))

    // Calendrier d'ESPN : les matchs de jeudi soir sont terminés, celui de ce soir est à venir.
    private val schedule = listOf(
        match(1, Competition.EUROPA_LEAGUE, "Bayer Leverkusen", "Marseille", thursdayEvening, MatchStatus.FINISHED),
        match(2, Competition.CONFERENCE_LEAGUE, "AS Monaco", "FC Nordsjælland", thursdayEvening, MatchStatus.FINISHED),
        match(3, Competition.BUNDESLIGA, "Hamburg SV", "Gladbach", fridayNoon + 8 * hour + 12 * 60_000L),
        match(4, Competition.NATIONAL_TEAMS, "Cambodia", "Laos", fridayNoon + 3 * hour),
        match(5, Competition.NATIONAL_TEAMS, "France", "Ukraine", fridayNoon + 9 * hour),
    )

    // Guide : rediffusions du vendredi après-midi, direct de ce soir.
    private val rows = listOf(
        row(event, "Ligue Europa : Rennes - Olympiakos", fridayNoon + 45 * 60_000L, 105),
        row(voo, "Ligue Europa : Bayer Leverkusen - Marseille", fridayNoon + 52 * 60_000L, 105),
        row(event, "Ligue Conférence : Monaco - FC Nordsjælland", fridayNoon + 4 * hour + 29 * 60_000L, 105),
        row(foot, "Bundesliga : HSV - M'gladbach", fridayNoon + 8 * hour, 135),
        row(foot, "Football - Match amical : France / Ukraine", fridayNoon + 9 * hour - 15 * 60_000L, 135),
    )

    @Test
    fun `only scheduled matches are listed, never replays from the guide`() {
        val matches = scheduledMatches(schedule, rows, now)
        assertEquals(listOf("Hamburg SV – Gladbach", "France – Ukraine"), matches.map { it.label })
        val hamburg = matches.first()
        assertEquals("FR| CANAL+ FOOT", hamburg.channel?.name)
        assertEquals("https://crest/Hamburg SV.png", hamburg.homeCrest)
    }

    @Test
    fun `live match stays listed with its score`() {
        val live = match(6, Competition.LIGUE_1, "Lens", "Lille", now - 2 * hour - 20 * 60_000L, MatchStatus.LIVE)
            .copy(homeScore = 1, awayScore = 1, clock = "120+2'")
        val shown = scheduledMatches(listOf(live), emptyList(), now).single()
        assertEquals("1 – 1", shown.score)
        assertEquals("120+2'", shown.clock)
        assertTrue(shown.endMillis > now)
    }
}
