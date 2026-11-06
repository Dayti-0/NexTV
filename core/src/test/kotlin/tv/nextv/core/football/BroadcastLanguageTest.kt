package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcastLanguageTest {

    @Test
    fun `french channels come first`() {
        assertEquals(0, BroadcastLanguage.rank("FR| CANAL+ LIVE 10"))
        assertEquals(0, BroadcastLanguage.rank("FR: beIN SPORTS 1 HD"))
        assertEquals(0, BroadcastLanguage.rank("[FR] RMC Sport 1"))
        assertEquals(0, BroadcastLanguage.rank("Canal+ Foot"))
        assertEquals(0, BroadcastLanguage.rank("L'Équipe"))
    }

    @Test
    fun `foreign channels come last`() {
        assertEquals(2, BroadcastLanguage.rank("DE| RTL HD+"))
        assertEquals(2, BroadcastLanguage.rank("UK: Sky Sports Main Event"))
        assertEquals(2, BroadcastLanguage.rank("DE| Eurosport 1"))
        assertEquals(1, BroadcastLanguage.rank("Sky Sport Bundesliga"))
    }

    @Test
    fun `scheduled match is found on every channel but not on its replay`() {
        val kickoff = 1_793_908_800_000L
        val match = ScheduledMatch(
            id = 1,
            competition = Competition.EUROPA_LEAGUE,
            kickoffMillis = kickoff,
            status = MatchStatus.SCHEDULED,
            home = Team("Bayer Leverkusen", "Leverkusen", "B04", null),
            away = Team("Marseille", null, "OLM", null),
        )
        val broadcasts = listOf(
            Broadcast("rtl", "Fußball: UEFA Europa League", "Bayer 04 Leverkusen - Olympique Marseille", "Sport", null, kickoff - 45 * 60_000L, kickoff + 2 * 3_600_000L),
            Broadcast("canal", "Bayer Leverkusen / Marseille", null, "Football", null, kickoff, kickoff + 2 * 3_600_000L),
            Broadcast("other", "Celtic / Besiktas", null, "Football", null, kickoff, kickoff + 2 * 3_600_000L),
            Broadcast("replay", "Ligue Europa : Bayer Leverkusen - Marseille", null, "Football", null, kickoff + 17 * 3_600_000L, kickoff + 19 * 3_600_000L),
        )
        assertEquals(listOf("rtl", "canal"), BroadcastFinder.assign(listOf(match), broadcasts)[match])
    }
}
