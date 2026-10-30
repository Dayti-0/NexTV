package tv.nextv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.model.StreamQuality

class QualitySwitcherTest {

    private var now = 0L
    private val fhd = QualityOption(1, StreamQuality.FHD)
    private val hd = QualityOption(2, StreamQuality.HD)
    private val sd = QualityOption(3, StreamQuality.SD)
    private val low = QualityOption(4, StreamQuality.LOW)

    private fun switcher(initial: Long = 1, enabled: Boolean = true, options: List<QualityOption> = listOf(sd, fhd, low, hd)) =
        QualitySwitcher(options, initial, enabled, clock = { now })

    @Test
    fun `ladder is ordered from best to worst`() {
        assertEquals(listOf(fhd, hd, sd, low), switcher().ladder)
    }

    @Test
    fun `errors step down one quality at a time and never leave the channel`() {
        val switcher = switcher()

        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.ERROR), switcher.onError())
        assertEquals(QualitySwitcher.Decision.Switch(sd, QualitySwitcher.Reason.ERROR), switcher.onError())
        assertEquals(QualitySwitcher.Decision.Switch(low, QualitySwitcher.Reason.ERROR), switcher.onError())
        assertEquals(QualitySwitcher.Decision.GiveUp, switcher.onError())
    }

    @Test
    fun `missing versions are skipped`() {
        val switcher = switcher(options = listOf(fhd, low))

        assertEquals(QualitySwitcher.Decision.Switch(low, QualitySwitcher.Reason.ERROR), switcher.onError())
    }

    @Test
    fun `a slow start steps down after the start allowance`() {
        val switcher = switcher()
        switcher.onBufferingStarted()
        now += 5_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(null))

        now += 3_000
        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.BUFFERING), switcher.evaluate(null))
    }

    @Test
    fun `a stall during playback steps down within seconds`() {
        val switcher = switcher()
        switcher.onBufferingStarted()
        now += 2_000
        switcher.onReady()
        now += 60_000

        switcher.onBufferingStarted()
        now += 2_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(null))
        now += 1_000
        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.BUFFERING), switcher.evaluate(null))
    }

    @Test
    fun `the new version gets the start allowance again`() {
        val switcher = switcher()
        switcher.onReady()
        switcher.onBufferingStarted()
        now += 3_000
        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.BUFFERING), switcher.evaluate(null))

        switcher.onBufferingStarted()
        now += 5_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(null))
    }

    @Test
    fun `repeated short rebuffering steps down`() {
        val switcher = switcher()
        repeat(3) {
            switcher.onBufferingStarted()
            now += 1_000
            switcher.onReady()
            now += 5_000
        }

        assertEquals(QualitySwitcher.Decision.Switch(hd, QualitySwitcher.Reason.BUFFERING), switcher.evaluate(null))
    }

    @Test
    fun `stable playback with enough bandwidth upgrades after the failed version cooled down`() {
        val switcher = switcher()
        switcher.onError() // FHD → HD

        now += 3 * 60_000
        // FHD est encore en délai de grâce (5 min) : pas de remontée.
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(50_000_000))

        now += 3 * 60_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(4_000_000)) // débit insuffisant
        assertEquals(QualitySwitcher.Decision.Switch(fhd, QualitySwitcher.Reason.UPGRADE), switcher.evaluate(20_000_000))
    }

    @Test
    fun `repeated failures lengthen the cooldown`() {
        val switcher = switcher(initial = 2, options = listOf(fhd, hd))
        now = 10 * 60_000
        // Lecture HD stable : remontée en FHD qui échoue deux fois.
        assertEquals(QualitySwitcher.Decision.Switch(fhd, QualitySwitcher.Reason.UPGRADE), switcher.evaluate(50_000_000))
        switcher.onError()
        now += 6 * 60_000
        assertEquals(QualitySwitcher.Decision.Switch(fhd, QualitySwitcher.Reason.UPGRADE), switcher.evaluate(50_000_000))
        switcher.onError()
        now += 6 * 60_000
        // Second échec : délai de 10 min, pas encore écoulé.
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(50_000_000))
    }

    @Test
    fun `disabled switching never changes quality`() {
        val switcher = switcher(enabled = false)
        switcher.onBufferingStarted()
        now += 60_000

        assertFalse(switcher.isAdaptive)
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(null))
        assertEquals(QualitySwitcher.Decision.GiveUp, switcher.onError())
    }

    @Test
    fun `single version is not adaptive`() {
        assertFalse(switcher(options = listOf(fhd)).isAdaptive)
        assertTrue(switcher().isAdaptive)
    }

    @Test
    fun `too many dropped frames steps down to a lower quality and never comes back`() {
        val uhd = QualityOption(5, StreamQuality.UHD)
        val uhdBackup = QualityOption(6, StreamQuality.UHD)
        val switcher = switcher(initial = 5, options = listOf(uhd, uhdBackup, fhd))

        assertEquals(QualitySwitcher.Decision.Stay, switcher.onDroppedFrames(20))
        now += 2_000
        // Une autre version 4K poserait le même problème : passage direct en FHD.
        assertEquals(QualitySwitcher.Decision.Switch(fhd, QualitySwitcher.Reason.DECODER), switcher.onDroppedFrames(20))

        now += 600_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(bandwidthBps = 200_000_000))
    }

    @Test
    fun `dropped frames spread over time are tolerated`() {
        val switcher = switcher()
        repeat(5) {
            assertEquals(QualitySwitcher.Decision.Stay, switcher.onDroppedFrames(20))
            now += 10_000
        }
    }

    @Test
    fun `dropped frames do nothing when automatic quality is off`() {
        val switcher = switcher(enabled = false)

        assertEquals(QualitySwitcher.Decision.Stay, switcher.onDroppedFrames(500))
    }

    @Test
    fun `a limit seen on another channel starts below it`() {
        val uhd = QualityOption(5, StreamQuality.UHD)
        val switcher = QualitySwitcher(listOf(uhd, fhd, hd), 5, clock = { now }, decoderCeilingRank = StreamQuality.UHD.rank)

        assertEquals(fhd, switcher.current)
        now += 600_000
        assertEquals(QualitySwitcher.Decision.Stay, switcher.evaluate(bandwidthBps = 200_000_000))
    }

    @Test
    fun `the limit is ignored when automatic quality is off`() {
        val uhd = QualityOption(5, StreamQuality.UHD)
        val switcher = QualitySwitcher(listOf(uhd, fhd), 5, enabled = false, clock = { now }, decoderCeilingRank = StreamQuality.UHD.rank)

        assertEquals(uhd, switcher.current)
    }
}
