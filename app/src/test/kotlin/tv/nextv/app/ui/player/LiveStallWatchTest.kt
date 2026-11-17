package tv.nextv.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct figé sans erreur : reconnu quand plus rien n'avance. */
class LiveStallWatchTest {

    @Test
    fun aLiveThatKeepsGoingIsNeverStalled() {
        val watch = LiveStallWatch(limitMillis = 20_000)
        for (second in 0..600) assertFalse(watch.check(second * 1_000L, progress = second * 25L))
    }

    @Test
    fun nothingNewForTheLimitIsAStall() {
        val watch = LiveStallWatch(limitMillis = 20_000)
        assertFalse(watch.check(0, 100))
        assertFalse(watch.check(19_500, 100))
        assertTrue(watch.check(20_000, 100))
        // Signalé une fois : la surveillance repart de zéro.
        assertFalse(watch.check(20_500, 100))
        assertFalse(watch.check(40_000, 100))
        assertTrue(watch.check(40_500, 100))
    }

    @Test
    fun aShortFreezeIsNotAStall() {
        val watch = LiveStallWatch(limitMillis = 20_000)
        assertFalse(watch.check(0, 100))
        assertFalse(watch.check(15_000, 100))
        // L'image repart (chargement de quelques secondes) : le compte repart.
        assertFalse(watch.check(15_500, 112))
        assertFalse(watch.check(30_000, 112))
        assertTrue(watch.check(35_500, 112))
    }

    @Test
    fun pausesAndErrorsAreNotWatched() {
        val watch = LiveStallWatch(limitMillis = 20_000)
        assertFalse(watch.check(0, 100))
        // Pause (repère absent) pendant longtemps, puis reprise au même repère : pas un blocage.
        assertFalse(watch.check(10_000, null))
        assertFalse(watch.check(60_000, 100))
        assertFalse(watch.check(79_000, 100))
        assertTrue(watch.check(80_000, 100))
    }

    @Test
    fun resetStartsOver() {
        val watch = LiveStallWatch(limitMillis = 20_000)
        assertFalse(watch.check(0, 100))
        watch.reset()
        assertFalse(watch.check(25_000, 100))
        assertTrue(watch.check(45_000, 100))
    }
}
