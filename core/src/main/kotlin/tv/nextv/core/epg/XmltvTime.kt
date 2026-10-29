package tv.nextv.core.epg

/**
 * Horodatages XMLTV : `AAAAMMJJhhmmss ±hhmm`. Les composantes finales peuvent manquer
 * (`AAAAMMJJhhmm`), le décalage peut être collé, absent (UTC) ou écrit `Z`, `UTC`, `GMT`.
 *
 * Calcul sans `java.time` pour rester compatible avec toutes les versions d'Android visées.
 */
object XmltvTime {

    private val FORMAT = Regex("""^(\d{8,14})\s*(?:([+-])(\d{2}):?(\d{2})|Z|UTC|GMT)?$""", RegexOption.IGNORE_CASE)

    /** @return millisecondes epoch UTC, ou `null` si la valeur est invalide. */
    fun parse(value: String?): Long? {
        val match = FORMAT.matchEntire(value?.trim() ?: return null) ?: return null
        val digits = match.groupValues[1].padEnd(14, '0')
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = digits.substring(12, 14).toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        var offsetSeconds = 0
        if (match.groupValues[2].isNotEmpty()) {
            val hours = match.groupValues[3].toInt()
            val minutes = match.groupValues[4].toInt()
            if (hours > 14 || minutes > 59) return null
            offsetSeconds = (hours * 3600 + minutes * 60) * if (match.groupValues[2] == "-") -1 else 1
        }
        val epochSeconds = daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
        return epochSeconds * 1000
    }

    /** Nombre de jours depuis le 1970-01-01 (algorithme de H. Hinnant). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
