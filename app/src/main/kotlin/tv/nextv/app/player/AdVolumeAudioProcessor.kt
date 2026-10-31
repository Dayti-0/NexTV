package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nextv.core.audio.AdVolumeGuard
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Option « baisser le son des pubs », partagée entre le lecteur et le traitement du son. */
class AdVolumeControl {
    @Volatile
    var enabled: Boolean = false

    /** Pub détectée : son baissé (affiché par le lecteur). */
    @Volatile
    var attenuating: Boolean = false
        internal set
}

/**
 * Applique [AdVolumeGuard] au son décodé (PCM 16 bits). Le son envoyé tel quel à l'ampli (Dolby en
 * HDMI) n'est pas décodé par l'application : il n'est pas modifié.
 */
@OptIn(UnstableApi::class)
class AdVolumeAudioProcessor(private val control: AdVolumeControl) : BaseAudioProcessor() {

    private var guard = AdVolumeGuard()
    private var gain = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (!control.enabled) {
            gain = 1.0
            control.attenuating = false
            output.put(inputBuffer)
            output.flip()
            return
        }
        val start = inputBuffer.position()
        val samples = size / 2
        var energy = 0.0
        for (i in 0 until samples) {
            val sample = inputBuffer.getShort(start + 2 * i) / 32_768.0
            energy += sample * sample
        }
        val frames = samples / inputAudioFormat.channelCount.coerceAtLeast(1)
        val millis = frames * 1_000L / inputAudioFormat.sampleRate.coerceAtLeast(1)
        val target = guard.process(energy / samples.coerceAtLeast(1), millis)
        control.attenuating = guard.attenuating
        // Passage progressif de l'ancien gain au nouveau sur le bloc : pas de claquement.
        for (i in 0 until samples) {
            val g = gain + (target - gain) * (i + 1) / samples
            val value = (inputBuffer.getShort(start + 2 * i) * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(value.toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        gain = target
        output.flip()
    }

    override fun onReset() {
        guard = AdVolumeGuard()
        gain = 1.0
        control.attenuating = false
    }
}
