package tv.nextv.core.mma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmaEventsTest {

    private fun fight(date: String, a: String, b: String, state: String = "pre", winner: Int? = null, period: Int = 0, clock: String = "-") = """
        {"date": "$date", "type": {"abbreviation": "Light Heavyweight"}, "format": {"regulation": {"periods": 3}},
         "status": {"period": $period, "displayClock": "$clock", "type": {"state": "$state"}},
         "competitors": [
           {"order": 1, "id": "3156612", "winner": ${winner == 0}, "athlete": {"displayName": "$a", "flag": {"alt": "Czechia", "href": "https://x/cze.png"}}, "records": [{"summary": "32-6-1"}]},
           {"order": 2, "winner": ${winner == 1}, "athlete": {"displayName": "$b"}}
         ]}
    """

    private val board = """
        {"events": [{"id": "1", "name": "UFC Fight Night: Procházka vs. Stirling", "date": "2026-11-21T15:00Z",
          "status": {"type": {"state": "post"}},
          "competitions": [
            ${fight("2026-11-21T15:00Z", "Shoaib Yousaf", "John Mitchell", "post", winner = 1, period = 1, clock = "3:23")},
            ${fight("2026-11-21T18:00Z", "Aljamain Sterling", "Kevin Vallejos", "post", winner = 0, period = 3, clock = "5:00")},
            ${fight("2026-11-21T18:00Z", "Jiří Procházka", "Navajo Stirling")}
          ]}]}
    """

    @Test
    fun espnBoardGivesCardTimesAndResults() {
        val event = MmaEspn.parse(board, MmaPromotion.UFC)!!.single()
        assertEquals("UFC Fight Night: Procházka vs. Stirling", event.name)
        assertEquals(1_795_273_200_000L, event.startMillis)
        assertEquals(1_795_284_000_000L, event.mainCardMillis)
        assertTrue(event.finished)
        val main = event.mainEvent!!
        assertEquals("Jiří Procházka – Navajo Stirling", main.label)
        assertEquals("Poids mi-lourds", main.weightClass)
        assertEquals("32-6-1", main.fighters[0].record)
        assertEquals("https://a.espncdn.com/combiner/i?img=/i/headshots/mma/players/full/3156612.png&w=240&h=174", main.fighters[0].photoUrl())
        assertNull(main.fighters[1].photoUrl())
        assertTrue(main.mainCard)
        assertNull(main.result)
        val decision = event.fights[1].result!!
        assertEquals(0, decision.winner)
        assertTrue(decision.decision)
        val prelim = event.fights[2]
        assertFalse(prelim.mainCard)
        assertEquals(MmaResult(1, 1, "3:23", decision = false), prelim.result)
    }

    @Test
    fun guideKeepsEventsAndDropsMagazines() {
        assertEquals(MmaPromotion.CAGE_WARRIORS, MmaGuide.promotionOf("MMA : Cage Warriors", null, "Sports de combat"))
        assertEquals(MmaPromotion.UFC, MmaGuide.promotionOf("MMA : UFC", "Ciryl Gane / Josh Hokit", null))
        assertEquals(MmaPromotion.KSW, MmaGuide.promotionOf("KSW 123. Poids légers - Valeriu Mircea - Marcin Held", "KSW", "Sport / MMA"))
        assertEquals(MmaPromotion.ARES, MmaGuide.promotionOf("ARES 45. 1re partie", "ARES Fighting Championship", "Sport / MMA"))
        assertEquals(MmaPromotion.HEXAGONE, MmaGuide.promotionOf("MMA : Hexagone", "Hexagone MMA 48.", null))
        assertNull(MmaGuide.promotionOf("Format UFC", null, "Magazine sportif"))
        assertNull(MmaGuide.promotionOf("MMA : UFC Classiques", null, null))
        assertNull(MmaGuide.promotionOf("ARES 45. Les temps forts", "ARES Fighting Championship", "Sport / MMA"))
        assertNull(MmaGuide.promotionOf("Jiu-jitsu brésilien : UFC BJJ", null, null))
        assertNull(MmaGuide.promotionOf("Les aventures d'Arès", null, "Film"))
        assertEquals("Cage Warriors", MmaGuide.eventName("MMA : Cage Warriors"))
    }
}
