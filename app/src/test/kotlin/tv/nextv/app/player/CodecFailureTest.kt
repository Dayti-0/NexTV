package tv.nextv.app.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Échec de décodage : piste en cause, codec et message lisible. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR")
class CodecFailureTest {

    private fun rendererError(format: Format, code: Int) = ExoPlaybackException.createForRenderer(
        RuntimeException("décodeur"), "renderer", 0, format, C.FORMAT_UNSUPPORTED_SUBTYPE, false, code,
    )

    private val hevc10 = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs("hvc1.2.4.L153.B0")
        .setWidth(3840)
        .setHeight(2160)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).setLumaBitdepth(10).build())
        .build()

    @Test
    fun videoFailureIsDescribedPrecisely() {
        val failure = CodecFailure.of(rendererError(hevc10, PlaybackException.ERROR_CODE_DECODING_FAILED))!!
        assertEquals(C.TRACK_TYPE_VIDEO, failure.trackType)
        assertEquals(false, failure.initFailed)
        assertEquals("HEVC (H.265) 10 bits HDR · 3840×2160", describeVideoFormat(hevc10))
        val message = RuntimeEnvironment.getApplication().resources.describeCodecFailure(failure)!!
        println(message)
        assertTrue(message, message.startsWith("Vidéo HEVC (H.265) 10 bits HDR · 3840×2160"))
    }

    @Test
    fun tenBitIsReadFromTheCodecString() {
        val avc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.640028").setWidth(1920).setHeight(1080).build()
        assertEquals("H.264 · 1920×1080", describeVideoFormat(avc))
        val main10 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hev1.2.4.L120.90").build()
        assertEquals("HEVC (H.265) 10 bits", describeVideoFormat(main10))
    }

    @Test
    fun audioAndSubtitleFailures() {
        val dts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build()
        val audio = CodecFailure.of(rendererError(dts, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))!!
        assertEquals(C.TRACK_TYPE_AUDIO, audio.trackType)
        assertEquals(true, audio.initFailed)
        assertEquals("Son DTS-HD : cet appareil ne sait pas le lire.", RuntimeEnvironment.getApplication().resources.describeCodecFailure(audio))
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
        assertEquals(C.TRACK_TYPE_TEXT, CodecFailure.of(rendererError(pgs, PlaybackException.ERROR_CODE_DECODING_FAILED))!!.trackType)
    }

    @Test
    fun otherErrorsAreNotCodecFailures() {
        assertNull(CodecFailure.of(PlaybackException("réseau", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
    }
}
