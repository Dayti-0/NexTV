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
     * tête et pluriels retirés, « & » → « et ». Les précisions entre parenthèses ou crochets sont
     * ignorées (« MATCH 4 (1 AUDIO StadiumFX) » : ni « audio », ni son « 1 »), comme « 24/24 ».
     */
    fun tokens(name: String): List<String> {
        val withoutDetails = ROUND_THE_CLOCK.replace(BRACKETED.replace(name, " "), " ")
        val spelled = withoutDetails.lowercase().replace("+", " plus ").replace("&", " et ")
        val folded = MARKS.replace(Normalizer.normalize(spelled, Normalizer.Form.NFD), "")
        return folded.split(NON_ALPHANUMERIC)
            .flatMap { it.split(LETTER_DIGIT) }
            .filter { it.isNotEmpty() }
            .map { token ->
                if (token.all(Char::isDigit)) token.trimStart('0').ifEmpty { "0" } else token.removeSuffix("s").ifEmpty { token }
            }
    }

    /**
     * Nom « collé » : les mots à la suite, sans les mots passe-partout ([GENERIC]) et quelle que soit la
     * façon de les couper. « FRANCE INFO TV » et « franceinfo: », « L'EQUIPE » et « La chaine l'Équipe »,
     * « B SMART » et « BSmartTV », « EUROPE 1 » et « Europe 1 TV » donnent le même. Les numéros restent
     * séparés (« France 2 4K » n'est pas « France 24 »).
     *
     * `null` : nom trop court pour être sûr. Une fois ses mots passe-partout retirés, un nom sans numéro
     * doit garder un vrai mot : « LA CHAINE INFO » (« info ») ne désigne pas « Info TV ».
     */
    fun key(name: String): String? {
        val all = tokens(CAMEL_CASE.replace(name, " "))
        val words = all.filter { it !in GENERIC }
        val letters = words.sumOf { word -> if (word.all(Char::isDigit)) 0 else word.length }
        val numbered = words.any { word -> word.all(Char::isDigit) }
        val shortened = words.size < all.size
        if (letters < MIN_KEY_LETTERS || (shortened && !numbered && letters < MIN_SHORTENED_LETTERS)) return null
        return words.joinToString("") { word -> if (word.all(Char::isDigit)) "#$word#" else word }
    }

    /**
     * Index des chaînes du guide ([names] : identifiant → noms affichés ; [formerNames] : identifiant →
     * autres noms sûrs, comme l'identifiant sans son domaine, qui garde souvent l'ancien nom de la
     * chaîne). [find] rend, dans l'ordre :
     * 1. la chaîne du guide dont tous les mots figurent dans le nom donné, avec exactement les mêmes
     *    numéros et sans autre mot que des mentions sans importance (la plus précise) ;
     * 2. celle qui a le même nom « collé » ([key]), parmi ses noms affichés et ses autres noms ;
     * 3. celle que ce nom désigne sous une autre appellation ([ALIASES] : « TV5 NEWS » → « TV5 Monde Info »).
     *
     * Même nom dans plusieurs pays : la chaîne française. `null` en cas de doute.
     */
    class Index(names: Map<String, List<String>>, formerNames: Map<String, List<String>> = emptyMap()) {
        private val byNumbers: Map<String, List<Pair<Set<String>, String>>> = names
            .flatMap { (id, displayNames) -> displayNames.map { tokens(it).toSet() to id } }
            .filter { (words, _) -> words.any { word -> !word.all(Char::isDigit) } }
            .groupBy { (words, _) -> numbers(words) }

        private val byKey: Map<String, List<String>> = (names.entries + formerNames.entries)
            .flatMap { (id, displayNames) -> displayNames.mapNotNull { name -> key(name)?.let { it to id } } }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, ids) -> ids.distinct() }

        fun find(name: String): String? = byWords(name) ?: key(name)?.let { key -> byKey(key) ?: ALIASES[key]?.firstNotNullOfOrNull(::byKey) }

        private fun byWords(name: String): String? {
            val words = tokens(name).toSet()
            if (words.isEmpty()) return null
            val candidates = byNumbers[numbers(words)] ?: return null
            val fitting = candidates.filter { (guideWords, _) -> words.containsAll(guideWords) && (words - guideWords).all { it in NOISE } }
            if (fitting.isEmpty()) return null
            val precision = fitting.maxOf { it.first.size }
            return single(fitting.filter { it.first.size == precision }.map { it.second }.distinct())
        }

        private fun byKey(key: String): String? = byKey[key]?.let(::single)

        /** Une seule chaîne, ou la seule française parmi plusieurs (« Canal+ Cinéma » français et suisse). */
        private fun single(ids: List<String>): String? =
            ids.singleOrNull() ?: ids.filter { it.endsWith(".fr", ignoreCase = true) }.singleOrNull()

        private fun numbers(words: Collection<String>) = words.filter { word -> word.all(Char::isDigit) }.sorted().joinToString(",")
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val BRACKETED = Regex("""\([^)]*\)?|\[[^\]]*]?""")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val LETTER_DIGIT = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")

    /** « 24/24 », « 24h/24 » : chaînes à thème unique du guide (« Turbo 24/24 »). */
    private val ROUND_THE_CLOCK = Regex("""\b24\s*[hH]?\s*/\s*24\b""")

    /** Coupure entre deux mots écrits à la suite (« MotorVision », « BSmartTV », « LaChaineMeteo »). */
    private val CAMEL_CASE = Regex("(?<=[a-z])(?=[A-Z])")

    /** Lettres qu'un nom « collé » doit compter au moins (« LCI », « TF1 »). */
    private const val MIN_KEY_LETTERS = 3

    /** Lettres qu'il doit garder une fois ses mots passe-partout retirés, s'il n'a pas de numéro. */
    private const val MIN_SHORTENED_LETTERS = 5

    /** Mots passe-partout, ajoutés ou omis d'un nom à l'autre sans changer de chaîne. */
    private val GENERIC = setOf("tv", "la", "le", "chaine", "channel", "fr", "hd", "fhd", "sd", "uhd", "hevc")

    /**
     * Mentions ajoutées par les fournisseurs, sans rapport avec l'identité de la chaîne (au singulier,
     * comme [tokens]). « Match du dimanche soir » : l'affiche du dimanche, diffusée par la chaîne
     * principale (« LIGUE 1+| MATCH DU DIMANCHE SOIR » → « Ligue 1+ »).
     */
    private val NOISE = setOf(
        "fr", "hd", "fhd", "sd", "uhd", "hdr", "hevc", "h265", "h264", "fp", "low", "high", "raw", "backup", "bkp",
        "live", "direct", "match", "event", "evenement", "multi", "vf", "vo", "vostfr", "ppv", "exclusive", "exclusif",
        "only", "tv", "channel", "chaine", "la", "officiel", "official", "k", "p", "du", "dimanche", "samedi", "soir",
        "rtbf",
    )

    /**
     * Chaînes que les fournisseurs nomment autrement que le guide : appellation → nom ou identifiant
     * du guide (plusieurs : le premier présent dans le guide). Les noms sont comparés « collés » ([key]).
     */
    private val ALIASES: Map<String, List<String>> = listOf(
        "TV5 News" to listOf("TV5 Monde Info"),
        "TV5 Info" to listOf("TV5 Monde Info"),
        "TV5 Monde News" to listOf("TV5 Monde Info"),
        "TV5 Monde FBS" to listOf("TV5 Monde"),
        "TV5 Monde Style" to listOf("TV5 Style"),
        "Nat Geo" to listOf("National Geographic"),
        "National Geo" to listOf("National Geographic"),
        "National Geographic Wild" to listOf("NatGeoWild", "Nat Geo Wild"),
        "Planete+ CI" to listOf("PlaneteJustice", "Planète+ Crime Investigation"),
        "Planete+ Crime" to listOf("PlaneteJustice", "Planète+ Crime Investigation"),
        "Planete+ A&E" to listOf("PlaneteAction", "Planete+ Aventure"),
        "Planete+ Aventure Experience" to listOf("PlaneteAction", "Planete+ Aventure"),
        "Nickelodeon Jr" to listOf("Nickelodeon Junior"),
        "Nick Junior" to listOf("Nickelodeon Junior"),
        "Nick Teen" to listOf("Nickelodeon4Teen", "Nickelodeon Teen"),
        "TMC Monte Carlo" to listOf("TMC"),
        "Tele Monte Carlo" to listOf("TMC"),
        "LCP Assemblee Nationale" to listOf("LaChaineParlementaire", "LCP"),
        "LCP AN" to listOf("LaChaineParlementaire", "LCP"),
        "LCP Public Senat" to listOf("LaChaineParlementaire", "LCP"),
        "CStar Hits" to listOf("CStar Hits France"),
        "Cine+ OCS" to listOf("CinePlusPremier", "OCS"),
        "Ginx Esports" to listOf("Ginx"),
        "ABXplore" to listOf("ABXploreFR", "ABX"),
        "France 3 NOA" to listOf("France 3 Nouvelle Aquitaine"),
        "Les Marseillais" to listOf("LesMarseillais2424"),
    ).mapNotNull { (alias, targets) -> key(alias)?.let { it to targets.mapNotNull(::key) } }.toMap()
}
