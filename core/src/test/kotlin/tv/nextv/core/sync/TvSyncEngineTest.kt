package tv.nextv.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Télés reliées : liaison par code, puis favoris, effacements et réglages qui passent d'une télé à l'autre. */
class TvSyncEngineTest {

    /** Page filebin en mémoire. */
    private class FakeRelay : SyncRelay {
        val bins = HashMap<String, HashMap<String, ByteArray>>()
        val closed = HashSet<String>()
        var offline = false

        private fun check() {
            if (offline) throw IOException("hors ligne")
        }

        override suspend fun list(bin: String): Map<String, String> {
            check()
            return bins[bin].orEmpty().mapValues { (_, bytes) -> bytes.contentHashCode().toString() }
        }

        override suspend fun read(bin: String, name: String): ByteArray? {
            check()
            return bins[bin]?.get(name)
        }

        override suspend fun write(bin: String, name: String, bytes: ByteArray) {
            check()
            if (bin in closed) throw SyncBinClosedException()
            bins.getOrPut(bin) { HashMap() }[name] = bytes
        }

        override suspend fun deleteFile(bin: String, name: String) {
            check()
            bins[bin]?.remove(name)
        }

        override suspend fun deleteBin(bin: String) {
            check()
            bins.remove(bin)
            closed += bin
        }
    }

    /** Télé en mémoire : ses éléments, et les portées de ses playlists. */
    private class FakeTv : SyncLocal {
        val values = HashMap<String, String>()
        val scopes = HashSet<String>()

        override suspend fun snapshot() = SyncSnapshot(HashMap(values), HashSet(scopes))

        override suspend fun apply(target: Map<String, String>, current: SyncSnapshot, ignoredScopes: Set<String>): SyncApplied {
            var added = 0
            for ((key, _) in target) {
                if (!SyncKeys.isPlaylist(key)) continue
                val scope = SyncKeys.parts(key)[1]
                if (scope !in scopes && scope !in ignoredScopes) {
                    scopes += scope
                    values[key] = target.getValue(key)
                    added++
                }
            }
            var changes = 0
            fun applies(key: String) = SyncKeys.scopeOf(key)?.let { it in scopes } ?: !SyncKeys.isPlaylist(key)
            for ((key, value) in target) {
                if (applies(key) && values[key] != value) {
                    values[key] = value
                    changes++
                }
            }
            for (key in values.keys.toList()) {
                if (applies(key) && key !in target) {
                    values.remove(key)
                    changes++
                }
            }
            return SyncApplied(changes, added)
        }
    }

    private class MemoryStore : SyncStateStore {
        var text: String? = null
        override fun load() = text?.let(SyncStateCodec::decode)
        override fun save(state: SyncState) {
            text = SyncStateCodec.encode(state)
        }
    }

    private var now = 1_000_000L
    private val relay = FakeRelay()

    private fun engine(tv: FakeTv, name: String, store: MemoryStore = MemoryStore()) =
        TvSyncEngine(relay, tv, store, { name }, "test", clock = { now++ }, pollMillis = 1)

    private val scope = "provider.example"
    private val playlist = SyncKeys.playlist(scope)
    private fun fav(key: String) = SyncKeys.favorite(scope, "LIVE", key)
    private val theme = SyncKeys.setting("theme_style")

    private fun tvWithPlaylist() = FakeTv().apply {
        scopes += scope
        values[playlist] = """{"name":"Ma playlist"}"""
    }

    /** Relie [b] au groupe de [a] par le code affiché sur [a]. */
    private fun link(a: TvSyncEngine, b: TvSyncEngine): JoinResult = runBlocking {
        val invite = a.createInvite()
        val answer = async { a.awaitAnswer(invite) }
        yield()
        val result = b.join(invite.code)
        assertEquals("B", answer.await())
        result
    }

    @Test
    fun `a second tv joins with the code, gets the favourites and keeps the first tv settings`() = runBlocking {
        val tvA = tvWithPlaylist().apply {
            values[fav("tf1")] = "1"
            values[theme] = "s:DARK"
        }
        val tvB = tvWithPlaylist().apply {
            values[fav("m6")] = "1"
            values[theme] = "s:LIGHT"
        }
        val a = engine(tvA, "A")
        val b = engine(tvB, "B")
        val result = link(a, b)
        assertTrue(result is JoinResult.Joined)
        assertEquals("A", (result as JoinResult.Joined).from)
        // B a les favoris de A et les siens ; le réglage de A l'emporte (B vient d'arriver).
        assertEquals("1", tvB.values[fav("tf1")])
        assertEquals("1", tvB.values[fav("m6")])
        assertEquals("s:DARK", tvB.values[theme])
        a.sync()
        assertEquals("1", tvA.values[fav("m6")])
        assertEquals("s:DARK", tvA.values[theme])
        assertEquals(listOf("B"), a.status.value.peers.map { it.name })
        assertEquals(listOf("A"), b.status.value.peers.map { it.name })
        // L'invitation a disparu : le code ne sert qu'une fois.
        assertTrue(relay.bins.keys.none { it.startsWith("nixtv-link-") })
    }

    @Test
    fun `changes and removals travel, even to a tv that was off`() = runBlocking {
        val tvA = tvWithPlaylist().apply { values[fav("tf1")] = "1" }
        val tvB = tvWithPlaylist()
        val tvC = FakeTv()
        val a = engine(tvA, "A")
        val b = engine(tvB, "B")
        val c = engine(tvC, "C")
        link(a, b)
        val invite = b.createInvite()
        val answer = async { b.awaitAnswer(invite) }
        yield()
        c.join(invite.code)
        answer.await()
        // C n'avait aucune playlist : celle du groupe lui est ajoutée, avec ses favoris.
        assertTrue(scope in tvC.scopes)
        assertEquals("1", tvC.values[fav("tf1")])

        // B retire le favori et change le thème pendant que A et C sont éteintes.
        tvB.values.remove(fav("tf1"))
        tvB.values[theme] = "s:LIGHT"
        b.sync()
        a.sync()
        c.sync()
        assertNull(tvA.values[fav("tf1")])
        assertNull(tvC.values[fav("tf1")])
        assertEquals("s:LIGHT", tvA.values[theme])
        assertEquals("s:LIGHT", tvC.values[theme])

        // Changement le plus récent gagnant, quel que soit l'ordre des passages.
        tvA.values[theme] = "s:DARK"
        a.sync()
        tvC.values[theme] = "s:BLUE"
        c.sync()
        b.sync()
        a.sync()
        assertEquals("s:BLUE", tvA.values[theme])
        assertEquals("s:BLUE", tvB.values[theme])
    }

    @Test
    fun `a playlist removed from one tv is not put back, and its favourites stay on the others`() = runBlocking {
        val tvA = tvWithPlaylist().apply { values[fav("tf1")] = "1" }
        val tvB = tvWithPlaylist()
        val a = engine(tvA, "A")
        val b = engine(tvB, "B")
        link(a, b)
        assertEquals("1", tvB.values[fav("tf1")])
        // B retire sa playlist (et donc ses favoris).
        tvB.scopes.clear()
        tvB.values.keys.removeAll { SyncKeys.scopeOf(it) != null || SyncKeys.isPlaylist(it) }
        b.sync()
        a.sync()
        b.sync()
        assertFalse(scope in tvB.scopes)
        assertEquals("1", tvA.values[fav("tf1")])
    }

    @Test
    fun `wrong or used codes are refused, and nothing is lost offline`() = runBlocking {
        val tvA = tvWithPlaylist()
        val tvB = FakeTv()
        val a = engine(tvA, "A")
        val b = engine(tvB, "B")
        assertEquals(JoinResult.UnknownCode, b.join("000000"))
        link(a, b)
        relay.offline = true
        tvA.values[fav("canal")] = "1"
        a.sync()
        assertTrue(a.status.value.failedAt > 0)
        relay.offline = false
        a.sync()
        assertEquals(0L, a.status.value.failedAt)
        b.sync()
        assertEquals("1", tvB.values[fav("canal")])
    }

    @Test
    fun `a closed page is replaced by the next one on every tv`() = runBlocking {
        val tvA = tvWithPlaylist()
        val tvB = tvWithPlaylist()
        val a = engine(tvA, "A")
        val b = engine(tvB, "B")
        link(a, b)
        // Filebin ferme la page (7 jours sans dépôt) : chaque télé passe à la suivante en voulant déposer.
        val page = relay.bins.keys.single { it.startsWith(SyncGroup.BIN_PREFIX) }
        relay.bins.remove(page)
        relay.closed += page
        tvA.values[fav("arte")] = "1"
        a.sync()
        b.sync()
        b.sync()
        assertEquals("1", tvB.values[fav("arte")])
        assertEquals(listOf("A"), b.status.value.peers.map { it.name })
    }

    @Test
    fun `the state survives a restart`() = runBlocking {
        val tvA = tvWithPlaylist().apply { values[fav("tf1")] = "1" }
        val tvB = tvWithPlaylist()
        val storeB = MemoryStore()
        val a = engine(tvA, "A")
        link(a, engine(tvB, "B", storeB))
        // B relancée : même groupe, même document ; un effacement fait avant le relancement part bien.
        tvB.values.remove(fav("tf1"))
        val restarted = engine(tvB, "B", storeB)
        restarted.sync()
        a.sync()
        assertNull(tvA.values[fav("tf1")])
        assertTrue(restarted.status.value.linked)
        restarted.leave()
        assertFalse(restarted.status.value.linked)
        a.sync()
        assertTrue(a.status.value.peers.isEmpty())
    }
}
