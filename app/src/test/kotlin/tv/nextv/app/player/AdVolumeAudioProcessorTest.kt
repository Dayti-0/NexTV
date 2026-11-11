package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.core.audio.LogoState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/** « Baisser le son des pubs » sur le son décodé, tel que le lecteur le reçoit (blocs AAC de 1024 échantillons). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdVolumeAudioProcessorTest {

    private val control = AdVolumeControl().apply { enabled = true }
    private val processor = AdVolumeAudioProcessor(control).apply {
        configure(AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))
        flush()
    }
    private var phase = 0

    /** [seconds] de son (ou de silence), par blocs de 1024 échantillons stéréo ; rend le plus fort échantillon du dernier bloc. */
    private fun play(seconds: Double, silent: Boolean = false): Int {
        var peak = 0
        repeat((seconds * SAMPLE_RATE / BLOCK).toInt()) {
            val input = ByteBuffer.allocateDirect(BLOCK * 4).order(ByteOrder.nativeOrder())
            repeat(BLOCK) {
                val sample = if (silent) 0 else (AMPLITUDE * sin(phase++ * 0.05)).toInt().toShort()
                input.putShort(sample.toShort()).putShort(sample.toShort())
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            peak = 0
            while (output.hasRemaining()) peak = maxOf(peak, abs(output.short.toInt()))
        }
        return peak
    }

    private fun assertFullVolume(peak: Int) = assertEquals(AMPLITUDE.toDouble(), peak.toDouble(), 2.0)

    @Test
    fun `the silences between the spots turn the sound down, the programme brings it back`() {
        assertFullVolume(play(60.0))
        // Trois pubs de vingt secondes, séparées par une demi-seconde de silence.
        play(0.5, silent = true)
        assertFullVolume(play(20.0)) // une seule coupure : pas encore une page de pubs
        play(0.5, silent = true)
        val lowered = play(20.0)
        assertTrue(control.attenuating)
        assertTrue("son presque au minimum : $lowered", lowered < AMPLITUDE / 10)
        play(0.5, silent = true)
        assertTrue(play(20.0) < AMPLITUDE / 10)

        // Programme : plus de silence.
        assertFullVolume(play(40.0))
        assertFalse(control.attenuating)
    }

    @Test
    fun `option off, nothing is measured or changed`() {
        control.enabled = false
        repeat(4) {
            play(0.5, silent = true)
            assertFullVolume(play(20.0))
        }
        assertFalse(control.attenuating)
    }

    @Test
    fun `a new stream forgets the logo of the previous channel`() {
        assertEquals(LogoState.UNKNOWN, control.logo)
        control.frame(ByteArray(AdVolumeControl.FRAME_WIDTH * AdVolumeControl.FRAME_HEIGHT))
        control.newStream()
        assertEquals(LogoState.UNKNOWN, control.logo)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 1024
        const val AMPLITUDE = 8_000
    }
}
