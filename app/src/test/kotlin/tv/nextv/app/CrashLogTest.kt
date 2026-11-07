package tv.nextv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.log.Redactor

class CrashLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun recordsTheCrashWithoutCredentials() {
        val log = CrashLog(folder.root.resolve("crash.txt"), Redactor()::redact)
        assertNull(log.last())
        val error = IllegalStateException("Lecture de http://provider.example:8080/live/alice/s3cretpass/42.ts impossible")
        log.record(error)
        val entry = log.last()!!
        assertTrue(entry.summary.startsWith("java.lang.IllegalStateException"))
        assertFalse("Identifiants masqués : ${entry.details}", "s3cretpass" in entry.details || "alice" in entry.details)
        assertTrue(entry.details.lines().size <= 40)
        log.clear()
        assertNull(log.last())
    }

    @Test
    fun summaryPointsAtTheCauseAndTheAppCode() {
        val file = folder.root.resolve("crash.txt")
        file.writeText(
            "1700000000000\n0.2.15\njava.lang.RuntimeException: boom\n\tat android.os.Handler.dispatch(Handler.java:1)\n" +
                "\tat tv.nextv.app.ui.player.PlayerViewModel.start(PlayerViewModel.kt:10)\nCaused by: java.io.IOException: réseau\n",
        )
        val entry = CrashLog(file) { it }.last()!!
        assertEquals(1_700_000_000_000L, entry.atMillis)
        assertEquals("0.2.15", entry.version)
        assertEquals("java.io.IOException: réseau\ntv.nextv.app.ui.player.PlayerViewModel.start(PlayerViewModel.kt:10)", entry.summary)
    }
}
