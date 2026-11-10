package tv.nextv.app.ui.guide

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.model.Channel
import tv.nextv.core.model.Programme
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput

/** Grille du guide : programmes chargés sans leur description, lue à la demande pour le programme sélectionné. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GuideDataTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val container = AppContainer(application)
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    private suspend fun playlistWithGuide(): Long {
        val sourceId = container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u")))
        container.playlistStore.replaceChannels(
            sourceId,
            listOf(Channel(position = 0, name = "TF1", stream = StreamDescriptor("http://127.0.0.1:9/live/1.ts"), tvgId = "tf1.fr", title = "TF1")),
        )
        val channelId = checkNotNull(container.channelDao.primaryIdByKey(sourceId, "live:tf1"))
        val session = container.playlistStore.beginEpgImport(sourceId)
        session.write(
            listOf(
                Programme("tf1.fr", now - hour, now + hour, "Journal", "Édition du soir", "Toute l’actualité du jour.", "Information", 2026, null),
                Programme("tf1.fr", now + hour, now + 3 * hour, "Le Film", null, "Un long résumé du film.", "Film", 1998, "http://img/film.jpg"),
                Programme("tf1.fr", now + 3 * hour, now + 4 * hour, "Sans résumé", null, null, null, null, null),
            ),
        )
        session.commit(mapOf(channelId to "tf1.fr"))
        return sourceId
    }

    @Test
    fun theGridLoadsProgrammesWithoutTheirDescription() = runBlocking {
        val sourceId = playlistWithGuide()
        val rows = container.programmeDao.gridWindow(sourceId, listOf("tf1.fr"), now - 24 * hour, now + 24 * hour)
        assertEquals(listOf("Journal", "Le Film", "Sans résumé"), rows.map { it.title })
        assertEquals("Édition du soir", rows[0].subtitle)
        assertEquals("http://img/film.jpg", rows[1].imageUrl)
        assertEquals(1998, rows[1].year)
        // Mêmes programmes que la lecture complète, dans le même ordre.
        val full = container.programmeDao.inWindow(sourceId, listOf("tf1.fr"), now - 24 * hour, now + 24 * hour)
        assertEquals(full.map { it.id }, rows.map { it.id })
        assertEquals("Un long résumé du film.", container.programmeDao.description(rows[1].id))
        assertNull(container.programmeDao.description(rows[2].id))
    }

    @Test
    fun theFocusedProgrammeGetsItsDescriptionOnDemand() = runBlocking {
        val sourceId = playlistWithGuide()
        val viewModel = GuideViewModel(container, sourceId, application.resources)
        val row = container.programmeDao.gridWindow(sourceId, listOf("tf1.fr"), now - 24 * hour, now + 24 * hour)[1]
        val light = GuideProgramme(row.title, row.startMillis, row.stopMillis, row.subtitle, description = null, row.category, row.year, row.imageUrl, id = row.id)

        val described = viewModel.describe(light)
        assertEquals("Un long résumé du film.", described.description)
        assertEquals(light.copy(description = "Un long résumé du film."), described)

        // Programme déjà complet, sans numéro, ou remplacé par un nouvel import du guide : rendu tel quel.
        assertTrue(viewModel.describe(described) === described)
        val foreign = light.copy(id = 0)
        assertTrue(viewModel.describe(foreign) === foreign)
        val replaced = light.copy(id = 9_999_999)
        assertTrue(viewModel.describe(replaced) === replaced)
    }
}
