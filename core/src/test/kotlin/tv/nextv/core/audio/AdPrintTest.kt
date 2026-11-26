package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Empreintes du son : une « pub » (son synthétique qui change sans cesse, comme une voix sur une musique) apprise une
 * fois est reconnue quand elle repasse, à un autre volume, à une autre fréquence d'échantillonnage et avec un peu de
 * bruit ; un autre son ne l'est pas.
 */
class AdPrintTest {

    /** Son « parlé » : notes et bruit dont la hauteur et le volume changent tous les dixièmes de seconde. */
    private fun ad(seed: Int, seconds: Double, rate: Int, gain: Float = 0.3f, noise: Float = 0f): FloatArray {
        val random = Random(seed)
        val length = (seconds * rate).toInt()
        val steps = (seconds * 10).toInt() + 1
        val notes = DoubleArray(steps) { 200.0 + random.nextDouble() * 2_500 }
        val levels = DoubleArray(steps) { 0.2 + random.nextDouble() }
        val hiss = Random(seed * 31 + 7)
        val extra = Random(rate)
        return FloatArray(length) { i ->
            val t = i / rate.toDouble()
            val step = (t * 10).toInt()
            val tone = sin(2 * PI * notes[step] * t) + 0.5 * sin(2 * PI * notes[step] * 1.5 * t) + 0.3 * sin(2 * PI * (notes[(step + 3) % steps] / 2) * t)
            val voice = (hiss.nextFloat() - 0.5f) * 0.4
            ((tone * levels[step] * 0.4 + voice * levels[(step + 1) % steps]) * gain + (extra.nextFloat() - 0.5f) * noise).toFloat()
        }
    }

    /** Le même son à une autre fréquence d'échantillonnage (interpolation linéaire), avec un peu de bruit. */
    private fun resample(samples: FloatArray, from: Int, to: Int, noise: Float = 0f): FloatArray {
        val random = Random(to)
        return FloatArray((samples.size.toLong() * to / from).toInt()) { i ->
            val x = i * from.toDouble() / to
            val k = x.toInt().coerceAtMost(samples.size - 2)
            val f = (x - k).toFloat()
            samples[k] * (1 - f) + samples[k + 1] * f + (random.nextFloat() - 0.5f) * noise
        }
    }

    private fun prints(samples: FloatArray, rate: Int): Pair<IntArray, BooleanArray> {
        val prints = ArrayList<Int>()
        val loud = ArrayList<Boolean>()
        val fingerprinter = AudioFingerprinter(rate)
        var i = 0
        val chunk = FloatArray(1_000)
        while (i < samples.size) {
            val n = minOf(chunk.size, samples.size - i)
            samples.copyInto(chunk, 0, i, i + n)
            fingerprinter.feed(chunk, n) { print, audible ->
                prints += print
                loud += audible
            }
            i += n
        }
        return prints.toIntArray() to loud.toBooleanArray()
    }

    @Test
    fun aKnownAdIsRecognizedAgainEvenAtAnotherRateAndVolume() {
        val library = AdPrintLibrary()
        val (learned, learnedLoud) = prints(ad(seed = 1, seconds = 20.0, rate = 48_000), 48_000)
        assertTrue(library.learn(learned, learnedLoud, confirmed = true, now = 1) > 0)
        assertEquals(1, library.size)

        // Repassée plus fort, à 44,1 kHz, avec du bruit : reconnue sur 2,5 s prises au milieu.
        val (heard, heardLoud) = prints(resample(ad(seed = 1, seconds = 20.0, rate = 48_000, gain = 0.6f), 48_000, 44_100, noise = 0.01f), 44_100)
        val from = 600
        val match = library.match(heard, heardLoud, AdPrintLibrary.BLOCK, from)
        assertNotNull(match)
        assertTrue("écart ${match!!.errorRate}", match.errorRate < 0.2)
        // Au bon endroit de la pub (à quelques empreintes près).
        assertTrue("décalage ${match.offset}", kotlin.math.abs(match.offset - from) <= 3)
    }

    @Test
    fun anotherSoundIsNotRecognized() {
        val library = AdPrintLibrary()
        val (learned, learnedLoud) = prints(ad(seed = 1, seconds = 30.0, rate = 48_000), 48_000)
        library.learn(learned, learnedLoud, confirmed = true, now = 1)
        val (other, otherLoud) = prints(ad(seed = 2, seconds = 30.0, rate = 48_000), 48_000)
        var position = 0
        while (position + AdPrintLibrary.BLOCK <= other.size) {
            assertNull(library.match(other, otherLoud, AdPrintLibrary.BLOCK, position))
            position += 40
        }
    }

    @Test
    fun learningTheSameBreakTwiceKeepsOnlyWhatIsNew() {
        val library = AdPrintLibrary()
        val first = ad(seed = 1, seconds = 20.0, rate = 48_000)
        val second = ad(seed = 3, seconds = 20.0, rate = 48_000)
        val (a, aLoud) = prints(first, 48_000)
        library.learn(a, aLoud, confirmed = false, now = 1)
        // Page suivante : la même pub, puis une nouvelle.
        val (b, bLoud) = prints(first + second, 48_000)
        val added = library.learn(b, bLoud, confirmed = true, now = 2)
        assertTrue("ajoutées $added", added in (15 * AudioFingerprinter.PER_SECOND).toInt()..(22 * AudioFingerprinter.PER_SECOND).toInt())
        assertEquals(2, library.size)
    }

    @Test
    fun silenceIsNeverLearnedNorRecognized() {
        val library = AdPrintLibrary()
        val (quiet, quietLoud) = prints(FloatArray(48_000 * 10), 48_000)
        assertFalse(quietLoud.any { it })
        assertEquals(0, library.learn(quiet, quietLoud, confirmed = true, now = 1))
    }

    @Test
    fun savedAndRestored() {
        val library = AdPrintLibrary()
        val (learned, learnedLoud) = prints(ad(seed = 5, seconds = 12.0, rate = 48_000), 48_000)
        library.learn(learned, learnedLoud, confirmed = true, now = 7)
        val copy = AdPrintLibrary()
        assertTrue(copy.restore(ByteArrayInputStream(library.encode())))
        assertEquals(1, copy.size)
        assertNotNull(copy.match(learned, learnedLoud, AdPrintLibrary.BLOCK, 100))
        assertFalse(AdPrintLibrary().restore(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
    }

    /**
     * Bibliothèque fournie avec l'appli ajoutée à celle de l'appareil : ses pubs y sont reconnues, à l'essai, sans
     * doublon de ce que l'appareil connaissait déjà, et sans vieillir la pub déjà apprise par l'appareil.
     */
    @Test
    fun aSeedLibraryIsMergedWithoutDuplicates() {
        val device = AdPrintLibrary()
        val (known, knownLoud) = prints(ad(seed = 11, seconds = 15.0, rate = 48_000), 48_000)
        device.learn(known, knownLoud, confirmed = true, now = 29_000_000)
        val seed = AdPrintLibrary()
        seed.learn(known, knownLoud, confirmed = false, now = 3)
        val (fresh, freshLoud) = prints(ad(seed = 12, seconds = 15.0, rate = 48_000), 48_000)
        seed.learn(fresh, freshLoud, confirmed = false, now = 4)
        val pack = AdPrintLibrary(Int.MAX_VALUE).apply { restore(ByteArrayInputStream(seed.encode())) }
        assertTrue(device.merge(pack) > 0)
        assertEquals(2, device.size)
        val match = device.match(fresh, freshLoud, AdPrintLibrary.BLOCK, 200)
        assertNotNull(match)
        assertFalse(match!!.chunk.confirmed)
        // La pub déjà connue reste celle de l'appareil, sûre et récente.
        val own = device.match(known, knownLoud, AdPrintLibrary.BLOCK, 200)!!.chunk
        assertTrue(own.confirmed)
        assertEquals(29_000_000L, own.lastUsed)
    }

    @Test
    fun aKnownAdLowersTheSoundThroughTheGuard() {
        val library = AdPrintLibrary()
        val rate = 48_000
        val spot = ad(seed = 9, seconds = 15.0, rate = rate)
        val (learned, learnedLoud) = prints(spot, rate)
        library.learn(learned, learnedLoud, confirmed = true, now = 1)
        val guard = AdVolumeGuard()
        val watch = AdPrintWatch(library, guard, learner = { _, _, _ -> })
        val programme = ad(seed = 10, seconds = 20.0, rate = rate)
        var loweredAt = -1.0
        for ((index, part) in listOf(programme, spot).withIndex()) {
            var i = 0
            val slice = rate / 50
            while (i + slice <= part.size) {
                var energy = 0.0
                for (k in i until i + slice) energy += part[k] * part[k]
                guard.process(energy / slice, 20)
                watch.feed(part.copyOfRange(i, i + slice), slice, rate, guard.now)
                if (index == 0) assertFalse("programme baissé à ${guard.now} ms", guard.attenuating)
                if (index == 1 && guard.attenuating && loweredAt < 0) loweredAt = i / rate.toDouble()
                i += slice
            }
        }
        assertTrue("baissé après $loweredAt s de pub", loweredAt in 0.0..3.5)
    }
}
