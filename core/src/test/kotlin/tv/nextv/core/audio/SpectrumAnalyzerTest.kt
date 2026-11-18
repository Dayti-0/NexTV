package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private fun tone(analyzer: SpectrumAnalyzer, hz: Double, amplitude: Double, seconds: Double, now: Long) {
        val count = (analyzer.sampleRate * seconds).toInt()
        val samples = FloatArray(count) { i -> (amplitude * sin(2 * PI * hz * i / analyzer.sampleRate)).toFloat() }
        analyzer.write(samples, count, now)
    }

    @Test
    fun aToneLightsItsOwnBand() {
        val analyzer = SpectrumAnalyzer().apply { active = true }
        val bands = FloatArray(analyzer.bandCount)
        var now = 1_000_000_000L
        repeat(20) {
            tone(analyzer, 1_000.0, 0.05, 0.05, now)
            now += 50_000_000L
            analyzer.read(bands, 0.05f, now)
        }
        val loudest = bands.indices.maxBy { bands[it] }
        assertTrue("bande ${analyzer.bandEdge(loudest)}–${analyzer.bandEdge(loudest + 1)} Hz", analyzer.bandEdge(loudest) <= 1_000f && analyzer.bandEdge(loudest + 1) >= 1_000f)
        assertTrue(bands[loudest] > 0.4f)
        // Loin du son : presque rien.
        assertTrue(bands[analyzer.bandCount - 1] < 0.3f)
        assertTrue(analyzer.level > 0.2f)
        assertTrue(analyzer.bass < 0.3f)
    }

    @Test
    fun aBassToneMovesTheBassLevel() {
        val analyzer = SpectrumAnalyzer().apply { active = true }
        val bands = FloatArray(analyzer.bandCount)
        var now = 1_000_000_000L
        repeat(20) {
            tone(analyzer, 60.0, 0.6, 0.05, now)
            now += 50_000_000L
            analyzer.read(bands, 0.05f, now)
        }
        assertTrue(analyzer.bass > 0.7f)
    }

    @Test
    fun withoutSoundEverythingFallsBack() {
        val analyzer = SpectrumAnalyzer().apply { active = true }
        val bands = FloatArray(analyzer.bandCount)
        var now = 1_000_000_000L
        repeat(10) {
            tone(analyzer, 440.0, 0.5, 0.05, now)
            now += 50_000_000L
            analyzer.read(bands, 0.05f, now)
        }
        assertTrue(bands.max() > 0.5f)
        // Plus rien de versé depuis plus d'une seconde (radio coupée) : retour à zéro.
        repeat(60) {
            now += 100_000_000L
            analyzer.read(bands, 0.1f, now)
        }
        assertTrue(bands.max() < 0.05f)
        assertEquals(false, analyzer.hasSound(now))
    }

    @Test
    fun nothingIsKeptWhileInactive() {
        val analyzer = SpectrumAnalyzer()
        tone(analyzer, 440.0, 0.5, 0.1, 1_000_000_000L)
        assertEquals(false, analyzer.hasSound(1_000_000_000L))
    }
}
