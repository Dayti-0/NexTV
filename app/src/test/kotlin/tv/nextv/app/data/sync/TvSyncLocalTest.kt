package tv.nextv.app.data.sync

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.FavoriteEntity
import tv.nextv.app.data.db.HiddenGroupEntity
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.progress.ProgressDatabase
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.progress.WatchedDatabase
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialStore
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.source.PlaylistLoaderRegistry
import tv.nextv.core.sync.SyncBinClosedException
import tv.nextv.core.sync.SyncKeys
import tv.nextv.core.sync.SyncRelay
import tv.nextv.core.sync.SyncState
import tv.nextv.core.sync.SyncStateStore
import tv.nextv.core.sync.TvSyncEngine
import java.io.IOException

/**
 * Télés reliées, avec les vraies bases de l'appli : une télé neuve reçoit la playlist (et son compte), les favoris,
 * « Déjà vu », reprises et catégories masquées de l'autre ; un favori retiré sur l'une l'est sur l'autre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TvSyncLocalTest {

    private class MemoryRelay : SyncRelay {
        val bins = HashMap<String, HashMap<String, ByteArray>>()
        val closed = HashSet<String>()
        override suspend fun list(bin: String) = bins[bin].orEmpty().mapValues { it.value.contentHashCode().toString() }
        override suspend fun read(bin: String, name: String) = bins[bin]?.get(name)
        override suspend fun write(bin: String, name: String, bytes: ByteArray) {
            if (bin in closed) throw SyncBinClosedException()
            bins.getOrPut(bin) { HashMap() }[name] = bytes
        }
        override suspend fun deleteFile(bin: String, name: String) {
            bins[bin]?.remove(name)
        }
        override suspend fun deleteBin(bin: String) {
            bins.remove(bin)
            closed += bin
        }
    }

    private class MemoryStore : SyncStateStore {
        var state: SyncState? = null
        override fun load() = state
        override fun save(state: SyncState) {
            this.state = state
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

    /** Une télé : ses bases (les réglages, eux, sont ceux de l'appli de test, communs aux deux). */
    private inner class Tv(name: String) {
        val db: NexTvDatabase = Room.inMemoryDatabaseBuilder(context, NexTvDatabase::class.java).allowMainThreadQueries().build()
        val progressDb: ProgressDatabase = Room.inMemoryDatabaseBuilder(context, ProgressDatabase::class.java).allowMainThreadQueries().build()
        val watchedDb: WatchedDatabase = Room.inMemoryDatabaseBuilder(context, WatchedDatabase::class.java).allowMainThreadQueries().build()
        val store = RoomPlaylistStore(db)
        val credentials = MemoryCredentials()
        val sources = SourceRepository(
            store = store,
            credentialStore = credentials,
            loaders = PlaylistLoaderRegistry(emptyList()),
            epgLoader = EpgLoader(CompositeContentOpener(emptyList()), EpgImporter({ error("pas de guide") })),
            redactor = Redactor(),
        )
        val progress = ProgressRepository(progressDb)
        val watched = WatchedRepository(watchedDb)
        val engine = TvSyncEngine(
            relay = relay,
            local = RoomSyncLocal(
                playlistStore = store,
                sources = sources,
                favoriteDao = db.favoriteDao(),
                hiddenGroupDao = db.hiddenGroupDao(),
                settings = settings,
                progress = progress,
                watched = watched,
                channelDao = db.channelDao(),
                inTransaction = { block -> db.withTransaction { block() } },
            ),
            store = MemoryStore(),
            deviceName = { name },
            appVersion = "test",
            pollMillis = 1,
        )

        fun close() {
            db.close()
            progressDb.close()
            watchedDb.close()
        }
    }

    private val context = RuntimeEnvironment.getApplication()
    private val relay = MemoryRelay()
    private val settings = SettingsRepository(context, KeystoreSecretCipher())
    private val tvs = mutableListOf<Tv>()

    private fun tv(name: String) = Tv(name).also { tvs += it }

    @After
    fun tearDown() = tvs.forEach { it.close() }

    @Test
    fun `a new tv gets the playlist and everything watched on the other one`() = runBlocking {
        val salon = tv("Salon")
        val sourceId = salon.sources.add(
            SourceInput(
                name = "Mon abonnement",
                location = SourceLocation.Remote("http://provider.example:8080/get.php"),
                epg = EpgSettings(location = null, useHeaderUrls = false),
                credentials = CredentialsChange.Replace(Credentials("moi", "secret")),
            ),
        )
        salon.db.favoriteDao().insert(FavoriteEntity(sourceId = sourceId, kind = "LIVE", itemKey = "tf1", title = "TF1", logoUrl = null, position = 0))
        salon.db.hiddenGroupDao().hide(HiddenGroupEntity(sourceId, "MOVIE", "Films arabes"))
        salon.watched.put(sourceId, "movie:dune", 1234)
        salon.progress.replace(ProgressEntity(sourceId, "movie:alien", "MOVIE", 42, "Alien", null, null, 600_000, 7_000_000, 99))

        val chambre = tv("Chambre")
        val invite = salon.engine.createInvite()
        val answer = async { salon.engine.awaitAnswer(invite) }
        yield()
        chambre.engine.join(invite.code)
        assertEquals("Chambre", answer.await())

        val added = chambre.store.getSources().single()
        assertEquals("Mon abonnement", added.name)
        assertEquals("moi", chambre.credentials.map[added.id]?.username)
        assertEquals(listOf("tf1"), chambre.db.favoriteDao().all(added.id).map { it.itemKey })
        assertEquals(listOf("Films arabes"), chambre.db.hiddenGroupDao().titles(added.id, "MOVIE"))
        assertEquals(listOf("movie:dune" to 1234L), chambre.watched.all(added.id).map { it.itemKey to it.watchedAt })
        val resume = chambre.progress.all(added.id).single()
        assertEquals(600_000L, resume.positionMs)
        // Le numéro de ligne d'une télé ne veut rien dire sur l'autre.
        assertEquals(0L, resume.channelId)

        // Favori retiré dans la chambre : retiré aussi au salon.
        chambre.db.favoriteDao().delete(added.id, "LIVE", "tf1")
        chambre.engine.sync()
        salon.engine.sync()
        assertTrue(salon.db.favoriteDao().all(sourceId).isEmpty())
        // Le salon garde son propre numéro de ligne pour la reprise.
        assertEquals(42L, salon.progress.all(sourceId).single().channelId)
    }

    @Test
    fun `settings are shared except those of the device`() = runBlocking {
        settings.setAutoQuality(false)
        settings.ensureAdUploadBin { "nixtv-test" }
        val synced = settings.syncedPreferences()
        assertEquals("b:false", synced["auto_quality"])
        assertFalse("ad_upload_bin" in synced)
        assertEquals(1, settings.applySyncedPreferences(mapOf("auto_quality" to "b:true", "ad_upload_bin" to "s:autre", "inconnu" to "s:x")))
        assertTrue(settings.autoQuality.first())
        assertEquals("nixtv-test", settings.adUploadBin.first())
        // Valeur d'un autre type (autre version de l'appli) : ignorée.
        settings.applySyncedPreferences(mapOf("auto_quality" to "i:3"))
        assertTrue(settings.autoQuality.first())
        // Les clés du document partagé portent bien le nom du réglage.
        assertEquals("s", SyncKeys.parts(SyncKeys.setting("auto_quality"))[0])
        Unit
    }
}
