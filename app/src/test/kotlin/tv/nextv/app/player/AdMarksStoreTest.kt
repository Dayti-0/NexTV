package tv.nextv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.audio.AdMarks
import java.io.File

class AdMarksStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val marks = AdMarks(logo = ByteArray(200) { it.toByte() }, separator = ByteArray(72) { 60 }, logoFollowsBreaks = true)

    private fun directory() = File(folder.root, "ad_marks")

    private fun awaitFile(): File {
        repeat(200) {
            directory().listFiles { file -> file.name.endsWith(".marks") }?.firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("repères jamais écrits")
    }

    @Test
    fun `marks are known at once, and still there after the app restarted`() {
        val store = AdMarksStore(directory(), scope)
        assertNull(store.load(ADDRESS))
        store.save(ADDRESS, marks)
        assertTrue("relus sans attendre l'écriture", marks.sameAs(store.load(ADDRESS)))
        awaitFile()

        val restarted = AdMarksStore(directory(), scope)
        val read = restarted.load(ADDRESS)!!
        assertArrayEquals(marks.logo, read.logo)
        assertArrayEquals(marks.separator, read.separator)
        assertTrue(read.logoFollowsBreaks)
        assertNull(restarted.load("$ADDRESS-autre"))
    }

    @Test
    fun `the stream address is never written, only a fingerprint of it`() {
        AdMarksStore(directory(), scope).save(ADDRESS, marks)
        val file = awaitFile()
        assertFalse(file.name.contains("identifiant"))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("identifiant"))
        assertTrue(file.name.matches(Regex("[0-9a-f]{32}\\.marks")))
    }

    @Test
    fun `empty marks and damaged files are ignored`() {
        val store = AdMarksStore(directory(), scope)
        store.save(ADDRESS, AdMarks(null, null, false))
        assertNull(store.load(ADDRESS))

        store.save(ADDRESS, marks)
        awaitFile().writeBytes(byteArrayOf(7, 7, 7))
        assertNull(AdMarksStore(directory(), scope).load(ADDRESS))
    }

    private companion object {
        const val ADDRESS = "http://serveur.example/identifiant/secret/1149"
    }
}
