package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import tv.nextv.core.audio.AdMarks
import tv.nextv.core.audio.AudioFingerprinter
import tv.nextv.core.audio.LogoState
import tv.nextv.core.audio.LogoWatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * « Baisser le son des pubs » tel que le lecteur le fait tourner : le son décodé par blocs AAC de 1024 échantillons,
 * et l'image de l'écran (480 × 270) une fois par seconde, plus souvent pendant un silence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdVolumeAudioProcessorTest {

    private val random = Random(5)
    private val control = AdVolumeControl().apply { enabled = true }
    private val processor = AdVolumeAudioProcessor(control).apply {
        configure(AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))
        flush()
    }
    private var phase = 0
    private var block = 0

    /** Image à l'écran ; `null` : l'appareil ne laisse pas lire l'image. */
    private var picture: ByteArray? = null

    private val now: Long get() = block * 1000L * BLOCK / SAMPLE_RATE

    /** Un plan : grands pavés gris tirés au hasard. */
    private fun scene(): ByteArray {
        val columns = WIDTH / 40
        val levels = IntArray(columns * (HEIGHT / 40 + 1)) { 20 + random.nextInt(200) }
        return ByteArray(WIDTH * HEIGHT) { i -> levels[(i / WIDTH) / 40 * columns + (i % WIDTH) / 40].toByte() }
    }

    /** Carton de la chaîne entre deux pubs : un fond uni, son logo au milieu. */
    private fun card() = ByteArray(WIDTH * HEIGHT) { i -> (if (i % WIDTH in 200..280 && i / WIDTH in 110..160) 230 else 60).toByte() }

    /** [seconds] de son (ou de silence), par blocs de 1024 échantillons stéréo ; rend le plus fort échantillon du dernier bloc. */
    private fun play(seconds: Double, silent: Boolean = false): Int {
        var peak = 0
        repeat((seconds * SAMPLE_RATE / BLOCK).toInt()) {
            // L'écran : une image par seconde, et une tous les dixièmes de seconde quand la détection les demande.
            if (block % 47 == 0 || (control.wantsFrames && block % 5 == 0)) picture?.let { control.frame(it, now) }
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
            block++
        }
        return peak
    }

    /** Fin d'une pub : une demi-seconde de silence sur le carton de la chaîne, puis la pub suivante. */
    private fun nextSpot(card: Boolean = true) {
        if (picture != null) picture = if (card) card() else scene()
        play(0.5, silent = true)
        if (picture != null) picture = scene()
    }

    private fun assertFullVolume(peak: Int) = assertEquals(AMPLITUDE.toDouble(), peak.toDouble(), 2.0)

    @Test
    fun `the channel card between two spots turns the sound down, the end of the spots brings it back`() {
        picture = scene()
        assertFullVolume(play(60.0))
        nextSpot()
        assertFullVolume(play(20.0)) // une seule jonction : pas encore une page de pubs
        nextSpot()
        val lowered = play(20.0)
        assertTrue(control.attenuating)
        assertEquals(AdWatch.LOWERED, control.watch(now))
        assertTrue("son presque au minimum : $lowered", lowered < AMPLITUDE / 10)
        nextSpot()
        assertTrue(play(20.0) < AMPLITUDE / 10)

        // Programme : plus de jonction.
        assertFullVolume(play(60.0))
        assertFalse(control.attenuating)
    }

    @Test
    fun `silences without a change of picture are the programme`() {
        picture = scene()
        play(60.0)
        repeat(6) {
            play(0.5, silent = true)
            assertFullVolume(play(20.0))
        }
        assertFalse(control.attenuating)
        assertEquals(AdWatch.LEARNING, control.watch(now))
    }

    @Test
    fun `without a readable picture, silences spaced like spots still turn the sound down`() {
        assertFullVolume(play(60.0))
        repeat(3) {
            play(0.42, silent = true)
            assertFullVolume(play(20.0))
        }
        play(0.42, silent = true)
        val lowered = play(20.0)
        assertTrue(control.attenuating)
        assertTrue(lowered < AMPLITUDE / 10)
        assertFullVolume(play(45.0))
        assertEquals(AdWatch.NO_PICTURE, control.watch(now))
    }

    @Test
    fun `option off, nothing is measured or changed`() {
        control.enabled = false
        picture = scene()
        repeat(4) {
            nextSpot()
            assertFullVolume(play(20.0))
        }
        assertFalse(control.attenuating)
        assertFalse(control.wantsFrames)
        assertNull(control.watch(now))
    }

    @Test
    fun `sound that the app does not decode is said so`() {
        // Son envoyé tel quel à l'ampli : le traitement ne reçoit rien.
        assertEquals(AdWatch.NO_SOUND, control.watch(now))
    }

    @Test
    fun `a new stream forgets the logo and the spots of the previous channel`() {
        picture = scene()
        play(30.0)
        nextSpot()
        play(15.0)
        nextSpot()
        play(2.0)
        assertTrue(control.attenuating)
        control.newStream()
        assertFalse(control.attenuating)
        assertEquals(LogoState.UNKNOWN, control.logo)
        assertFullVolume(play(5.0))
    }

    @Test
    fun `what a channel taught is known again when it is reopened`() {
        // Logo appris pendant une première lecture (un cadre clair en haut à droite, sur une image qui change).
        val watcher = LogoWatcher(WIDTH, HEIGHT, learnFrames = 20)
        repeat(45) { watcher.frame(scene().withLogo()) }
        val marks = AdMarks(watcher.export(), separator = null, logoFollowsBreaks = true)

        control.newStream(marks)
        assertEquals("logo connu, pas encore revu", LogoState.HIDDEN, control.logo)
        assertTrue(control.guard.logoFollowsBreaks)
        var at = 0L
        repeat(3) {
            control.frame(scene().withLogo(), at)
            at += 1_000
        }
        assertEquals(LogoState.VISIBLE, control.logo)
        assertEquals(marks.logo!!.size, control.marks().logo!!.size)
    }

    @Test
    fun `a break announced by the stream turns the sound down at once, its end brings it back`() {
        picture = scene()
        assertFullVolume(play(10.0))
        assertEquals(0, control.details()!!.cues)
        // Message SCTE-35 : page de pub dans 2 s, pour 30 s (positions de lecture, ici l'horloge du son).
        control.cues.splice(out = true, atMs = now + 2_000, durationMs = 30_000)
        control.cueAt(now)
        assertFullVolume(play(1.0))
        control.cueAt(now + 1_500)
        val lowered = play(2.0)
        assertTrue(control.attenuating)
        assertTrue("son baissé : $lowered", lowered < AMPLITUDE / 10)
        assertTrue(control.details()!!.cueBreak)
        // Fin annoncée : le son revient, sans attendre le logo ni la fin des jonctions.
        control.cueAt(now + 40_000)
        play(1.5)
        assertFalse(control.attenuating)
        assertFullVolume(play(0.5))
    }

    @Test
    fun `an ad already heard on another channel is recognized within seconds`() {
        val store = AdPrintStore(File.createTempFile("prints", ".bin").also { it.delete() }, CoroutineScope(Dispatchers.Unconfined), 100_000)
        val spot = voice(seed = 3, seconds = 12.0)
        // Apprise ailleurs (page de pubs annoncée par le flux d'une autre chaîne).
        val fingerprinter = AudioFingerprinter(SAMPLE_RATE)
        val prints = ArrayList<Int>()
        val loud = ArrayList<Boolean>()
        fingerprinter.feed(FloatArray(spot.size) { spot[it] / 32_768f }, spot.size) { print, audible ->
            prints += print
            loud += audible
        }
        assertTrue(store.library.learn(prints.toIntArray(), loud.toBooleanArray(), confirmed = true, now = 1) > 0)

        control.prints = store
        control.newStream()
        picture = scene()
        playSamples(voice(seed = 4, seconds = 20.0))
        assertFalse("programme baissé", control.attenuating)
        val loweredAfter = playSamples(spot, stopWhenLowered = true)
        assertTrue("baissé après $loweredAfter s", loweredAfter in 0.0..3.5)
        assertTrue(control.details()!!.recognizedCount >= 1)
    }

    /** Son « parlé » (notes et souffle qui changent tous les dixièmes de seconde), 16 bits. */
    private fun voice(seed: Int, seconds: Double): ShortArray {
        val random = Random(seed)
        val steps = (seconds * 10).toInt() + 1
        val notes = DoubleArray(steps) { 200.0 + random.nextDouble() * 2_500 }
        val levels = DoubleArray(steps) { 0.2 + random.nextDouble() }
        return ShortArray((seconds * SAMPLE_RATE).toInt()) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            val step = (t * 10).toInt()
            val tone = sin(2 * PI * notes[step] * t) + 0.5 * sin(2 * PI * notes[step] * 1.5 * t)
            (AMPLITUDE * 0.5 * (tone * levels[step] + (random.nextDouble() - 0.5) * 0.3)).toInt().toShort()
        }
    }

    /** Joue [samples] (mono, envoyé sur les deux canaux) ; rend le temps (s) au bout duquel le son a été baissé, ou −1. */
    private fun playSamples(samples: ShortArray, stopWhenLowered: Boolean = false): Double {
        var i = 0
        while (i + BLOCK <= samples.size) {
            if (block % 47 == 0 || (control.wantsFrames && block % 5 == 0)) picture?.let { control.frame(it, now) }
            val input = ByteBuffer.allocateDirect(BLOCK * 4).order(ByteOrder.nativeOrder())
            for (k in i until i + BLOCK) input.putShort(samples[k]).putShort(samples[k])
            input.flip()
            processor.queueInput(input)
            processor.output
            block++
            i += BLOCK
            if (stopWhenLowered && control.attenuating) return i / SAMPLE_RATE.toDouble()
        }
        return -1.0
    }

    private fun ByteArray.withLogo() = apply {
        for (y in 0 until 20) for (x in 0 until 28) {
            if (x < 3 || x >= 25 || y < 3 || y >= 17) {
                val i = (20 + y) * WIDTH + WIDTH - 60 + x
                this[i] = (((this[i].toInt() and 0xFF) + 255) / 2).toByte()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 1024
        const val AMPLITUDE = 8_000
        const val WIDTH = AdVolumeControl.FRAME_WIDTH
        const val HEIGHT = AdVolumeControl.FRAME_HEIGHT
    }
}
