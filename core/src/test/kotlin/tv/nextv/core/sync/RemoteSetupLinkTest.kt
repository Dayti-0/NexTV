package tv.nextv.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Configuration à distance : code affiché sur la télé à configurer, configuration envoyée, réponse, page supprimée. */
class RemoteSetupLinkTest {

    /** Pages filebin en mémoire (une page supprimée ne reprend plus rien, comme sur filebin). */
    private class FakeRelay : SyncRelay {
        val bins = HashMap<String, HashMap<String, ByteArray>>()
        val closed = HashSet<String>()
        var offline = false

        private fun check() {
            if (offline) throw IOException("hors ligne")
        }

        override suspend fun list(bin: String): Map<String, String> {
            check()
            return bins[bin].orEmpty().mapValues { (_, bytes) -> bytes.contentHashCode().toString() }
        }

        override suspend fun read(bin: String, name: String): ByteArray? {
            check()
            return bins[bin]?.get(name)
        }

        override suspend fun write(bin: String, name: String, bytes: ByteArray) {
            check()
            if (bin in closed) throw SyncBinClosedException()
            bins.getOrPut(bin) { HashMap() }[name] = bytes
        }

        override suspend fun deleteFile(bin: String, name: String) {
            check()
            bins[bin]?.remove(name)
        }

        override suspend fun deleteBin(bin: String) {
            check()
            bins.remove(bin)
            closed += bin
        }
    }

    private val config = RemoteConfig(
        playlist = RemotePlaylist("Mon fournisseur", "http://example.test/get.php?username=ami&password=secret", mirrors = listOf("http://backup.test")),
        settings = mapOf("theme_style" to "s:LIGHT", "french_only" to "b:true"),
        serviceKeys = mapOf("tmdb" to "cle"),
        hidden = listOf(RemoteHiddenGroup("LIVE", "Adultes"), RemoteHiddenGroup("MOVIE", "")),
        from = "Salon",
    )

    @Test
    fun `the configuring tv finds the code, sends the configuration and reads the answer`() = runBlocking {
        val relay = FakeRelay()
        val friend = RemoteSetupLink(relay, { "Fire TV de Paul" }, pollMillis = 1)
        val me = RemoteSetupLink(relay, { "Salon" }, pollMillis = 1)
        val offer = friend.open()
        assertEquals(6, offer.code.length)

        assertEquals(RemoteSetupFind.Found("Fire TV de Paul"), me.find(offer.code))
        val received = async { friend.awaitConfig(offer) }
        yield()
        me.send(offer.code, config)
        val got = received.await()!!
        assertEquals(config.playlist.url, got.playlist.url)
        assertEquals(listOf("http://backup.test"), got.playlist.mirrors)
        assertEquals(config.settings, got.settings)
        assertEquals(config.serviceKeys, got.serviceKeys)
        assertEquals(config.hidden, got.hidden)
        assertEquals("Salon", got.from)

        friend.reply(offer, installed = true)
        assertEquals(RemoteSetupReply("Fire TV de Paul", true), me.awaitReply(offer.code))
        // Rien ne reste sur filebin, et le code ne resservira plus.
        assertTrue(relay.bins.isEmpty())
        assertEquals(RemoteSetupFind.UnknownCode, me.find(offer.code))
    }

    @Test
    fun `nothing is readable on the page without the code`() = runBlocking {
        val relay = FakeRelay()
        val offer = RemoteSetupLink(relay, { "TV" }).open()
        val me = RemoteSetupLink(relay, { "Salon" })
        me.send(offer.code, config)
        val stored = relay.bins.getValue(RemoteSetupLink.binOf(offer.code)).getValue(RemoteSetupLink.CONFIG_FILE)
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("secret"))
        // Autre code : pas de télé, et la configuration ne se lit pas.
        val other = if (offer.code == "000000") "111111" else "000000"
        assertEquals(RemoteSetupFind.UnknownCode, me.find(other))
    }

    @Test
    fun `an unknown code, an expired code and no network are told apart`() = runBlocking {
        val relay = FakeRelay()
        val me = RemoteSetupLink(relay, { "Salon" })
        assertEquals(RemoteSetupFind.UnknownCode, me.find("123456"))
        relay.offline = true
        assertEquals(RemoteSetupFind.Offline, me.find("123456"))
        relay.offline = false

        var now = 0L
        val friend = RemoteSetupLink(relay, { "TV" }, clock = { now }, pollMillis = 1)
        val offer = friend.open()
        now = offer.expiresAt
        assertNull(friend.awaitConfig(offer))
        friend.close(offer)
        // Code expiré et supprimé : l'envoi échoue au lieu de déposer la configuration dans le vide.
        assertEquals(RemoteSetupFind.UnknownCode, me.find(offer.code))
        try {
            me.send(offer.code, config)
            throw AssertionError("envoi accepté")
        } catch (e: IOException) {
            // Attendu.
        }
    }

    @Test
    fun `a used code is never drawn again`() = runBlocking {
        val relay = FakeRelay()
        // Tirage truqué : le premier code est déjà servi (page supprimée), le second est libre.
        val codes = ArrayDeque(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1))
        val random = object : java.security.SecureRandom() {
            override fun nextInt(bound: Int): Int = codes.removeFirst()
        }
        relay.closed += RemoteSetupLink.binOf("000000")
        val offer = RemoteSetupLink(relay, { "TV" }, random = random).open()
        assertEquals("111111", offer.code)
    }

    @Test
    fun `settings, keys and hidden categories are optional`() {
        val minimal = RemoteConfig(RemotePlaylist("", "http://example.test/liste.m3u"))
        val parsed = RemoteSetupLink.parseConfig(JSONObject(RemoteSetupLink.configJson(minimal).toString()))!!
        assertEquals(minimal.playlist.url, parsed.playlist.url)
        assertNull(parsed.playlist.username)
        assertTrue(parsed.settings.isEmpty())
        assertTrue(parsed.serviceKeys.isEmpty())
        assertNull(parsed.hidden)
        assertNull(RemoteSetupLink.parseConfig(JSONObject().put("playlist", JSONObject().put("url", ""))))
    }
}
