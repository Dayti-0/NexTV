package tv.nextv.app.ui.phonesetup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.app.PlaylistImport
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Page du téléphone : seule l'adresse avec le code répond, une seule playlist est acceptée. */
class PhoneSetupServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = mutableListOf<PlaylistImport>()
    private val server = PhoneSetupServer { received += it }
    private val port = server.start(scope)

    @After
    fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun request(path: String, form: Map<String, String>? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (form != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test
    fun wrongAddressIsRefused() {
        assertEquals(404, request("/nimporte").first)
        assertEquals(404, request("/").first)
    }

    @Test
    fun xtreamAccountArrivesOnce() {
        val (code, page) = request("/${server.token}")
        assertEquals(200, code)
        assertTrue(page.contains("Envoyer à la télé"))
        val (_, sent) = request("/${server.token}", mapOf("mode" to "xtream", "name" to "Salon", "server" to "http://serveur.example:8080", "username" to "moi", "password" to "secret"))
        assertTrue(sent, sent.contains("C'est envoyé"))
        assertEquals(1, received.size)
        val import = received.single()
        assertEquals("Salon", import.name)
        assertTrue(import.url, import.url.startsWith("http://serveur.example:8080/get.php") && import.url.contains("username=moi"))
        // Second envoi : refusé.
        val (_, again) = request("/${server.token}", mapOf("mode" to "m3u", "url" to "http://autre.example/p.m3u"))
        assertTrue(again.contains("Déjà envoyé"))
        assertEquals(1, received.size)
    }

    @Test
    fun formErrorsAreShownOnThePhone() {
        val (_, page) = request("/${server.token}", mapOf("mode" to "m3u", "url" to "ftp://non"))
        assertTrue(page.contains("http:// ou https://"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun formParsing() {
        assertEquals(mapOf("a" to "b c", "d" to "é&"), parseForm("a=b+c&d=%C3%A9%26"))
        assertTrue(phoneImport(mapOf("mode" to "xtream", "server" to "", "username" to "u", "password" to "p")) is PhoneImportResult.Error)
        val m3u = phoneImport(mapOf("mode" to "m3u", "url" to " https://x.example/l.m3u ")) as PhoneImportResult.Ok
        assertEquals("https://x.example/l.m3u", m3u.import.url)
        assertNull(m3u.import.name)
    }
}
