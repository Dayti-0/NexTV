package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.log10

/** Son MP2 (couche II du MPEG audio) décodé dans l'appli : même résultat que FFmpeg. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MpegAudioDecoderTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("audio/$name")!!.readBytes()

    /** Images MPEG audio du fichier, comme les donnent les lecteurs de conteneurs (une par échantillon). */
    private fun frames(data: ByteArray): List<ByteArray> {
        val bitrates = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        val rates = intArrayOf(44_100, 48_000, 32_000)
        val frames = mutableListOf<ByteArray>()
        var position = 0
        while (position + 4 <= data.size) {
            val header = (data[position].toInt() and 0xFF shl 24) or (data[position + 1].toInt() and 0xFF shl 16) or
                (data[position + 2].toInt() and 0xFF shl 8) or (data[position + 3].toInt() and 0xFF)
            if (header and 0xFFE00000.toInt() != 0xFFE00000.toInt()) {
                position++
                continue
            }
            val size = 144 * bitrates[header shr 12 and 15] * 1000 / rates[header shr 10 and 3] + (header shr 9 and 1)
            frames += data.copyOfRange(position, minOf(data.size, position + size))
            position += size
        }
        return frames
    }

    @Test
    fun decodesLayerTwoLikeFfmpeg() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG_L2).setChannelCount(2).setSampleRate(48_000).build()
        val decoder = MpegAudioDecoder(format)
        val pcm = ByteArrayOutputStream()
        val frames = frames(resource("stereo.mp2"))
        var queued = 0
        var dequeued = 0
        var guard = 0
        while (dequeued < frames.size && guard++ < 10_000) {
            if (queued < frames.size) {
                decoder.dequeueInputBuffer()?.let { input ->
                    val frame = frames[queued]
                    input.ensureSpaceForWrite(frame.size)
                    input.data!!.put(frame)
                    input.data!!.flip()
                    input.timeUs = queued * 24_000L
                    decoder.queueInputBuffer(input)
                    queued++
                }
            }
            val output: SimpleDecoderOutputBuffer = decoder.dequeueOutputBuffer() ?: run { Thread.sleep(1); null } ?: continue
            if (!output.shouldBeSkipped) {
                val data = output.data!!
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                pcm.write(bytes)
            }
            output.release()
            dequeued++
        }
        decoder.release()
        assertEquals(2, decoder.channels)
        assertEquals(48_000, decoder.sampleRate)
        val mine = java.nio.ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
        val reference = java.nio.ByteBuffer.wrap(resource("stereo.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals("échantillons", reference.remaining(), mine.remaining())
        var noise = 0.0
        var signal = 0.0
        for (i in 0 until reference.remaining()) {
            val a = mine.get(i).toDouble()
            val b = reference.get(i).toDouble()
            noise += (a - b) * (a - b)
            signal += b * b
        }
        val snr = 10 * log10(signal / maxOf(noise, 1.0))
        println("MP2 : ${frames.size} images, ${reference.remaining() / 2} échantillons par canal, écart avec FFmpeg : SNR %.1f dB".format(snr))
        assertTrue("son trop différent de FFmpeg : SNR $snr dB", snr > 40)
    }

    @Test
    fun rendererTakesLayerOneAndTwoOnly() {
        val types = MpegAudioRenderer.MIME_TYPES
        assertTrue(MimeTypes.AUDIO_MPEG_L2 in types)
        assertTrue(MimeTypes.AUDIO_MPEG_L1 in types)
        assertTrue(MimeTypes.AUDIO_AAC !in types)
        assertEquals(C.TRACK_TYPE_AUDIO, MimeTypes.getTrackType(MimeTypes.AUDIO_MPEG_L2))
    }
}
