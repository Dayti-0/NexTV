package tv.nextv.core.mma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Galas de l'ARES Fighting Championship, d'après le site officiel (ESPN ne les suit pas) :
 * - liste des galas : `wp-json/wp/v2/competition` (titre « ARES 45 », `acf.event_date` « 2026-11-20 19:30:00 », heure de
 *   Paris, `acf.main_event` « Jordan Zebo vs. Baissangour Chamsoudinov 2 » ou « Coming soon ») ;
 * - carte d'un gala : page `events/?competition=<id>`, un bloc `fighter-line` par combat, le combat principal en premier
 *   (catégorie en anglais, photo, nom, surnom, drapeau, palmarès, « Winner » une fois le combat passé).
 */
object AresSite {

    /** Galas de la réponse de `competition` : identifiant, nom, début, combat principal annoncé. */
    data class Competition(val id: Long, val title: String, val startMillis: Long, val mainEvent: String?)

    fun competitions(body: String): List<Competition>? = try {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            val acf = json.optJSONObject("acf") ?: return@mapNotNull null
            val start = eventTime(acf.optString("event_date")) ?: return@mapNotNull null
            Competition(
                id = json.optLong("id"),
                title = decode(json.optJSONObject("title")?.optString("rendered").orEmpty()).trim(),
                startMillis = start,
                mainEvent = acf.optString("main_event").trim().takeIf { it.isNotEmpty() && !it.equals("Coming soon", ignoreCase = true) },
            )
        }
    } catch (e: JSONException) {
        null
    }

    /** Combats de la page de la carte, le combat principal en premier ; liste vide si la carte n'est pas encore publiée. */
    fun fights(html: String, startMillis: Long): List<MmaFight> = FIGHT_LINE.split(html).drop(1).mapNotNull { block ->
        val line = block.substringBefore("fights-comming-soon")
        val one = line.substringAfter("class=\"fighter-one\"", "").substringBefore("class=\"fighter-stats\"")
        val two = line.substringAfter("class=\"fighter-two\"", "")
        if (one.isEmpty() || two.isEmpty()) return@mapNotNull null
        val records = line.substringAfter("class=\"records\"", "")
        val left = STAT_LEFT.find(records)?.groupValues?.get(1)
        val right = STAT_RIGHT.find(records)?.groupValues?.get(1)
        val fighters = listOf(fighter(one, left), fighter(two, right))
        if (fighters.any { it == null }) return@mapNotNull null
        val winner = when {
            "winner-left" in line || "class=\"winner" in one -> 0
            "winner-right" in line || "class=\"winner" in two -> 1
            else -> null
        }
        MmaFight(
            weightClass = WEIGHT.find(line)?.groupValues?.get(1)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }?.let(MmaEspn::frenchWeightClass),
            fighters = fighters.filterNotNull(),
            startMillis = startMillis,
            mainCard = true,
            // Pas de reprise ni de temps sur le site : seulement le vainqueur.
            result = winner?.let { MmaResult(it, round = 0, clock = "", decision = false) },
        )
    }

    private fun fighter(part: String, record: String?): MmaFighter? {
        val name = NAME.find(part)?.groupValues?.get(1)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val country = FLAG.find(part)?.groupValues?.get(1)
        return MmaFighter(
            name = name,
            nickname = NICKNAME.find(part)?.groupValues?.get(1)?.let(::decode)?.trim()?.trim('“', '”', '"')?.takeIf { it.isNotEmpty() },
            country = country?.uppercase(Locale.ROOT),
            // Drapeaux du site en SVG (non lus par l'appli) : le même pays en PNG.
            flagUrl = country?.let { "https://flagcdn.com/w80/$it.png" },
            record = record?.trim()?.takeIf { RECORD.matches(it) },
            photo = PHOTO.find(part)?.groupValues?.get(1)?.takeIf { "fighter-temp" !in it },
        )
    }

    /** « 2026-11-20 19:30:00 » (heure de Paris) ; « 20260410 » (jour seul, anciens galas) : 20 h. */
    internal fun eventTime(text: String): Long? {
        val value = text.trim()
        val (pattern, input) = when {
            DATE_TIME.matches(value) -> "yyyy-MM-dd HH:mm:ss" to value
            DAY_ONLY.matches(value) -> "yyyyMMdd HH:mm" to "$value 20:00"
            else -> return null
        }
        return try {
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = PARIS }.parse(input)?.time
        } catch (e: ParseException) {
            null
        }
    }

    /** Entités HTML des noms (« &#8211; », « &rsquo; », « &amp; »). */
    internal fun decode(text: String): String = ENTITY.replace(text) { match ->
        val code = match.groupValues[1]
        when {
            code.startsWith("#x", ignoreCase = true) -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[code]
        } ?: match.value
    }

    private val PARIS = TimeZone.getTimeZone("Europe/Paris")
    private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val DAY_ONLY = Regex("""\d{8}""")
    private val FIGHT_LINE = Regex("""<div class="fighter-line""")
    private val WEIGHT = Regex("""class="fight-name">\s*<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("""<h4>(.*?)</h4>""", RegexOption.DOT_MATCHES_ALL)
    private val NICKNAME = Regex("""<h5>(.*?)</h5>""", RegexOption.DOT_MATCHES_ALL)
    private val FLAG = Regex("""img/flags/([a-z]{2})(?:-[a-z]+)?\.svg""")
    private val PHOTO = Regex("""<img src="(https?://[^"]+)"""")
    private val STAT_LEFT = Regex("""class="stat stat-left">([^<]*)<""")
    private val STAT_RIGHT = Regex("""class="stat stat-right">([^<]*)<""")
    private val RECORD = Regex("""\d+-\d+-\d+""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "eacute" to "é", "nbsp" to " ")
}

/**
 * Calendrier de l'ARES (site officiel) : liste des galas gardée [listCacheMillis], carte de chaque gala gardée
 * [cardCacheMillis] ; en cas d'échec, la réponse précédente reste utilisée.
 */
class AresScheduleClient(
    private val clients: HttpClientProvider,
    private val baseUrl: HttpUrl = "https://www.aresfighting.com/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listCacheMillis: Long = 6 * 60 * 60_000L,
    private val cardCacheMillis: Long = 30 * 60_000L,
    private val callTimeoutMillis: Long = 10_000,
) {
    private val mutex = Mutex()
    private var list: Pair<Long, List<AresSite.Competition>>? = null
    private val cards = HashMap<Long, Pair<Long, List<MmaFight>>>()

    /** Galas de l'ARES de [from] à [to], avec leur carte ; `null` si le site ne répond pas. */
    suspend fun events(from: Long, to: Long): List<MmaEvent>? {
        val competitions = competitions() ?: return null
        val now = clock()
        return competitions
            .filter { it.startMillis + MmaEvent.WHOLE_EVENT_MILLIS > from && it.startMillis < to }
            .map { competition ->
                MmaEvent(
                    id = "ares-${competition.id}",
                    promotion = MmaPromotion.ARES,
                    name = competition.mainEvent?.let { "${competition.title}: $it" } ?: competition.title,
                    startMillis = competition.startMillis,
                    mainCardMillis = null,
                    fights = card(competition).orEmpty(),
                    finished = competition.startMillis + MmaEvent.WHOLE_EVENT_MILLIS < now,
                )
            }
    }

    private suspend fun competitions(): List<AresSite.Competition>? {
        val now = clock()
        mutex.withLock { list }?.takeIf { now - it.first < listCacheMillis }?.let { return it.second }
        val url = baseUrl.newBuilder().addPathSegments("wp-json/wp/v2/competition")
            .addQueryParameter("per_page", "20")
            .addQueryParameter("_fields", "id,title,acf.event_date,acf.main_event")
            .build()
        val fresh = fetch(url)?.let(AresSite::competitions) ?: return mutex.withLock { list }?.second
        mutex.withLock { list = now to fresh }
        return fresh
    }

    private suspend fun card(competition: AresSite.Competition): List<MmaFight>? {
        val now = clock()
        mutex.withLock { cards[competition.id] }?.takeIf { now - it.first < cardCacheMillis }?.let { return it.second }
        val url = baseUrl.newBuilder().addPathSegment("events").addPathSegment("")
            .addQueryParameter("competition", competition.id.toString())
            .apply { if (competition.startMillis < now) addQueryParameter("filter", "past") }
            .build()
        val fresh = fetch(url)?.let { AresSite.fights(it, competition.startMillis) } ?: return mutex.withLock { cards[competition.id] }?.second
        mutex.withLock { cards[competition.id] = now to fresh }
        return fresh
    }

    private suspend fun fetch(url: HttpUrl): String? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        withContext(Dispatchers.IO) { call.await().use { response -> if (response.isSuccessful) response.body.string() else null } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Site de l'ARES injoignable", e)
        null
    }

    private companion object {
        const val TAG = "AresSchedule"
    }
}
