package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nextv.core.audio.AdVolumeGuard
import tv.nextv.core.audio.LogoState
import tv.nextv.core.audio.LogoWatcher
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** Option « baisser le son des pubs », partagée entre le lecteur et le traitement du son. */
class AdVolumeControl {
    @Volatile
    var enabled: Boolean = false

    /** Pub détectée : son baissé (affiché par le lecteur). */
    @Volatile
    var attenuating: Boolean = false
        internal set

    /** Flux en cours de lecture : à chaque nouveau flux, la mesure du volume repart de zéro. */
    @Volatile
    var stream: Int = 0
        private set

    /** Logo de la chaîne à l'image ([frame]) : il disparaît pendant les pubs et revient avec le programme. */
    @Volatile
    var logo: LogoState = LogoState.UNKNOWN
        private set

    private val logoWatcher = LogoWatcher(FRAME_WIDTH, FRAME_HEIGHT)

    /** Nouveau flux (autre chaîne, autre version…) : les silences et le logo du précédent ne comptent plus. */
    fun newStream() {
        stream++
        attenuating = false
        synchronized(logoWatcher) {
            logoWatcher.reset()
            logo = LogoState.UNKNOWN
        }
    }

    /**
     * Image affichée par le lecteur, une fois par seconde environ : [FRAME_WIDTH] × [FRAME_HEIGHT] points,
     * en niveaux de gris, ligne par ligne. Appelé hors du fil principal.
     */
    fun frame(gray: ByteArray) {
        synchronized(logoWatcher) { logo = logoWatcher.frame(gray) }
    }

    companion object {
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 270
    }
}

/**
 * Applique [AdVolumeGuard] au son décodé (PCM 16 bits). Le son envoyé tel quel à l'ampli (Dolby en
 * HDMI) n'est pas décodé par l'application : il n'est pas modifié.
 */
@OptIn(UnstableApi::class)
class AdVolumeAudioProcessor(private val control: AdVolumeControl) : BaseAudioProcessor() {

    private var guard = AdVolumeGuard()
    private var gain = 1.0
    private var stream = control.stream

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (stream != control.stream) {
            // Nouveau flux dans le même lecteur : mesure remise à zéro.
            stream = control.stream
            guard = AdVolumeGuard()
            gain = 1.0
        }
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
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        // Mesure par tranches de 20 ms : le silence entre deux pubs dure quelques dixièmes de seconde.
        val slice = (sampleRate / 50).coerceAtLeast(1) * channels
        guard.logo = control.logo
        var target = gain
        var offset = 0
        while (offset < samples) {
            val count = min(slice, samples - offset)
            var energy = 0.0
            for (i in offset until offset + count) {
                val sample = inputBuffer.getShort(start + 2 * i) / 32_768.0
                energy += sample * sample
            }
            target = guard.process(energy / count, (count / channels * 1_000L / sampleRate).coerceAtLeast(1))
            offset += count
        }
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
