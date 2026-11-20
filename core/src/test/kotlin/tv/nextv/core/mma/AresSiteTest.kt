package tv.nextv.core.mma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AresSiteTest {

    private fun side(cls: String, name: String, nickname: String?, flag: String, photo: String, winner: Boolean) = """
        <div class="$cls">
            <img src="https://www.aresfighting.com/wp-content/uploads/$photo.png" alt="$name">
            <div class="basic-info"><h4>$name</h4>${nickname?.let { "<h5>“$it”</h5>" } ?: ""}
                <span class="flag" style="background-image: url('https://www.aresfighting.com/wp-content/themes/aresfighting/img/flags/$flag.svg');"></span>
            </div>
            ${if (winner) "<span class=\"winner winner-${if (cls == "fighter-one") "left" else "right"}\">Winner</span>" else ""}
        </div>
    """

    private fun line(weight: String, a: String, b: String, recordA: String, recordB: String, winner: Int? = null) = """
        <div class="fighter-line no-trad" data-aos="fade-left">
            <div class="fight-name"><h3>$weight</h3></div>
            ${side("fighter-one", a, "Le Fauve", "fr", "a", winner == 0)}
            <div class="fighter-stats">
                <div class="records"><span class="stat stat-left">$recordA</span><span class="info-name">Records (v-d-n)</span><span class="stat stat-right">$recordB</span></div>
            </div>
            ${side("fighter-two", b, null, "br", "b", winner == 1)}
        </div>
    """

    @Test
    fun theFightCardIsReadFromTheEventPage() {
        val html = "<section id=\"main-card\">" + line("Welterweight", "Jordan Zebo", "Baissangour Chamsoudinov", "11-1-0", "11-1-0") +
            line("Women&#8217;s Flyweight", "Ana Silva", "Lea Martin", "N/A", "3-0-0", winner = 1) +
            "<div class=\"fights-comming-soon\"><img class=\"temp-fighter-left\" src=\"https://x/fighter-temp-left.png\"></div></section>"
        val fights = AresSite.fights(html, 1_000L)
        assertEquals(2, fights.size)
        val main = fights[0]
        assertEquals("Poids mi-moyens", main.weightClass)
        assertEquals("Jordan Zebo – Baissangour Chamsoudinov", main.label)
        assertEquals("Le Fauve", main.fighters[0].nickname)
        assertEquals("https://flagcdn.com/w80/fr.png", main.fighters[0].flagUrl)
        assertEquals("https://www.aresfighting.com/wp-content/uploads/a.png", main.fighters[0].photoUrl())
        assertEquals("11-1-0", main.fighters[1].record)
        assertNull(main.result)
        assertNull(fights[1].fighters[0].record)
        assertEquals(1, fights[1].result?.winner)
    }

    @Test
    fun competitionsGiveNameStartAndAnnouncedMainEvent() {
        val body = """[
            {"id": 3368, "title": {"rendered": "ARES 45"}, "acf": {"event_date": "2026-11-20 19:30:00", "main_event": "Jordan Zebo vs. Baissangour Chamsoudinov 2"}},
            {"id": 3788, "title": {"rendered": "ARES 46"}, "acf": {"event_date": "2026-12-11 19:30:00", "main_event": "Coming soon"}},
            {"id": 3293, "title": {"rendered": "ARES 40"}, "acf": {"event_date": "20260410", "main_event": "Paulin Begai vs. Moustapha Diakhaté"}}
        ]"""
        val list = AresSite.competitions(body)!!
        assertEquals(AresSite.Competition(3368, "ARES 45", 1_795_199_400_000L, "Jordan Zebo vs. Baissangour Chamsoudinov 2"), list[0])
        assertNull(list[1].mainEvent)
        assertEquals(1_775_844_000_000L, list[2].startMillis)
    }
}
