package tv.nextv.app.ui.football

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow

class FootballGuideTest {

    private val foot = EpgChannelRow("canalplusfoot.fr", 1, "FR| CANAL+ FOOT", "FR| CANAL+ FOOT", null)
    private val event = EpgChannelRow("canalplusevent.fr", 2, "FR| CANAL+ ÉVÉNEMENT", "FR| CANAL+ ÉVÉNEMENT", null)

    private fun row(channel: EpgChannelRow, title: String, subtitle: String, start: Long, minutes: Int) = BroadcastRow(
        channel,
        null,
        FootballProgrammeRow(channel.epgChannelId!!, title, subtitle, null, "Football", null, start, start + minutes * 60_000L),
    )

    private val yesterdayEvening = 1_793_899_800_000L // 2026-11-05T17:30Z, avant-match du coup d'envoi de 17:45Z
    private val todayNoon = 1_793_963_880_000L // 2026-11-06T11:18Z
    private val now = todayNoon + 20 * 60_000L

    private val live = row(foot, "Football : Ligue Europa", "Crystal Palace / Hoffenheim", yesterdayEvening, 135)
    private val replay = row(event, "UEFA Europa League", "Crystal Palace - Hoffenheim", todayNoon, 105)
    private val tonight = row(foot, "Football : Ligue 1", "Lens / Lille", todayNoon + 8 * 60 * 60_000L, 135)

    private fun labels(aired: List<BroadcastRow>) = guideMatches(aired.filter { it.stopMillis > now }, aired).map { it.label }

    @Test
    fun `next-day replay of a match already broadcast is left out`() {
        assertEquals(listOf("Lens – Lille"), labels(listOf(live, replay, tonight)))
    }

    @Test
    fun `without the earlier broadcast the airing is listed`() {
        assertEquals(listOf("Crystal Palace – Hoffenheim", "Lens – Lille"), labels(listOf(replay, tonight)))
    }

    @Test
    fun `second half of a match in progress is not a replay`() {
        val firstHalf = row(foot, "Football : Ligue Europa", "Crystal Palace / Hoffenheim", todayNoon - 60 * 60_000L, 60)
        val secondHalf = row(foot, "Football : Ligue Europa", "Crystal Palace / Hoffenheim", todayNoon, 60)
        assertEquals(listOf("Crystal Palace – Hoffenheim"), labels(listOf(firstHalf, secondHalf)))
    }
}
