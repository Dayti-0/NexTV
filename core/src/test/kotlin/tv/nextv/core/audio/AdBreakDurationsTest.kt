package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdBreakDurationsTest {

    @Test
    fun `typical length is the median once three breaks are known`() {
        val durations = AdBreakDurations()
        durations.add("M6", 240_000)
        durations.add("M6", 180_000)
        assertNull(durations.typical("M6"))
        durations.add("M6", 400_000)
        assertEquals(240_000L, durations.typical("M6"))
        assertNull(durations.typical("TF1"))
    }

    @Test
    fun `too short or too long breaks are not kept`() {
        val durations = AdBreakDurations()
        assertFalse(durations.add("M6", 10_000))
        assertFalse(durations.add("M6", 30 * 60_000L))
        assertFalse(durations.add(" ", 120_000))
    }

    @Test
    fun `only the last breaks count and they survive a restart`() {
        val durations = AdBreakDurations()
        repeat(10) { durations.add("TF1", 60_000) }
        repeat(6) { durations.add("TF1", 300_000) }
        assertEquals(300_000L, durations.typical("TF1"))
        val restored = AdBreakDurations.decode(durations.encode())
        assertEquals(300_000L, restored.typical("TF1"))
        assertNull(AdBreakDurations.decode("abîmé\n\tsans nom").typical("abîmé"))
    }
}
