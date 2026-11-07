package tv.nextv.core.meta

/**
 * Langue probable d'un texte d'après ses petits mots (« le », « het », « the »…), sans réseau : décide
 * s'il faut traduire une description du guide (chaînes belges, anglaises…).
 */
object Languages {

    /** Petits mots propres à chaque langue (ceux que plusieurs langues partagent n'y sont pas : « de », « en », « la »). */
    private val WORDS: Map<String, Set<String>> = mapOf(
        // « l », « d », « qu » : élisions (« l'équipe », « d'Europe », « qu'il »).
        "fr" to setOf(
            "le", "les", "des", "du", "et", "une", "est", "au", "aux", "pour", "dans", "sur", "avec", "qui", "pas",
            "cette", "sont", "ses", "leur", "elle", "mais", "ce", "sa", "ils", "être", "été", "où", "très",
            "après", "deux", "lors", "chez", "sous", "à", "l", "d", "qu",
        ),
        "nl" to setOf(
            "het", "een", "van", "op", "voor", "niet", "zijn", "wordt", "naar", "bij", "uit", "ook", "dat", "deze",
            "worden", "hij", "zij", "haar", "hun", "wat", "maar", "nog", "tegen", "tussen", "vanuit", "eerste",
            "twee", "werd", "jaar",
        ),
        "en" to setOf(
            "the", "and", "of", "to", "with", "for", "this", "that", "from", "his", "by", "are", "was", "he",
            "she", "they", "their", "after", "about", "into", "who", "which", "first",
        ),
        "de" to setOf(
            "der", "das", "und", "ist", "mit", "für", "von", "auf", "dem", "ein", "eine", "nicht", "sich", "zum",
            "zur", "im", "wird", "werden", "bei", "nach", "aus", "auch", "ersten",
        ),
        "es" to setOf("el", "los", "las", "para", "por", "su", "sus", "como", "más", "pero", "este", "esta", "sobre", "partido", "primera"),
        "it" to setOf("gli", "della", "di", "e", "per", "che", "è", "nel", "sono", "alla", "dei", "delle", "questo", "anche", "partita"),
    )

    private val WORD = Regex("""\p{L}+""")

    /**
     * Code de la langue (« fr », « nl », « en », « de », « es », « it »), ou `null` quand le texte n'a pas
     * assez de petits mots pour le dire (titre, noms propres) ou que deux langues sont à égalité.
     */
    fun guess(text: String): String? {
        val scores = HashMap<String, Int>()
        for (match in WORD.findAll(text.lowercase())) {
            for ((language, words) in WORDS) if (match.value in words) scores.merge(language, 1, Int::plus)
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        if (best.value < MIN_WORDS || best.value == ranked.getOrNull(1)?.value) return null
        return best.key
    }

    private const val MIN_WORDS = 2
}
