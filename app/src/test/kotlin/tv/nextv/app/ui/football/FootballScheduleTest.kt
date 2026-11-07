package tv.nextv.app.ui.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.EventChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.core.football.Competition
import tv.nextv.core.football.EventChannels
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.football.Team
import tv.nextv.core.source.XtreamProgramme

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

    @Test
    fun `ligue 1 plus comes first for a ligue 1 match`() {
        val dazn = EpgChannelRow("dazn1.fr", 4, "FR| DAZN PPV 1", "FR| DAZN PPV 1", null)
        val ligue1 = EpgChannelRow("ligue1plus3.fr", 5, "LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)", "LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)", null)
        val kickoff = fridayNoon + 32 * hour
        val psg = match(7, Competition.LIGUE_1, "Paris Saint-Germain", "Troyes", kickoff)
        val shown = scheduledMatches(
            listOf(psg),
            listOf(
                row(dazn, "Ligue 1 : PSG / Troyes", kickoff - 30 * 60_000L, 150),
                row(ligue1, "Ligue 1 McDonald's : PSG / Troyes", kickoff, 120),
            ),
            now,
        ).single()
        // L'ancien diffuseur, encore annoncé par le guide, est écarté.
        assertEquals(listOf("LIGUE 1+| MATCH 3 (MULTI AUDIO SANS PUB)"), shown.channels.map { it.name })
    }

    /** Ce que montrait la Fire TV : le guide de « Canal+ Sport 1 » annonçait le match, la chaîne diffusait du rugby. */
    @Test
    fun `bundesliga match is offered on bein sports, not on a canal plus channel the guide gets wrong`() {
        val canal = EpgChannelRow("canalplussport1", 10, "FR| CANAL+ SPORT 1", "FR| CANAL+ SPORT 1", null)
        val bein = EpgChannelRow("beinsports3.fr", 11, "FR| BEIN SPORTS 3", "FR| BEIN SPORTS 3", null)
        val kickoff = fridayNoon + 9 * hour
        val hamburg = match(12, Competition.BUNDESLIGA, "Hamburger SV", "Borussia Mönchengladbach", kickoff, MatchStatus.LIVE)
        val shown = scheduledMatches(
            listOf(hamburg),
            listOf(
                row(canal, "Hambourg / Mönchengladbach", kickoff - 5 * 60_000L, 140),
                row(bein, "Football : Bundesliga | Hambourg / Borussia Mönchengladbach", kickoff, 120),
            ),
            now,
        ).single()
        assertEquals(listOf("FR| BEIN SPORTS 3"), shown.channels.map { it.name })
    }

    @Test
    fun `france plays on the national channel first, under french names`() {
        val tf1 = EpgChannelRow("tf1.fr", 6, "FR| TF1", "FR| TF1", null)
        val rts = EpgChannelRow("rts2.ch", 7, "CH| RTS Deux", "CH| RTS Deux", null)
        val ligue1 = EpgChannelRow("ligue1plus.fr", 8, "FR| LIGUE 1+ LA CHAINE", "FR| LIGUE 1+ LA CHAINE", null)
        val kickoff = fridayNoon + 6 * 24 * hour + 8 * hour
        val italy = ScheduledMatch(9, Competition.NATIONAL_TEAMS, kickoff, MatchStatus.SCHEDULED, Team("Italy", null, null, null), Team("France", null, null, null))
        val shown = scheduledMatches(
            listOf(italy),
            listOf(
                row(rts, "Football: Italie - France", kickoff - 15 * 60_000L, 135),
                row(tf1, "Football - Ligue des nations : Italie / France", kickoff - 30 * 60_000L, 150),
                BroadcastRow(
                    ligue1,
                    null,
                    FootballProgrammeRow(
                        ligue1.epgChannelId!!,
                        "Ligue 1 McDonald's",
                        null,
                        "1ère période du match de la Journée 5 du Championnat de France de Ligue 1 McDonald's opposant AS Monaco et RC Lens",
                        "Football",
                        null,
                        kickoff,
                        kickoff + 60 * 60_000L,
                    ),
                ),
            ),
            now,
        ).single()
        assertEquals("Italie – France", shown.label)
        assertEquals(listOf("FR| TF1", "CH| RTS Deux"), shown.channels.map { it.name })
    }
    @Test
    fun `liga matches missing from the guide are offered on the playlist event channels`() {
        // Samedi : les chaînes « FR| DAZN LALIGA n (LIVE MATCH) », sans programme dans le guide, montrent
        // le n-ième prochain match de Liga (images d'attente de LALIGA 1 à 5).
        val day = fridayNoon + 24 * hour - 11 * hour - 18 * 60_000L // samedi, minuit passé
        val sevilla = match(20, Competition.LA_LIGA, "Sevilla", "Alavés", day - 3 * hour, MatchStatus.FINISHED)
        val malaga = match(21, Competition.LA_LIGA, "Málaga", "Racing Santander", day + 14 * hour)
        val espanyol = match(22, Competition.LA_LIGA, "Espanyol", "Deportivo", day + 16 * hour + 15 * 60_000L)
        val osasuna = match(23, Competition.LA_LIGA, "Osasuna", "Athletic Club", day + 18 * hour + 30 * 60_000L)
        val valencia = match(24, Competition.LA_LIGA, "Valencia", "Real Madrid", day + 21 * hour)
        val events = (1..4).map { number ->
            val name = "FR| DAZN LALIGA $number (LIVE MATCH)"
            EventChannelRow(100L + number, name, name, null, hasGuide = false) to checkNotNull(EventChannels.parse(name))
        }
        val dazn2 = EpgChannelRow("dazn2.fr", 9, "FR| DAZN PPV 2", "FR| DAZN PPV 2", null)
        val shown = scheduledMatches(
            listOf(sevilla, malaga, espanyol, osasuna, valencia),
            listOf(row(dazn2, "Valence / Real Madrid", day + 20 * hour + 55 * 60_000L, 155)),
            day + 9 * hour,
            events,
        ).associateBy { it.label }
        val first = shown.getValue("Málaga – Racing Santander")
        assertEquals("FR| DAZN LALIGA 1 (LIVE MATCH)", first.channel?.name)
        assertTrue(first.probable)
        assertEquals("FR| DAZN LALIGA 2 (LIVE MATCH)", shown.getValue("Espanyol – Deportivo").channel?.name)
        assertEquals("FR| DAZN LALIGA 3 (LIVE MATCH)", shown.getValue("Osasuna – Athletic Club").channel?.name)
        // Annoncé par le guide sur DAZN 2 : cette chaîne d'abord, la chaîne événement en plus.
        val classico = shown.getValue("Valencia – Real Madrid")
        assertEquals(listOf("FR| DAZN PPV 2", "FR| DAZN LALIGA 4 (LIVE MATCH)"), classico.channels.map { it.name })
        assertTrue(!classico.probable)
        assertEquals(1, classico.otherChannels)
    }

    @Test
    fun `the provider guide of an event channel is the sure source`() {
        // Guide du compte Xtream : LALIGA 3 annonce Espanyol – Deportivo (contre la numérotation devinée).
        val day = fridayNoon + 24 * hour - 11 * hour - 18 * 60_000L // samedi, minuit passé
        val malaga = match(21, Competition.LA_LIGA, "Málaga", "Racing Santander", day + 14 * hour)
        val espanyol = match(22, Competition.LA_LIGA, "Espanyol", "Deportivo", day + 16 * hour + 15 * 60_000L)
        val osasuna = match(23, Competition.LA_LIGA, "Osasuna", "Athletic Club", day + 18 * hour + 30 * 60_000L)
        val events = (1..5).map { number ->
            val name = "FR| DAZN LALIGA $number (LIVE MATCH ONLY)"
            EventChannelRow(100L + number, name, name, null, hasGuide = false, streamUrl = "http://serveur/u/p/${500 + number}") to
                checkNotNull(EventChannels.parse(name))
        }
        val announced = providerRow(
            events[2].first,
            XtreamProgramme("Espanyol vs. Deportivo de La Coruña", null, espanyol.kickoffMillis, espanyol.kickoffMillis + 2 * hour),
        )
        val shown = scheduledMatches(listOf(malaga, espanyol, osasuna), listOf(announced), day + 9 * hour, events)
            .associateBy { it.label }
        val sure = shown.getValue("Espanyol – Deportivo")
        assertEquals("FR| DAZN LALIGA 3 (LIVE MATCH ONLY)", sure.channel?.name)
        assertTrue(!sure.probable)
        // Les autres restent devinés, sans la chaîne dont le fournisseur a donné le match.
        val first = shown.getValue("Málaga – Racing Santander")
        assertEquals("FR| DAZN LALIGA 1 (LIVE MATCH ONLY)", first.channel?.name)
        assertTrue(first.probable)
        assertEquals(null, shown.getValue("Osasuna – Athletic Club").channel)
    }
}
