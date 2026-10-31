package tv.nextv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TonightTest {

    @Test
    fun `free tnt channels are found by name or guide id`() {
        assertEquals(1, TntChannels.number("FR| TF1 FHD"))
        assertEquals(2, TntChannels.number("FR: France 2 HD"))
        assertEquals(6, TntChannels.number("M6 4K"))
        assertEquals(20, TntChannels.number("TF1 Séries Films"))
        assertEquals(21, TntChannels.number("L'Équipe"))
        assertEquals(25, TntChannels.number("Chérie 25 SD"))
        assertEquals(16, TntChannels.number("Chaîne info", epgChannelId = "franceinfo.fr"))
        assertEquals(3, TntChannels.number("Unknown", epgChannelId = "France3.fr"))
        assertNull(TntChannels.number("FR| CANAL+ FHD"))
        assertNull(TntChannels.number("TF1 +1"))
        assertNull(TntChannels.number("France 3 Alpes"))
    }

    private data class P(val title: String, val start: Long, val stop: Long)

    private val evening = 21 * 60L
    private fun at(hour: Int, minute: Int) = hour * 60L + minute
    private fun pick(vararg programmes: P) = EveningPicker.pick(programmes.toList(), evening * MINUTE_SCALE, { it.start * MINUTE_SCALE }, { it.stop * MINUTE_SCALE })

    @Test
    fun `main programme around 9 pm and the following one, short programmes skipped`() {
        val result = pick(
            P("Journal", at(20, 0), at(20, 45)),
            P("Météo", at(20, 45), at(20, 55)),
            P("Le film", at(21, 5), at(22, 55)),
            P("Programme court", at(22, 55), at(23, 0)),
            P("Magazine", at(23, 0), at(0 + 24, 15)),
        )
        assertEquals("Le film", result.first?.title)
        assertEquals("Magazine", result.second?.title)
    }

    @Test
    fun `programme already on air at 9 10 pm when none starts around 9 pm`() {
        val result = pick(P("Long match", at(20, 0), at(22, 30)), P("Après-match", at(22, 30), at(23, 30)))
        assertEquals("Long match", result.first?.title)
        assertEquals("Après-match", result.second?.title)
    }

    @Test
    fun `no guide for the evening`() {
        val result = pick(P("Matin", at(8, 0), at(9, 0)))
        assertNull(result.first)
        assertNull(result.second)
    }

    private companion object {
        const val MINUTE_SCALE = 60_000L
    }
}
