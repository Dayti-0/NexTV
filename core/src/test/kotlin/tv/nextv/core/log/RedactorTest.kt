package tv.nextv.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private val redactor = Redactor()

    @Test
    fun `masks user info in urls`() {
        assertEquals("GET http://***@example.com/list.m3u", redactor.redact("GET http://bob:hunter2@example.com/list.m3u"))
    }

    @Test
    fun `masks sensitive query parameters`() {
        assertEquals(
            "http://h:8080/get.php?username=***&password=***&type=m3u_plus",
            redactor.redact("http://h:8080/get.php?username=bob&password=hunter2&type=m3u_plus"),
        )
        assertEquals("https://cdn/x.m3u8?token=***", redactor.redact("https://cdn/x.m3u8?token=abcdef"))
    }

    @Test
    fun `masks xtream style path credentials`() {
        assertEquals("http://h:8080/live/***/***/123.ts", redactor.redact("http://h:8080/live/bob/hunter2/123.ts"))
        assertEquals("http://h/movie/***/***/9.mkv", redactor.redact("http://h/movie/bob/hunter2/9.mkv"))
    }

    @Test
    fun `masks authorization headers`() {
        assertEquals("Authorization: ***", redactor.redact("Authorization: Basic Ym9iOmh1bnRlcjI="))
        assertEquals("proxy-authorization: ***", redactor.redact("proxy-authorization=abc"))
    }

    @Test
    fun `masks registered secrets anywhere, including url encoded`() {
        redactor.registerSecret("p@ss word")
        redactor.registerSecret("ab") // trop court : ignoré

        val text = redactor.redact("login failed for p@ss word / p%40ss+word / ab")

        assertFalse(text.contains("p@ss word"))
        assertFalse(text.contains("p%40ss+word"))
        assertTrue(text.endsWith("/ ab"))
    }

    @Test
    fun `redacting logger sanitizes messages and exceptions`() {
        val captured = CapturingLogger()
        redactor.registerSecret("hunter2")
        val logger = RedactingLogger(captured, redactor)
        val cause = IllegalStateException("upstream http://bob:hunter2@h/x")

        logger.warn("T", "Échec http://h/get.php?password=hunter2", java.io.IOException("failed hunter2", cause))

        val entry = captured.entries.single()
        assertFalse(entry.message.contains("hunter2"))
        val throwable = entry.throwable!!
        assertFalse(throwable.toString().contains("hunter2"))
        assertTrue(throwable.toString().startsWith("java.io.IOException"))
        assertFalse(throwable.cause.toString().contains("hunter2"))
        assertTrue(throwable.stackTrace.isNotEmpty())
    }
}

class CapturingLogger : Logger {
    data class Entry(val level: String, val tag: String, val message: String, val throwable: Throwable?)

    val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()

    override fun debug(tag: String, message: String) {
        entries += Entry("D", tag, message, null)
    }

    override fun info(tag: String, message: String) {
        entries += Entry("I", tag, message, null)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        entries += Entry("W", tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        entries += Entry("E", tag, message, throwable)
    }

    /** Tout le texte journalisé, exceptions et causes comprises. */
    fun allText(): String = entries.joinToString("\n") { entry ->
        buildString {
            append(entry.message)
            var t = entry.throwable
            while (t != null) {
                append('\n').append(t.toString())
                t = t.cause
            }
        }
    }
}
