package tv.nextv.app.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Direct gardé pour revenir en arrière : mémoire en anneau, heures d'arrivée, disque, reprise de la connexion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveRecordingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun bytes(from: Int, count: Int) = ByteArray(count) { ((from + it) % 251).toByte() }

    @Test
    fun ringKeepsTheLastBytesAndTheirArrival() {
        val recording = LiveRecording(tailBytes = 100)
        // 10 morceaux de 30 octets, un toutes les secondes.
        repeat(10) { i -> recording.append(bytes(i * 30, 30), 0, 30, now = 1_000L * (i + 1)) }
        assertEquals(300, recording.total)
        // Seuls les 100 derniers octets restent en mémoire.
        assertEquals(200, recording.oldest())
        // Arrivé à 8 s : 240 octets ; trop ancien (plus en mémoire) : le plus ancien disponible.
        assertEquals(240, recording.positionAt(8_000))
        assertEquals(200, recording.positionAt(5_000))
        assertEquals(300, recording.positionAt(99_000))
        assertEquals(8_000, recording.timeAt(240))
    }

    @Test
    fun diskStartsWithTheBytesKeptInMemory() {
        val recording = LiveRecording(tailBytes = 100)
        recording.append(bytes(0, 250), 0, 250, now = 1_000)
        val disk = checkNotNull(recording.startDisk(File(folder.root, "shift.ts"), maxBytes = 10_000))
        assertEquals(150, recording.diskBase)
        recording.append(bytes(250, 50), 0, 50, now = 2_000)
        assertEquals(150L, disk.written)
        // 100 octets recopiés de la mémoire, puis les 50 reçus ensuite.
        val reader = disk.openReader()
        val read = ByteArray(150)
        var got = 0
        while (got < 150) got += disk.read(reader, got.toLong(), read, got, 150 - got)
        assertArrayEquals(bytes(150, 150), read)
        recording.stop()
    }

    @Test
    fun diskRefusedLeavesTheLiveUntouched() {
        val recording = LiveRecording(tailBytes = 100)
        recording.append(bytes(0, 250), 0, 250, now = 1_000)
        // Fichier impossible à créer (dossier absent : stockage refusé) : pas d'exception, rien sur le disque.
        assertNull(recording.startDisk(File(folder.root, "absent/shift.ts"), maxBytes = 10_000))
        assertNull(recording.disk)
        // Le direct continue d'être gardé en mémoire, et un nouvel essai reste possible.
        recording.append(bytes(250, 50), 0, 50, now = 2_000)
        assertEquals(300, recording.total)
        assertEquals(200, recording.oldest())
        val disk = checkNotNull(recording.startDisk(File(folder.root, "shift.ts"), maxBytes = 10_000))
        assertEquals(100L, disk.written)
        recording.stop()
    }

    @Test
    fun stoppedRecordingKeepsNothingOnDisk() {
        val recording = LiveRecording(tailBytes = 100)
        recording.append(bytes(0, 250), 0, 250, now = 1_000)
        recording.stop()
        val file = File(folder.root, "shift.ts")
        // Arrêt (autre chaîne, retour au direct) arrivé avant la copie : elle n'a pas lieu.
        assertNull(recording.startDisk(file, maxBytes = 10_000))
        assertNull(recording.disk)
        assertTrue("fichier laissé sur le disque", !file.exists())
    }

    @Test
    fun stopRemovesTheRecordingFromDisk() {
        val recording = LiveRecording(tailBytes = 100)
        recording.append(bytes(0, 250), 0, 250, now = 1_000)
        val file = File(folder.root, "shift.ts")
        checkNotNull(recording.startDisk(file, maxBytes = 10_000))
        assertTrue(file.exists())
        recording.stop()
        assertTrue("fichier laissé sur le disque", !file.exists())
        // Ce qui arrive encore après l'arrêt ne fait pas d'erreur.
        recording.append(bytes(250, 50), 0, 50, now = 2_000)
    }

    @Test
    fun theSameMemoryServesOneChannelAfterAnother() {
        // Un seul bloc de mémoire pour tous les directs (pas de nouveau bloc à chaque zapping).
        val ring = ByteArray(100)
        val first = LiveRecording(ring)
        first.append(bytes(0, 80), 0, 80, now = 1_000)
        // Autre chaîne : le direct précédent cède l'anneau avant que le nouveau n'y écrive.
        first.retire()
        val second = LiveRecording(ring)
        second.append(bytes(500, 60), 0, 60, now = 2_000)
        // Derniers octets du flux précédent, encore en train de se fermer : ils ne touchent plus à l'anneau.
        first.append(bytes(80, 40), 0, 40, now = 2_100)
        assertNull(first.startDisk(File(folder.root, "old.ts"), maxBytes = 10_000))
        val disk = checkNotNull(second.startDisk(File(folder.root, "shift.ts"), maxBytes = 10_000))
        val reader = disk.openReader()
        val read = ByteArray(60)
        var got = 0
        while (got < 60) got += disk.read(reader, got.toLong(), read, got, 60 - got)
        assertArrayEquals(bytes(500, 60), read)
        second.stop()
    }

    @Test
    fun aRecordingPluggedOnAnOpenStreamKeepsWhatComesNext() {
        // Aperçu de l'accueil : rien n'est gardé ; repris par le plein écran, la suite du flux l'est.
        var recording: LiveRecording? = null
        val upstream = FakeLive()
        val source = LiveRecordingDataSource(upstream) { recording }
        source.open(DataSpec(Uri.parse("http://direct.example/1.ts")))
        val buffer = ByteArray(100)
        upstream.feed(bytes(0, 40))
        assertEquals(40, source.read(buffer, 0, 100))
        val plugged = LiveRecording(tailBytes = 1_000)
        recording = plugged
        upstream.feed(bytes(40, 30))
        assertEquals(30, source.read(buffer, 0, 100))
        assertEquals(30, plugged.total)
        plugged.stop()
        source.close()
        assertTrue(upstream.closed)
    }

    @Test
    fun connectionContinuesAfterThePlayerLetsGo() {
        val recording = LiveRecording(tailBytes = 1_000)
        val upstream = FakeLive()
        val source = LiveRecordingDataSource(upstream) { recording }
        source.open(DataSpec(Uri.parse("http://direct.example/1.ts")))
        upstream.feed(bytes(0, 40))
        val buffer = ByteArray(100)
        assertEquals(40, source.read(buffer, 0, 100))
        recording.startDisk(File(folder.root, "shift.ts"), maxBytes = 10_000)
        recording.requestTakeOver()
        // Le lecteur qui s'arrête ne lit plus la connexion, et sa fermeture ne la coupe pas.
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 100))
        source.close()
        assertTrue("connexion fermée trop tôt", !upstream.closed)
        upstream.feed(bytes(40, 60))
        val deadline = System.currentTimeMillis() + 5_000
        while (recording.total < 100 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(100, recording.total)
        recording.stop()
        upstream.end()
        val closedBy = System.currentTimeMillis() + 5_000
        while (!upstream.closed && System.currentTimeMillis() < closedBy) Thread.sleep(10)
        assertTrue("connexion pas fermée à l'arrêt", upstream.closed)
    }

    /** Connexion du direct simulée : les morceaux arrivent quand le test les envoie. */
    private class FakeLive : BaseDataSource(true) {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var pending: ByteArray? = null
        private var at = 0

        @Volatile
        var closed = false

        fun feed(data: ByteArray) = chunks.put(data)
        fun end() = chunks.put(ByteArray(0))

        override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val current = pending ?: chunks.poll(5, TimeUnit.SECONDS)?.also { pending = it; at = 0 } ?: return C.RESULT_END_OF_INPUT
            if (current.isEmpty()) return C.RESULT_END_OF_INPUT
            val n = minOf(length, current.size - at)
            System.arraycopy(current, at, buffer, offset, n)
            at += n
            if (at == current.size) pending = null
            return n
        }

        override fun getUri(): Uri? = null

        override fun close() {
            closed = true
        }
    }
}
