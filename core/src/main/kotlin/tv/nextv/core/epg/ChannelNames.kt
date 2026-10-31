package tv.nextv.core.epg

import java.text.Normalizer

/**
 * Rapprochement souple des noms de chaînes de la playlist et du guide, quand les noms exacts
 * diffèrent : « FR| LIGUE 1+ MATCH 05 FHD » et « Ligue 1+ 5 » ont les mêmes mots et les mêmes
 * numéros, à des mentions sans importance près (match, event, multi, backup…).
 */
object ChannelNames {

    /**
     * Mots d'un nom : minuscules sans accents, « + » → « plus », lettres et chiffres séparés, zéros de
     * tête et pluriels retirés. Les précisions entre parenthèses ou crochets sont ignorées
     * (« MATCH 4 (1 AUDIO StadiumFX) » : ni « audio », ni son « 1 »).
     */
    fun tokens(name: String): List<String> {
        val withoutDetails = BRACKETED.replace(name, " ")
        val folded = MARKS.replace(Normalizer.normalize(withoutDetails.lowercase().replace("+", " plus "), Normalizer.Form.NFD), "")
        return folded.split(NON_ALPHANUMERIC)
            .flatMap { it.split(LETTER_DIGIT) }
            .filter { it.isNotEmpty() }
            .map { token ->
                if (token.all(Char::isDigit)) token.trimStart('0').ifEmpty { "0" } else token.removeSuffix("s").ifEmpty { token }
            }
    }

    /**
     * Index des chaînes du guide ([names] : identifiant → noms affichés). [find] rend la chaîne du
     * guide dont tous les mots figurent dans le nom donné, avec exactement les mêmes numéros et sans
     * autre mot que des mentions sans importance ; la plus précise, et `null` en cas de doute.
     */
    class Index(names: Map<String, List<String>>) {
        private val byNumbers: Map<String, List<Pair<Set<String>, String>>> = names
            .flatMap { (id, displayNames) -> displayNames.map { tokens(it).toSet() to id } }
            .filter { (words, _) -> words.any { word -> !word.all(Char::isDigit) } }
            .groupBy { (words, _) -> numbers(words) }

        fun find(name: String): String? {
            val words = tokens(name).toSet()
            if (words.isEmpty()) return null
            val candidates = byNumbers[numbers(words)] ?: return null
            val fitting = candidates.filter { (guideWords, _) -> words.containsAll(guideWords) && (words - guideWords).all { it in NOISE } }
            if (fitting.isEmpty()) return null
            val precision = fitting.maxOf { it.first.size }
            return fitting.filter { it.first.size == precision }.map { it.second }.distinct().singleOrNull()
        }

        private fun numbers(words: Collection<String>) = words.filter { word -> word.all(Char::isDigit) }.sorted().joinToString(",")
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val BRACKETED = Regex("""\([^)]*\)?|\[[^\]]*]?""")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val LETTER_DIGIT = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")

    /** Mentions ajoutées par les fournisseurs, sans rapport avec l'identité de la chaîne (au singulier, comme [tokens]). */
    private val NOISE = setOf(
        "fr", "hd", "fhd", "sd", "uhd", "hdr", "hevc", "h265", "h264", "fp", "low", "high", "raw", "backup", "bkp",
        "live", "direct", "match", "event", "evenement", "multi", "vf", "vo", "vostfr", "ppv", "exclusive", "exclusif",
        "only", "tv", "channel", "chaine", "la", "officiel", "official", "k", "p",
    )
}
