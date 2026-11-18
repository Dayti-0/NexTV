package tv.nextv.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDocumentTest {

    @Test
    fun `merging gives the same document in any order`() {
        val a = SyncDocument(mapOf("x" to SyncEntry("1", 10), "y" to SyncEntry(null, 20), "z" to SyncEntry("a", 5)))
        val b = SyncDocument(mapOf("x" to SyncEntry("2", 11), "y" to SyncEntry("keep", 15), "z" to SyncEntry("b", 5)))
        assertEquals(a.merge(b), b.merge(a))
        assertEquals(mapOf("x" to "2", "z" to "b"), a.merge(b).values())
    }

    @Test
    fun `local changes are dated, removals become tombstones`() {
        val key = SyncKeys.setting("theme")
        val base = SyncSnapshot(mapOf(key to "s:A", "s\u001Fother" to "b:true"))
        val doc = SyncDocument(mapOf(key to SyncEntry("s:A", 50), "s\u001Fother" to SyncEntry("b:true", 50)))
        val changed = doc.withLocalChanges(base, SyncSnapshot(mapOf(key to "s:B")), now = 100)
        assertEquals(SyncEntry("s:B", 100), changed.entries[key])
        assertEquals(SyncEntry(null, 100), changed.entries["s\u001Fother"])
        // Horloge en retard sur une autre télé : le changement passe quand même après.
        val late = changed.withLocalChanges(SyncSnapshot(mapOf(key to "s:B")), SyncSnapshot(mapOf(key to "s:C")), now = 20)
        assertEquals(SyncEntry("s:C", 101), late.entries[key])
        // Vieux effacements oubliés.
        assertNull(changed.pruned(100 + SyncDocument.TOMBSTONE_MILLIS + 1).entries["s\u001Fother"])
    }

    @Test
    fun `items of a playlist missing here are left alone`() {
        val fav = SyncKeys.favorite("a.example", "LIVE", "tf1")
        val doc = SyncDocument(mapOf(fav to SyncEntry("1", 10)))
        val after = doc.withLocalChanges(SyncSnapshot(mapOf(fav to "1"), setOf("a.example")), SyncSnapshot(), now = 100)
        assertEquals(doc, after)
    }

    @Test
    fun `scopes ignore credentials, port and www`() {
        assertEquals("provider.example", SyncKeys.scopeOfUrl("http://user:pass@www.Provider.example:8080/get.php?username=u&password=p"))
        assertEquals("provider.example", SyncKeys.scopeOfUrl("https://provider.example"))
        assertNull(SyncKeys.scopeOfUrl("/storage/playlist.m3u"))
    }

    @Test
    fun `files are encrypted and refused with another key`() {
        val group = SyncGroup.create()
        val file = SyncFile(SyncDevice("abc", "Salon", "0.2.186", 5), SyncDocument(mapOf("k" to SyncEntry("é", 3), "d" to SyncEntry(null, 4))))
        val bytes = SyncCodec.encodeFile(file, group.key)
        assertTrue(String(bytes, Charsets.ISO_8859_1).indexOf("Salon") < 0)
        assertEquals(file, SyncCodec.decodeFile(bytes, group.key))
        val other = SyncGroup.create()
        assertTrue(runCatching { SyncCodec.decodeFile(bytes, other.key) }.exceptionOrNull() is SyncFormatException)
        val decoded = SyncGroup.decode(group.encode())!!
        assertEquals(group.bin, decoded.bin)
        assertArrayEquals(group.key, decoded.key)
    }

    @Test
    fun `codes are six digits and invitations need the right code`() {
        val code = SyncPairing.newCode()
        assertEquals(6, code.length)
        assertEquals("123456", SyncPairing.normalize(" 123 456 "))
        assertNull(SyncPairing.normalize("12345"))
        assertEquals("482 913", SyncPairing.display("482913"))
        val group = SyncGroup.create()
        val invite = SyncPairing.encodeInvite(group, "Salon", "111111")
        assertEquals("Salon", SyncPairing.decodeInvite(invite, "111111")?.from)
        assertNull(SyncPairing.decodeInvite(invite, "111112"))
    }
}
