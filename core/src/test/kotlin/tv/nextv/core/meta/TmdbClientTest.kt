package tv.nextv.core.meta

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.nextv.core.net.HttpClientProvider

class TmdbClientTest {

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var key: String? = "abc123"
    private lateinit var client: TmdbClient

    private val searchJson = """
        {"results":[
          {"id":10,"title":"Avatar : La Voie de l'eau","original_title":"Avatar: The Way of Water"},
          {"id":19995,"title":"Avatar","original_title":"Avatar"}
        ]}
    """.trimIndent()

    private val detailsJson = """
        {"id":19995,"title":"Avatar","original_title":"Avatar","overview":"Sur Pandora…","tagline":"Entrez dans le monde",
         "release_date":"2009-12-15","vote_average":7.6,"vote_count":30000,"runtime":162,
         "genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science-Fiction"}],
         "poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg",
         "videos":{"results":[
           {"site":"YouTube","type":"Featurette","key":"feat","iso_639_1":"fr"},
           {"site":"YouTube","type":"Trailer","key":"en-trailer","iso_639_1":"en"},
           {"site":"YouTube","type":"Trailer","key":"fr-trailer","iso_639_1":"fr"}
         ]}}
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/search/movie") -> json(searchJson)
                    path.endsWith("/movie/19995") -> json(detailsJson)
                    path.endsWith("/search/tv") -> json("""{"results":[]}""")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        client = TmdbClient(HttpClientProvider(), apiKey = { key }, baseUrl = server.url("/3/"))
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `finds the best matching movie and reads its details`() = runBlocking {
        val result = client.lookup(MediaType.MOVIE, "Avatar", 2009) as TmdbResult.Found

        val details = result.details
        assertEquals(19995L, details.tmdbId)
        assertEquals("Avatar", details.title)
        assertEquals("Sur Pandora…", details.overview)
        assertEquals(2009, details.year)
        assertEquals(3.8, details.stars!!, 0.001)
        assertEquals(listOf("Action", "Science-Fiction"), details.genres)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", details.posterUrl)
        assertEquals("fr-trailer", details.trailerYoutubeKey)
        assertEquals(162, details.runtimeMinutes)

        val search = requests.first()
        assertEquals("Avatar", search.url.queryParameter("query"))
        assertEquals("2009", search.url.queryParameter("year"))
        assertEquals("fr-FR", search.url.queryParameter("language"))
        assertEquals("abc123", search.url.queryParameter("api_key"))
    }

    @Test
    fun `read access tokens are sent as bearer header`() = runBlocking {
        key = "eyJhbGciOi.token"
        client.lookup(MediaType.MOVIE, "Avatar", null)

        val search = requests.first()
        assertNull(search.url.queryParameter("api_key"))
        assertEquals("Bearer eyJhbGciOi.token", search.headers["Authorization"])
    }

    @Test
    fun `missing key, unknown titles and invalid keys`() = runBlocking {
        assertEquals(TmdbResult.NotFound, client.lookup(MediaType.SERIES, "Inconnue", 2020))

        key = null
        assertEquals(TmdbResult.Failed(TmdbError.MISSING_KEY), client.lookup(MediaType.MOVIE, "Avatar", null))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(401).build()
        }
        key = "bad"
        assertEquals(TmdbResult.Failed(TmdbError.INVALID_KEY), client.lookup(MediaType.MOVIE, "Avatar", null))
    }
}
