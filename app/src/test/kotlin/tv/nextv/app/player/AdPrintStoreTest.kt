package tv.nextv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.audio.AdPrintLibrary
import java.io.ByteArrayInputStream
import java.io.File

/** Bibliothèque de pubs fournie avec l'appli : ajoutée une fois à celle de l'appareil, puis gardée avec elle. */
class AdPrintStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Morceau de pub fictif : empreintes variées, toutes audibles. */
    private fun seedBytes(): ByteArray {
        val library = AdPrintLibrary()
        val random = java.util.Random(7)
        val prints = IntArray(800) { random.nextInt() }
        library.learn(prints, BooleanArray(prints.size) { true }, confirmed = false, now = 1)
        return library.encode()
    }

    private fun awaitSize(store: AdPrintStore, size: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (store.library.size != size && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `the bundled library is added once and kept`() {
        val bytes = seedBytes()
        var opened = 0
        val file = File(folder.root, "ad_prints/library.bin")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = AdPrintStore(file, scope, AdPrintLibrary.DEFAULT_MAX_PRINTS) { opened++; ByteArrayInputStream(bytes) }
        first.load()
        awaitSize(first, 1)
        assertEquals(1, first.library.size)
        assertTrue(File(file.parentFile, "seed.version").exists())
        // Lancement suivant : la mémoire de l'appareil la contient déjà, la bibliothèque n'est plus relue.
        Thread.sleep(200)
        val second = AdPrintStore(file, scope, AdPrintLibrary.DEFAULT_MAX_PRINTS) { opened++; ByteArrayInputStream(bytes) }
        second.load()
        awaitSize(second, 1)
        assertEquals(1, second.library.size)
        assertEquals(1, opened)
    }
}
