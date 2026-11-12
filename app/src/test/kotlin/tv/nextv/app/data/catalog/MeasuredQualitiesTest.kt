package tv.nextv.app.data.catalog

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.source.VodVideo

/**
 * Films sans qualité dans leur nom : la fiche du serveur Xtream ou la lecture donnent leur badge, gardé quand la
 * playlist est actualisée.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeasuredQualitiesTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao

    private fun movie(position: Int, title: String, quality: StreamQuality? = null) = Channel(
        position = position, name = title, stream = StreamDescriptor("http://example.com/movie/u/p/${100 + position}.mkv"),
        groupTitle = "Films", kind = ContentKind.MOVIE, title = title, quality = quality,
    )

    private val episode = Channel(
        position = 3, name = "Série S01 E01", stream = StreamDescriptor("http://example.com/series/u/p/900.mkv"),
        groupTitle = "Séries", kind = ContentKind.SERIES, title = "Série S01 E01",
    )

    @Test
    fun serverAndPlaybackGiveTheBadgeAndItSurvivesARefresh() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        val playlist = listOf(movie(0, "Sans mention"), movie(1, "Fiche muette"), movie(2, "Annoncé", StreamQuality.HD), episode)
        container.playlistStore.replaceChannels(id, playlist) {}
        val asked = mutableListOf<String>()
        val qualities = MeasuredQualities(dao, { container.playlistStore.getSource(it) }, { _, streamId ->
            asked += streamId
            if (streamId == "100") VodVideo(1920, 800) else VodVideo(null, null)
        })
        suspend fun row(position: Int) = dao.idsAndUrls(id, 0, 100).let { rows -> dao.get(rows[position].id)!! }

        // Fiche du serveur : 1920 × 800 → FHD, posé sur la ligne.
        assertEquals(StreamQuality.FHD, qualities.probe(row(0)))
        assertEquals("FHD", row(0).quality)
        // Serveur muet : rien, et pas redemandé ; film qui annonce sa qualité : jamais demandé.
        assertNull(qualities.probe(row(1)))
        assertNull(qualities.probe(row(1)))
        assertNull(qualities.probe(row(2)))
        assertEquals(listOf("100", "101"), asked)
        // Une autre session (nouvelle instance) ne redemande pas un film déjà connu.
        val again = MeasuredQualities(dao, { container.playlistStore.getSource(it) }, { _, streamId -> asked += streamId; null })
        assertNull(again.probe(row(1)))
        assertEquals(listOf("100", "101"), asked)

        // Lecture : l'épisode lu en 1280 × 720 → HD ; le film muet lu en 3840 × 2160 → 4K.
        qualities.recordPlayback(row(3), 1280, 720)
        qualities.recordPlayback(row(1), 3840, 2160)
        assertEquals("HD", row(3).quality)
        assertEquals("UHD", row(1).quality)

        // Actualisation de la playlist : les qualités trouvées restent, celle annoncée par le nom aussi.
        container.playlistStore.replaceChannels(id, playlist) {}
        assertEquals(listOf("FHD", "UHD", "HD", "HD"), (0..3).map { row(it).quality })
    }
}
