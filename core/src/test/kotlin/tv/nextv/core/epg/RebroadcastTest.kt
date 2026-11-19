package tv.nextv.core.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebroadcastTest {

    @Test
    fun aMatchAlreadyShownIsARebroadcast() {
        assertTrue(Rebroadcast.isRebroadcast("Football : Süper Lig | Galatasaray / Fenerbahçe", null) { true })
        assertFalse(Rebroadcast.isRebroadcast("Football : Süper Lig | Galatasaray / Fenerbahçe", null) { false })
        assertTrue(Rebroadcast.isRebroadcast("Basket-ball : NBA | Miami Heat / Milwaukee Bucks", null) { true })
    }

    @Test
    fun repeatedShowsAreNotRebroadcastsByTitleAlone() {
        assertFalse(Rebroadcast.isRebroadcast("NBA Extra", null) { true })
        assertFalse(Rebroadcast.isRebroadcast("Ça se passe sur beIN SPORTS", null) { true })
        assertFalse(Rebroadcast.isRebroadcast("Journal de 20 heures", null) { true })
        assertFalse(Rebroadcast.isRebroadcast("Basket-ball : avant-match | Paris / Hapoel", null) { true })
    }

    @Test
    fun anAnnouncedRebroadcastNeedsNoEarlierShowing() {
        assertTrue(Rebroadcast.isRebroadcast("Rugby : Top 14 (R)", null) { false })
        assertTrue(Rebroadcast.isRebroadcast("Ligue 1", "Rediffusion") { false })
        assertTrue(Rebroadcast.isRebroadcast("Tennis : Masters en différé", null) { false })
        assertFalse(Rebroadcast.isRebroadcast("Rugby : Top 14", "Toulouse / Racing 92") { false })
    }

    @Test
    fun theEarlierShowingMustBeAtLeastAnHourBefore() {
        val start = 10L * 24 * 60 * 60_000L
        val window = Rebroadcast.earlierWindow(start)
        assertFalse(start - 30 * 60_000L in window)
        assertTrue(start - 2 * 60 * 60_000L in window)
        assertFalse(start - 8L * 24 * 60 * 60_000L in window)
    }
}
