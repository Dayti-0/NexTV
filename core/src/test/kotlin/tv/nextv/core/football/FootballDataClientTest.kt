package tv.nextv.core.football

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

class FootballDataClientTest {

    private val server = MockWebServer()
    private var status = 200
    private var now = 1_793_404_800_000L // 2026-10-31T00:00:00Z
    private var key: String? = "secret-token"
    private lateinit var client: FootballDataClient

    private val body = """
        {
          "filters": {"dateFrom": "2026-10-31", "dateTo": "2026-11-08"},
          "resultSet": {"count": 3},
          "matches": [
            {
              "id": 11, "utcDate": "2026-10-31T20:00:00Z", "status": "TIMED", "matchday": 9,
              "competition": {"id": 2015, "name": "Ligue 1", "code": "FL1"},
              "homeTeam": {"id": 524, "name": "Paris Saint-Germain FC", "shortName": "PSG", "tla": "PSG", "crest": "https://crests.football-data.org/524.svg"},
              "awayTeam": {"id": 516, "name": "Olympique de Marseille", "shortName": "Marseille", "tla": "MAR", "crest": "https://crests.football-data.org/516.png"}
            },
            {
              "id": 12, "utcDate": "2026-11-04T20:00:00Z", "status": "IN_PLAY",
              "competition": {"id": 2001, "name": "UEFA Champions League", "code": "CL"},
              "homeTeam": {"id": 5, "name": "FC Bayern München", "shortName": "Bayern", "tla": "FCB", "crest": null},
              "awayTeam": {"id": 81, "name": "FC Barcelona", "shortName": "Barça", "tla": "FCB", "crest": null}
            },
            {
              "id": 13, "utcDate": "2026-11-05T20:00:00Z", "status": "SCHEDULED",
              "competition": {"id": 2001, "name": "UEFA Champions League", "code": "CL"},
              "homeTeam": {"id": null, "name": null, "shortName": null, "tla": null, "crest": null},
              "awayTeam": {"id": 81, "name": "FC Barcelona", "shortName": "Barça", "tla": "FCB", "crest": null}
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (status == 200) MockResponse.Builder().code(200).body(body).build() else MockResponse.Builder().code(status).build()
        }
        server.start()
        client = FootballDataClient(HttpClientProvider(), { key }, baseUrl = server.url("/v4/"), clock = { now })
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `matches are read with competition, teams and status`() = runBlocking {
        val result = client.matches(now, now + 8 * DAY) as FootballDataResult.Success
        val request = server.takeRequest()
        assertEquals("secret-token", request.headers["X-Auth-Token"])
        assertEquals("/v4/matches", request.url.encodedPath)
        assertEquals("2015,2021,2014,2019,2002,2001", request.url.queryParameter("competitions"))
        assertEquals("2026-10-31", request.url.queryParameter("dateFrom"))
        assertEquals("2026-11-08", request.url.queryParameter("dateTo"))

        // Le match dont une équipe n'est pas encore connue est ignoré.
        assertEquals(listOf(11L, 12L), result.matches.map { it.id })
        val classico = result.matches[0]
        assertEquals(Competition.LIGUE_1, classico.competition)
        assertEquals(1_793_476_800_000L, classico.kickoffMillis)
        assertEquals(MatchStatus.SCHEDULED, classico.status)
        assertEquals("PSG", classico.home.displayName)
        assertEquals("https://crests.football-data.org/524.png", classico.home.crestUrl)
        assertEquals(MatchStatus.LIVE, result.matches[1].status)
        assertEquals(Competition.CHAMPIONS_LEAGUE, result.matches[1].competition)
        assertNull(result.matches[1].home.crestUrl)
    }

    @Test
    fun `without key nothing is requested`() = runBlocking {
        key = null
        assertEquals(FootballDataResult.Failed(FootballDataError.MISSING_KEY), client.matches(now, now + DAY))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refused key and rate limit are reported`() = runBlocking {
        status = 400
        assertEquals(FootballDataResult.Failed(FootballDataError.INVALID_KEY), client.matches(now, now + DAY))
        status = 429
        assertEquals(FootballDataResult.Failed(FootballDataError.RATE_LIMITED), client.matches(now, now + DAY))
    }

    @Test
    fun `recent answers are reused, and kept when the service fails`() = runBlocking {
        val first = client.matches(now, now + DAY)
        now += 10 * 60_000L
        assertEquals(first, client.matches(now, now + DAY))
        assertEquals(1, server.requestCount)

        now += 60 * 60_000L
        status = 503
        assertEquals(first, client.matches(now, now + DAY))
        assertEquals(2, server.requestCount)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
