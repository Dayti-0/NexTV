package tv.nextv.core.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Replay (« catch-up ») des comptes Xtream Codes : le serveur garde quelques jours de programmes
 * passés de certaines chaînes, lus à l'adresse
 * `http://serveur/timeshift/identifiant/mot-de-passe/durée-en-minutes/aaaa-MM-jj:HH-mm/flux.ts`
 * (heure de début dans le fuseau horaire du serveur).
 */
object Catchup {

    /** Identifiant Xtream d'un flux : dernier segment numérique de son adresse (« …/live/u/p/1234.ts » → « 1234 »). */
    fun streamId(url: String): String? {
        val segment = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return null
        return segment.substringBefore('.').takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }
    }

    /** Chaînes en direct avec les jours de replay de leur flux ([days] : par identifiant de flux). */
    fun annotate(channels: List<Channel>, days: Map<String, Int>): List<Channel> {
        if (days.isEmpty()) return channels
        return channels.map { channel ->
            val archive = if (channel.kind == ContentKind.LIVE) streamId(channel.stream.url)?.let(days::get) else null
            if (archive != null && archive > 0) channel.copy(catchupDays = archive) else channel
        }
    }

    /**
     * Adresse du programme enregistré de [startMillis] à [stopMillis], sur le serveur du flux en
     * direct [streamUrl] ; `null` si l'adresse du flux n'est pas celle d'un serveur Xtream.
     * @param extension « ts » (flux continu) ou « m3u8 » (HLS : on peut s'y déplacer à la seconde).
     */
    fun xtreamUrl(
        streamUrl: String,
        account: XtreamAccount,
        startMillis: Long,
        stopMillis: Long,
        timeZone: TimeZone,
        extension: String = "ts",
    ): String? {
        val url = streamUrl.toHttpUrlOrNull() ?: return null
        val id = streamId(streamUrl) ?: return null
        val minutes = ((stopMillis - startMillis + MINUTE - 1) / MINUTE).coerceAtLeast(1)
        val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT).apply { this.timeZone = timeZone }.format(Date(startMillis))
        return url.newBuilder()
            .encodedPath("/")
            .addPathSegment("timeshift")
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment(minutes.toString())
            .addPathSegment(start)
            .addPathSegment("$id.$extension")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    /** Programme de [startMillis] encore gardé par le serveur ([days] jours de replay) et déjà commencé. */
    fun isAvailable(days: Int, startMillis: Long, now: Long): Boolean =
        days > 0 && startMillis <= now && startMillis >= now - days * DAY

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
}
