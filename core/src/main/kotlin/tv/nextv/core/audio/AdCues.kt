package tv.nextv.core.audio

/** Ce que les repères de pub du flux disent du moment présent ([AdCues]). */
enum class CueState {
    /** Le flux n'a encore annoncé aucune page de pub : les repères ne disent rien. */
    NONE,

    /** Le flux annonce ses pages de pub, et il n'y en a pas en ce moment : c'est le programme. */
    PROGRAMME,

    /** Page de pub annoncée par le flux. */
    BREAK,
}

/**
 * Pages de pub annoncées par le flux lui-même, quand la chaîne (ou le fournisseur) les y laisse :
 *
 * - dans la liste HLS : `#EXT-X-DATERANGE` avec `SCTE35-OUT` (début et durée), `#EXT-X-CUE-OUT` / `#EXT-X-CUE-OUT-CONT`
 *   / `#EXT-X-CUE-IN`, `#EXT-X-SCTE35:CUE-OUT=YES` ([hlsPlaylist]) ;
 * - dans un flux continu (MPEG-TS) : les messages SCTE-35 « splice insert », que le lecteur décode ([splice]).
 *
 * C'est la vérité de la régie : quand elle est là, elle décide seule. Les temps sont ceux de la lecture (position dans
 * la « période » du lecteur, en millisecondes), qui ne bougent pas quand la liste HLS avance.
 */
class AdCues {

    private val breaks = ArrayList<Interval>()

    /** Le flux a annoncé au moins une page de pub : ses repères sont fiables pour le reste de la lecture. */
    @Volatile
    var seen: Boolean = false
        private set

    /** Pages de pub annoncées depuis l'ouverture du flux (infos de lecture : le flux porte-t-il des repères ?). */
    @Volatile
    var count: Int = 0
        private set

    /** Messages SCTE-35 d'un autre type (« time signal ») : présents, mais pas lisibles ici. */
    @Volatile
    var unreadable: Int = 0
        private set

    /** État à la position [positionMs] de la lecture. */
    @Synchronized
    fun state(positionMs: Long): CueState {
        if (!seen) return CueState.NONE
        return if (breaks.any { positionMs >= it.start && positionMs < it.end }) CueState.BREAK else CueState.PROGRAMME
    }

    /** Pages de pub connues (début et fin, en millisecondes de lecture) : pour les essais. */
    @Synchronized
    fun intervals(): List<LongArray> = breaks.map { longArrayOf(it.start, it.end) }

    /**
     * Liste HLS du direct (ses balises, dans l'ordre : celles du lecteur, `HlsPlaylist.tags`, ou les lignes de la liste).
     * @param firstSegmentMs position de lecture du premier morceau de la liste.
     */
    fun hlsPlaylist(tags: List<String>, firstSegmentMs: Long) {
        var segmentStart = firstSegmentMs
        // Morceau qui suit les balises lues : les repères sans date s'appliquent à son début.
        var dateAnchor: Pair<Long, Long>? = null
        var pendingDate: Long? = null
        for (raw in tags) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    pendingDate?.let { dateAnchor = it to segmentStart }
                    pendingDate = null
                    val seconds = line.substring(8).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    segmentStart += (seconds * 1_000).toLong()
                }
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pendingDate = epochMillis(line.substringAfter(':'))
                line.startsWith("#EXT-X-DATERANGE:") -> {
                    val attributes = attributes(line.substringAfter(':'))
                    val anchor = pendingDate?.let { it to segmentStart } ?: dateAnchor ?: continue
                    val start = attributes["START-DATE"]?.let(::epochMillis) ?: continue
                    val at = anchor.second + (start - anchor.first)
                    val duration = (attributes["DURATION"] ?: attributes["PLANNED-DURATION"])?.toDoubleOrNull()?.let { (it * 1_000).toLong() }
                    val end = attributes["END-DATE"]?.let(::epochMillis)?.let { anchor.second + (it - anchor.first) }
                    when {
                        attributes.containsKey("SCTE35-OUT") -> open(at, end ?: duration?.let { at + it })
                        attributes.containsKey("SCTE35-IN") -> close(at)
                    }
                }
                line.startsWith("#EXT-X-CUE-OUT-CONT") -> {
                    // Page de pub en cours (lecture ouverte au milieu) : « ElapsedTime=12,Duration=30 » ou « 12/30 ».
                    val value = line.substringAfter(':', "")
                    val attributes = attributes(value)
                    val elapsed = (attributes["ELAPSEDTIME"] ?: value.substringBefore('/', "").takeIf { '/' in value })?.toDoubleOrNull()
                    val duration = (attributes["DURATION"] ?: value.substringAfter('/', "").takeIf { '/' in value })?.toDoubleOrNull()
                    if (elapsed != null) {
                        val start = segmentStart - (elapsed * 1_000).toLong()
                        open(start, duration?.let { start + (it * 1_000).toLong() })
                    } else {
                        open(segmentStart, null, joined = true)
                    }
                }
                line.startsWith("#EXT-X-CUE-OUT") -> {
                    val value = line.substringAfter(':', "")
                    val seconds = (attributes(value)["DURATION"] ?: value).trim().toDoubleOrNull()
                    open(segmentStart, seconds?.let { segmentStart + (it * 1_000).toLong() })
                }
                line.startsWith("#EXT-X-CUE-IN") -> close(segmentStart)
                line.startsWith("#EXT-X-SCTE35:") -> {
                    val attributes = attributes(line.substringAfter(':'))
                    val duration = attributes["DURATION"]?.toDoubleOrNull()?.let { (it * 1_000).toLong() }
                    if (attributes["CUE-OUT"].equals("YES", ignoreCase = true)) open(segmentStart, duration?.let { segmentStart + it })
                    if (attributes["CUE-OUT"].equals("CONT", ignoreCase = true)) open(segmentStart, null, joined = true)
                    if (attributes["CUE-IN"].equals("YES", ignoreCase = true)) close(segmentStart)
                }
            }
        }
    }

    /**
     * Message SCTE-35 « splice insert » d'un flux continu.
     * @param out `true` : début d'une page de pub ; `false` : retour au programme.
     * @param atMs position de lecture de la coupure.
     * @param durationMs durée annoncée de la page de pub, si elle l'est.
     */
    fun splice(out: Boolean, atMs: Long, durationMs: Long?, cancel: Boolean = false) {
        if (cancel) return
        if (out) open(atMs, durationMs?.takeIf { it > 0 }?.let { atMs + it }) else close(atMs)
    }

    /** Message SCTE-35 d'un autre type, compté pour les infos de lecture. */
    @Synchronized
    fun other() {
        unreadable++
    }

    @Synchronized
    private fun open(start: Long, end: Long?, joined: Boolean = false) {
        seen = true
        // La même page, revue dans une liste suivante (ou annoncée deux fois) : sa fin est précisée si besoin.
        val same = breaks.firstOrNull { start >= it.start - SAME_MILLIS && start <= (if (joined) it.end else it.start + SAME_MILLIS) }
        if (same != null) {
            if (end != null && !same.closed) {
                same.end = end
                same.closed = true
            } else if (joined && !same.closed) {
                same.end = maxOf(same.end, start + OPEN_MILLIS)
            }
            return
        }
        count++
        breaks += Interval(start, end ?: (start + OPEN_MILLIS), closed = end != null)
        breaks.sortBy { it.start }
        while (breaks.size > KEPT) breaks.removeAt(0)
    }

    @Synchronized
    private fun close(at: Long) {
        val open = breaks.lastOrNull { it.start <= at && at <= it.end + SAME_MILLIS } ?: return
        if (open.closed && open.end == at) return
        open.end = at
        open.closed = true
    }

    private class Interval(val start: Long, var end: Long, var closed: Boolean)

    private companion object {
        /** Deux annonces de début à moins de cela : la même page de pub. */
        const val SAME_MILLIS = 2_000L

        /** Page de pub sans durée ni fin annoncée : tenue au plus ce temps, faute de retour annoncé. */
        const val OPEN_MILLIS = 8 * 60_000L
        const val KEPT = 32

        private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?(Z|[+-]\d{2}:?\d{2})?""")

        /** Date ISO 8601 (« 2026-11-15T13:11:17.967Z », « …+01:00 ») en millisecondes ; sans `java.time` (Android 7). */
        fun epochMillis(text: String): Long? {
            val match = DATE.matchEntire(text.trim().trim('"')) ?: return null
            val v = match.groupValues
            val year = v[1].toInt()
            val month = v[2].toInt()
            val day = v[3].toInt()
            // Jours depuis le 1er janvier 1970 (calendrier grégorien).
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            val days = era * 146_097L + dayOfEra - 719_468L
            val fraction = v[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
            var millis = ((days * 24 + v[4].toInt()) * 60 + v[5].toInt()) * 60_000L + v[6].toInt() * 1_000L + fraction
            val zone = v[8]
            if (zone.isNotEmpty() && zone != "Z") {
                val digits = zone.substring(1).replace(":", "")
                val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
                millis -= if (zone[0] == '+') offset else -offset
            }
            return millis
        }

        /** Attributs « NOM=valeur,NOM="valeur, avec virgule" » d'une balise HLS (noms en majuscules). */
        fun attributes(text: String): Map<String, String> {
            val result = HashMap<String, String>()
            var i = 0
            while (i < text.length) {
                val equals = text.indexOf('=', i)
                if (equals < 0) break
                val name = text.substring(i, equals).trim().uppercase()
                var j = equals + 1
                val value: String
                if (j < text.length && text[j] == '"') {
                    val close = text.indexOf('"', j + 1).let { if (it < 0) text.length else it }
                    value = text.substring(j + 1, close)
                    j = close + 1
                } else {
                    val comma = text.indexOf(',', j).let { if (it < 0) text.length else it }
                    value = text.substring(j, comma).trim()
                    j = comma
                }
                result[name] = value
                i = text.indexOf(',', j).let { if (it < 0) text.length else it + 1 }
            }
            return result
        }
    }
}
