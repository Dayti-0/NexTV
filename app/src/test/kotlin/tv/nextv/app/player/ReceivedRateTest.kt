package tv.nextv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceivedRateTest {

    @Test
    fun `a continuous stream gets a rate every window`() {
        val rate = ReceivedRate(windowMillis = 2_000)
        // 1 Mo par seconde pendant 2 s : 8 Mbit/s.
        assertNull(rate.add(500_000, 0))
        assertNull(rate.add(500_000, 1_000))
        assertNull(rate.add(500_000, 1_500))
        assertEquals(8.0, rate.add(500_000, 2_000)!!, 0.01)
        // Fenêtre suivante, plus lente : 2 Mbit/s.
        assertNull(rate.add(250_000, 3_000))
        assertEquals(2.0, rate.add(250_000, 4_000)!!, 0.01)
    }

    @Test
    fun `a new stream starts a new window`() {
        val rate = ReceivedRate(windowMillis = 2_000)
        rate.add(1_000_000, 0)
        rate.reset()
        assertNull(rate.add(100, 10_000))
        assertEquals(0.0008, rate.add(100, 12_000)!!, 0.0001)
    }
}
