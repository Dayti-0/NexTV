package tv.nextv.app.ui.mma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.app.ui.football.BroadcastRow
import tv.nextv.core.mma.MmaEvent
import tv.nextv.core.mma.MmaPromotion

class MmaCardsTest {
    private val hour = 60 * 60_000L
    private val now = 1_795_000_000_000L
    private val rmc = EpgChannelRow("RMCSport1.fr", 7, "FR| RMC SPORT 1", "RMC Sport 1", null)
    private val canal = EpgChannelRow("CanalPlusSport360.fr", 9, "FR| CANAL+ SPORT 360", "Canal+ Sport 360", null)

    private fun row(channel: EpgChannelRow, title: String, start: Long, length: Long = 3 * hour, subtitle: String? = null) =
        BroadcastRow(channel, null, FootballProgrammeRow(channel.epgChannelId!!, title, subtitle, null, "Sports de combat", null, start, start + length))

    @Test
    fun espnEventGetsItsChannelAndGuideRerunsAreDropped() {
        val ufc = MmaEvent("1", MmaPromotion.UFC, "UFC Fight Night: A vs. B", now + 2 * hour, now + 5 * hour, emptyList())
        val rows = listOf(
            row(rmc, "MMA : UFC", now + 5 * hour),
            // Rediffusion d'UFC le lendemain : ESPN fait foi, pas de gala de plus.
            row(rmc, "MMA : UFC", now + 30 * hour),
            row(rmc, "Format UFC", now + 4 * hour, length = hour / 2),
            // Cage Warriors : seulement dans le guide, puis repassé le lendemain sous le même titre.
            row(rmc, "MMA : Cage Warriors", now + 24 * hour),
            row(rmc, "MMA : Cage Warriors", now + 48 * hour),
            // ARES en deux parties : un seul gala.
            row(canal, "ARES 45. 1re partie", now + 50 * hour, length = 2 * hour, subtitle = "ARES Fighting Championship"),
            row(canal, "Partie 2", now + 52 * hour, length = 2 * hour, subtitle = "ARES Fighting Championship"),
        )
        val cards = mmaCards(listOf(ufc), rows, now)
        assertEquals(listOf(MmaPromotion.UFC, MmaPromotion.CAGE_WARRIORS, MmaPromotion.ARES), cards.map { it.promotion })
        assertEquals(7L, cards[0].channel?.id)
        assertEquals("Cage Warriors", cards[1].name)
        assertNull(cards[1].event)
        assertEquals("ARES 45", cards[2].name)
        assertEquals(now + 54 * hour, cards[2].endMillis)
    }
}
