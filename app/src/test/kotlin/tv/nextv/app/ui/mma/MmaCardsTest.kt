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

    @Test
    fun aDistantEventGetsTheUsualChannelOfItsPromotion() {
        val far = MmaEvent("2", MmaPromotion.UFC, "UFC 335: Oliveira vs. Lopes", now + 22 * 24 * hour, now + 22 * 24 * hour + 2 * hour, emptyList())
        val rmc = tv.nextv.app.ui.guide.GuideChannel(7, "RMC Sport 1", null, null, emptyList())
        val card = mmaCards(listOf(far), emptyList(), now, mapOf("rmcsport1.fr" to rmc)).single()
        assertEquals(7L, card.channel?.id)
        assertEquals(true, card.usualChannel)
    }

    @Test
    fun theFrenchGuideGridComesBeforeAnotherCountrysOne() {
        val canalAfrica = EpgChannelRow("canalplussport3.af", 21, "FR| CANAL+ SPORT 3", "CANAL+ SPORT 3", null)
        val canal360 = EpgChannelRow("canalplussport360.fr", 22, "FR| CANAL+ SPORT 360", "Canal+ Sport 360", null)
        val rows = listOf(
            row(canalAfrica, "ARES 45. 1re partie", now + 2 * hour, length = 2 * hour, subtitle = "ARES Fighting Championship"),
            row(canal360, "MMA : ARES Fighting Championship", now + 2 * hour, length = 2 * hour),
        )
        val card = mmaCards(emptyList(), rows, now).single()
        assertEquals(listOf(22L, 21L), card.channels.map { it.id })
    }

    @Test
    fun anAresEventFromItsSiteTakesTheGuideBroadcastWithoutDuplicate() {
        val ares = MmaEvent("ares-3368", MmaPromotion.ARES, "ARES 45: Jordan Zebo vs. Baissangour Chamsoudinov 2", now + 2 * hour, null, emptyList())
        val canal360 = EpgChannelRow("canalplussport360.fr", 22, "FR| CANAL+ SPORT 360", "Canal+ Sport 360", null)
        val rows = listOf(
            row(canal360, "MMA : ARES Fighting Championship", now + 3 * hour, length = 2 * hour),
            row(canal360, "MMA : ARES Fighting Championship", now + 5 * hour, length = 2 * hour),
        )
        val card = mmaCards(listOf(ares), rows, now).single()
        assertEquals(ares, card.event)
        assertEquals(22L, card.channel?.id)
    }

    @Test
    fun aDayOnlyEventTakesTheTimeOfItsGuideBroadcast() {
        // Jour du gala à minuit UTC + 20 h (heure provisoire de Sherdog).
        val day = (now / (24 * hour)) * (24 * hour) + 24 * hour
        val ksw = MmaEvent("sherdog-1", MmaPromotion.KSW, "KSW 123: Mircea vs. Held", day + 20 * hour, null, emptyList(), timeKnown = false)
        val later = MmaEvent("sherdog-2", MmaPromotion.KSW, "KSW 124: Pawlak vs. Kuberski", day + 28 * 24 * hour + 20 * hour, null, emptyList(), timeKnown = false)
        val rows = listOf(
            row(rmc, "MMA : KSW", day + 22 * hour + 30 * 60_000L, length = 3 * hour, subtitle = "Mircea / Held"),
            // Rediffusion de la même nuit : pas l'heure du gala.
            row(rmc, "MMA : KSW", day + 26 * hour, length = 3 * hour, subtitle = "Mircea / Held"),
        )
        val cards = mmaCards(listOf(ksw, later), rows, now)
        val first = cards.first { it.event == ksw }
        assertEquals(day + 22 * hour + 30 * 60_000L, first.startMillis)
        assertEquals(true, first.timeKnown)
        assertEquals(7L, first.channel?.id)
        assertEquals(false, cards.first { it.event == later }.timeKnown)
        assertEquals(2, cards.size)
    }

    @Test
    fun aDayOnlyEventPrefersItsEveningBroadcastToAMorningShow() {
        val utc = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Paris"))
        try {
            val day = (now / (24 * hour)) * (24 * hour) + 24 * hour
            val hexagone = MmaEvent("sherdog-3", MmaPromotion.HEXAGONE, "Hexagone MMA 30", day + 20 * hour, null, emptyList(), timeKnown = false)
            val rows = listOf(
                // Le matin : une ancienne soirée (autre sous-titre, donc pas vue comme une rediffusion).
                row(rmc, "MMA : Hexagone MMA", day + 10 * hour + 21 * 60_000L, length = 2 * hour, subtitle = "Hexagone 29"),
                // Le soir : le gala en direct (19:30 UTC = 20:30 à Paris).
                row(rmc, "MMA : Hexagone MMA", day + 19 * hour + 30 * 60_000L, length = 3 * hour, subtitle = "Hexagone 30"),
            )
            val card = mmaCards(listOf(hexagone), rows, now).first { it.event == hexagone }
            assertEquals(day + 19 * hour + 30 * 60_000L, card.startMillis)
        } finally {
            java.util.TimeZone.setDefault(utc)
        }
    }
}
