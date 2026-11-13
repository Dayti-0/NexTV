package tv.nextv.app.data.catalog

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.app.ui.detail.DetailViewModel
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpisodeInfo
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput
import java.util.Collections

/**
 * Durée des épisodes lue sur le serveur Xtream de la playlist : la fiche de la série affiche celle de chaque épisode,
 * même quand TMDB ne la donne pas (série trop récente).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EpisodeDurationsTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val server = MockWebServer()
    private val actions: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                if (url.encodedPath != "/player_api.php") return MockResponse.Builder().code(404).build()
                val action = url.queryParameter("action").orEmpty()
                actions += action + (url.queryParameter("category_id")?.let { ":$it" } ?: "") + (url.queryParameter("series_id")?.let { ":$it" } ?: "")
                val body = when (action) {
                    "get_series_categories" -> """[{"category_id":"7","category_name":"Séries VOSTFR"},{"category_id":"8","category_name":"K-Drama"}]"""
                    "get_series" -> when (url.queryParameter("category_id")) {
                        // Même titre en VOSTFR (autres épisodes) et en MULTI (ceux de la playlist).
                        "8" -> """[{"name":"L'impératrice remariée (VOSTFR)","series_id":50},{"name":"L'impératrice remariée (MULTI) FHD","series_id":51},{"name":"Autre","series_id":52}]"""
                        else -> "[]"
                    }
                    "get_series_info" -> when (url.queryParameter("series_id")) {
                        "50" -> """{"episodes":{"1":[{"id":"9001","info":{"duration_secs":3000}}]}}"""
                        "51" -> """{"episodes":{"1":[{"id":"1001","info":{"duration_secs":3480}},{"id":"1002","info":{"duration":"01:02:10"}}]}}"""
                        else -> """{"episodes":{}}"""
                    }
                    else -> """{"user_info":{"auth":1,"status":"Active"}}"""
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun idle(until: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
    }

    /** Playlist Xtream d'une série de trois épisodes (le troisième, inconnu du serveur). */
    private fun playlist(): Pair<Long, Long> = runBlocking {
        val root = server.url("/").toString().trimEnd('/')
        val id = container.sourceRepository.add(SourceInput("Xtream", SourceLocation.Remote("$root/get.php?username=u&password=p&type=m3u_plus")))
        val channels = (1..3).map { number ->
            val name = "L'impératrice remariée (MULTI) FHD S01 E0$number"
            val url = "$root/series/u/p/${1000 + number}.mkv"
            val result = ContentClassifier.classify(name, url, "K-Drama")
            assertEquals(ContentKind.SERIES, result.kind)
            Channel(
                position = number - 1, name = name, stream = StreamDescriptor(url), groupTitle = "K-Drama", kind = ContentKind.SERIES,
                title = result.title, episode = result.episode ?: EpisodeInfo(result.title, 1, number),
            )
        }
        container.playlistStore.replaceChannels(id, channels) {}
        val first = checkNotNull(container.channelDao.episodes(id, "series:l imperatrice remariee").firstOrNull()).id
        id to first
    }

    @Test
    fun theSheetShowsTheDurationOfEachEpisode() {
        val (_, first) = playlist()
        val viewModel = DetailViewModel(container, first, ContentKind.SERIES)
        idle { viewModel.state.value.episodeSeconds.isNotEmpty() }
        val state = viewModel.state.value
        val episodes = state.seasons.single().episodes
        // 58 min, 1 h 02 ; le troisième (inconnu du serveur, pas de fiche TMDB) : sans durée.
        assertEquals(listOf(58, 62, null), episodes.map { state.episodeMinutes(it) })
        // Catégorie de la playlist, séries de cette catégorie, puis la fiche de la bonne version (la VOSTFR n'a pas ces épisodes).
        assertTrue("Demandes : $actions", actions.containsAll(listOf("get_series_categories", "get_series:8", "get_series_info:51")))
        assertTrue("Tout le catalogue demandé : $actions", "get_series" !in actions)

        // Fiche rouverte : rien n'est redemandé au serveur.
        val asked = actions.size
        val again = DetailViewModel(container, first, ContentKind.SERIES)
        idle { again.state.value.episodeSeconds.isNotEmpty() }
        assertEquals(2, again.state.value.episodeSeconds.size)
        assertEquals(asked, actions.size)
    }

    @Test
    fun aPlaylistWithoutXtreamAsksNothing() = runBlocking {
        val durations = container.episodeDurations.of(1, "serie", "Séries", listOf("http://example.com/serie/s01e01.mkv"))
        assertEquals(emptyMap<String, Int>(), durations)
        assertTrue(actions.isEmpty())
    }
}
