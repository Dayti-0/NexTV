package tv.nextv.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Credentials

class RemoteUrlValidatorTest {

    private fun error(input: String) = (RemoteUrlValidator.validate(input) as RemoteUrlValidator.Result.Invalid).error

    private fun valid(input: String) = RemoteUrlValidator.validate(input) as RemoteUrlValidator.Result.Valid

    @Test
    fun `accepts http and https urls`() {
        assertEquals("http://example.com/list.m3u", valid("  http://example.com/list.m3u ").url.toString())
        assertEquals("https://example.com:8443/get.php?type=m3u_plus", valid("https://example.com:8443/get.php?type=m3u_plus").url.toString())
        assertNull(valid("http://192.168.1.10:8000/tv.m3u").embeddedCredentials)
    }

    @Test
    fun `rejects empty, schemeless and malformed urls`() {
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.EMPTY), error("   "))
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.MISSING_SCHEME), error("example.com/list.m3u"))
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.MISSING_SCHEME), error("example.com:8080/list.m3u"))
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.MALFORMED), error("http:/example.com"))
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.MALFORMED), error("http://"))
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.INVALID_CHARACTERS), error("http://a.com/\nb"))
    }

    @Test
    fun `rejects unsupported schemes`() {
        assertEquals(SourceError.UnsupportedScheme("ftp"), error("ftp://example.com/list.m3u"))
        assertEquals(SourceError.UnsupportedScheme("file"), error("file:///sdcard/list.m3u"))
        assertEquals(SourceError.UnsupportedScheme("rtsp"), error("rtsp://cam/stream"))
    }

    @Test
    fun `extracts embedded credentials and removes them from the url`() {
        val result = valid("http://alice:s%40cret@example.com/list.m3u")

        assertEquals("http://example.com/list.m3u", result.url.toString())
        assertEquals(Credentials("alice", "s@cret"), result.embeddedCredentials)
    }

    @Test
    fun `local address detection`() {
        listOf("localhost", "192.168.1.2", "10.0.0.1", "172.20.1.1", "127.0.0.1", "nas", "box.local", "[::1]", "fd12::1")
            .forEach { assertTrue(it, LocalAddresses.isLocal(it)) }
        listOf("example.com", "8.8.8.8", "172.32.0.1", "2001:db8::1")
            .forEach { assertFalse(it, LocalAddresses.isLocal(it)) }
    }

    @Test
    fun `proxy settings validation and scopes`() {
        val proxy = ProxySettings(enabled = true, host = "proxy.example", port = 3128, scopes = setOf(ProxyScope.STREAMS))

        assertTrue(proxy.validate().isEmpty())
        assertTrue(proxy.appliesTo(RequestPurpose.STREAM))
        assertFalse(proxy.appliesTo(RequestPurpose.PLAYLIST))
        assertFalse(proxy.copy(enabled = false).appliesTo(RequestPurpose.STREAM))
        assertEquals(listOf(ProxyProblem.INVALID_HOST), proxy.copy(host = "http://proxy").validate())
        assertEquals(listOf(ProxyProblem.INVALID_PORT), proxy.copy(port = 0).validate())
        assertEquals(
            listOf(ProxyProblem.SOCKS_AUTH_UNSUPPORTED),
            proxy.copy(type = ProxyType.SOCKS, credentials = Credentials("a", "b")).validate(),
        )
        // Un proxy invalide n'est jamais appliqué.
        assertFalse(proxy.copy(host = "").appliesTo(RequestPurpose.STREAM))
    }
}
