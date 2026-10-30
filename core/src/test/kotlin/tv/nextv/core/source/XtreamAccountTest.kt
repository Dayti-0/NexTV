package tv.nextv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamAccountTest {

    @Test
    fun `playlist link is built from server, username and password`() {
        assertEquals(
            "http://iptv.example.com:8080/get.php?username=user1&password=secret&type=m3u_plus&output=ts",
            XtreamAccount("iptv.example.com:8080", "user1", "secret").playlistUrl(),
        )
        assertEquals(
            "https://iptv.example.com/get.php?username=user1&password=secret&type=m3u_plus&output=ts",
            XtreamAccount(" https://iptv.example.com/ ", "user1", "secret").playlistUrl(),
        )
    }

    @Test
    fun `default port, pasted path and parameters are dropped from the server`() {
        assertEquals(
            "http://iptv.example.com/get.php?username=u&password=p&type=m3u_plus&output=ts",
            XtreamAccount("http://iptv.example.com:80/get.php?username=old&password=old", "u", "p").playlistUrl(),
        )
    }

    @Test
    fun `special characters are encoded`() {
        val url = XtreamAccount("iptv.example.com", "a b", "p&w=d#").playlistUrl()!!
        assertEquals(XtreamAccount("http://iptv.example.com", "a b", "p&w=d#"), XtreamAccount.parse(url))
    }

    @Test
    fun `invalid servers are rejected`() {
        assertNull(XtreamAccount("", "u", "p").playlistUrl())
        assertNull(XtreamAccount("ftp://iptv.example.com", "u", "p").playlistUrl())
        assertNull(XtreamAccount("iptv example.com", "u", "p").playlistUrl())
    }

    @Test
    fun `xtream playlist links are recognised`() {
        assertEquals(
            XtreamAccount("http://iptv.example.com:8080", "user1", "secret"),
            XtreamAccount.parse("http://iptv.example.com:8080/get.php?username=user1&password=secret&type=m3u_plus&output=ts"),
        )
        assertEquals(
            XtreamAccount("http://iptv.example.com", "user1", "secret"),
            XtreamAccount.parse("http://iptv.example.com:80/get.php?username=user1&password=secret&type=m3u_plus&output=ts"),
        )
        assertNull(XtreamAccount.parse("http://iptv.example.com/playlist.m3u"))
        assertNull(XtreamAccount.parse("http://iptv.example.com/get.php?type=m3u_plus"))
    }

    @Test
    fun `password never appears in toString`() {
        assertFalse("secret" in XtreamAccount("iptv.example.com", "user1", "secret").toString())
    }
}
