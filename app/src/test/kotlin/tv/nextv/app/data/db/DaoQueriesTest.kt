package tv.nextv.app.data.db

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpisodeInfo
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamQuality

/** Requêtes réécrites pour la vitesse : mêmes résultats qu'avant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DaoQueriesTest {

    private lateinit var db: NexTvDatabase
    private var sourceId = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NexTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sourceId = fill(db)
    }

    /** Playlist d'exemple : chaînes en plusieurs versions, épisodes de deux séries, un programme en cours. */
    private suspend fun fill(db: NexTvDatabase): Long {
        val store = RoomPlaylistStore(db)
        val sourceId = db.sourceDao().insert(
            SourceEntity(
                name = "P", type = "m3u", locationKind = "remote", location = "http://example.com/p.m3u", locationName = null,
                epgLocationKind = null, epgLocation = null, epgLocationName = null, epgUseHeaderUrls = false,
                userAgent = null, hasCredentials = false, createdAt = 0, updatedAt = 0, epgGeneration = 1,
            ),
        )
        var position = 0
        fun live(name: String, group: String, quality: StreamQuality?, key: String) = Channel(
            position = position++, name = name, stream = StreamDescriptor("http://example.com/live/${position}.ts"),
            groupTitle = group, kind = ContentKind.LIVE, title = name, variantKey = key, quality = quality,
        )
        fun episode(series: String, number: Int, group: String) = Channel(
            position = position++, name = "$series S01E$number", stream = StreamDescriptor("http://example.com/series/${position}.mkv"),
            groupTitle = group, kind = ContentKind.SERIES, title = series, episode = EpisodeInfo(series, 1, number),
        )
        val channels = listOf(
            live("TF1 FHD", "FHD", StreamQuality.FHD, "tf1"),
            live("TF1 HD", "HD", StreamQuality.HD, "tf1"),
            live("M6 HD", "HD", StreamQuality.HD, "m6"),
            live("Arte", "Divers", null, "arte"),
            episode("Lupin", 1, "Séries FR"),
            episode("Dark", 1, "Séries DE"),
            episode("Lupin", 2, "Séries FR"),
            episode("Lupin", 3, "Séries FR"),
            episode("Dark", 2, "Séries DE"),
        )
        store.replaceChannels(sourceId, channels) {}
        val dao = db.channelDao()
        // Guide : TF1 a un programme en cours.
        dao.setEpg(dao.primaryIdByKey(sourceId, "live:tf1")!!, "tf1.fr")
        db.programmeDao().insertAll(
            listOf(
                ProgrammeEntity(sourceId = sourceId, generation = 1, epgChannelId = "tf1.fr", startMillis = 0, stopMillis = 1_000, title = "Avant", subtitle = null, description = null, category = null),
                ProgrammeEntity(sourceId = sourceId, generation = 1, epgChannelId = "tf1.fr", startMillis = 1_000, stopMillis = 2_000, title = "En cours", subtitle = null, description = null, category = null),
                ProgrammeEntity(sourceId = sourceId, generation = 2, epgChannelId = "tf1.fr", startMillis = 1_000, stopMillis = 2_000, title = "Import en cours", subtitle = null, description = null, category = null),
            ),
        )
        return sourceId
    }

    @After
    fun tearDown() = db.close()

    private fun <T : Any> PagingSource<Int, T>.all(): List<T> = runBlocking {
        (load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page).data
    }

    @Test
    fun `channel list shows the best version of each channel with its current programme`() {
        val rows = db.channelDao().pageChannels(sourceId, 0, "", "", now = 1_500).all()
        assertEquals(listOf("TF1 FHD", "M6 HD", "Arte"), rows.map { it.name })
        assertEquals("En cours", rows[0].nowTitle)
        assertNull(rows[1].nowTitle)
        assertEquals(2, rows[0].variantCount)
    }

    @Test
    fun `channel list of a category and search`() {
        val dao = db.channelDao()
        assertEquals(listOf("TF1 HD", "M6 HD"), dao.pageChannels(sourceId, 1, "HD", "", 1_500).all().map { it.name })
        assertEquals(listOf("M6 HD"), dao.pageChannels(sourceId, 0, "", "m6", 1_500).all().map { it.name })
        runBlocking { db.hiddenGroupDao().hide(HiddenGroupEntity(sourceId, "LIVE", "FHD")) }
        // Version FHD masquée : la meilleure version visible prend sa place.
        assertEquals(listOf("TF1 HD", "M6 HD", "Arte"), dao.pageChannels(sourceId, 0, "", "", 1_500).all().map { it.name })
    }

    @Test
    fun `numbered parameters replace every named parameter`() {
        assertEquals("a = ?1 AND b = ?2 OR a = ?1", numberParameters("a = :sourceId AND b = :now OR a = :sourceId", listOf("sourceId", "now")))
        assertEquals(false, Regex(":\\w").containsMatchIn(PAGE_CHANNELS))
        // La requête brute est valide telle quelle (comptage fait par la pagination).
        val count = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM ( $PAGE_CHANNELS )", arrayOf(sourceId, 0, "", "", 1_500L, ""))).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(3, count)
    }

    @Test
    fun `search ignores accents and punctuation`() = runBlocking {
        fun movie(title: String) = Channel(
            position = 100, name = title, stream = StreamDescriptor("http://example.com/movie/${title.length}.mkv"),
            groupTitle = "Films", kind = ContentKind.MOVIE, title = title, year = 2024,
        )
        val other = db.sourceDao().insert(
            SourceEntity(
                name = "Q", type = "m3u", locationKind = "remote", location = "http://example.com/q.m3u", locationName = null,
                epgLocationKind = null, epgLocation = null, epgLocationName = null, epgUseHeaderUrls = false,
                userAgent = null, hasCredentials = false, createdAt = 0, updatedAt = 0, epgGeneration = 1,
            ),
        )
        RoomPlaylistStore(db).replaceChannels(
            other,
            listOf(movie("À la dérive"), movie("L'Été dernier"), movie("Arrival"), episodeOf("Les Têtes brûlées")),
        ) {}
        val dao = db.channelDao()
        assertEquals(listOf("À la dérive"), dao.pageMovies(other, 0, "", "a la derive").all().map { it.title })
        assertEquals(listOf("À la dérive"), dao.pageMovies(other, 0, "", "À LA DÉRIVE").all().map { it.title })
        assertEquals(listOf("L'Été dernier"), dao.pageMovies(other, 0, "", "l ete").all().map { it.title })
        assertEquals(listOf("Les Têtes brûlées"), dao.pageSeries(other, 0, "", "tetes brulees").all().map { it.title })
        // Les préfixes des clés (« movie: ») ne sont pas cherchés.
        assertEquals(emptyList<String>(), dao.pageMovies(other, 0, "", "movie").all().map { it.title })
        assertEquals(listOf("Arte"), dao.pageChannels(sourceId, 0, "", "arté", 1_500).all().map { it.name })
    }

    @Test
    fun `films and series carry the quality announced by the playlist`() = runBlocking {
        fun movie(title: String, quality: StreamQuality?) = Channel(
            position = 100 + title.length, name = title, stream = StreamDescriptor("http://example.com/movie/${title.length}.mkv"),
            groupTitle = "Films", kind = ContentKind.MOVIE, title = title, year = 2024, quality = quality,
        )
        fun episode(number: Int, quality: StreamQuality?) = Channel(
            position = 200 + number, name = "Lupin S01E$number", stream = StreamDescriptor("http://example.com/series/$number.mkv"),
            groupTitle = "Séries", kind = ContentKind.SERIES, title = "Lupin", episode = EpisodeInfo("Lupin", 1, number), quality = quality,
        )
        val other = db.sourceDao().insert(
            SourceEntity(
                name = "Q", type = "m3u", locationKind = "remote", location = "http://example.com/q.m3u", locationName = null,
                epgLocationKind = null, epgLocation = null, epgLocationName = null, epgUseHeaderUrls = false,
                userAgent = null, hasCredentials = false, createdAt = 0, updatedAt = 0, epgGeneration = 1,
            ),
        )
        RoomPlaylistStore(db).replaceChannels(
            other,
            listOf(movie("Heat", StreamQuality.UHD), movie("Alien", null), episode(1, StreamQuality.HD), episode(2, StreamQuality.UHD)),
        ) {}
        val dao = db.channelDao()
        assertEquals(listOf("Heat" to "UHD", "Alien" to null), dao.pageMovies(other, 0, "", "").all().map { it.title to it.quality })
        assertEquals(listOf("Heat" to "UHD", "Alien" to null), dao.movieRow(other, "Films", 10).map { it.title to it.quality })
        // La série reste représentée par son premier épisode, même si un autre est annoncé en meilleure qualité.
        val series = dao.pageSeries(other, 0, "", "").all().single()
        assertEquals("HD", series.quality)
        assertEquals(dao.episodes(other, "series:lupin").first().id, series.id)
        assertEquals("HD", dao.seriesRow(other, "Séries", 10).single().quality)
    }

    /** « Masquer les chaînes 4K » : un film qui existe aussi hors 4K n'affiche plus le badge 4K sur son affiche. */
    @Test
    fun `with 4K hidden, a film also offered in FHD shows the FHD badge`() = runBlocking {
        fun copy(name: String, quality: StreamQuality, n: Int) = Channel(
            position = 300 + n, name = name, stream = StreamDescriptor("http://example.com/movie/hg$n.mkv"),
            groupTitle = "Sagas", kind = ContentKind.MOVIE, title = "Hunger Games", year = 2015, quality = quality,
        )
        fun only4k(n: Int) = Channel(
            position = 310 + n, name = "Seul en 4K", stream = StreamDescriptor("http://example.com/movie/u$n.mkv"),
            groupTitle = "Sagas", kind = ContentKind.MOVIE, title = "Seul en 4K", year = 2020, quality = StreamQuality.UHD,
        )
        val other = db.sourceDao().insert(
            SourceEntity(
                name = "U", type = "m3u", locationKind = "remote", location = "http://example.com/u.m3u", locationName = null,
                epgLocationKind = null, epgLocation = null, epgLocationName = null, epgUseHeaderUrls = false,
                userAgent = null, hasCredentials = false, createdAt = 0, updatedAt = 0, epgGeneration = 1,
            ),
        )
        RoomPlaylistStore(db).replaceChannels(
            other,
            listOf(copy("Hunger Games (4K)", StreamQuality.UHD, 0), copy("Hunger Games (MULTI)", StreamQuality.FHD, 1), only4k(0)),
        ) {}
        val dao = db.channelDao()
        fun badges() = runBlocking { dao.movieRow(other, "Sagas", 10).associate { it.title to it.quality } }
        val shown = badges()
        // 4K masquée : l'affiche du film porte la qualité de sa meilleure copie hors 4K ; un film seulement en 4K garde la sienne.
        db.hiddenGroupDao().replace(other, HIDDEN_UHD, setOf(""))
        val hidden = badges()
        assertEquals("FHD", hidden["Hunger Games"])
        assertEquals("UHD", hidden["Seul en 4K"])
        assertEquals(shown.keys, hidden.keys)
        assertEquals(hidden["Hunger Games"], dao.pageMovies(other, 0, "", "").all().first { it.title == "Hunger Games" }.quality)
    }

    private fun episodeOf(series: String) = Channel(
        position = 200, name = "$series S01E01", stream = StreamDescriptor("http://example.com/series/t.mkv"),
        groupTitle = "Séries", kind = ContentKind.SERIES, title = series, episode = EpisodeInfo(series, 1, 1),
    )

    @Test
    fun `series are listed once with their episode count`() {
        val dao = db.channelDao()
        val series = dao.pageSeries(sourceId, 0, "", "").all()
        assertEquals(listOf("Lupin" to 3, "Dark" to 2), series.map { it.title to it.episodeCount })
        assertEquals(listOf("Dark"), dao.pageSeries(sourceId, 1, "Séries DE", "").all().map { it.title })
        assertEquals(listOf("Lupin"), dao.pageSeries(sourceId, 0, "", "lup").all().map { it.title })
        runBlocking {
            assertEquals(2, dao.count(sourceId, "SERIES"))
            assertEquals(3, dao.episodes(sourceId, "series:lupin").size)
        }
    }

    @Test
    fun `series groups count series, not episodes`() = runBlocking {
        val groups = db.channelDao().observeAllGroupsOnce()
        assertEquals(mapOf("Séries FR" to 1, "Séries DE" to 1), groups)
    }

    @Test
    fun `migration marks the first episode of each series`() = runBlocking {
        // Base d'une version précédente : tous les épisodes étaient des lignes principales.
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET isPrimary = 1, variantCount = 1 WHERE kind = 'SERIES'")
        db.openHelper.writableDatabase.execSQL(NexTvDatabase.SERIES_PRIMARY_SQL)
        val series = db.channelDao().pageSeries(sourceId, 0, "", "").all()
        assertEquals(listOf("Lupin" to 3, "Dark" to 2), series.map { it.title to it.episodeCount })
    }

    @Test
    fun `version 6 database is migrated and keeps its data`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val old = Room.databaseBuilder(context, NexTvDatabase::class.java, name).allowMainThreadQueries().build()
        val id = fill(old)
        // Retour au schéma et aux données de la version 6.
        old.openHelper.writableDatabase.apply {
            execSQL("DROP INDEX `index_channels_sourceId_kind_itemKey_position`")
            execSQL("CREATE INDEX `index_channels_sourceId_kind_seriesName` ON `channels` (`sourceId`, `kind`, `seriesName`)")
            execSQL("UPDATE channels SET isPrimary = 1, variantCount = 1 WHERE kind = 'SERIES'")
            execSQL("DROP TABLE `guide_only_channels`")
            execSQL("ALTER TABLE `programmes` DROP COLUMN `imageUrl`")
            execSQL("ALTER TABLE `tmdb_cache` DROP COLUMN `trailerLanguage`")
            execSQL("DROP TABLE `measured_quality`")
            version = 6
        }
        old.close()
        val migrated = Room.databaseBuilder(context, NexTvDatabase::class.java, name)
            .addMigrations(NexTvDatabase.MIGRATION_6_7, NexTvDatabase.MIGRATION_7_8, NexTvDatabase.MIGRATION_8_9, NexTvDatabase.MIGRATION_9_10, NexTvDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
        try {
            val series = migrated.channelDao().pageSeries(id, 0, "", "").all()
            assertEquals(listOf("Lupin" to 3, "Dark" to 2), series.map { it.title to it.episodeCount })
            assertEquals(3, migrated.channelDao().pageChannels(id, 0, "", "", 1_500).all().size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `favourites are listed in their order`() = runBlocking {
        val favorites = db.favoriteDao()
        favorites.insert(FavoriteEntity(sourceId = sourceId, kind = "LIVE", itemKey = "live:m6", title = "M6", logoUrl = null, position = 0))
        favorites.insert(FavoriteEntity(sourceId = sourceId, kind = "LIVE", itemKey = "live:tf1", title = "TF1", logoUrl = null, position = 1))
        favorites.insert(FavoriteEntity(sourceId = sourceId, kind = "SERIES", itemKey = "series:dark", title = "Dark", logoUrl = null, position = 0))
        val dao = db.channelDao()
        val channels = dao.observeFavoriteChannels(sourceId, 1_500).first()
        assertEquals(listOf("M6 HD", "TF1 FHD"), channels.map { it.name })
        assertEquals("En cours", channels[1].nowTitle)
        assertEquals(listOf("Dark" to 2), dao.observeFavoriteSeries(sourceId).first().map { it.title to it.episodeCount })
        assertEquals(listOf("TF1 FHD"), dao.guideFavoriteChannels(sourceId).map { it.name })
    }

    /**
     * Réorganisation : un favori qui ne s'affiche plus (chaîne disparue de la playlist) ne compte pas. La dernière
     * chaîne affichée monte d'une place à chaque appui, et rien ne passe au-dessus de la première.
     */
    @Test
    fun `favourites move among the shown ones`() = runBlocking {
        val repository = tv.nextv.app.data.catalog.FavoritesRepository(db)
        // Ordre : M6, (chaîne disparue), TF1.
        listOf("live:m6", "live:gone", "live:tf1").forEach { repository.toggle(sourceId, ContentKind.LIVE, it, it, null) }
        val dao = db.channelDao()
        suspend fun shown() = dao.observeFavoriteChannels(sourceId, 1_500).first().map { it.itemKey }
        assertEquals(listOf("live:m6", "live:tf1"), shown())
        // TF1 (la dernière) monte : elle passe devant M6 du premier coup.
        repository.move(sourceId, ContentKind.LIVE, "live:tf1", -1, shown())
        assertEquals(listOf("live:tf1", "live:m6"), shown())
        // Encore plus haut : elle reste la première.
        repository.move(sourceId, ContentKind.LIVE, "live:tf1", -1, shown())
        assertEquals(listOf("live:tf1", "live:m6"), shown())
        // Et redescend en dernière place.
        repository.move(sourceId, ContentKind.LIVE, "live:tf1", 1, shown())
        assertEquals(listOf("live:m6", "live:tf1"), shown())
    }

    @Test
    fun `guide versions list the best visible version of a channel first`() = runBlocking {
        val dao = db.channelDao()
        // Les deux versions de TF1 associées au même identifiant de guide.
        dao.variants(sourceId, "tf1").forEach { dao.setEpg(it.id, "tf1.fr") }
        val versions = dao.guideVersions(sourceId)
        assertEquals(listOf("TF1 FHD", "TF1 HD"), versions.map { it.name })
        assertEquals(listOf("TF1 FHD"), versions.distinctBy { it.epgChannelId }.map { it.name })
        // Version FHD masquée : la version HD représente la chaîne.
        db.hiddenGroupDao().hide(HiddenGroupEntity(sourceId, "LIVE", "FHD"))
        assertEquals(listOf("TF1 HD"), dao.guideVersions(sourceId).map { it.name })
    }

    @Test
    fun `guide-only sports channels are saved with the guide`() = runBlocking {
        val session = RoomPlaylistStore(db).beginEpgImport(sourceId)
        session.commit(emptyMap(), mapOf("ligue1plus5.fr" to "Ligue 1+ 5"))
        assertEquals(listOf("ligue1plus5.fr" to "Ligue 1+ 5"), db.guideOnlyChannelDao().list(sourceId).map { it.epgChannelId to it.name })
        // Import suivant : la liste est remplacée.
        RoomPlaylistStore(db).beginEpgImport(sourceId).commit(emptyMap(), emptyMap())
        assertEquals(emptyList<GuideOnlyChannelEntity>(), db.guideOnlyChannelDao().list(sourceId))
    }

    @Test
    fun `l'essentiel lists hand-picked channels once, apart from favourites`() = runBlocking {
        val dao = db.channelDao()
        dao.variants(sourceId, "tf1").forEach { dao.setEpg(it.id, "tf1.fr") }
        val favorites = tv.nextv.app.data.catalog.FavoritesRepository(db)
        assertEquals(true, favorites.toggleGuideSelection(sourceId, "live:tf1", "TF1", null))
        // Une seule ligne pour TF1 (sa meilleure version associée au guide), pas d'étoile de favori à l'accueil.
        assertEquals(listOf("TF1 FHD"), dao.guideSelection(sourceId).map { it.name })
        assertEquals(emptyList<String>(), db.favoriteDao().observeKeys(sourceId).first())
        assertEquals(listOf("live:tf1"), favorites.observeGuideSelection(sourceId).first())
        assertEquals(false, favorites.toggleGuideSelection(sourceId, "live:tf1", "TF1", null))
        assertEquals(emptyList<GuideVersionRow>(), dao.guideSelection(sourceId))
    }

    @Test
    fun `ids are listed page by page in id order`() = runBlocking {
        val dao = db.channelDao()
        val first = dao.idsAndUrls(sourceId, 0, 4)
        val second = dao.idsAndUrls(sourceId, first.last().id, 100)
        val ids = (first + second).map { it.id }
        assertEquals(9, ids.size)
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `item keys are unchanged by the faster normalization`() {
        fun regexVersion(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        for (text in listOf("Lupin", "  La Casa de Papel (ES) ", "Élite", "Ça -- c'est: ÉNORME!!", "Pokémon 2000", "İstanbul", "", "---", "Ｆｕｌｌ"))
            assertEquals(text, regexVersion(text), ItemKeys.normalize(text))
    }

    @Test
    fun oldMetadataSheetsArePurged() = runBlocking {
        val day = 24 * 60 * 60_000L
        val now = 1_800_000_000_000L
        val dao = db.tmdbDao()
        dao.put(TmdbCacheEntity(cacheKey = "movie:ancien", found = true, fetchedAt = now - 120 * day, title = "Ancien"))
        dao.put(TmdbCacheEntity(cacheKey = "movie:inconnu", found = false, fetchedAt = now - 95 * day))
        dao.put(TmdbCacheEntity(cacheKey = "movie:recent", found = true, fetchedAt = now - 40 * day, title = "Récent"))
        // Fiches lues depuis plus de trois mois : retirées ; les autres restent (même expirées, elles servent hors ligne).
        assertEquals(2, dao.deleteOlderThan(now - 90 * day))
        assertEquals(null, dao.get("movie:ancien"))
        assertEquals("Récent", dao.get("movie:recent")?.title)
    }

    @Test
    fun sheetsOfAFilmFoundUnderTwoTitlesAreKept() = runBlocking {
        val day = 24 * 60 * 60_000L
        val now = 1_800_000_000_000L
        val dao = db.tmdbDao()
        val old = now - 120 * day
        // « The Virtuoso » est rangé sous « Le Virtuose » : sa fiche n'est plus relue, mais elle relie les deux titres.
        dao.put(TmdbCacheEntity(cacheKey = "v3|movie:le virtuose:2021", found = true, fetchedAt = old, tmdbId = 808023, title = "Le Virtuose"))
        dao.put(TmdbCacheEntity(cacheKey = "v3|movie:the virtuoso:2021", found = true, fetchedAt = old, tmdbId = 808023, title = "Le Virtuose"))
        dao.put(TmdbCacheEntity(cacheKey = "v3|movie:ancien:2001", found = true, fetchedAt = old, tmdbId = 42, title = "Ancien"))
        // Une série de même numéro TMDB n'est pas le même titre.
        dao.put(TmdbCacheEntity(cacheKey = "v3|series:ancienne", found = true, fetchedAt = old, tmdbId = 42, title = "Ancienne"))
        assertEquals(2, dao.deleteOlderThan(now - 90 * day))
        assertEquals(setOf("v3|movie:le virtuose:2021", "v3|movie:the virtuoso:2021"), dao.movieTitles().map { it.cacheKey }.toSet())
        assertEquals(2, dao.movieTitlesOf(808023).size)
    }

    private suspend fun ChannelDao.observeAllGroupsOnce(): Map<String?, Int> =
        observeAllGroups(sourceId, "SERIES").first().associate { it.title to it.count }
}
