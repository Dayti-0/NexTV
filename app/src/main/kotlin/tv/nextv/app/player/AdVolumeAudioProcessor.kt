package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import tv.nextv.core.audio.AdCues
import tv.nextv.core.audio.AdMarks
import tv.nextv.core.audio.AdPrintWatch
import tv.nextv.core.audio.AdVolumeGuard
import tv.nextv.core.audio.CueState
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

/**
 * Renforts de la détection des pubs, affichés dans les infos de lecture : ce que dit le flux, ce que l'appareil a appris.
 *
 * @property cues repères de pub lus dans le flux (0 : il n'en porte pas, ou pas encore vu).
 * @property unreadableCues messages SCTE-35 d'un type que l'application ne lit pas encore.
 * @property cueBreak le flux annonce une page de pub en ce moment.
 * @property knownSeconds durée des pubs déjà apprises (toutes chaînes).
 * @property recognized une pub connue passe en ce moment.
 * @property recognizedCount pubs connues reconnues depuis l'ouverture de la chaîne.
 */
data class AdDetails(
    val cues: Int,
    val unreadableCues: Int,
    val cueBreak: Boolean,
    val knownSeconds: Int,
    val recognized: Boolean,
    val recognizedCount: Int,
)

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

    /** Pubs déjà entendues, communes à toutes les chaînes (absentes pour l'aperçu de l'accueil). */
    @Volatile
    var prints: AdPrintStore? = null

    /** Pages de pub annoncées par le flux en cours (repères HLS ou SCTE-35), lues par le lecteur. */
    @Volatile
    var cues: AdCues = AdCues()
        private set

    /** Reconnaissance des pubs déjà entendues pour le flux en cours, branchée sur [guard]. */
    @Volatile
    var printWatch: AdPrintWatch? = null
        private set

    /**
     * Musique pendant les pubs (radio ou morceaux de l'appareil) : le son de la chaîne est alors coupé, plus seulement
     * baissé, pendant la page de pubs.
     */
    @Volatile
    var muteAds: Boolean = false

    /** « Ce n'est pas une pub » : le son revient, et pas de nouvelle page de pubs pendant quelques minutes. */
    fun dismissBreak() {
        guard.dismiss()
        attenuating = guard.attenuating
    }

    /** Enregistrement pour l'analyse des pubs en cours ([AdRecordings]) : reçoit le son, les images et les décisions. */
    @Volatile
    var recorder: AdRecorder? = null
        private set

    private var recordedLogo: LogoState? = null
    private var recordedAttenuating = false
    private var recordedCue: CueState? = null

    /** Branche un enregistrement pour l'analyse ; il continue d'un flux à l'autre ([newStream]) jusqu'à [stopRecording]. */
    fun record(recorder: AdRecorder, channel: String? = null) {
        this.recorder?.finish()
        recordedLogo = null
        recordedCue = null
        recordedAttenuating = false
        this.recorder = recorder
        guard.onEvent = eventSink
        channel?.let { recorder.event(guard.now, "chaine", it) }
    }

    /** Ce que la détection voit et décide (ce qui ouvre ou ferme une page de pubs…), pour l'enregistrement d'analyse. */
    private val eventSink: (Long, String, String) -> Unit = { time, what, detail -> recorder?.takeIf { it.active }?.event(time, what, detail) }

    /** Chaîne en cours, notée dans l'enregistrement d'analyse (après un changement de chaîne). */
    fun noteChannel(name: String) {
        recorder?.takeIf { it.active }?.event(guard.now, "chaine", name)
    }

    /** Arrête l'enregistrement d'analyse en cours (lecteur fermé). */
    fun stopRecording() {
        recorder?.finish()
        recorder = null
    }

    /** Durée de l'enregistrement d'analyse en cours (ms) ; `null` sans enregistrement. */
    val recordingMillis: Long? get() = recorder?.takeIf { it.active }?.recordedMillis

    /** Ce que la détection décide, pour l'enregistrement d'analyse (appelé depuis le fil du son). */
    internal fun recordDecision() {
        val recorder = recorder?.takeIf { it.active } ?: return
        val guard = guard
        if (guard.attenuating != recordedAttenuating) {
            recordedAttenuating = guard.attenuating
            recorder.event(guard.now, "baisse", if (recordedAttenuating) "1" else "0")
        }
    }

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
        // L'enregistrement d'analyse continue à la suite : la détection repart de zéro, il le note.
        recorder?.takeIf { it.active }?.newStream()
        recordedLogo = null
        recordedCue = null
        recordedAttenuating = false
        synchronized(logoWatcher) {
            logoWatcher.reset()
            marks?.logo?.let(logoWatcher::restore)
            logo = logoWatcher.state
            logoFrameAt = NEVER
            pictureAt = NEVER
            soundAt = NEVER
            cues = AdCues()
            val fresh = AdVolumeGuard().also {
                it.onEvent = eventSink
                it.separator = marks?.separator
                it.logoFollowsBreaks = marks?.logoFollowsBreaks == true
                it.logo = logo
            }
            printWatch = prints?.let { store ->
                store.load()
                AdPrintWatch(store.library, fresh, learner = store::learn, forget = store::forget)
            }
            guard = fresh
        }
    }

    /** Repères du flux à la position de lecture [positionMs] (temps de la période du lecteur), donnés à la détection. */
    fun cueAt(positionMs: Long) {
        val state = cues.state(positionMs)
        guard.cue = state
        val recorder = recorder?.takeIf { it.active } ?: return
        if (state != recordedCue) {
            recordedCue = state
            recorder.event(guard.now, "reperes", state.name)
        }
    }

    /** Renforts de la détection, pour les infos de lecture ; `null` si l'option est désactivée. */
    fun details(): AdDetails? {
        if (!enabled) return null
        val cues = cues
        val watch = printWatch
        return AdDetails(
            cues = cues.count,
            unreadableCues = cues.unreadable,
            cueBreak = guard.cue == CueState.BREAK,
            knownSeconds = prints?.library?.seconds ?: 0,
            recognized = watch?.recognized == true,
            recognizedCount = watch?.matches ?: 0,
        )
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
            val recorder = recorder?.takeIf { it.active }
            // Changements de plan : toutes les images. Logo : une par seconde suffit.
            if (SmallFrame.reduce(gray, FRAME_WIDTH, FRAME_HEIGHT, small)) {
                guard.frame(small)
                recorder?.small(small, guard.now)
            }
            if (logoFrameAt == NEVER || nowMillis - logoFrameAt >= LOGO_PERIOD_MILLIS) {
                logoFrameAt = nowMillis
                recorder?.logoFrame(gray, FRAME_WIDTH, FRAME_HEIGHT, guard.now)
                logo = logoWatcher.frame(gray)
                guard.logo = logo
                if (recorder != null && logo != recordedLogo) {
                    recordedLogo = logo
                    recorder.event(guard.now, "logo", logo.name)
                }
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

    /** Reste (millièmes d'échantillon × fréquence) des durées en millisecondes données à la détection. */
    private var carry = 0L

    /** Son de la tranche en cours ramené en mono, pour les empreintes. */
    private var mono = FloatArray(0)

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
            carry = 0
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
        val watch = control.printWatch?.takeIf { it.guard === guard }
        val recorder = control.recorder?.takeIf { it.active }
        if ((watch != null || recorder != null) && mono.size < slice / channels + 1) mono = FloatArray(slice / channels + 1)
        while (offset < samples) {
            val count = min(slice, samples - offset)
            var energy = 0.0
            for (i in offset until offset + count) {
                val sample = inputBuffer.getShort(start + 2 * i) / 32_768.0
                energy += sample * sample
            }
            // Durée exacte : le reste en millisecondes est reporté (sinon l'horloge prenait 1,5 % de retard).
            carry += count / channels * 1_000L
            val duration = carry / sampleRate
            carry -= duration * sampleRate
            target = guard.process(energy / count, duration)
            if (watch != null || recorder != null) {
                // Empreintes du son (pubs déjà entendues), enregistrement d'analyse : moyenne des canaux.
                val frames = count / channels
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += inputBuffer.getShort(start + 2 * (offset + f * channels + c)).toInt()
                    mono[f] = sum / (32_768f * channels)
                }
                watch?.feed(mono, frames, sampleRate, guard.now)
                recorder?.sound(energy / count, duration, mono, frames, sampleRate, guard.now)
            }
            offset += count
        }
        // Musique pendant les pubs : le son de la chaîne s'efface complètement (même fondu que la baisse).
        if (control.muteAds) target = ((target - MIN_GAIN) / (1 - MIN_GAIN)).coerceIn(0.0, 1.0)
        control.attenuating = guard.attenuating
        control.heard()
        if (recorder != null) control.recordDecision()
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

    private companion object {
        /** Gain le plus bas de la détection (−24 dB) : ramené à zéro quand la musique remplace la pub. */
        val MIN_GAIN = Math.pow(10.0, -24.0 / 20)
    }
}
