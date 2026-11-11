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
    private var clock = 0.0

    /** [seconds] de son à [db] dBFS, par blocs de 20 ms. */
    private fun play(db: Double, seconds: Double) {
        repeat((seconds * 50).toInt()) { gain = guard.process(10.0.pow(db / 10), 20) }
        clock += seconds
    }

    /** Son à [db] dBFS jusqu'à l'instant [until] (en secondes depuis le début). */
    private fun playUntil(until: Double, db: Double = PROGRAMME) = play(db, until - clock)

    /** Silences complets relevés sur un enregistrement (instant en secondes → durée en millisecondes), son à [db] entre eux. */
    private fun playRecording(silences: List<Pair<Double, Int>>, db: Double = PROGRAMME) {
        for ((at, millis) in silences) {
            playUntil(at, db)
            play(SILENCE, millis / 1000.0)
        }
    }

    private fun gainDb() = 20 * log10(gain)

    @Test
    fun `a real ad break is turned down from its first spot to its last`() {
        // Programme sans un silence, puis le jingle « publicité » entre deux silences.
        playRecording(FRANCE_2.take(1))
        assertFalse("un seul silence ne suffit pas", guard.attenuating)
        playRecording(FRANCE_2.drop(1).take(1))
        playUntil(65.0)
        assertTrue("son baissé dès le premier spot", guard.attenuating)
        assertEquals(-24.0, gainDb(), 0.01)

        // Treize spots de 7 à 31 secondes : le son reste bas d'un bout à l'autre.
        for (silence in FRANCE_2.drop(2)) {
            playRecording(listOf(silence))
            assertTrue("son remonté pendant les pubs, à ${silence.first} s", guard.attenuating)
        }

        // Reprise : plus de silence entre les spots, le son revient.
        playUntil(345.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `ads as loud as the programme are turned down all the same`() {
        play(PROGRAMME, 120.0)
        repeat(6) {
            play(SILENCE, 0.5)
            play(PROGRAMME, 20.0)
        }
        assertTrue(guard.attenuating)
    }

    @Test
    fun `a film keeps its volume, its rare silences included`() {
        // « Tears of Steel » : trois silences complets en douze minutes.
        playRecording(listOf(462.5 to 700, 732.1 to 1880), db = -34.0)
        playUntil(760.0, db = -34.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `a film or a match that starts louder keeps its volume`() {
        play(-32.0, 90.0)
        play(-14.0, 300.0)
        play(-30.0, 60.0)
        play(-12.0, 120.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `quiet passages are not the silence between two spots`() {
        play(-30.0, 60.0)
        repeat(10) {
            play(-52.0, 1.0)
            play(-30.0, 12.0)
        }
        assertFalse(guard.attenuating)
    }

    @Test
    fun `long silences and the silence before the first sound open nothing`() {
        play(SILENCE, 1.0)
        play(PROGRAMME, 20.0)
        play(SILENCE, 8.0)
        play(PROGRAMME, 20.0)
        play(SILENCE, 8.0)
        play(PROGRAMME, 20.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `the sound comes back as soon as the channel logo does`() {
        guard.logo = LogoState.VISIBLE
        play(PROGRAMME, 120.0)
        guard.logo = LogoState.HIDDEN
        play(PROGRAMME, 4.0)
        // Logo tout juste parti : le premier silence ouvre la page de pubs.
        play(SILENCE, 0.6)
        play(PROGRAMME, 1.0)
        assertTrue(guard.attenuating)
        repeat(5) {
            play(PROGRAMME, 20.0)
            play(SILENCE, 0.6)
        }
        assertTrue(guard.attenuating)

        play(PROGRAMME, 2.0)
        guard.logo = LogoState.VISIBLE
        play(PROGRAMME, 1.5)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `a programme without its logo for a long time needs two silences`() {
        guard.logo = LogoState.HIDDEN
        play(PROGRAMME, 300.0)
        play(SILENCE, 0.6)
        play(PROGRAMME, 120.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `silences under the channel logo are not an ad break, unless they keep coming`() {
        guard.logo = LogoState.VISIBLE
        play(PROGRAMME, 60.0)
        repeat(3) {
            play(SILENCE, 0.6)
            play(PROGRAMME, 20.0)
        }
        assertFalse("logo à l'image : le programme", guard.attenuating)

        // La chaîne garde son logo pendant les pubs : il n'est plus écouté.
        play(SILENCE, 0.6)
        play(PROGRAMME, 20.0)
        assertTrue(guard.attenuating)
        play(SILENCE, 0.6)
        play(PROGRAMME, 20.0)
        assertTrue(guard.attenuating)
        play(PROGRAMME, 20.0)
        assertFalse("plus de silence : le programme a repris", guard.attenuating)
    }

    private companion object {
        const val PROGRAMME = -26.0
        const val SILENCE = -90.0

        /**
         * France 2, 12 avril 2024 : fin de l'émission, jingle « publicité » (58,7 → 62,9 s), treize spots,
         * puis une bande-annonce. Fin de chaque silence complet (secondes) et sa durée (millisecondes).
         */
        val FRANCE_2 = listOf(
            58.7 to 300, 62.9 to 1160, 94.0 to 1020, 125.0 to 1020, 140.7 to 1300, 171.8 to 1460, 187.5 to 1500, 194.9 to 1260,
            226.0 to 980, 239.1 to 620, 256.8 to 1160, 273.0 to 1040, 289.1 to 300, 290.0 to 1980, 306.9 to 1060,
        )
    }
}
