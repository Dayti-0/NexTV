package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nextv.core.audio.AdMarks
import tv.nextv.core.audio.AdVolumeGuard
import tv.nextv.core.audio.LogoState
import tv.nextv.core.audio.LogoWatcher
import tv.nextv.core.audio.SmallFrame
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** Ce que « baisser le son des pubs » peut observer du flux en cours (panneau « Infos de lecture »). */
enum class AdWatch {
    /** Le son n'est pas décodé par l'application (envoyé tel quel à l'ampli) : rien n'est mesuré ni baissé. */
    NO_SOUND,

    /** L'appareil ne laisse pas lire l'image : détection au son seul, plus prudente et plus lente. */
    NO_PICTURE,

    /** Image lue, logo de la chaîne pas encore connu. */
    LEARNING,
    LOGO_VISIBLE,
    LOGO_HIDDEN,

    /** Page de pubs détectée : son baissé. */
    LOWERED,
}

/** Option « baisser le son des pubs », partagée entre le lecteur et le traitement du son. */
class AdVolumeControl {
    @Volatile
    var enabled: Boolean = false

    /** Pub détectée : son baissé (affiché par le lecteur). */
    @Volatile
    var attenuating: Boolean = false
        internal set

    /** Logo de la chaîne à l'image ([frame]) : il disparaît pendant les pubs et revient avec le programme. */
    @Volatile
    var logo: LogoState = LogoState.UNKNOWN
        private set

    /**
     * Détection du flux en cours : le traitement du son lui donne le son, le lecteur les images. Remplacée à
     * chaque nouveau flux ([newStream]).
     */
    @Volatile
    var guard: AdVolumeGuard = AdVolumeGuard()
        private set

    private val logoWatcher = LogoWatcher(FRAME_WIDTH, FRAME_HEIGHT)
    private val small = ByteArray(SmallFrame.SIZE)
    private var logoFrameAt = NEVER

    @Volatile
    private var pictureAt = NEVER

    @Volatile
    private var soundAt = NEVER

    /**
     * Images rapprochées demandées (une dizaine par seconde au lieu d'une) : un silence est en cours, et c'est
     * l'image de ce moment qui dit s'il sépare deux pubs.
     */
    val wantsFrames: Boolean get() = enabled && guard.wantsFrames

    /**
     * Nouveau flux (autre chaîne, autre version…) : les silences et le logo du précédent ne comptent plus.
     * @param marks ce qui a déjà été appris de ce flux (logo, carton entre deux pubs), connu d'emblée.
     */
    fun newStream(marks: AdMarks? = null) {
        attenuating = false
        synchronized(logoWatcher) {
            logoWatcher.reset()
            marks?.logo?.let(logoWatcher::restore)
            logo = logoWatcher.state
            logoFrameAt = NEVER
            pictureAt = NEVER
            soundAt = NEVER
            guard = AdVolumeGuard().also {
                it.separator = marks?.separator
                it.logoFollowsBreaks = marks?.logoFollowsBreaks == true
                it.logo = logo
            }
        }
    }

    /** Ce qui a été appris de ce flux, à garder pour sa prochaine ouverture. */
    fun marks(): AdMarks = synchronized(logoWatcher) { AdMarks(logoWatcher.export(), guard.separator, guard.logoFollowsBreaks) }

    /**
     * Image affichée par le lecteur : [FRAME_WIDTH] × [FRAME_HEIGHT] points, en niveaux de gris, ligne par ligne.
     * Une par seconde, et une dizaine par seconde tant que [wantsFrames] le demande. Appelé hors du fil principal.
     */
    fun frame(gray: ByteArray, nowMillis: Long = System.nanoTime() / 1_000_000) {
        synchronized(logoWatcher) {
            pictureAt = nowMillis
            val guard = guard
            // Changements de plan : toutes les images. Logo : une par seconde suffit.
            if (SmallFrame.reduce(gray, FRAME_WIDTH, FRAME_HEIGHT, small)) guard.frame(small)
            if (logoFrameAt == NEVER || nowMillis - logoFrameAt >= LOGO_PERIOD_MILLIS) {
                logoFrameAt = nowMillis
                logo = logoWatcher.frame(gray)
                guard.logo = logo
            }
        }
    }

    internal fun heard(nowMillis: Long = System.nanoTime() / 1_000_000) {
        soundAt = nowMillis
    }

    /** Ce que la détection observe en ce moment ; `null` si l'option est désactivée. */
    fun watch(nowMillis: Long = System.nanoTime() / 1_000_000): AdWatch? = when {
        !enabled -> null
        soundAt == NEVER || nowMillis - soundAt > STALE_MILLIS -> AdWatch.NO_SOUND
        attenuating -> AdWatch.LOWERED
        pictureAt == NEVER || nowMillis - pictureAt > STALE_MILLIS -> AdWatch.NO_PICTURE
        logo == LogoState.VISIBLE -> AdWatch.LOGO_VISIBLE
        logo == LogoState.HIDDEN -> AdWatch.LOGO_HIDDEN
        else -> AdWatch.LEARNING
    }

    companion object {
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 270
        private const val NEVER = Long.MIN_VALUE
        private const val LOGO_PERIOD_MILLIS = 900L
        private const val STALE_MILLIS = 5_000L
    }
}

/**
 * Applique [AdVolumeGuard] au son décodé (PCM 16 bits). Le son envoyé tel quel à l'ampli (Dolby en
 * HDMI) n'est pas décodé par l'application : il n'est pas modifié.
 */
@OptIn(UnstableApi::class)
class AdVolumeAudioProcessor(private val control: AdVolumeControl) : BaseAudioProcessor() {

    private var guard: AdVolumeGuard? = null
    private var gain = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val guard = control.guard
        if (guard !== this.guard) {
            // Nouveau flux dans le même lecteur : mesure remise à zéro.
            this.guard = guard
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
        control.heard()
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
        guard = null
        gain = 1.0
        control.attenuating = false
    }
}
