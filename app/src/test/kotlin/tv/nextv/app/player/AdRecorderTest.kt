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

    /** Horloge des images (ms), au rythme du son joué. */
    private var clock = 0L

    private fun play(seconds: Double) {
        repeat((seconds * SAMPLE_RATE / BLOCK).toInt()) { block ->
            clock += BLOCK * 1_000L / SAMPLE_RATE
            if (block % 47 == 0) control.frame(ByteArray(AdVolumeControl.FRAME_WIDTH * AdVolumeControl.FRAME_HEIGHT) { (it % 200).toByte() }, clock)
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
            // Taille annoncée d'avance, exacte : le téléphone sait quand le fichier est complet.
            assertTrue(connection.contentLengthLong > 0)
            assertEquals(connection.contentLengthLong, zip.length())
            return ZipFile(zip).use { file -> file.entries().toList().associate { it.name to file.getInputStream(it).readBytes() } }
        }
    }

    @Test
    fun `a recording keeps the sound, the pictures and the decisions until it is stopped`() {
        control.prints = AdPrintStore(java.io.File(folder.newFolder("prints"), "library.bin"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), 10_000)
        control.newStream()
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
        val recorder = recordings.start("TF1 HD", control)!!
        assertNotNull(control.recordingMillis)
        play(70.0)
        // Pas de durée limite : toujours en cours.
        assertTrue(control.recordingMillis!! in 69_000..71_000)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        val file = recordings.files.value.single()
        assertTrue(file.name, file.name.startsWith("pubs_TF1-HD_") && file.name.endsWith(".zip"))
        val entries = download(recordings, file.name)
        assertEquals(setOf("info.txt", "levels.bin", "prints.bin", "frames.bin", "events.tsv"), entries.keys)
        assertTrue("chaine=TF1 HD" in entries.getValue("info.txt").decodeToString())
        // Une tranche de 20 ms (et un reste) par bloc de son, 6 octets chacune.
        val levels = entries.getValue("levels.bin")
        assertEquals(0, levels.size % 6)
        var total = 0L
        DataInputStream(levels.inputStream()).use { input ->
            repeat(levels.size / 6) {
                input.readFloat()
                total += input.readShort()
            }
        }
        assertTrue("durée $total", total in 69_000..71_000)
        // Empreintes du son (62,5 par seconde), 9 octets chacune, à la place du son lui-même.
        val prints = entries.getValue("prints.bin")
        assertEquals(0, prints.size % 9)
        assertTrue("empreintes ${prints.size / 9}", abs(prints.size / 9 - 70 * 62.5) < 300)
        // Images : petites images (J), coins du logo (C) une par seconde, vignette (T) toutes les 5 s.
        val types = HashMap<Char, Int>()
        DataInputStream(entries.getValue("frames.bin").inputStream()).use { input ->
            while (input.available() > 0) {
                val type = input.readByte().toInt().toChar()
                input.readLong()
                input.skipBytes(input.readInt())
                types[type] = (types[type] ?: 0) + 1
            }
        }
        assertTrue("$types", (types['C'] ?: 0) in 60..80 && (types['T'] ?: 0) in 12..17 && (types['J'] ?: 0) >= 60)
        // Taille écrite bien comptée (c'est elle qui fait couper les fichiers avant 30 Mo).
        val written = entries.filterKeys { it != "info.txt" }.values.sumOf { it.size }.toLong()
        assertTrue("comptés ${recorder.bytes}, écrits $written", abs(recorder.bytes - written) < 1_000)
        val events = entries.getValue("events.tsv").decodeToString()
        assertTrue(events, "chaine\tTF1 HD" in events)
        assertNull(control.recordingMillis)
    }

    @Test
    fun `the recording says what opened and closed each break`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
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
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
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
        val recordings = AdRecordings(folder.newFolder("internal"), "0.0.0-test", minMillis = 60_000, removable = {
            if (plugged) listOf(AdRecordings.Location("1234-ABCD", "Clé USB SanDisk", usb)) else emptyList()
        })
        assertEquals(listOf(null, "1234-ABCD"), recordings.locations().map { it.key })
        recordings.selected = "1234-ABCD"
        assertNotNull(recordings.start("TF1", control))
        play(62.0)
        recordings.stop()
        waitFor { recordings.files.value.isNotEmpty() }
        assertEquals("Clé USB SanDisk", recordings.files.value.single().location)
        assertTrue(usb.listFiles()!!.single().name.startsWith("pubs_TF1_"))
        assertEquals(setOf("info.txt", "levels.bin", "prints.bin", "frames.bin", "events.tsv"), download(recordings, recordings.files.value.single().name).keys)

        // Clé débranchée (vue à la relecture des clés, faite en arrière-plan) : rien ne commence, et l'appli dit pourquoi.
        plugged = false
        recordings.locations()
        assertNull(recordings.start("TF1", control))
        assertEquals("usb", recordings.refusal)
        assertNull(control.recordingMillis)
    }

    @Test
    fun `one recording can be deleted on its own, and a too short one is not kept`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
        recordings.start("TF1", control)
        play(62.0)
        recordings.stop()
        waitFor { recordings.files.value.size == 1 }
        val first = recordings.files.value.single().name
        Thread.sleep(1_100)
        control.newStream()
        recordings.start("M6", control)
        play(62.0)
        recordings.stop()
        waitFor { recordings.files.value.size == 2 }
        // Arrêté avant le minimum : pas gardé.
        control.newStream()
        val short = recordings.start("W9", control)!!
        play(30.0)
        recordings.stop()
        waitFor { !short.active }
        Thread.sleep(300)
        assertEquals(2, recordings.files.value.size)
        recordings.delete(first)
        assertEquals(1, recordings.files.value.size)
        assertTrue(recordings.files.value.single().name.startsWith("pubs_M6_"))
    }

    @Test
    fun `less than a minute is not kept`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
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
        java.io.File(directory, "pubs_TF1_2026-11-15_20-00.part/levels.bin").writeBytes(ByteArray(600_000))
        val recordings = AdRecordings(directory, "0.0.0-test")
        recordings.load()
        assertEquals(listOf("pubs_TF1_2026-11-15_20-00.zip"), recordings.files.value.map { it.name })
    }

    @Test
    fun `creating the recordings and starting one never touches the USB drives`() {
        // Sur la box, créer un dossier sur la clé USB depuis le fil principal a bloqué l'appli plus de 5 s (ANR à
        // l'ouverture des réglages) : seules les relectures faites en arrière-plan ([AdRecordings.locations]) y touchent.
        val usb = folder.newFolder("usb")
        var reads = 0
        val recordings = AdRecordings(folder.newFolder("internal"), "0.0.0-test", minMillis = 60_000, removable = {
            reads++
            listOf(AdRecordings.Location("1234-ABCD", "Clé USB SanDisk", usb))
        })
        recordings.selected = "1234-ABCD"
        assertEquals(listOf<String?>(null), recordings.knownLocations().map { it.key })
        // Clé pas encore relue : « débranchée », sans l'attendre.
        assertNull(recordings.start("TF1", control))
        assertEquals("usb", recordings.refusal)
        assertEquals(0, reads)
        // Relue en arrière-plan : l'enregistrement commence ensuite sans la relire.
        recordings.locations()
        assertEquals(1, reads)
        assertNotNull(recordings.start("TF1", control))
        assertEquals(1, reads)
        recordings.stop()
    }

    @Test
    fun `without the code, the phone gets nothing`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
        recordings.share(host = "127.0.0.1")!!.use { share ->
            val wrong = URL(share.address.substringBeforeLast('/').substringBeforeLast('/') + "/autre/x.zip").openConnection() as HttpURLConnection
            assertEquals(404, wrong.responseCode)
        }
    }

    @Test
    fun `music is sent from the phone to the device`() {
        val recordings = AdRecordings(folder.newFolder("recordings"), "0.0.0-test", minMillis = 60_000)
        val music = AdMusicLibrary(folder.newFolder("music"))
        recordings.share(host = "127.0.0.1", music = music)!!.use { share ->
            assertTrue(URL(share.address).openStream().use { it.readBytes().decodeToString() }.contains("Envoyer vers la télé"))
            fun put(name: String, bytes: ByteArray): Int {
                val connection = URL(share.address + "musique/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")).openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                return connection.responseCode
            }
            val song = ByteArray(300_000) { (it % 251).toByte() }
            assertEquals(200, put("Mon morceau prefere.mp3", song))
            // Pas un morceau de musique, ou un chemin : refusé.
            assertEquals(400, put("photo.jpg", ByteArray(10)))
            assertEquals(400, put("../../evil.mp3", ByteArray(0)))
            assertEquals(1, share.received)
        }
        val tracks = music.tracks()
        assertEquals(listOf("Mon morceau prefere.mp3"), tracks.map { it.name })
        assertEquals(300_000L, tracks.single().length())
        assertEquals(1, music.count.value)
        music.deleteUploads()
        assertEquals(0, music.count.value)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 1024
    }
}
