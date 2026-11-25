package tv.nextv.core.epg

/**
 * Horodatages XMLTV : `AAAAMMJJhhmmss ±hhmm`. Les composantes finales peuvent manquer
 * (`AAAAMMJJhhmm`), le décalage peut être collé, absent (UTC) ou écrit `Z`, `UTC`, `GMT`.
 *
 * Calcul sans `java.time` pour rester compatible avec toutes les versions d'Android visées.
 */
object XmltvTime {

    /**
     * @return millisecondes epoch UTC, ou `null` si la valeur est invalide.
     *
     * Lu caractère par caractère, sans expression régulière : deux horodatages par programme, plus de 100 000
     * programmes par guide ; sur Android, chaque recherche d'expression régulière réserve de la mémoire native à
     * libérer ensuite, ce qui chargeait la box pendant l'import du guide (mesuré le 25/11/2026).
     */
    fun parse(value: String?): Long? {
        val text = value?.trim() ?: return null
        var i = 0
        while (i < text.length && text[i] in '0'..'9') i++
        if (i !in 8..14) return null
        val digits = text.substring(0, i).padEnd(14, '0')
        while (i < text.length && text[i].isWhitespace()) i++
        var offsetSeconds = 0
        val rest = text.substring(i)
        when {
            rest.isEmpty() || rest.equals("Z", ignoreCase = true) || rest.equals("UTC", ignoreCase = true) || rest.equals("GMT", ignoreCase = true) -> Unit
            rest[0] == '+' || rest[0] == '-' -> {
                // « +0100 » ou « +01:00 ».
                val zone = rest.substring(1).let { if (it.length == 5 && it[2] == ':') it.removeRange(2, 3) else it }
                if (zone.length != 4 || zone.any { it !in '0'..'9' }) return null
                val hours = zone.substring(0, 2).toInt()
                val minutes = zone.substring(2, 4).toInt()
                if (hours > 14 || minutes > 59) return null
                offsetSeconds = (hours * 3600 + minutes * 60) * if (rest[0] == '-') -1 else 1
            }
            else -> return null
        }
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = digits.substring(12, 14).toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
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
