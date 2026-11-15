package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin

/**
 * Enregistrement pour l'analyse des pubs : branché sur le traitement du son comme dans le lecteur, il produit un `.zip`
 * relisible par `AdDeviceReplayTool`, que le petit serveur du réseau local donne au téléphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val control = AdVolumeControl().apply { enabled = true }
    private val processor = AdVolumeAudioProcessor(control).apply {
        configure(AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))
        flush()
    }
    private var phase = 0

    private fun play(seconds: Double) {
        repeat((seconds * SAMPLE_RATE / BLOCK).toInt()) { block ->
            if (block % 47 == 0) control.frame(ByteArray(AdVolumeControl.FRAME_WIDTH * AdVolumeControl.FRAME_HEIGHT) { (it % 200).toByte() })
            val input = ByteBuffer.allocateDirect(BLOCK * 4).order(ByteOrder.nativeOrder())
            repeat(BLOCK) {
                val sample = (8_000 * sin(phase++ * 0.05)).toInt().toShort()
                input.putShort(sample).putShort(sample)
            }
            input.flip()
            processor.queueInput(input)
            processor.output
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test
    fun `a recording keeps the sound, the pictures and the decisions in one zip`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        assertNotNull(recordings.start("TF1 HD", control))
        assertNotNull(control.recordingMillis)
        play(70.0)
        assertTrue(control.recordingMillis!! >= 69_000)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        val file = recordings.files.value.single()
        assertTrue(file.name, file.name.startsWith("pubs_TF1-HD_"))
        ZipFile(recordings.file(file.name)!!).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("info.txt", "levels.bin", "audio.ulaw", "frames.bin", "events.tsv"), names)
            val info = zip.getInputStream(zip.getEntry("info.txt")).readBytes().decodeToString()
            assertTrue(info, "chaine=TF1 HD" in info)
            // Une tranche de 20 ms (et un reste) par bloc de son, 8 octets chacune.
            val levels = zip.getInputStream(zip.getEntry("levels.bin")).readBytes()
            assertEquals(0, levels.size % 8)
            var total = 0L
            var samples = 0
            DataInputStream(levels.inputStream()).use { input ->
                repeat(levels.size / 8) {
                    input.readFloat()
                    total += input.readShort()
                    samples += input.readShort()
                }
            }
            assertTrue("durée $total", total in 60_000..71_000)
            // Son à 8 000 Hz (48 000 / 6), un octet par échantillon.
            val audio = zip.getInputStream(zip.getEntry("audio.ulaw")).readBytes()
            assertEquals(samples, audio.size)
            assertTrue("échantillons ${audio.size}", abs(audio.size - 70 * 8_000) < 8_000)
            val frames = zip.getInputStream(zip.getEntry("frames.bin")).readBytes()
            assertTrue(frames.size > 70 * 5_000)
            assertTrue(zip.getInputStream(zip.getEntry("events.tsv")).readBytes().decodeToString().contains("frequence\t48000"))
        }
        assertNull(control.recordingMillis)
    }

    @Test
    fun `a new channel ends the recording`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        recordings.start("M6", control)
        play(5.0)
        control.newStream()
        assertNull(control.recordingMillis)
        // Moins d'une minute : rien n'est gardé.
        waitFor { recordings.current?.active == false }
        Thread.sleep(200)
        assertTrue(recordings.files.value.isEmpty())
    }

    @Test
    fun `the phone downloads the recordings from the local address`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        recordings.start("France 2", control)
        play(62.0)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        // Ici, pas toujours de réseau local : l'adresse de la machine elle-même.
        val share = recordings.share(host = "127.0.0.1")
        assertNotNull(share)
        share!!.use {
            // Même machine : l'adresse locale de l'appareil, avec le port et le code.
            val page = URL(share.address).openStream().use { it.readBytes().decodeToString() }
            val name = recordings.files.value.single().name
            assertTrue(page, name in page)
            val connection = URL(share.address + name).openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertEquals(recordings.files.value.single().bytes, connection.inputStream.use { it.readBytes() }.size.toLong())
            // Sans le code : rien.
            val wrong = URL(share.address.substringBeforeLast('/').substringBeforeLast('/') + "/autre/" + name).openConnection() as HttpURLConnection
            assertEquals(404, wrong.responseCode)
        }
    }

    @Test
    fun `ulaw keeps quiet sounds and silence`() {
        assertEquals(0xFF.toByte(), AdRecorder.ulaw(0))
        // Plus fort : code plus petit (µ-law est inversé), dans l'ordre.
        val codes = listOf(0, 10, 100, 1_000, 10_000, 32_000).map { AdRecorder.ulaw(it).toInt() and 0x7F }
        assertEquals(codes.sortedDescending(), codes)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 1024
    }
}
