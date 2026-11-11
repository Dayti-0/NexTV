package tv.nextv.core.catalog

import java.text.Normalizer

/**
 * Éditions régionales de France 3 (« FR| France 3 Alpes », « F3 Bretagne ») : une seule est gardée
 * dans les listes, celle choisie dans les réglages.
 *
 * Les playlists écrivent la même région de plusieurs façons, souvent d'une qualité à l'autre (« PARIS IDF » en HD,
 * « PARIS ILE DE FRANCE » en FHD ; « NORD PDC », « BNORMANDIE », « POITOUCHAR », « PROV ALPES », « RHONES-ALPES ») :
 * toutes sont ramenées au nom de l'antenne ([Region.key]), pour que ses versions soient regroupées, qu'elle reçoive
 * le programme du guide et qu'elle n'apparaisse qu'une fois dans les réglages.
 */
object FranceRegions {

    /** Édition régionale : [key] sans accents ni ponctuation (« paris ile de france »), [label] à afficher. */
    data class Region(val key: String, val label: String)

    /**
     * Antenne régionale de France 3.
     *
     * @param forms ses noms, en mots sans accents : le premier est sa clé ; les suivants, d'autres appellations
     *   (ville du siège, nom courant).
     */
    private class Antenna(val label: String, vararg forms: String) {
        val key: String = forms.first()
        val forms: List<List<String>> = forms.map { it.split(' ') }
    }

    private val ANTENNAS = listOf(
        Antenna("Alpes", "alpes", "grenoble"),
        Antenna("Alsace", "alsace", "strasbourg"),
        Antenna("Aquitaine", "aquitaine", "bordeaux"),
        Antenna("Auvergne", "auvergne", "clermont ferrand", "clermont"),
        Antenna("Basse-Normandie", "basse normandie", "caen"),
        Antenna("Bourgogne", "bourgogne", "dijon"),
        Antenna("Bretagne", "bretagne", "rennes"),
        Antenna("Centre", "centre", "centre val de loire", "orleans"),
        Antenna("Champagne-Ardenne", "champagne ardenne", "reims"),
        Antenna("Corse ViaStella", "corse via stella", "corse viastella", "corse", "via stella", "viastella", "ajaccio"),
        Antenna("Côte d'Azur", "cote d azur", "nice"),
        Antenna("Franche-Comté", "franche comte", "besancon"),
        Antenna("Haute-Normandie", "haute normandie", "rouen"),
        Antenna("Languedoc-Roussillon", "languedoc roussillon", "montpellier"),
        Antenna("Limousin", "limousin", "limoges"),
        Antenna("Lorraine", "lorraine", "nancy"),
        Antenna("Midi-Pyrénées", "midi pyrenees", "toulouse"),
        Antenna("NoA", "noa", "nouvelle aquitaine"),
        Antenna("Nord-Pas-de-Calais", "nord pas de calais", "lille"),
        Antenna("Paris Île-de-France", "paris ile de france", "ile de france"),
        Antenna("Pays de la Loire", "pays de la loire", "nantes"),
        Antenna("Picardie", "picardie", "amiens"),
        Antenna("Poitou-Charentes", "poitou charentes", "poitiers"),
        Antenna("Provence-Alpes", "provence alpes", "marseille"),
        Antenna("Rhône-Alpes", "rhone alpes", "lyon"),
    )

    private val BY_FORM: Map<String, Antenna> = ANTENNAS.flatMap { antenna -> antenna.forms.map { it.joinToString(" ") to antenna } }.toMap()

    /** Villes du siège des antennes : « Normandie Caen », « Grand Est Nancy » désignent l'antenne de la ville. */
    private val CITIES = setOf(
        "grenoble", "strasbourg", "bordeaux", "clermont ferrand", "clermont", "caen", "dijon", "rennes", "orleans", "reims",
        "ajaccio", "nice", "besancon", "rouen", "montpellier", "limoges", "nancy", "toulouse", "lille", "nantes", "amiens",
        "poitiers", "marseille", "lyon",
    )
    private val BY_CITY: Map<String, Antenna> = BY_FORM.filterKeys { it in CITIES }

    /** Mots d'un nom d'antenne (hors mots de liaison), pour corriger une lettre de travers (« POTOU », « RHONES »). */
    private val VOCABULARY: Set<String> = ANTENNAS.flatMap { it.forms.flatten() }.filterTo(HashSet()) { it.length >= MIN_CORRECTED_LENGTH }

    /** Mots de liaison, écrits ou non (« Pays de Loire », « Côte Azur »). */
    private val LINKS = setOf("de", "la", "d", "du", "le", "et")

    /** « France 3 », « France3 », « F3 », puis le nom de la région. */
    private val PATTERN = Regex("""(?i)(?<![\p{L}\p{Nd}])(?:france\s*3|f3)(?![\p{L}\p{Nd}])[\s:|\-–]*(.*)$""")

    /** La même chose dans une clé de version (mots en minuscules séparés par une espace). */
    private val KEY_PATTERN = Regex("""^(.*?)(?<![a-z0-9])(?:france ?3|f3) (.+)$""")

    /** Identifiant du guide écrit sans espaces (« France3BasseNormandie ») : coupé entre ses mots. */
    private val GLUED_WORDS = Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])")

    /** Ce qui suit « France 3 » sans être une région. */
    private val NOT_REGIONS = setOf(
        "+1", "1", "backup", "bkp", "secours", "national", "nationale", "nat", "france", "fr", "direct", "live", "tv",
        "regions", "region", "test", "vip", "multi", "eu",
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val WORD_START = Regex("""(?<![\p{L}'’])\p{L}""")
    private val NON_WORD = Regex("[^a-z0-9+]+")

    private const val MIN_CORRECTED_LENGTH = 5

    /** Lettres qu'une abréviation doit compter au moins (« BRET », « NPDC ») : en deçà, elle ne désigne rien de sûr. */
    private const val MIN_ABBREVIATION = 3

    fun regionOf(channelName: String): Region? {
        // Mentions de qualité et de codec retirées (« France 3 HD » est la chaîne nationale).
        val base = QualityParser.parse(channelName).baseName
        val rest = PATTERN.find(base)?.groupValues?.get(1)?.trim()?.trim('-', '|', ':', '(', ')', '[', ']', ' ')
            ?: return null
        val key = Normalizer.normalize(rest.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").trim()
        if (!isRegion(key)) return null
        antennaOf(key)?.let { return Region(it.key, it.label) }
        // Région inconnue : gardée telle que la playlist l'écrit. « PARIS ÎLE-DE-FRANCE » → « Paris Île-De-France ».
        val label = if (rest == rest.uppercase()) WORD_START.replace(rest.lowercase()) { it.value.uppercase() } else rest
        return Region(key, label)
    }

    /** Région d'une chaîne du guide, d'après un de ses noms ou son identifiant sans domaine (« France3Alsace »). */
    fun regionOfGuide(name: String): Region? = regionOf(GLUED_WORDS.replace(name, " "))

    /**
     * Clé d'une région enregistrée par une version précédente (réglage « Région de France 3 ») : celle de son
     * antenne (« paris idf » → « paris ile de france »), ou la même si elle n'en désigne aucune.
     */
    fun keyOf(regionKey: String): String = antennaOf(regionKey)?.key ?: regionKey

    /**
     * Clé de version d'une édition régionale, avec le nom de son antenne : « fr france 3 paris idf » et
     * « fr f3 paris ile de france » → « fr france 3 paris ile de france ». `null` si la clé n'est pas celle d'une
     * édition régionale connue.
     */
    fun variantKey(variantKey: String): String? {
        val match = KEY_PATTERN.matchEntire(variantKey) ?: return null
        val region = match.groupValues[2]
        if (!isRegion(region)) return null
        val antenna = antennaOf(region) ?: return null
        return match.groupValues[1] + "france 3 " + antenna.key
    }

    private fun isRegion(key: String) = key.isNotEmpty() && key !in NOT_REGIONS && !key.split(' ').all { it in NOT_REGIONS }

    /** Antenne que [key] désigne (mots sans accents) : nom exact, ville, abréviation ou nom à une lettre près. */
    private fun antennaOf(key: String): Antenna? {
        BY_FORM[key]?.let { return it }
        val words = key.split(' ').filter { it.isNotEmpty() }
        // « Normandie Caen », « Auvergne-Rhône-Alpes Clermont-Ferrand » : la ville du siège.
        BY_CITY[words.takeLast(2).joinToString(" ")]?.let { return it }
        BY_CITY[words.last()]?.let { return it }
        // Tel qu'écrit d'abord (« BNORMANDIE »), puis en corrigeant une lettre de travers (« POTOU-CHARENTES »).
        return bestFit(words.joinToString("")) ?: bestFit(words.joinToString("") { corrected(it) })
    }

    /** Antenne dont le nom correspond le mieux à [glued] ; aucune si deux antennes se valent (« B » : Bretagne, Bourgogne). */
    private fun bestFit(glued: String): Antenna? {
        var best: Antenna? = null
        var bestFit = Fit.NONE
        var tied = false
        for (antenna in ANTENNAS) {
            val fit = antenna.forms.maxOf { fit(glued, it) }
            if (fit == Fit.NONE) continue
            when {
                fit > bestFit -> {
                    best = antenna
                    bestFit = fit
                    tied = false
                }
                fit == bestFit && antenna !== best -> tied = true
            }
        }
        return best.takeUnless { tied }
    }

    /** Mot à une lettre près d'un mot d'un nom d'antenne (« potou » → « poitou », « rhones » → « rhone »). */
    private fun corrected(word: String): String {
        if (word.length < MIN_CORRECTED_LENGTH || word in VOCABULARY) return word
        return VOCABULARY.singleOrNull { oneLetterApart(word, it) } ?: word
    }

    /** Vrai si [a] devient [b] en changeant, ajoutant ou retirant une lettre. */
    private fun oneLetterApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var start = 0
        while (start < a.length && start < b.length && a[start] == b[start]) start++
        val (restA, restB) = a.substring(start) to b.substring(start)
        return when {
            restA.length == restB.length -> restA.drop(1) == restB.drop(1)
            restA.length > restB.length -> restA.drop(1) == restB
            else -> restA == restB.drop(1)
        }
    }

    /** Façon dont un nom écrit sans espaces correspond à un nom d'antenne, de la plus sûre à la moins sûre. */
    private enum class Fit { NONE, START, ABBREVIATED, FULL }

    /**
     * Compare [glued] (le nom de la playlist, mots à la suite) aux mots [form] d'un nom d'antenne :
     * - [Fit.FULL] : tous les mots, écrits en entier (les mots de liaison peuvent manquer : « Pays de Loire ») ;
     * - [Fit.ABBREVIATED] : tous les mots, certains réduits à leur début (« CHAMP ARDE », « NORD PDC », « BNORMANDIE ») ;
     * - [Fit.START] : les premiers mots seulement, en entier (« LANGUEDOC », « PARIS », « CHAMPAGNE »).
     */
    private fun fit(glued: String, form: List<String>): Fit {
        fun from(index: Int, position: Int, abbreviated: Boolean): Fit {
            if (position == glued.length) {
                return when {
                    form.drop(index).all { it in LINKS } -> if (abbreviated) Fit.ABBREVIATED else Fit.FULL
                    // Les mots suivants manquent : seulement après des mots entiers.
                    !abbreviated && index > 0 -> Fit.START
                    else -> Fit.NONE
                }
            }
            if (index == form.size) return Fit.NONE
            val word = form[index]
            var best = Fit.NONE
            if (glued.startsWith(word, position)) best = from(index + 1, position + word.length, abbreviated)
            if (word in LINKS) best = maxOf(best, from(index + 1, position, abbreviated))
            if (best == Fit.FULL) return best
            for (length in 1 until word.length) {
                if (position + length > glued.length || glued[position + length - 1] != word[length - 1]) break
                best = maxOf(best, from(index + 1, position + length, true))
            }
            return best
        }
        val found = from(0, 0, false)
        return if (found == Fit.ABBREVIATED && glued.length < MIN_ABBREVIATION) Fit.NONE else found
    }
}
