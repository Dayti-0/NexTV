package tv.nextv.app.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nextv.core.audio.AudioSnippet
import tv.nextv.core.audio.SpectrumAnalyzer
import java.nio.ByteBuffer

/**
 * Son décodé versé, sans le modifier, au spectre des animations des radios ([SpectrumAnalyzer]) et aux dernières secondes
 * gardées pour reconnaître le morceau ([AudioSnippet]) : rien d'autre qu'une copie tant qu'aucune animation n'est affichée.
 */
@OptIn(UnstableApi::class)
class SpectrumTap(
    private val analyzer: SpectrumAnalyzer,
    /** Dernières secondes du son, pour reconnaître le morceau d'une radio qui ne l'annonce pas. */
    private val snippet: AudioSnippet? = null,
) : BaseAudioProcessor() {

    private var mono = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (analyzer.active) {
            val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            val frames = size / 2 / channels
            if (mono.size < frames) mono = FloatArray(frames)
            val start = inputBuffer.position()
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += inputBuffer.getShort(start + 2 * (f * channels + c))
                mono[f] = sum / (32_768f * channels)
            }
            analyzer.sampleRate = inputAudioFormat.sampleRate
            analyzer.write(mono, frames, SystemClock.elapsedRealtimeNanos())
            snippet?.write(mono, frames, inputAudioFormat.sampleRate)
        }
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }
}
