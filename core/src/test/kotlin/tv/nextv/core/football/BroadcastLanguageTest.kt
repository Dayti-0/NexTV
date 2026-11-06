package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `ligue 1 plus is the official ligue 1 channel`() {
        assertTrue(BroadcastLanguage.isOfficial(Competition.LIGUE_1, "LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.LIGUE_1, "FR| LIGUE 1+ LA CHAINE"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.LIGUE_1, "Ligue1+ 2"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.LIGUE_1, "FR| DAZN PPV 1"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.LIGUE_1, "FR| CANAL+ SPORT 1"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.PREMIER_LEAGUE, "LIGUE 1+| MATCH 3"))
    }

    @Test
    fun `each competition has its french broadcaster`() {
        assertTrue(BroadcastLanguage.isOfficial(Competition.BUNDESLIGA, "FR| BEIN SPORTS 3"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.LA_LIGA, "FR: beIN SPORTS MAX 4"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.PREMIER_LEAGUE, "FR| CANAL+ FOOT"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.CHAMPIONS_LEAGUE, "FR| CANAL+ LIVE 4"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.BUNDESLIGA, "FR| CANAL+ SPORT 1"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.CHAMPIONS_LEAGUE, "FR| BEIN SPORTS 1"))
    }

    /** Guide d'un autre pays sur une chaîne française : « Canal+ Sport 1 » annoncé avec la Bundesliga, qui est sur beIN Sports. */
    @Test
    fun `french channels outside the broadcaster are dropped when the broadcaster is there`() {
        fun likely(competition: Competition, vararg names: String) = BroadcastLanguage.likelyBroadcasters(competition, names.toList()) { it }
        assertEquals(
            listOf("FR| BEIN SPORTS 3", "DE| SKY BUNDESLIGA 1"),
            likely(Competition.BUNDESLIGA, "FR| CANAL+ SPORT 1", "FR| BEIN SPORTS 3", "DE| SKY BUNDESLIGA 1"),
        )
        // Sans le diffuseur, ou seulement celui d'un autre pays : rien n'est écarté.
        assertEquals(listOf("FR| CANAL+ SPORT 1", "DE| SKY 1"), likely(Competition.BUNDESLIGA, "FR| CANAL+ SPORT 1", "DE| SKY 1"))
        assertEquals(listOf("FR| CANAL+ SPORT 1", "AR| BEIN SPORTS 1"), likely(Competition.BUNDESLIGA, "FR| CANAL+ SPORT 1", "AR| BEIN SPORTS 1"))
        // Coupes d'Europe : Canal+.
        assertEquals(listOf("FR| CANAL+ FOOT"), likely(Competition.CHAMPIONS_LEAGUE, "FR| CANAL+ FOOT", "FR| RMC SPORT 1"))
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

    @Test
    fun `national team match keeps its own broadcasts and names countries in french`() {
        val kickoff = 1_794_512_700_000L // jeudi 12 novembre 2026, 20:45 à Paris
        val match = ScheduledMatch(
            id = 2,
            competition = Competition.NATIONAL_TEAMS,
            kickoffMillis = kickoff,
            status = MatchStatus.SCHEDULED,
            home = Team("Italy", null, "ITA", null),
            away = Team("France", null, "FRA", null),
        )
        val broadcasts = listOf(
            Broadcast("tf1", "Football - Ligue des nations", "Italie / France", "Football", null, kickoff - 30 * 60_000L, kickoff + 2 * 3_600_000L),
            Broadcast("rts", "Football: Italie - France", null, "Sport", null, kickoff - 15 * 60_000L, kickoff + 2 * 3_600_000L),
            Broadcast(
                "ligue1",
                "Ligue 1 McDonald's",
                null,
                "Football",
                "1ère période du match de la Journée 5 du Championnat de France de Ligue 1 McDonald's opposant AS Monaco et RC Lens",
                kickoff,
                kickoff + 60 * 60_000L,
            ),
        )
        assertEquals(listOf("tf1", "rts"), BroadcastFinder.assign(listOf(match), broadcasts)[match])
        assertEquals("Italie", CountryNames.french("Italy"))
        assertEquals("France", CountryNames.french("France"))
        assertEquals("Italie", CountryNames.french("Italie"))
        assertEquals(null, CountryNames.french("Lyon"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.NATIONAL_TEAMS, "FR| TF1"))
        assertTrue(BroadcastLanguage.isOfficial(Competition.NATIONAL_TEAMS, "FR| M6 4K"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.NATIONAL_TEAMS, "FR| LIGUE 1+ LA CHAINE"))
        assertFalse(BroadcastLanguage.isOfficial(Competition.NATIONAL_TEAMS, "CH| RTS Deux"))
    }
}
