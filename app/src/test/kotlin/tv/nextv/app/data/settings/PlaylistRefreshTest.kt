package tv.nextv.app.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRefreshTest {

    private val hour = 60 * 60_000L
    private val now = 1_000 * 24 * hour

    @Test
    fun everyStartRefreshesAtEachOpeningAndDailyWhileRunning() {
        assertTrue(PlaylistRefresh.EVERY_START.dueAtStart(now - 1, now))
        assertFalse(PlaylistRefresh.EVERY_START.dueWhileRunning(now - 23 * hour, now))
        assertTrue(PlaylistRefresh.EVERY_START.dueWhileRunning(now - 24 * hour, now))
    }

    @Test
    fun intervalsApplyAtStartAndWhileRunning() {
        assertFalse(PlaylistRefresh.HOURS_12.dueAtStart(now - 11 * hour, now))
        assertTrue(PlaylistRefresh.HOURS_12.dueAtStart(now - 12 * hour, now))
        assertFalse(PlaylistRefresh.DAYS_3.dueAtStart(now - 71 * hour, now))
        assertTrue(PlaylistRefresh.DAYS_3.dueWhileRunning(now - 72 * hour, now))
        assertFalse(PlaylistRefresh.DAYS_7.dueWhileRunning(now - 6 * 24 * hour, now))
    }

    @Test
    fun neverLoadedPlaylistIsAlwaysDue() {
        PlaylistRefresh.entries.forEach {
            assertTrue(it.dueAtStart(null, now))
            assertTrue(it.dueWhileRunning(null, now))
        }
    }
}
