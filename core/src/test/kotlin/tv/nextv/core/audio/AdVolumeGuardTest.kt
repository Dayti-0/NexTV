package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class AdVolumeGuardTest {

    private val guard = AdVolumeGuard()
    private var gain = 1.0

    /** [seconds] de son à [db] dBFS, par blocs de 20 ms. */
    private fun play(db: Double, seconds: Double) {
        repeat((seconds * 50).toInt()) { gain = guard.process(10.0.pow(db / 10), 20) }
    }

    private fun gainDb() = 20 * log10(gain)

    @Test
    fun `ads louder than the programme are turned down, then back up`() {
        play(-30.0, 60.0)
        assertEquals(0.0, gainDb(), 0.01)

        play(-23.0, 2.0)
        assertFalse("pas encore : quelques secondes de son plus fort", guard.attenuating)
        play(-23.0, 3.0)
        assertTrue(guard.attenuating)
        assertEquals(-24.0, gainDb(), 0.01)

        // Toute la page de pub reste basse.
        play(-22.0, 120.0)
        assertEquals(-24.0, gainDb(), 0.01)

        // Retour du programme : son rétabli.
        play(-30.0, 5.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `a short loud moment of the programme keeps its volume`() {
        play(-30.0, 60.0)
        play(-20.0, 2.0)
        play(-30.0, 10.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `silences do not lower the reference`() {
        play(-30.0, 60.0)
        play(-80.0, 30.0)
        play(-29.0, 20.0)
        assertFalse("le programme repris au même niveau n'est pas une pub", guard.attenuating)
    }

    @Test
    fun `a programme that stays louder becomes the reference`() {
        play(-30.0, 60.0)
        play(-23.0, 7 * 60.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }
}
