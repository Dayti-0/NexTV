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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Calendrier des galas de MMA d'après les données publiques d'ESPN (sans clé) : UFC, PFL ; l'ARES d'après son site
 * ([AresScheduleClient]) ; Cage Warriors, KSW, Hexagone et ONE d'après Sherdog ([SherdogScheduleClient]). Chaque
 * organisation est gardée [cacheMillis] (moins pendant un gala, [liveCacheMillis]) ; en cas d'échec, la réponse
 * précédente reste utilisée.
 */
class MmaScheduleClient(
    private val clients: HttpClientProvider,
    private val baseUrl: HttpUrl = "https://site.api.espn.com/apis/site/v2/sports/mma/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30 * 60_000L,
    private val liveCacheMillis: Long = 60_000L,
    private val callTimeoutMillis: Long = 10_000,
    /** Galas de l'ARES (site officiel : ESPN ne les suit pas) ; `null` : pas d'ARES (tests). */
    private val ares: AresScheduleClient? = AresScheduleClient(clients, logger = logger, clock = clock),
    /** Cage Warriors, KSW, Hexagone, ONE (Sherdog) ; `null` : pas de Sherdog (tests). */
    private val sherdog: SherdogScheduleClient? = SherdogScheduleClient(clients, logger = logger, clock = clock),
) {
    private val mutex = Mutex()
    private val cached = HashMap<MmaPromotion, Pair<Long, List<MmaEvent>>>()

    /** Galas de [from] à [to] de chaque organisation suivie (ESPN, site de l'ARES), `null` si aucune source n'a répondu. */
    suspend fun events(from: Long, to: Long): List<MmaEvent>? = coroutineScope {
        val aresEvents = ares?.let { async { it.events(from, to) } }
        val sherdogEvents = sherdog?.let { async { it.events(from, to) } }
        val results = MmaPromotion.entries.filter { it.espnLeague != null }.map { promotion -> async { events(promotion, from, to) } }.awaitAll() +
            listOf(aresEvents?.await(), sherdogEvents?.await()).filter { it != null }
        if (results.all { it == null }) null else results.filterNotNull().flatten().filter { !it.timeKnown || it.endMillis > from && it.startMillis < to }
    }

    private suspend fun events(promotion: MmaPromotion, from: Long, to: Long): List<MmaEvent>? {
        val now = clock()
        val previous = mutex.withLock { cached[promotion] }
        if (previous != null) {
            val live = previous.second.any { it.isLive(now) || (it.startMillis > now && it.startMillis - now < liveCacheMillis * 5) }
            if (now - previous.first < if (live) liveCacheMillis else cacheMillis) return previous.second
        }
        val league = promotion.espnLeague ?: return null
        val url = baseUrl.newBuilder().addPathSegment(league).addPathSegment("scoreboard")
            .addQueryParameter("dates", "${day(from)}-${day(to)}")
            .build()
        val fresh = fetch(url)?.let { MmaEspn.parse(it, promotion) }
        if (fresh == null) return previous?.second
        mutex.withLock { cached[promotion] = now to fresh }
        return fresh
    }

    private suspend fun fetch(url: HttpUrl): String? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        withContext(Dispatchers.IO) { call.await().use { response -> if (response.isSuccessful) response.body.string() else null } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Calendrier MMA injoignable", e)
        null
    }

    private fun day(millis: Long): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    private companion object {
        const val TAG = "MmaSchedule"
    }
}
