package tv.nextv.core.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private fun parse(text: String, parser: M3uParser = M3uParser()) = parser.parse(StringReader(text))

    @Test
    fun `parses header epg urls and extinf attributes`() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="http://epg.example/a.xml.gz,http://epg.example/b.xml" x-tvg-url="http://epg.example/a.xml.gz"
            #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1" tvg-logo="http://logo/tf1.png" group-title="Généralistes",TF1 HD
            http://stream.example/tf1.m3u8
            """.trimIndent(),
        )

        assertTrue(playlist.hasHeader)
        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), playlist.epgUrls)
        val entry = playlist.entries.single()
        assertEquals("TF1 HD", entry.title)
        assertEquals("http://stream.example/tf1.m3u8", entry.url)
        assertEquals(-1.0, entry.duration!!, 0.0)
        assertEquals("tf1.fr", entry.attributes["tvg-id"])
        assertEquals("http://logo/tf1.png", entry.attributes["tvg-logo"])
        assertEquals("Généralistes", entry.group)
        assertTrue(entry.hasExtInf)
    }

    @Test
    fun `commas inside quoted attributes do not end the attribute list`() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, Sport" tvg-name='Euro, News',Euronews, édition FR
            http://s/1
            """.trimIndent(),
        ).entries.single()

        assertEquals("News, Sport", entry.group)
        assertEquals("Euro, News", entry.attributes["tvg-name"])
        assertEquals("Euronews, édition FR", entry.title)
    }

    @Test
    fun `unquoted attributes and missing title`() {
        val entry = parse("#EXTM3U\n#EXTINF:0 tvg-id=abc TVG-NAME=Chaine\nhttp://s/1\n").entries.single()

        assertEquals("abc", entry.attributes["tvg-id"])
        assertEquals("Chaine", entry.attributes["tvg-name"])
        assertNull(entry.title)
    }

    @Test
    fun `extgrp and vlc options apply to the next entry only`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Première
            #EXTGRP:Cinéma
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11)
            #EXTVLCOPT:http-referrer=http://site.example/
            #EXTVLCOPT:network-caching=1000
            http://s/1
            #EXTINF:-1,Deuxième
            http://s/2
            """.trimIndent(),
        )

        val (first, second) = playlist.entries
        assertEquals("Cinéma", first.group)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0 (X11)", "Referer" to "http://site.example/"), first.headers)
        assertNull(second.group)
        assertTrue(second.headers.isEmpty())
    }

    @Test
    fun `exthttp json and kodiprop headers and drm`() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1,Protégée
            #EXTHTTP:{"cookie":"session=1","User-Agent":"Player\/1.0"}
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.stream_headers=Referer=http%3A%2F%2Fa.example%2F
            https://s/manifest.mpd
            """.trimIndent(),
        ).entries.single()

        assertTrue(entry.drmProtected)
        assertEquals("session=1", entry.headers["Cookie"])
        assertEquals("Player/1.0", entry.headers["User-Agent"])
        assertEquals("http://a.example/", entry.headers["Referer"])
    }

    @Test
    fun `handles bom, crlf, blank lines and comments`() {
        val playlist = parse("﻿#EXTM3U\r\n\r\n#EXTREM: commentaire\r\n#EXTINF:-1,A\r\nhttp://s/a\r\n#EXTINF:-1,B\rhttp://s/b")

        assertTrue(playlist.hasHeader)
        assertEquals(listOf("A", "B"), playlist.entries.map { it.title })
        assertEquals(listOf("http://s/a", "http://s/b"), playlist.entries.map { it.url })
    }

    @Test
    fun `plain url list without header`() {
        val playlist = parse("http://s/a.ts\nhttp://s/b.ts\n")

        assertFalse(playlist.hasHeader)
        assertEquals(2, playlist.entries.size)
        assertFalse(playlist.entries.first().hasExtInf)
    }

    @Test
    fun `detects hls media playlists`() {
        val master = parse("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nlow/index.m3u8\n")
        val media = parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:9.97,\nseg0.ts\n")

        assertTrue(master.isHlsMedia)
        assertTrue(media.isHlsMedia)
        assertTrue(media.entries.isEmpty())
    }

    @Test
    fun `truncates beyond max entries`() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(10) { append("#EXTINF:-1,C$it\nhttp://s/$it\n") }
        }
        val playlist = parse(text, M3uParser(maxEntries = 3))

        assertEquals(3, playlist.entries.size)
        assertTrue(playlist.truncated)
    }

    @Test(expected = LineTooLongException::class)
    fun `rejects content without line breaks`() {
        parse("x".repeat(200), M3uParser(maxLineLength = 100))
    }
}
