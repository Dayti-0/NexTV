package tv.nextv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.StreamDescriptor
import java.util.TimeZone

class CatchupTest {

    private val account = XtreamAccount("http://line.example", "user", "pass")

    @Test
    fun `stream ids come from the last numeric segment`() {
        assertEquals("1234", Catchup.streamId("http://line.example:8080/live/user/pass/1234.ts"))
        assertEquals("1234", Catchup.streamId("http://line.example/user/pass/1234"))
        assertEquals("77", Catchup.streamId("http://line.example/live/user/pass/77.m3u8?token=x"))
        assertNull(Catchup.streamId("http://cdn.example/tf1/index.m3u8"))
    }

    @Test
    fun `the recorded programme is requested in the server time zone`() {
        // 30 octobre 2026, 19 h 58 UTC : 20 h 58 à Paris (heure d'hiver).
        val start = 1_793_390_280_000L
        val url = Catchup.xtreamUrl(
            "http://backup.example:8080/live/user/pass/1234.ts",
            account,
            startMillis = start,
            stopMillis = start + 52 * 60_000 + 10_000,
            timeZone = TimeZone.getTimeZone("Europe/Paris"),
        )

        assertEquals("http://backup.example:8080/timeshift/user/pass/53/2026-10-30:20-58/1234.ts", url)
    }

    @Test
    fun `live channels get the replay days of their stream`() {
        val channels = listOf(
            Channel(0, "TF1", StreamDescriptor("http://line.example/live/user/pass/101.ts")),
            Channel(1, "France 2", StreamDescriptor("http://line.example/live/user/pass/102.ts")),
            Channel(2, "Film", StreamDescriptor("http://line.example/movie/user/pass/101.mkv"), kind = ContentKind.MOVIE),
        )

        val annotated = Catchup.annotate(channels, mapOf("101" to 7))

        assertEquals(listOf(7, 0, 0), annotated.map { it.catchupDays })
    }

    @Test
    fun `only programmes that started within the replay days are available`() {
        val now = 1_793_390_280_000L
        val day = 86_400_000L
        assertTrue(Catchup.isAvailable(days = 2, startMillis = now - day, now = now))
        assertTrue(Catchup.isAvailable(days = 2, startMillis = now - 60_000, now = now))
        assertFalse(Catchup.isAvailable(days = 2, startMillis = now - 3 * day, now = now))
        assertFalse(Catchup.isAvailable(days = 2, startMillis = now + 60_000, now = now))
        assertFalse(Catchup.isAvailable(days = 0, startMillis = now - 60_000, now = now))
    }
}
