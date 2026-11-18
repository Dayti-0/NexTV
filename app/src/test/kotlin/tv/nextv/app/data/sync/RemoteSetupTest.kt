package tv.nextv.app.data.sync

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.FavoriteEntity
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.app.ui.settings.RemoteSendForm
import tv.nextv.app.ui.settings.RemoteSendViewModel
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialStore
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.source.PlaylistLoaderRegistry
import tv.nextv.core.sync.RemoteConfig
import tv.nextv.core.sync.RemoteHiddenGroup
import tv.nextv.core.sync.RemotePlaylist
import tv.nextv.core.sync.RemoteSetupFind
import tv.nextv.core.sync.RemoteSetupLink
import tv.nextv.core.sync.SyncBinClosedException
import tv.nextv.core.sync.SyncRelay

/**
 * Configuration à distance avec les vraies bases de l'appli : la télé d'un ami affiche un code, la configuration envoyée
 * de chez soi y installe la playlist (choisie), les réglages, les clés et les catégories masquées ; une seconde
 * configuration du même fournisseur (code expiré remplacé) met à jour la playlist sans perdre les favoris.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteSetupTest {

    private class MemoryRelay : SyncRelay {
        val bins = HashMap<String, HashMap<String, ByteArray>>()
        val closed = HashSet<String>()

        // Deux télés à la fois (fils différents) : accès un par un.
        override suspend fun list(bin: String) = synchronized(this) { bins[bin].orEmpty().mapValues { it.value.contentHashCode().toString() } }

        override suspend fun read(bin: String, name: String) = synchronized(this) { bins[bin]?.get(name) }

        override suspend fun write(bin: String, name: String, bytes: ByteArray) = synchronized(this) {
            if (bin in closed) throw SyncBinClosedException()
            bins.getOrPut(bin) { HashMap() }[name] = bytes
        }

        override suspend fun deleteFile(bin: String, name: String) {
            synchronized(this) { bins[bin]?.remove(name) }
        }

        override suspend fun deleteBin(bin: String) = synchronized(this) {
            bins.remove(bin)
            closed += bin
            Unit
        }
    }

    private class MemoryCredentials : CredentialStore {
        val map = HashMap<Long, Credentials>()
        override suspend fun get(sourceId: Long) = map[sourceId]
        override suspend fun put(sourceId: Long, credentials: Credentials) {
            map[sourceId] = credentials
        }
        override suspend fun delete(sourceId: Long) {
            map.remove(sourceId)
        }
    }

    private val context = RuntimeEnvironment.getApplication()
    private val relay = MemoryRelay()
    private val settings = SettingsRepository(context, KeystoreSecretCipher())
    private val db: NexTvDatabase = Room.inMemoryDatabaseBuilder(context, NexTvDatabase::class.java).allowMainThreadQueries().build()
    private val store = RoomPlaylistStore(db)
    private val sources = SourceRepository(
        store = store,
        credentialStore = MemoryCredentials(),
        loaders = PlaylistLoaderRegistry(emptyList()),
        epgLoader = EpgLoader(CompositeContentOpener(emptyList()), EpgImporter({ error("pas de guide") })),
        redactor = Redactor(),
    )
    private val applier = RemoteSetupApplier(store, sources, db.hiddenGroupDao(), settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiver = RemoteSetupReceiver(
        link = RemoteSetupLink(relay, { "Fire TV de Paul" }, pollMillis = 1),
        install = applier::apply,
        scope = scope,
        logger = object : tv.nextv.core.log.Logger {
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, throwable: Throwable?) {
                println("$message : ${throwable?.stackTraceToString()}")
            }
            override fun error(tag: String, message: String, throwable: Throwable?) = warn(tag, message, throwable)
        },
        retryMillis = 1,
        cleanupMillis = 200,
    )
    private val me = RemoteSetupLink(relay, { "Salon" }, pollMillis = 1)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Code affiché sur la télé de l'ami. */
    private suspend fun shownCode(): String = withTimeout(10_000) {
        (receiver.state.first { it is RemoteReceiveState.Waiting } as RemoteReceiveState.Waiting).offer.code
    }

    private fun config(url: String, theme: String = "s:LIGHT") = RemoteConfig(
        playlist = RemotePlaylist("Mon fournisseur", url, mirrors = listOf("http://backup.example:8080")),
        settings = mapOf("theme_style" to theme, "auto_quality" to "b:false"),
        serviceKeys = mapOf(RemoteSetupApplier.TMDB to "cle-tmdb"),
        hidden = listOf(RemoteHiddenGroup("LIVE", "Adultes"), RemoteHiddenGroup("MOVIE", "Films arabes")),
    )

    @Test
    fun `the friend's tv installs everything sent with its code`() = runBlocking {
        settings.setAutoQuality(true)
        receiver.acquire()
        val code = shownCode()
        assertEquals(RemoteSetupFind.Found("Fire TV de Paul"), me.find(code))
        val installed = async { withTimeout(10_000) { receiver.installed.first() } }
        me.send(code, config("http://provider.example:8080/get.php?username=paul&password=secret&type=m3u_plus&output=ts"))
        val reply = me.awaitReply(code)!!
        assertTrue(reply.installed)
        assertEquals("Fire TV de Paul", reply.name)
        val id = installed.await()

        val source = store.getSources().single()
        assertEquals(id, source.id)
        assertEquals("Mon fournisseur", source.name)
        assertTrue((source.location as SourceLocation.Remote).url.contains("username=paul"))
        assertEquals(listOf("http://backup.example:8080"), source.mirrors)
        assertEquals(id, settings.activePlaylistId.first())
        assertFalse(settings.autoQuality.first())
        // Clé de service : chiffrée par le coffre de l'appareil, absent des tests ; l'installation se fait sans elle.
        assertEquals(listOf("Adultes"), db.hiddenGroupDao().titles(id, "LIVE"))
        assertEquals(listOf("Films arabes"), db.hiddenGroupDao().titles(id, "MOVIE"))
        // La télé est prête : pas de configuration rapide par-dessus l'accueil.
        assertTrue(settings.quickSetupDone.first())
        assertTrue(receiver.state.value is RemoteReceiveState.Installed)
        // Rien ne reste sur filebin.
        assertTrue(relay.bins.isEmpty())
        receiver.release()

        // Plus tard, nouveau code de l'abonnement : même fournisseur, la playlist est mise à jour (favoris gardés).
        db.favoriteDao().insert(FavoriteEntity(sourceId = id, kind = "LIVE", itemKey = "tf1", title = "TF1", logoUrl = null, position = 0))
        receiver.acquire()
        val second = shownCode()
        assertNotEquals(code, second)
        me.send(second, config("http://provider.example:8080/get.php?username=paul2&password=autre&type=m3u_plus&output=ts").copy(hidden = null))
        assertTrue(me.awaitReply(second)!!.installed)
        val updated = store.getSources().single()
        assertEquals(id, updated.id)
        assertTrue((updated.location as SourceLocation.Remote).url.contains("username=paul2"))
        assertEquals(listOf("tf1"), db.favoriteDao().all(id).map { it.itemKey })
        // Catégories non envoyées : laissées telles quelles.
        assertEquals(listOf("Adultes"), db.hiddenGroupDao().titles(id, "LIVE"))
        receiver.release()
    }

    @Test
    fun `a refused playlist is answered and a new code is shown`() = runBlocking {
        receiver.acquire()
        val code = shownCode()
        me.send(code, config("ftp://pas-une-playlist"))
        val reply = me.awaitReply(code)!!
        assertFalse(reply.installed)
        val next = withTimeout(10_000) {
            receiver.state.first { it is RemoteReceiveState.Waiting && it.offer.code != code } as RemoteReceiveState.Waiting
        }
        assertTrue(next.refused)
        assertTrue(store.getSources().isEmpty())
        receiver.release()
        assertEquals(RemoteReceiveState.Idle, receiver.state.value)
    }

    @Test
    fun `the playlist link is built from the server and the friend's account`() {
        val xtream = RemoteSendViewModel.playlistUrl(RemoteSendForm(server = "provider.example:8080", username = "paul", password = "secret"))!!
        assertEquals("http://provider.example:8080/get.php?username=paul&password=secret&type=m3u_plus&output=ts", xtream)
        // Lien M3U complet, sans identifiant.
        assertEquals("http://exemple.test/liste.m3u", RemoteSendViewModel.playlistUrl(RemoteSendForm(server = "http://exemple.test/liste.m3u")))
        assertEquals(null, RemoteSendViewModel.playlistUrl(RemoteSendForm(server = "")))
        assertEquals(null, RemoteSendViewModel.playlistUrl(RemoteSendForm(server = "pas une adresse")))
    }
}
