package tv.nextv.app.data.catalog

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.core.meta.MediaType
import tv.nextv.core.meta.TmdbClient
import tv.nextv.core.net.HttpClientProvider
import java.util.Collections

/**
 * Fiche d'une série dont le serveur Xtream donne l'identifiant TMDB : celle-là, même quand une autre série du même nom
 * (trouvée par le titre) est déjà en cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MetadataRepositoryTest {

    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var database: NexTvDatabase
    private lateinit var repository: MetadataRepository

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                paths += path
                val body = when {
                    // « Prisoner » : le feuilleton australien de 1979 vient en premier dans la recherche.
                    path.endsWith("/search/tv") -> """{"results":[{"id":1979,"name":"Prisoner"},{"id":311858,"name":"Prisoner"}]}"""
                    path.endsWith("/tv/1979") -> """{"id":1979,"name":"Prisoner","first_air_date":"1979-02-27"}"""
                    path.endsWith("/tv/311858") -> """{"id":311858,"name":"Prisoner","first_air_date":"2026-04-30"}"""
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NexTvDatabase::class.java).allowMainThreadQueries().build()
        repository = MetadataRepository(database, TmdbClient(HttpClientProvider(), apiKey = { "cle" }, baseUrl = server.url("/3/")))
    }

    @After
    fun stop() {
        database.close()
        server.close()
    }

    private fun MetadataState.year() = (this as MetadataState.Available).details.year

    @Test
    fun theProviderIdReplacesASeriesFoundByItsTitle() = runBlocking {
        // Par le titre seul (affiches de l'accueil) : la série de 1979, mise en cache.
        assertEquals(1979, repository.details("v:series:prisoner", MediaType.SERIES, "Prisoner", null).year())
        // Fiche ouverte avec l'identifiant du serveur : la bonne série, qui remplace l'autre dans le cache.
        assertEquals(2026, repository.details("v:series:prisoner", MediaType.SERIES, "Prisoner", 2026, tmdbId = 311858).year())
        assertEquals(2026, repository.details("v:series:prisoner", MediaType.SERIES, "Prisoner", null).year())
        // Rouverte avec le même identifiant : le cache suffit.
        val asked = paths.size
        assertEquals(2026, repository.details("v:series:prisoner", MediaType.SERIES, "Prisoner", null, tmdbId = 311858).year())
        assertEquals(asked, paths.size)
    }
}
