package tv.nextv.core.mma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
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
 * Galas et cartes des combats d'après Sherdog (base de données MMA publique) pour les organisations qu'ESPN ne suit pas ou
 * mal : Cage Warriors, KSW, Hexagone, ONE.
 * - page d'une organisation (`organizations/<nom>-<id>`) : onglets « Upcoming » et « Recent », une ligne par gala (date
 *   seule, `startDate` à minuit UTC : l'heure n'est pas donnée), lien vers sa page ;
 * - page d'un gala : combat principal (`fight_card` : photo 200 × 300, nom, palmarès, catégorie, « TITLE FIGHT », résultat
 *   et `fight_card_resume` méthode / reprise / temps), puis un tableau des autres combats, du plus important au premier.
 */
object SherdogSite {

    /** Gala listé sur la page d'une organisation : chemin de sa page, nom, jour (minuit UTC). */
    data class Listed(val path: String, val name: String, val dayMillis: Long)

    /** Galas à venir puis récents de la page d'une organisation. */
    fun events(html: String): List<Listed> {
        // Les onglets du menu (« data-id="upcoming_tab" ») précèdent les tableaux : seuls les blocs « single_tab » comptent.
        val upcoming = html.substringAfter("class=\"single_tab\" id=\"upcoming_tab\"", "").substringBefore("class=\"single_tab\" id=\"recent_tab\"")
        val recent = html.substringAfter("class=\"single_tab\" id=\"recent_tab\"", "")
        return (rows(upcoming) + rows(recent).take(RECENT_KEPT)).distinctBy { it.path }
    }

    private fun rows(part: String): List<Listed> = EVENT_ROW.findAll(part).mapNotNull { match ->
        val block = match.value
        val day = START_DATE.find(block)?.groupValues?.get(1)?.let(::day) ?: return@mapNotNull null
        val path = EVENT_LINK.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = NAME_SPAN.find(block)?.groupValues?.get(1)?.let(::text) ?: return@mapNotNull null
        Listed(path, name, day)
    }.toList()

    /** Combats de la page d'un gala, le combat principal en premier ; vide si la carte n'est pas encore connue. */
    fun fights(html: String, startMillis: Long): List<MmaFight> {
        val result = ArrayList<MmaFight>()
        val main = html.substringAfter("class=\"fight_card\"", "")
        if (main.isNotEmpty()) {
            val left = main.substringAfter("left_side", "").substringBefore("class=\"versus\"")
            val versus = main.substringAfter("class=\"versus\"", "").substringBefore("right_side")
            val right = main.substringAfter("right_side", "").substringBefore("fight_card_resume").substringBefore("<section")
            val resume = main.substringAfter("fight_card_resume", "").substringBefore("</table>")
            val a = mainFighter(left)
            val b = mainFighter(right)
            if (a != null && b != null) {
                val cells = RESUME_CELL.findAll(resume).associate { it.groupValues[1].trim().lowercase(Locale.ROOT) to text(it.groupValues[2]) }
                result += fight(
                    weight = WEIGHT.find(versus)?.groupValues?.get(1),
                    title = "title_fight" in versus,
                    fighters = listOf(a.first, b.first),
                    outcomes = listOf(a.second, b.second),
                    method = cells["method"],
                    round = cells["round"]?.toIntOrNull(),
                    clock = cells["time"],
                    startMillis = startMillis,
                )
            }
        }
        val table = html.substringAfter("class=\"new_table", "").substringBefore("</table>")
        for (row in TABLE_ROW.findAll(table)) {
            val cells = row.value
            val left = cells.substringAfter("fighter_list left", "").substringBefore("class=\"text_center\"")
            val middle = cells.substringAfter("class=\"text_center\"", "").substringBefore("fighter_list right")
            val right = cells.substringAfter("fighter_list right", "")
            val a = rowFighter(left) ?: continue
            val b = rowFighter(right) ?: continue
            val tail = right.substringAfter("</td>", "")
            val tds = TD.findAll(tail).map { text(it.groupValues[1]) }.toList()
            result += fight(
                weight = WEIGHT.find(middle)?.groupValues?.get(1),
                title = false,
                fighters = listOf(a.first, b.first),
                outcomes = listOf(a.second, b.second),
                method = WIN_BY.find(tail)?.groupValues?.get(1)?.let(::text),
                round = tds.getOrNull(1)?.toIntOrNull(),
                clock = tds.getOrNull(2),
                startMillis = startMillis,
            )
        }
        return result
    }

    private fun fight(
        weight: String?,
        title: Boolean,
        fighters: List<MmaFighter>,
        outcomes: List<String?>,
        method: String?,
        round: Int?,
        clock: String?,
        startMillis: Long,
    ): MmaFight {
        val winner = outcomes.indexOf("win").takeIf { it >= 0 }
        val over = winner != null || outcomes.any { it == "draw" || it == "nc" }
        val decision = method?.startsWith("Decision", ignoreCase = true) == true
        return MmaFight(
            weightClass = weight?.let(::text)?.takeIf { it.isNotBlank() }?.let(MmaEspn::frenchWeightClass)?.let { if (title) "$it · ceinture" else it },
            fighters = fighters,
            startMillis = startMillis,
            mainCard = true,
            result = if (over) MmaResult(winner, round ?: 0, clock.orEmpty(), decision = decision) else null,
        )
    }

    /** Combattant du combat principal : photo 200 × 300, nom, palmarès, résultat (« win », « loss », « yet to come »). */
    private fun mainFighter(part: String): Pair<MmaFighter, String?>? {
        val name = PERSON_NAME.find(part)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotBlank() } ?: return null
        return MmaFighter(
            name = name,
            nickname = nickname(part),
            record = RECORD.find(part)?.groupValues?.get(1),
            photo = IMAGE.find(part)?.groupValues?.get(1)?.let(::photo),
        ) to OUTCOME.find(part)?.groupValues?.get(1)
    }

    /** Combattant d'une ligne du tableau : vignette (agrandie à 200 × 200), nom sur deux lignes, palmarès, résultat. */
    private fun rowFighter(part: String): Pair<MmaFighter, String?>? {
        val name = PERSON_NAME.find(part)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotBlank() } ?: return null
        return MmaFighter(
            name = name,
            nickname = nickname(part),
            record = ROW_RECORD.find(part)?.groupValues?.get(1),
            photo = IMAGE.find(part)?.groupValues?.get(1)?.let(::photo),
        ) to OUTCOME.find(part)?.groupValues?.get(1)
    }

    /** « Valeriu 'The Solitary Wolf' Mircea » (titre de l'image) → « The Solitary Wolf ». */
    private fun nickname(part: String): String? =
        IMAGE_TITLE.find(part)?.groupValues?.get(1)?.let(::text)?.let { NICK.find(it)?.groupValues?.get(1) }

    /** Photo de Sherdog en 200 × 200 (portrait de face), adresse complète ; rien pour l'image par défaut. */
    private fun photo(src: String): String? {
        if ("_images/fighter/" !in src || "nopic" in src.lowercase(Locale.ROOT)) return null
        val image = src.substringAfter("_images/fighter/")
        return "https://www.sherdog.com/image_crop/200/200/_images/fighter/$image"
    }

    /** Texte d'un morceau de page : balises retirées (`<br />` → espace), entités décodées, espaces resserrés. */
    internal fun text(html: String): String =
        AresSite.decode(html.replace(BR, " ").replace(TAG, "")).replace(SPACES, " ").trim()

    private fun day(iso: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(10))?.time
    } catch (e: ParseException) {
        null
    }

    /** Galas récents gardés (résultats du gala de la veille). */
    private const val RECENT_KEPT = 2

    private val EVENT_ROW = Regex("""<tr onclick="document\.location='[^']*';"[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val START_DATE = Regex("""itemprop="startDate" content="([^"]+)"""")
    private val EVENT_LINK = Regex("""href="(/events/[^"]+)"""")
    private val NAME_SPAN = Regex("""itemprop="name">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val PERSON_NAME = Regex("""itemprop="name">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val RECORD = Regex("""class="record">\s*(\d+-\d+-\d+)""")
    private val ROW_RECORD = Regex("""class="record"><em>(\d+-\d+-\d+)</em>""")
    private val OUTCOME = Regex("""class="final_result ([a-z]+)""")
    private val IMAGE = Regex("""<img[^>]*?src="([^"]*_images/fighter/[^"]+)"""")
    private val IMAGE_TITLE = Regex("""<img[^>]*?title="([^"]+)"""")
    private val NICK = Regex("""'([^']+)'""")
    private val WEIGHT = Regex("""class="weight_class">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val RESUME_CELL = Regex("""<td><em>([^<]+)</em><br\s*/?>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
    private val TABLE_ROW = Regex("""<tr itemprop="subEvent".*?</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val WIN_BY = Regex("""class="winby"><b>(.*?)</b>""", RegexOption.DOT_MATCHES_ALL)
    private val TD = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
    private val BR = Regex("""<br\s*/?>""")
    private val TAG = Regex("""<[^>]+>""")
    private val SPACES = Regex("""\s+""")
}

/**
 * Calendrier Sherdog des organisations [organizations] : page de chaque organisation gardée [listCacheMillis], carte de
 * chaque gala [cardCacheMillis] ; en cas d'échec, la réponse précédente reste utilisée. L'heure des galas n'est pas
 * connue ([MmaEvent.timeKnown] faux) : la section MMA prend celle de la diffusion trouvée dans le guide.
 */
class SherdogScheduleClient(
    private val clients: HttpClientProvider,
    private val organizations: Map<MmaPromotion, String> = ORGANIZATIONS,
    private val baseUrl: HttpUrl = "https://www.sherdog.com/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listCacheMillis: Long = 6 * 60 * 60_000L,
    private val cardCacheMillis: Long = 60 * 60_000L,
    private val callTimeoutMillis: Long = 12_000,
) {
    private val mutex = Mutex()
    private val lists = HashMap<MmaPromotion, Pair<Long, List<SherdogSite.Listed>>>()
    private val cards = HashMap<String, Pair<Long, List<MmaFight>>>()

    /** Galas de [from] à [to] de toutes les organisations, `null` si aucune page n'a répondu. */
    suspend fun events(from: Long, to: Long): List<MmaEvent>? = coroutineScope {
        val results = organizations.map { (promotion, path) -> async { events(promotion, path, from, to) } }.awaitAll()
        if (results.all { it == null }) null else results.filterNotNull().flatten()
    }

    private suspend fun events(promotion: MmaPromotion, path: String, from: Long, to: Long): List<MmaEvent>? {
        val listed = listed(promotion, path) ?: return null
        val now = clock()
        return listed
            // Jour du gala (minuit UTC) : gardé jusqu'au lendemain midi, résultats compris.
            .filter { it.dayMillis + DAY_MILLIS + DAY_MILLIS / 2 > from && it.dayMillis < to }
            .filter { promotion != MmaPromotion.ONE || !it.name.contains("Friday Fights", ignoreCase = true) }
            .take(MAX_EVENTS)
            .mapNotNull { event ->
                val start = event.dayMillis + DEFAULT_HOUR_MILLIS
                val fights = card(event.path, start).orEmpty()
                // ONE : surtout de la boxe thaï et du kickboxing ; seuls les galas avec du MMA sont gardés.
                if (promotion == MmaPromotion.ONE && fights.size < MIN_ONE_FIGHTS) return@mapNotNull null
                MmaEvent(
                    id = "sherdog-${event.path.substringAfterLast('-')}",
                    promotion = promotion,
                    name = event.name.replace(" - ", ": "),
                    startMillis = start,
                    mainCardMillis = null,
                    fights = fights,
                    finished = fights.isNotEmpty() && fights.all { it.result != null } || event.dayMillis + 2 * DAY_MILLIS < now,
                    timeKnown = false,
                )
            }
    }

    private suspend fun listed(promotion: MmaPromotion, path: String): List<SherdogSite.Listed>? {
        val now = clock()
        mutex.withLock { lists[promotion] }?.takeIf { now - it.first < listCacheMillis }?.let { return it.second }
        val fresh = fetch(baseUrl.newBuilder().addPathSegments(path).build())?.let(SherdogSite::events)
            ?: return mutex.withLock { lists[promotion] }?.second
        mutex.withLock { lists[promotion] = now to fresh }
        return fresh
    }

    private suspend fun card(path: String, start: Long): List<MmaFight>? {
        val now = clock()
        // Le jour du gala : relu toutes les 10 min (résultats) ; sinon toutes les heures.
        val maxAge = if (now in start - DAY_MILLIS / 2..start + DAY_MILLIS) LIVE_CARD_CACHE_MILLIS else cardCacheMillis
        mutex.withLock { cards[path] }?.takeIf { now - it.first < maxAge }?.let { return it.second }
        val fresh = fetch(baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build())?.let { SherdogSite.fights(it, start) }
            ?: return mutex.withLock { cards[path] }?.second
        mutex.withLock { cards[path] = now to fresh }
        return fresh
    }

    private suspend fun fetch(url: HttpUrl): String? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        withContext(Dispatchers.IO) { call.await().use { response -> if (response.isSuccessful) response.body.string() else null } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Sherdog injoignable", e)
        null
    }

    companion object {
        private const val TAG = "SherdogSchedule"
        private const val DAY_MILLIS = 24 * 60 * 60_000L

        /** Heure provisoire d'un gala sans heure connue (20 h UTC, soir européen) : remplacée par celle du guide. */
        private const val DEFAULT_HOUR_MILLIS = 20 * 60 * 60_000L
        private const val LIVE_CARD_CACHE_MILLIS = 10 * 60_000L
        private const val MAX_EVENTS = 4
        private const val MIN_ONE_FIGHTS = 3

        /** Pages Sherdog des organisations suivies ainsi. */
        val ORGANIZATIONS = mapOf(
            MmaPromotion.CAGE_WARRIORS to "organizations/Cage-Warriors-186",
            MmaPromotion.KSW to "organizations/Konfrontacja-Sztuk-Walki-668",
            MmaPromotion.HEXAGONE to "organizations/Hexagone-MMA-16693",
            MmaPromotion.ONE to "organizations/One-Championship-3877",
        )
    }
}
