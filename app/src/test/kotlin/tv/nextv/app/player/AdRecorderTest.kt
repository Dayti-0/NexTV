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

    /** Télécharge l'enregistrement [name] comme le téléphone (zip fabriqué à la volée) ; rend ses fichiers. */
    private fun download(recordings: AdRecordings, name: String): Map<String, ByteArray> {
        val share = recordings.share(host = "127.0.0.1")!!
        share.use {
            val page = URL(share.address).openStream().use { it.readBytes().decodeToString() }
            assertTrue(page, name in page)
            val connection = URL(share.address + name).openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            val zip = folder.newFile()
            connection.inputStream.use { input -> zip.outputStream().use { input.copyTo(it) } }
            return ZipFile(zip).use { file -> file.entries().toList().associate { it.name to file.getInputStream(it).readBytes() } }
        }
    }

    @Test
    fun `a recording keeps the sound, the pictures and the decisions until it is stopped`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        assertNotNull(recordings.start("TF1 HD", control))
        assertNotNull(control.recordingMillis)
        play(70.0)
        // Pas de durée limite : toujours en cours.
        assertTrue(control.recordingMillis!! in 69_000..71_000)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        val file = recordings.files.value.single()
        assertTrue(file.name, file.name.startsWith("pubs_TF1-HD_") && file.name.endsWith(".zip"))
        val entries = download(recordings, file.name)
        assertEquals(setOf("info.txt", "levels.bin", "audio.ulaw", "frames.bin", "events.tsv"), entries.keys)
        assertTrue("chaine=TF1 HD" in entries.getValue("info.txt").decodeToString())
        // Une tranche de 20 ms (et un reste) par bloc de son, 8 octets chacune.
        val levels = entries.getValue("levels.bin")
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
        assertTrue("durée $total", total in 69_000..71_000)
        // Son à 8 000 Hz (48 000 / 6), un octet par échantillon.
        val audio = entries.getValue("audio.ulaw")
        assertEquals(samples, audio.size)
        assertTrue("échantillons ${audio.size}", abs(audio.size - 70 * 8_000) < 8_000)
        assertTrue(entries.getValue("frames.bin").size > 70 * 5_000)
        val events = entries.getValue("events.tsv").decodeToString()
        assertTrue(events, "frequence\t48000" in events)
        assertTrue(events, "chaine\tTF1 HD" in events)
        assertNull(control.recordingMillis)
    }

    @Test
    fun `the recording says what opened and closed each break`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        recordings.start("W9", control)
        play(10.0)
        // Page de pub annoncée par le flux, puis sa fin.
        control.cues.splice(out = true, atMs = 0, durationMs = 30_000)
        control.cueAt(control.guard.now)
        play(31.0)
        control.cueAt(control.guard.now + 60_000)
        play(25.0)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        val events = download(recordings, recordings.files.value.single().name).getValue("events.tsv").decodeToString()
        assertTrue(events, "ouverture\treperes du flux" in events)
        assertTrue(events, "fermeture\treperes du flux" in events)
        assertTrue(events, "baisse\t1" in events)
        assertTrue(events, "baisse\t0" in events)
        assertTrue(events, "reperes\tBREAK" in events)
    }

    @Test
    fun `changing channel keeps recording, marked, with the clock running on`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        recordings.start("M6", control)
        play(40.0)
        control.newStream()
        control.noteChannel("W9")
        assertNotNull(control.recordingMillis)
        play(30.0)
        assertTrue("durée ${control.recordingMillis}", control.recordingMillis!! in 69_000..71_000)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        val events = download(recordings, recordings.files.value.single().name).getValue("events.tsv").decodeToString()
        val stream = events.lines().first { "\tflux\t" in it }.substringBefore('\t').toLong()
        assertTrue("nouveau flux à $stream ms", stream in 39_000..41_000)
        val channel = events.lines().first { "\tchaine\tW9" in it }.substringBefore('\t').toLong()
        assertTrue(channel >= stream)
    }

    @Test
    fun `recordings go to the chosen USB drive, and say so when it is unplugged`() {
        val usb = folder.newFolder("usb")
        var plugged = true
        val recordings = AdRecordings(folder.newFolder("internal"), "0.0.0-test") {
            if (plugged) listOf(AdRecordings.Location("1234-ABCD", "Clé USB SanDisk", usb)) else emptyList()
        }
        assertEquals(listOf(null, "1234-ABCD"), recordings.locations().map { it.key })
        recordings.selected = "1234-ABCD"
        assertNotNull(recordings.start("TF1", control))
        play(62.0)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        assertEquals("Clé USB SanDisk", recordings.files.value.single().location)
        assertTrue(usb.listFiles()!!.single().name.startsWith("pubs_TF1_"))
        assertEquals(setOf("info.txt", "levels.bin", "audio.ulaw", "frames.bin", "events.tsv"), download(recordings, recordings.files.value.single().name).keys)

        // Clé débranchée : rien ne commence, et l'appli dit pourquoi.
        plugged = false
        assertNull(recordings.start("TF1", control))
        assertEquals("usb", recordings.refusal)
        assertNull(control.recordingMillis)
    }

    @Test
    fun `less than a minute is not kept`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        val recorder = recordings.start("M6", control)!!
        play(5.0)
        recordings.stop()
        waitFor { !recorder.active }
        Thread.sleep(300)
        assertTrue(recordings.files.value.isEmpty())
    }

    @Test
    fun `a recording cut short by the system is kept`() {
        val directory = folder.newFolder("recordings")
        java.io.File(directory, "pubs_TF1_2026-11-15_20-00.part").mkdirs()
        java.io.File(directory, "pubs_TF1_2026-11-15_20-00.part/audio.ulaw").writeBytes(ByteArray(600_000))
        val recordings = AdRecordings(directory, "0.0.0-test")
        assertEquals(listOf("pubs_TF1_2026-11-15_20-00.zip"), recordings.files.value.map { it.name })
    }

    @Test
    fun `without the code, the phone gets nothing`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test")
        recordings.share(host = "127.0.0.1")!!.use { share ->
            val wrong = URL(share.address.substringBeforeLast('/').substringBeforeLast('/') + "/autre/x.zip").openConnection() as HttpURLConnection
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
