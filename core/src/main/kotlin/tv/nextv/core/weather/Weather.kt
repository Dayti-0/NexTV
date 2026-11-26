package tv.nextv.core.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Ville de la météo : nom affiché et position. */
data class WeatherPlace(val name: String, val region: String?, val latitude: Double, val longitude: Double) {
    /** Forme gardée dans les réglages : « Montpellier|Occitanie|43.61093|3.87635 ». */
    fun encode(): String = listOf(name, region.orEmpty(), latitude.toString(), longitude.toString()).joinToString("|") { it.replace("|", " ") }

    companion object {
        fun decode(value: String?): WeatherPlace? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 4) return null
            val latitude = parts[2].toDoubleOrNull() ?: return null
            val longitude = parts[3].toDoubleOrNull() ?: return null
            return WeatherPlace(parts[0].ifBlank { return null }, parts[1].ifBlank { null }, latitude, longitude)
        }
    }
}

/** Temps qu'il fait, d'après le code météo de l'Organisation météorologique mondiale. */
enum class Sky {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SHOWERS, SNOW, STORM;

    companion object {
        fun of(code: Int): Sky = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67 -> RAIN
            in 80..82 -> SHOWERS
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}

/** Une heure à venir : « 23 h », 5°, pluie, nuit, risque de pluie en %. */
data class WeatherHour(val hour: Int, val temperature: Int, val sky: Sky, val day: Boolean, val rainChance: Int? = null)

/** Météo d'une ville : maintenant, les prochaines heures et demain. */
data class Weather(
    val place: WeatherPlace,
    val temperature: Int,
    val sky: Sky,
    val day: Boolean,
    val todayMin: Int,
    val todayMax: Int,
    val hours: List<WeatherHour>,
    val tomorrowSky: Sky?,
    val tomorrowMin: Int?,
    val tomorrowMax: Int?,
    /** Température ressentie. */
    val feelsLike: Int? = null,
    /** Humidité (%). */
    val humidity: Int? = null,
    /** Vent moyen (km/h). */
    val windKmh: Int? = null,
    /** Risque de pluie le plus fort d'aujourd'hui et de demain (%). */
    val todayRainChance: Int? = null,
    val tomorrowRainChance: Int? = null,
    /** Lever et coucher du soleil, heure locale de la ville (« 08:14 »). */
    val sunrise: String? = null,
    val sunset: String? = null,
)

/**
 * Météo par Open-Meteo (gratuit, sans clé ni compte ; seule la position de la ville est envoyée). Réponses gardées
 * [cacheMillis] par ville ; en cas d'échec (réseau, limite du jour atteinte), la précédente reste servie.
 */
class WeatherClient(
    private val clients: HttpClientProvider,
    private val forecastUrl: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val geocodingUrl: HttpUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30 * 60_000L,
    /** Nouvel essai après un échec, pas avant. */
    private val retryMillis: Long = 5 * 60_000L,
    private val callTimeoutMillis: Long = 8_000,
) {
    private val mutex = Mutex()
    private var cached: Pair<Long, Weather>? = null
    private var failedAt: Pair<WeatherPlace, Long>? = null

    /** Météo de [place] ; `null` tant qu'elle n'a jamais pu être lue. */
    suspend fun forecast(place: WeatherPlace): Weather? = mutex.withLock {
        val now = clock()
        val previous = cached?.takeIf { it.second.place == place }
        if (previous != null && now - previous.first < cacheMillis) return@withLock previous.second
        if (failedAt?.let { it.first == place && now - it.second < retryMillis } == true) return@withLock previous?.second
        val url = forecastUrl.newBuilder()
            .addQueryParameter("latitude", place.latitude.format())
            .addQueryParameter("longitude", place.longitude.format())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day")
            .addQueryParameter("hourly", "temperature_2m,weather_code,is_day,precipitation_probability")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset")
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("timezone", "auto")
            .build()
        val weather = fetchJson(url)?.let { parseForecast(it, place) }
        if (weather == null) {
            failedAt = place to now
            return@withLock previous?.second
        }
        failedAt = null
        cached = now to weather
        weather
    }

    /** Villes dont le nom commence par [name] (les plus peuplées d'abord), en français. */
    suspend fun search(name: String): List<WeatherPlace>? {
        val query = name.trim().takeIf { it.length >= 2 } ?: return emptyList()
        val url = geocodingUrl.newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "fr")
            .build()
        return fetchJson(url)?.let(::parsePlaces)
    }

    private suspend fun fetchJson(url: HttpUrl): JSONObject? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        withContext(Dispatchers.IO) {
            call.await().use { response -> if (response.isSuccessful) JSONObject(response.body.string()) else null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Météo injoignable", e)
        null
    } catch (e: JSONException) {
        logger.warn(TAG, "Météo illisible", e)
        null
    }

    companion object {
        private const val TAG = "Weather"

        /** Heures à venir montrées. */
        const val HOURS = 5

        private fun Double.format(): String = String.format(Locale.ROOT, "%.4f", this)

        /**
         * Réponse « forecast » d'Open-Meteo (heures locales de la ville, `timezone=auto`) ; `null` si elle est illisible
         * ou si c'est une erreur (« Daily API request limit exceeded »).
         */
        fun parseForecast(json: JSONObject, place: WeatherPlace): Weather? {
            if (json.optBoolean("error")) return null
            val current = json.optJSONObject("current") ?: return null
            val time = current.optString("time").takeIf { it.length >= 13 } ?: return null
            val temperature = current.optDouble("temperature_2m").takeIf { !it.isNaN() } ?: return null
            val hourly = json.optJSONObject("hourly")
            val hours = hourly?.let { block ->
                val times = block.optJSONArray("time") ?: return@let emptyList()
                val temperatures = block.optJSONArray("temperature_2m") ?: return@let emptyList()
                val codes = block.optJSONArray("weather_code") ?: return@let emptyList()
                val days = block.optJSONArray("is_day")
                val rain = block.optJSONArray("precipitation_probability")
                // Heure en cours (« 2026-11-25T22 ») : les suivantes.
                val currentHour = time.substring(0, 13)
                val start = (0 until times.length()).firstOrNull { times.optString(it).startsWith(currentHour) } ?: return@let emptyList()
                (start + 1 until minOf(times.length(), start + 1 + HOURS)).mapNotNull { index ->
                    val hour = times.optString(index).substring(11, 13).toIntOrNull() ?: return@mapNotNull null
                    val value = temperatures.optDouble(index).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    val chance = rain?.optDouble(index)?.takeIf { !it.isNaN() }?.roundToInt()
                    WeatherHour(hour, value.roundToInt(), Sky.of(codes.optInt(index)), days?.optInt(index, 1) != 0, chance)
                }
            }.orEmpty()
            val daily = json.optJSONObject("daily")
            fun dailyValue(name: String, index: Int): Double? =
                daily?.optJSONArray(name)?.optDouble(index)?.takeIf { !it.isNaN() }
            val tomorrowCode = daily?.optJSONArray("weather_code")?.takeIf { it.length() > 1 }?.optInt(1)
            fun current(name: String): Int? = current.optDouble(name).takeIf { !it.isNaN() }?.roundToInt()
            /** « 2026-11-25T08:14 » → « 08:14 ». */
            fun clock(name: String): String? = daily?.optJSONArray(name)?.optString(0)?.takeIf { it.length >= 16 }?.substring(11, 16)
            return Weather(
                place = place,
                temperature = temperature.roundToInt(),
                sky = Sky.of(current.optInt("weather_code")),
                day = current.optInt("is_day", 1) != 0,
                todayMin = (dailyValue("temperature_2m_min", 0) ?: temperature).roundToInt(),
                todayMax = (dailyValue("temperature_2m_max", 0) ?: temperature).roundToInt(),
                hours = hours,
                tomorrowSky = tomorrowCode?.let(Sky::of),
                tomorrowMin = dailyValue("temperature_2m_min", 1)?.roundToInt(),
                tomorrowMax = dailyValue("temperature_2m_max", 1)?.roundToInt(),
                feelsLike = current("apparent_temperature"),
                humidity = current("relative_humidity_2m"),
                windKmh = current("wind_speed_10m"),
                todayRainChance = dailyValue("precipitation_probability_max", 0)?.roundToInt(),
                tomorrowRainChance = dailyValue("precipitation_probability_max", 1)?.roundToInt(),
                sunrise = clock("sunrise"),
                sunset = clock("sunset"),
            )
        }

        /** Réponse « search » du géocodage d'Open-Meteo. */
        fun parsePlaces(json: JSONObject): List<WeatherPlace> {
            val results = json.optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { index ->
                val place = results.optJSONObject(index) ?: return@mapNotNull null
                val name = place.optString("name").trim().ifEmpty { return@mapNotNull null }
                val latitude = place.optDouble("latitude").takeIf { !it.isNaN() } ?: return@mapNotNull null
                val longitude = place.optDouble("longitude").takeIf { !it.isNaN() } ?: return@mapNotNull null
                // Région, et le pays hors de France (« Montréal · Québec, Canada »).
                val region = listOfNotNull(
                    place.optString("admin1").trim().takeIf { it.isNotEmpty() && it != name },
                    place.optString("country").trim().takeIf { it.isNotEmpty() && place.optString("country_code") != "FR" },
                ).joinToString(", ").ifEmpty { null }
                WeatherPlace(name, region, latitude, longitude)
            }
        }
    }
}
