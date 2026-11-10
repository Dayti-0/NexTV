package tv.nextv.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Relances d'un direct interrompu. */
class LiveRetriesTest {

    @Test
    fun aChannelThatNeverPlayedIsNotRetried() {
        val retries = LiveRetries(listOf(10L, 20L))
        // Chaîne en panne dès l'ouverture : l'erreur s'affiche tout de suite.
        assertNull(retries.next())
    }

    @Test
    fun aLiveThatPlayedIsRetriedThenGivesUp() {
        val retries = LiveRetries(listOf(10L, 20L))
        retries.playing()
        assertEquals(10L, retries.next())
        assertEquals(20L, retries.next())
        assertNull(retries.next())
        assertEquals(2, retries.done)
    }

    @Test
    fun aStablePlaybackStartsOverTheRetries() {
        val retries = LiveRetries(listOf(10L, 20L))
        retries.playing()
        assertEquals(10L, retries.next())
        assertEquals(20L, retries.next())
        // Le flux rouvert a rejoué assez longtemps : une nouvelle coupure repart du premier essai.
        retries.playing()
        retries.stable()
        assertEquals(10L, retries.next())
    }

    @Test
    fun aRelaunchedLiveThatDoesNotLastKeepsItsCount() {
        val retries = LiveRetries(listOf(10L, 20L))
        retries.playing()
        assertEquals(10L, retries.next())
        // Rouvert, il rejoue quelques secondes puis recoupe : c'est l'essai suivant, pas le premier.
        retries.playing()
        assertEquals(20L, retries.next())
        assertNull(retries.next())
    }

    @Test
    fun anotherChannelForgetsEverything() {
        val retries = LiveRetries(listOf(10L))
        retries.playing()
        assertEquals(10L, retries.next())
        retries.newChannel()
        assertNull(retries.next())
        retries.playing()
        assertEquals(10L, retries.next())
    }
}
