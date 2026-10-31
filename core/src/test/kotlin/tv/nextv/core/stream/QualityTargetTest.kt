package tv.nextv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.nextv.core.model.StreamQuality

class QualityTargetTest {

    private val fhd = QualityOption(1, StreamQuality.FHD)
    private val hd = QualityOption(2, StreamQuality.HD)
    private val sd = QualityOption(3, StreamQuality.SD)
    private val low = QualityOption(4, StreamQuality.LOW)

    @Test
    fun `best keeps the opened version`() {
        assertEquals(1L, QualityTarget.BEST.start(listOf(fhd, hd, sd), preferred = 1))
    }

    @Test
    fun `hd mode never starts above hd when a lower version exists`() {
        assertEquals(2L, QualityTarget.HD.start(listOf(fhd, hd, sd), preferred = 1))
        assertEquals(3L, QualityTarget.HD.start(listOf(fhd, sd), preferred = 1))
        // Version plus basse choisie : respectée.
        assertEquals(3L, QualityTarget.HD.start(listOf(fhd, hd, sd), preferred = 3))
        // Seulement en FHD : lisible quand même.
        assertEquals(1L, QualityTarget.HD.start(listOf(fhd), preferred = 1))
    }

    @Test
    fun `eco mode prefers sd, then low, otherwise hd`() {
        assertEquals(3L, QualityTarget.ECO.start(listOf(fhd, hd, sd, low), preferred = 1))
        assertEquals(4L, QualityTarget.ECO.start(listOf(fhd, hd, low), preferred = 1))
        assertEquals(2L, QualityTarget.ECO.start(listOf(fhd, hd), preferred = 1))
    }

    @Test
    fun `switcher never upgrades above the target`() {
        var now = 0L
        val switcher = QualitySwitcher(listOf(fhd, hd, sd), initialChannelId = 3, clock = { now }, maxRank = QualityTarget.HD.maxRank(listOf(fhd, hd, sd)))
        switcher.onReady()
        now += 10 * 60_000
        val first = switcher.evaluate(bandwidthBps = 100_000_000)
        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.UPGRADE), first)
        switcher.onReady()
        now += 10 * 60_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(bandwidthBps = 100_000_000))
    }
}
