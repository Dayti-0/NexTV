package tv.nextv.app.data.progress

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.backup.BackupCodec
import tv.nextv.core.backup.SettingBackup
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpisodeInfo
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput
import kotlinx.coroutines.flow.first
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Reprises de lecture après une actualisation de la playlist qui change l'adresse des flux (donc le numéro de
 * ligne de chaque titre), et à travers une sauvegarde : le film ou l'épisode est retrouvé par sa clé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressRelinkTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao

    private fun channels(host: String) = listOf(
        Channel(position = 0, name = "Le Film (2020)", stream = StreamDescriptor("http://$host/movie/1.mkv"), kind = ContentKind.MOVIE, title = "Le Film", year = 2020),
        Channel(position = 1, name = "Lupin S01 E01", stream = StreamDescriptor("http://$host/series/11.mkv"), kind = ContentKind.SERIES, title = "Lupin", episode = EpisodeInfo("Lupin", 1, 1)),
        Channel(position = 2, name = "Lupin S01 E02", stream = StreamDescriptor("http://$host/series/12.mkv"), kind = ContentKind.SERIES, title = "Lupin", episode = EpisodeInfo("Lupin", 1, 2)),
        Channel(position = 3, name = "Retiré (2019)", stream = StreamDescriptor("http://$host/movie/9.mkv"), kind = ContentKind.MOVIE, title = "Retiré", year = 2019),
    )

    private suspend fun addPlaylist(host: String = "a.example"): Long {
        val id = container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://$host/list.m3u")))
        container.playlistStore.replaceChannels(id, channels(host))
        return id
    }

    private suspend fun movieId(sourceId: Long) = checkNotNull(dao.movieIdByKey(sourceId, "movie:le film:2020"))
    private suspend fun episodeId(sourceId: Long, episode: Int) = checkNotNull(dao.episodeIdByKey(sourceId, "series:lupin", 1, episode))

    private fun progress(sourceId: Long, itemKey: String, kind: ContentKind, channelId: Long, label: String? = null, position: Long = 600_000) =
        ProgressEntity(sourceId, itemKey, kind.name, channelId, "Titre", label, null, position, 3_600_000, 1_000)

    @Test
    fun resumeFollowsTheTitleWhenItsStreamAddressChanges() = runBlocking {
        val sourceId = addPlaylist()
        val oldMovie = movieId(sourceId)
        val oldEpisode = episodeId(sourceId, 2)
        val removed = checkNotNull(dao.movieIdByKey(sourceId, "movie:retire:2019"))
        container.progress.save(progress(sourceId, "movie:le film:2020", ContentKind.MOVIE, oldMovie))
        container.progress.save(progress(sourceId, "series:lupin", ContentKind.SERIES, oldEpisode, label = "S01E02"))
        container.progress.save(progress(sourceId, "movie:retire:2019", ContentKind.MOVIE, removed))

        // Le fournisseur change de serveur : mêmes titres, autres adresses, donc autres numéros de ligne ; un film
        // est retiré.
        container.playlistStore.replaceChannels(sourceId, channels("b.example").dropLast(1))
        assertNotEquals(oldMovie, movieId(sourceId))
        assertFalse(container.progress.isLinked(checkNotNull(container.progress.get(sourceId, "movie:le film:2020")), dao))

        container.progress.relink(sourceId, dao)

        val movie = checkNotNull(container.progress.get(sourceId, "movie:le film:2020"))
        assertEquals(movieId(sourceId), movie.channelId)
        assertEquals(600_000, movie.positionMs)
        assertTrue(container.progress.isLinked(movie, dao))
        assertEquals(episodeId(sourceId, 2), checkNotNull(container.progress.get(sourceId, "series:lupin")).channelId)
        // Titre retiré de la playlist : sa reprise est gardée telle quelle (elle sera rattachée s'il revient).
        val gone = checkNotNull(container.progress.get(sourceId, "movie:retire:2019"))
        assertEquals(removed, gone.channelId)
        assertFalse(container.progress.isLinked(gone, dao))

        // Il revient : rattaché.
        container.playlistStore.replaceChannels(sourceId, channels("c.example"))
        container.progress.relink(sourceId, dao)
        assertTrue(container.progress.isLinked(checkNotNull(container.progress.get(sourceId, "movie:retire:2019")), dao))
    }

    @Test
    fun episodeLabelsGiveSeasonAndNumber() {
        assertEquals(1 to 8, ProgressRepository.episodeOf("S01E08"))
        assertEquals(null to 12, ProgressRepository.episodeOf("E12"))
        assertNull(ProgressRepository.episodeOf(null))
        assertNull(ProgressRepository.episodeOf("Épisode spécial"))
    }

    @Test
    fun backupCarriesResumePositionsAndWatchedTitles() = runBlocking {
        val sourceId = addPlaylist()
        container.progress.save(progress(sourceId, "movie:le film:2020", ContentKind.MOVIE, movieId(sourceId)))
        container.progress.save(progress(sourceId, "series:lupin", ContentKind.SERIES, episodeId(sourceId, 2), label = "S01E02"))
        container.watched.mark(sourceId, "series:lupin|S1E1")
        val text = container.backup.export()
        val saved = BackupCodec.decode(text).playlists.single()
        assertEquals(setOf("movie:le film:2020", "series:lupin"), saved.progress.map { it.itemKey }.toSet())
        assertEquals(listOf("series:lupin|S1E1"), saved.watched.map { it.itemKey })

        // Autre appareil (ou playlist supprimée) : la playlist restaurée a un autre numéro et n'est pas encore chargée.
        container.sourceRepository.delete(sourceId)
        val result = container.backup.restore(text)
        assertEquals(1, result.added)
        val restored = container.sourceRepository.sources.first().single().id
        assertNotEquals(sourceId, restored)
        val pending = checkNotNull(container.progress.get(restored, "movie:le film:2020"))
        assertEquals(600_000, pending.positionMs)
        assertFalse(container.progress.isLinked(pending, dao))
        assertEquals(setOf("series:lupin|S1E1"), container.watched.observeKeys(restored).first())

        // Premier chargement de la playlist : les reprises retrouvent leur film et leur épisode.
        container.playlistStore.replaceChannels(restored, channels("a.example"))
        container.progress.relink(restored, dao)
        assertEquals(movieId(restored), checkNotNull(container.progress.get(restored, "movie:le film:2020")).channelId)
        assertEquals(episodeId(restored, 2), checkNotNull(container.progress.get(restored, "series:lupin")).channelId)

        // Restaurée une seconde fois : rien n'est dupliqué, et une reprise plus récente sur l'appareil est gardée.
        container.progress.save(progress(restored, "movie:le film:2020", ContentKind.MOVIE, movieId(restored), position = 1_200_000))
        assertEquals(0, container.backup.restore(text).added)
        assertEquals(1_200_000, checkNotNull(container.progress.get(restored, "movie:le film:2020")).positionMs)
    }

    @Test
    fun restoredSettingsOfTheWrongTypeAreIgnored() = runBlocking {
        // Fichier de sauvegarde modifié ou abîmé : un réglage du mauvais type ferait planter chaque lecture.
        container.settings.importPreferences(
            listOf(
                SettingBackup("theme_style", 5),
                SettingBackup("auto_quality", "oui"),
                SettingBackup("tiles_per_row", true),
                SettingBackup("réglage_inconnu", "x"),
                SettingBackup("hide_uhd", true),
                SettingBackup("aspect_mode", "ZOOM"),
            ),
        )
        assertEquals(ThemeStyle.NIGHT, container.settings.themeStyle.first())
        assertTrue(container.settings.autoQuality.first())
        assertEquals(4, container.settings.gridColumns.first().perRow)
        // Les réglages valides du même fichier sont repris.
        assertTrue(container.settings.hideUhd.first())
        assertEquals(tv.nextv.app.data.settings.AspectMode.ZOOM, container.settings.aspectMode.first())
        assertTrue(container.settings.exportPreferences().none { it.key == "réglage_inconnu" })
    }
}
