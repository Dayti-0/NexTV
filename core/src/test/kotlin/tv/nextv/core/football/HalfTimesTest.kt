package tv.nextv.core.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HalfTimesTest {
    private var now = 0L
    private val halfTimes = HalfTimes { now }
    private val kickoff = 1_000_000_000L
    private val minute = 60_000L
    private val pause = HalfTimes.HALF_TIME_MILLIS

    /** Retard moyen de l'annonce de la mi-temps sur coup d'envoi prévu + 45 min + temps additionnel. */
    private val late = 100_000L

    private fun at(minutes: Double) {
        now = kickoff + (minutes * minute).toLong()
    }

    @Test
    fun `pause seen starting counts from the whistle`() {
        at(47.0)
        assertNull(halfTimes.resumeAt("a", kickoff, inPlay = true, halfTime = false, minute = "45+2'"))
        at(47.5)
        val resume = halfTimes.resumeAt("a", kickoff, inPlay = true, halfTime = true, minute = "45+2'")
        // Coup de sifflet entre les deux relevés.
        assertEquals(kickoff + (47.25 * minute).toLong() + pause, resume)
        // Même reprise aux relevés suivants.
        at(55.0)
        assertEquals(resume, halfTimes.resumeAt("a", kickoff, inPlay = true, halfTime = true, minute = "45+2'"))
    }

    @Test
    fun `pause found already started is estimated from the kickoff and added time`() {
        at(52.0)
        assertEquals(kickoff + 48 * minute + late + pause, halfTimes.resumeAt("b", kickoff, inPlay = true, halfTime = true, minute = "45+3'"))
    }

    @Test
    fun `pause without added time keeps it at zero`() {
        at(52.0)
        assertEquals(kickoff + 45 * minute + late + pause, halfTimes.resumeAt("b0", kickoff, inPlay = true, halfTime = true, minute = "45'"))
    }

    @Test
    fun `unknown added time takes the usual two minutes`() {
        at(52.0)
        assertEquals(kickoff + 47 * minute + late + pause, halfTimes.resumeAt("c", kickoff, inPlay = true, halfTime = true, minute = null))
    }

    @Test
    fun `old first half reading is not taken for the whistle`() {
        at(30.0)
        halfTimes.resumeAt("d", kickoff, inPlay = true, halfTime = false, minute = "29'")
        at(31.0)
        halfTimes.resumeAt("d", kickoff, inPlay = true, halfTime = false, minute = "30'")
        // Chaîne quittée, puis rouverte à la pause : estimation par le coup d'envoi.
        at(55.0)
        assertEquals(kickoff + 47 * minute + late + pause, halfTimes.resumeAt("d", kickoff, inPlay = true, halfTime = true, minute = null))
    }

    @Test
    fun `estimate never starts the pause in the future`() {
        // Coup d'envoi donné en avance sur l'heure prévue (heure de la source décalée).
        at(45.0)
        assertEquals(now + pause, halfTimes.resumeAt("e", kickoff, inPlay = true, halfTime = true, minute = null))
    }

    @Test
    fun `second half forgets the pause`() {
        at(50.0)
        halfTimes.resumeAt("f", kickoff, inPlay = true, halfTime = true, minute = null)
        at(64.0)
        assertNull(halfTimes.resumeAt("f", kickoff, inPlay = true, halfTime = false, minute = "46'"))
    }
}
