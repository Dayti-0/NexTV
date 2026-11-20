package tv.nextv.core.mma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SherdogSiteTest {

    private fun eventRow(path: String, name: String, date: String) = """
        <tr onclick="document.location='$path';" itemscope itemtype="http://schema.org/Event">
            <td><meta itemprop="startDate" content="${date}T00:00:00+00:00"></td>
            <td><a itemprop="url" href="$path"><span itemprop="name">$name</span></a></td>
        </tr>"""

    @Test
    fun organizationPageListsUpcomingThenRecentEvents() {
        val html = """
            <div class="tab_menu"><a href="#" data-id="upcoming_tab" class="active">Upcoming</a><a href="#" data-id="recent_tab">Recent</a></div>
            <div class="single_tab" id="upcoming_tab"><table>${eventRow("/events/KSW-123-Mircea-vs-Held-114428", "KSW 123 - Mircea vs. Held", "2026-11-21")}</table></div>
            <div class="single_tab" id="recent_tab"><table>${eventRow("/events/KSW-122-Yakymenko-vs-Morelli-113862", "KSW 122 - Yakymenko vs. Morelli", "2026-10-10")}
            ${eventRow("/events/KSW-121-a-1", "KSW 121", "2026-09-19")}${eventRow("/events/KSW-120-a-2", "KSW 120", "2026-07-18")}</table></div>
        """
        val events = SherdogSite.events(html)
        assertEquals(listOf("KSW 123 - Mircea vs. Held", "KSW 122 - Yakymenko vs. Morelli", "KSW 121"), events.map { it.name })
        assertEquals(1_795_219_200_000L, events[0].dayMillis)
    }

    private fun mainSide(side: String, name: String, nickname: String?, record: String, outcome: String) = """
        <div class="fighter $side" itemprop="performer">
            <a href="/fighter/x"><img itemprop="image" src="/image_crop/200/300/_images/fighter/$name.jpg" title="${nickname?.let { "First '$it' $name" } ?: name}" /></a>
            <h3><a href="/fighter/x"><span itemprop="name">$name</span></a></h3>
            <span class="record">$record <em>(Win-Loss-Draw)</em> </span>
            <span class="final_result $outcome">$outcome</span>
        </div>"""

    private fun row(a: String, b: String, weight: String, method: String? = null) = """
        <tr itemprop="subEvent" itemscope itemtype="http://schema.org/Event">
            <td>8</td>
            <td class="text_right col_fc_upcoming"><div class="fighter_list left">
                <img class="lazy" src="/image_crop/44/44/_images/fighter/$a.jpg" title="$a" />
                <div class="fighter_result_data"><a href="/fighter/a"><span itemprop="name">${a.replace(" ", "<br />")}</span></a><br>
                ${if (method != null) "<span class=\"final_result win\">win</span>" else "<span class=\"record\"><em>26-12-1</em></span>"}</div></div></td>
            <td class="text_center"><img src="vs_small.svg" /><br /><span class="weight_class">$weight</span></td>
            <td class="text_left col_fc_upcoming"><div class="fighter_list right">
                <img class="lazy" src="/image_crop/44/44/_images/fighter/$b.jpg" title="$b" />
                <div class="fighter_result_data"><a href="/fighter/b"><span itemprop="name">${b.replace(" ", "<br />")}</span></a><br>
                ${if (method != null) "<span class=\"final_result loss\">loss</span>" else "<span class=\"record\"><em>18-10-0</em></span>"}</div></div></td>
            ${if (method != null) "<td class=\"winby\"><b>$method</b><br /></td><td>3</td><td>5:00</td>" else "<td></td>"}
        </tr>"""

    @Test
    fun eventPageGivesMainEventThenTheOtherFightsWithResults() {
        val html = """
            <div class="fight_card">${mainSide("left_side", "Valeriu Mircea", "The Solitary Wolf", "32-9-1", "win")}
                <div class="versus"><b>MAIN EVENT</b><span class="title_fight">TITLE FIGHT</span><br /><span class="weight_class">Lightweight</span></div>
                ${mainSide("right_side", "Marcin Held", null, "31-11-0", "loss")}
            </div>
            <table class="fight_card_resume"><tr><td><em>Match</em><br /> 9</td><td><em>Method</em><br /> Submission (Kneebar)</td><td><em>Round</em><br /> 5</td><td><em>Time</em><br /> 1:09</td></tr></table>
            <section><table border="0" class="new_table upcoming"><tbody><tr class="table_head"><td>Match</td></tr>
            ${row("Marian Ziolkowski", "Roman Szymanski", "Welterweight")}
            ${row("Andrzej Grzebyk", "Laid Zerhouni", "188lb Catchweight", method = "Decision (Majority)")}
            </tbody></table></section>
        """
        val fights = SherdogSite.fights(html, 0L)
        assertEquals(3, fights.size)
        val main = fights[0]
        assertEquals("Poids légers · ceinture", main.weightClass)
        assertEquals("Valeriu Mircea – Marcin Held", main.label)
        assertEquals("The Solitary Wolf", main.fighters[0].nickname)
        assertEquals("32-9-1", main.fighters[0].record)
        assertEquals("https://www.sherdog.com/image_crop/200/200/_images/fighter/Valeriu Mircea.jpg", main.fighters[0].photoUrl())
        assertEquals(MmaResult(0, 5, "1:09", decision = false), main.result)
        assertEquals("Marian Ziolkowski", fights[1].fighters[0].name)
        assertEquals("26-12-1", fights[1].fighters[0].record)
        assertNull(fights[1].result)
        assertEquals("Poids intermédiaire (85 kg)", fights[2].weightClass)
        assertEquals(MmaResult(0, 3, "5:00", decision = true), fights[2].result)
    }
}
