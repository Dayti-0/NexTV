package tv.nextv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport

class StreamUrlInspectorTest {

    @Test
    fun `http and https streams are supported with a format hint`() {
        val hls = StreamUrlInspector.inspect("https://cdn.example/live/index.m3u8?token=x", null)
        val ts = StreamUrlInspector.inspect("http://cdn.example/live/123.ts", null)
        val xtream = StreamUrlInspector.inspect("http://host:8080/live/u/p/42?output=m3u8", null)
        val unknown = StreamUrlInspector.inspect("http://host:8080/live/u/p/42", null)

        assertEquals(StreamSupport.SUPPORTED, hls.support)
        assertEquals(StreamFormat.HLS, hls.format)
        assertEquals(StreamFormat.PROGRESSIVE, ts.format)
        assertEquals(StreamFormat.HLS, xtream.format)
        assertEquals(StreamFormat.UNKNOWN, unknown.format)
        assertEquals(StreamFormat.DASH, StreamUrlInspector.inspect("https://a/b/manifest.mpd", null).format)
    }

    @Test
    fun `relative urls are resolved against the effective playlist url`() {
        val base = "https://cdn.example/lists/fr/playlist.m3u"

        assertEquals("https://cdn.example/lists/fr/tf1.m3u8", StreamUrlInspector.inspect("tf1.m3u8", base).url)
        assertEquals("https://cdn.example/live/2.ts", StreamUrlInspector.inspect("/live/2.ts", base).url)
        assertEquals("https://other.example/x.m3u8", StreamUrlInspector.inspect("//other.example/x.m3u8", base).url)
    }

    @Test
    fun `relative urls in a local playlist cannot be resolved`() {
        val result = StreamUrlInspector.inspect("videos/film.mp4", baseUrl = null)

        assertEquals(StreamSupport.UNSUPPORTED, result.support)
        assertEquals(setOf(StreamIssue.UNRESOLVED_RELATIVE_URL), result.issues)
    }

    @Test
    fun `kodi style headers are extracted`() {
        val result = StreamUrlInspector.inspect(
            "http://s.example/a.m3u8|User-Agent=VLC%2F3.0&Referer=http%3A%2F%2Fsite%2F",
            null,
            declaredHeaders = mapOf("Origin" to "http://site"),
        )

        assertEquals("http://s.example/a.m3u8", result.url)
        assertEquals(mapOf("Origin" to "http://site", "User-Agent" to "VLC/3.0", "Referer" to "http://site/"), result.headers)
    }

    @Test
    fun `invalid header values are dropped`() {
        val result = StreamUrlInspector.inspect("http://s/a", null, mapOf("User-Agent" to "bad\nvalue", "Host" to "evil", "X-Ok" to "1"))

        assertEquals(mapOf("X-Ok" to "1"), result.headers)
    }

    @Test
    fun `non http schemes are limited or unsupported`() {
        val rtsp = StreamUrlInspector.inspect("rtsp://cam.local/stream", null)
        val udp = StreamUrlInspector.inspect("udp://@239.0.0.1:1234", null)
        val rtmp = StreamUrlInspector.inspect("rtmp://live.example/app/key", null)
        val file = StreamUrlInspector.inspect("file:///sdcard/film.mp4", null)

        assertEquals(StreamSupport.LIMITED, rtsp.support)
        assertTrue(StreamIssue.PROXY_NOT_APPLICABLE in rtsp.issues)
        assertEquals(StreamSupport.LIMITED, udp.support)
        assertTrue(StreamIssue.MULTICAST_NETWORK_REQUIRED in udp.issues)
        assertEquals(StreamSupport.UNSUPPORTED, rtmp.support)
        assertEquals(setOf(StreamIssue.UNSUPPORTED_SCHEME), rtmp.issues)
        assertEquals(StreamSupport.UNSUPPORTED, file.support)
    }

    @Test
    fun `drm protected streams are unsupported`() {
        val result = StreamUrlInspector.inspect("https://s/manifest.mpd", null, drmProtected = true)

        assertEquals(StreamSupport.UNSUPPORTED, result.support)
        assertEquals(setOf(StreamIssue.DRM_PROTECTED), result.issues)
    }

    @Test
    fun `malformed http urls are reported`() {
        val result = StreamUrlInspector.inspect("http://", null)

        assertEquals(StreamSupport.UNSUPPORTED, result.support)
        assertEquals(setOf(StreamIssue.MALFORMED_URL), result.issues)
    }
}
