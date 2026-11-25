package tv.nextv.core.weather

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.nextv.core.net.HttpClientProvider

class WeatherClientTest {

    private val paris = WeatherPlace("Paris", "Île-de-France", 48.8534, 2.3488)

    /** Réponse relevée le 25/11/2026 à 22 h 30 (heures locales de la ville). */
    private val forecast = """
        {"latitude":48.85,"longitude":2.35,"utc_offset_seconds":3600,"timezone":"Europe/Paris",
         "current":{"time":"2026-11-25T22:30","interval":900,"temperature_2m":5.9,"apparent_temperature":3.3,"relative_humidity_2m":94,"wind_speed_10m":8.4,"weather_code":80,"is_day":0},
         "hourly":{"time":["2026-11-25T21:00","2026-11-25T22:00","2026-11-25T23:00","2026-11-26T00:00","2026-11-26T01:00","2026-11-26T02:00","2026-11-26T03:00"],
                   "temperature_2m":[5.8,5.9,5.8,5.4,5.6,4.2,4.0],"weather_code":[53,61,80,3,0,45,71],"is_day":[0,0,0,0,0,0,0],
                   "precipitation_probability":[40,38,35,28,20,10,null]},
         "daily":{"time":["2026-11-25","2026-11-26"],"weather_code":[80,2],"temperature_2m_max":[5.9,10.6],"temperature_2m_min":[4.0,1.2],
                  "precipitation_probability_max":[100,28],"sunrise":["2026-11-25T08:14","2026-11-26T08:15"],"sunset":["2026-11-25T17:00","2026-11-26T16:59"]}}
    """.trimIndent()

    private val server = MockWebServer()
    private var now = 0L
    private var answer = forecast
    private var calls = 0
    private lateinit var client: WeatherClient

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return MockResponse.Builder().body(answer).build()
            }
        }
        server.start()
        client = WeatherClient(HttpClientProvider(), forecastUrl = server.url("/v1/forecast"), geocodingUrl = server.url("/v1/search"), clock = { now })
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `reads now, the next hours and tomorrow`() {
        val weather = WeatherClient.parseForecast(JSONObject(forecast), paris)!!
        assertEquals(6, weather.temperature)
        assertEquals(Sky.SHOWERS, weather.sky)
        assertFalse(weather.day)
        assertEquals(4, weather.todayMin)
        assertEquals(6, weather.todayMax)
        assertEquals(listOf(23, 0, 1, 2, 3), weather.hours.map { it.hour })
        assertEquals(listOf(Sky.SHOWERS, Sky.CLOUDY, Sky.CLEAR, Sky.FOG, Sky.SNOW), weather.hours.map { it.sky })
        assertEquals(listOf(6, 5, 6, 4, 4), weather.hours.map { it.temperature })
        assertEquals(listOf(35, 28, 20, 10, null), weather.hours.map { it.rainChance })
        assertEquals(3, weather.feelsLike)
        assertEquals(94, weather.humidity)
        assertEquals(8, weather.windKmh)
        assertEquals(100, weather.todayRainChance)
        assertEquals(28, weather.tomorrowRainChance)
        assertEquals("08:14", weather.sunrise)
        assertEquals("17:00", weather.sunset)
        assertEquals(Sky.PARTLY_CLOUDY, weather.tomorrowSky)
        assertEquals(1, weather.tomorrowMin)
        assertEquals(11, weather.tomorrowMax)
    }

    @Test
    fun `daily limit answer is not a forecast`() {
        assertNull(WeatherClient.parseForecast(JSONObject("""{"reason":"Daily API request limit exceeded.","error":true}"""), paris))
    }

    @Test
    fun `answer is kept half an hour and the last one survives a failure`() = runBlocking {
        assertEquals(6, client.forecast(paris)?.temperature)
        now += 10 * 60_000L
        client.forecast(paris)
        assertEquals(1, calls)
        // Plus tard : limite du jour atteinte, la météo d'avant reste affichée.
        now += 30 * 60_000L
        answer = """{"reason":"Daily API request limit exceeded.","error":true}"""
        assertEquals(6, client.forecast(paris)?.temperature)
        assertEquals(2, calls)
        // Pas de nouvel essai avant quelques minutes.
        now += 60_000L
        client.forecast(paris)
        assertEquals(2, calls)
    }

    @Test
    fun `cities are found with their region`() {
        val places = WeatherClient.parsePlaces(
            JSONObject(
                """{"results":[
                  {"name":"Montpellier","latitude":43.61093,"longitude":3.87635,"country_code":"FR","country":"France","admin1":"Occitanie"},
                  {"name":"Montréal","latitude":45.50884,"longitude":-73.58781,"country_code":"CA","country":"Canada","admin1":"Québec"}]}""",
            ),
        )
        assertEquals(listOf("Occitanie", "Québec, Canada"), places.map { it.region })
        assertEquals(places[0], WeatherPlace.decode(places[0].encode()))
    }
}
