package tv.nextv.core.catalog

import java.text.Normalizer

/**
 * Éditions régionales de France 3 (« FR| France 3 Alpes », « F3 Bretagne ») : une seule est gardée
 * dans les listes, celle choisie dans les réglages.
 */
object FranceRegions {

    /** Édition régionale : [key] sans accents ni ponctuation (« paris ile de france »), [label] tel qu'écrit. */
    data class Region(val key: String, val label: String)

    /** « France 3 », « France3 », « F3 », puis le nom de la région. */
    private val PATTERN = Regex("""(?i)(?<![\p{L}\p{Nd}])(?:france\s*3|f3)(?![\p{L}\p{Nd}])[\s:|\-–]*(.*)$""")

    /** Ce qui suit « France 3 » sans être une région. */
    private val NOT_REGIONS = setOf(
        "+1", "1", "backup", "bkp", "secours", "national", "nationale", "nat", "france", "fr", "direct", "live", "tv",
        "regions", "region", "test", "vip", "multi", "eu",
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val WORD_START = Regex("""(?<![\p{L}'’])\p{L}""")
    private val NON_WORD = Regex("[^a-z0-9+]+")

    fun regionOf(channelName: String): Region? {
        // Mentions de qualité et de codec retirées (« France 3 HD » est la chaîne nationale).
        val base = QualityParser.parse(channelName).baseName
        val rest = PATTERN.find(base)?.groupValues?.get(1)?.trim()?.trim('-', '|', ':', '(', ')', '[', ']', ' ')
            ?: return null
        val key = Normalizer.normalize(rest.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").trim()
        if (key.isEmpty() || key in NOT_REGIONS || key.split(' ').all { it in NOT_REGIONS }) return null
        // « PARIS ÎLE-DE-FRANCE » → « Paris Île-De-France ».
        val label = if (rest == rest.uppercase()) WORD_START.replace(rest.lowercase()) { it.value.uppercase() } else rest
        return Region(key, label)
    }
}
