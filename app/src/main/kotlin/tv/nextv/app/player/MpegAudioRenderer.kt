package tv.nextv.app.player

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.JavaLayerException
import javazoom.jl.decoder.SampleBuffer
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Son MPEG audio décodé dans l'appli (JLayer) : couches I et II (MP2, le son stéréo des chaînes et des
 * enregistrements de la TNT), que ni les appareils Android ni FFmpeg tel qu'il est fourni ne savent lire —
 * l'image passait sans le son. Aussi le MP3 en secours, si le décodeur de l'appareil échoue.
 */
@OptIn(UnstableApi::class)
class MpegAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<MpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "MpegAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType !in MIME_TYPES) return C.FORMAT_UNSUPPORTED_TYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48_000
        return if (sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, channels, sampleRate))) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): MpegAudioDecoder = MpegAudioDecoder(format)

    override fun getOutputFormat(decoder: MpegAudioDecoder): Format =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, decoder.channels, decoder.sampleRate)

    companion object {
        val MIME_TYPES = setOf(MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG)
    }
}

/**
 * Une image MPEG audio par échantillon (ce que donnent les lecteurs de conteneurs) → PCM 16 bits. Un seul
 * flux JLayer, alimenté image par image : son décodeur garde l'état de ses filtres d'une image à l'autre
 * (un décodeur par image ferait un clic toutes les 24 ms).
 */
@OptIn(UnstableApi::class)
class MpegAudioDecoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        // Tableaux vides, remplis par SimpleDecoder (createInputBuffer, createOutputBuffer).
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(BUFFERS) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFERS) as Array<SimpleDecoderOutputBuffer>),
    ) {

    /** Canaux et fréquence du son décodé (ceux annoncés par le conteneur avant la première image). */
    @Volatile
    var channels: Int = format.channelCount.takeIf { it > 0 } ?: 2
        private set

    @Volatile
    var sampleRate: Int = format.sampleRate.takeIf { it > 0 } ?: 48_000
        private set

    private var feed = FrameFeed()
    private var bitstream = Bitstream(feed)
    private var decoder = Decoder()

    init {
        setInitialInputBufferSize(INPUT_BUFFER_SIZE)
    }

    override fun getName(): String = "jlayer-mpeg-audio"

    override fun createInputBuffer(): DecoderInputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): DecoderException = DecoderException("Décodage MPEG audio impossible", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        // Après un déplacement : nouveau départ (pas de reste de l'image d'avant dans les filtres).
        if (reset) restart()
        val data = inputBuffer.data ?: return null
        val frame = ByteArray(data.remaining())
        data.get(frame)
        feed.push(frame)
        try {
            val header = bitstream.readFrame()
            if (header == null) {
                outputBuffer.shouldBeSkipped = true
                return null
            }
            val samples = decoder.decodeFrame(header, bitstream) as SampleBuffer
            bitstream.closeFrame()
            channels = decoder.outputChannels
            sampleRate = decoder.outputFrequency
            val count = samples.bufferLength
            val out = outputBuffer.init(inputBuffer.timeUs, count * 2)
            out.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples.buffer, 0, count)
            out.position(0)
            out.limit(count * 2)
        } catch (e: JavaLayerException) {
            // Image abîmée (coupure du flux) : sautée, le décodage reprend à la suivante.
            outputBuffer.shouldBeSkipped = true
            restart()
        } catch (e: RuntimeException) {
            outputBuffer.shouldBeSkipped = true
            restart()
        }
        return null
    }

    private fun restart() {
        feed = FrameFeed()
        bitstream = Bitstream(feed)
        decoder = Decoder()
    }

    /** Flux lu par JLayer : les images y sont ajoutées une à une ; vide, il répond « fin » sans bloquer. */
    private class FrameFeed : InputStream() {
        private var data = ByteArray(0)
        private var position = 0

        fun push(frame: ByteArray) {
            val rest = data.size - position
            data = if (rest == 0) frame else ByteArray(rest + frame.size).also {
                System.arraycopy(data, position, it, 0, rest)
                System.arraycopy(frame, 0, it, rest, frame.size)
            }
            position = 0
        }

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }

        override fun available(): Int = data.size - position
    }

    private companion object {
        const val BUFFERS = 16

        /** Plus grande image MPEG audio (couche II à 384 kb/s et 32 kHz : 1 728 octets). */
        const val INPUT_BUFFER_SIZE = 4_096
    }
}
