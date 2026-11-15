package tv.nextv.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vidéo lue, notée pour « Dernier incident ». */
class VideoDescriptionTest {

    @Test
    fun describesSizeFormatAndDecoder() {
        val info = PlaybackInfo(videoMimeType = "video/hevc", width = 3840, height = 2160, videoDecoder = "OMX.MTK.VIDEO.DECODER.HEVC", hardwareDecoder = true)
        assertEquals("3840 × 2160 H.265, décodeur matériel OMX.MTK.VIDEO.DECODER.HEVC", videoDescription(info))
        assertEquals("H.264", videoDescription(PlaybackInfo(videoMimeType = "video/avc")))
        assertNull(videoDescription(PlaybackInfo()))
    }
}
