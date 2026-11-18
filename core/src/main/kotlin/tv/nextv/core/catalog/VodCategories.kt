package tv.nextv.core.catalog

import java.text.Normalizer

/**
 * Catégories de films et de séries de la playlist : nom propre (« SC FICTION | HORREUR » → « Science-fiction et
 * horreur », « DRAME ( NETFLIX| PRIME | HBO … ) » → « Drame », sans l'arabe ni les majuscules), et catégories d'autres
 * pays (films arabes, séries turques…) masquées par « Contenus français seulement ».
 *
 * [properName] redonne le même nom à un nom déjà propre : les catégories masquées enregistrées sous l'ancien nom y sont
 * ramenées au chargement de la playlist.
 */
object VodCategories {

    /** Nom affiché de la catégorie, `null` sans catégorie. */
    fun properName(group: String?): String? {
        if (group == null) return null
        val cleaned = clean(group)
        if (cleaned.isEmpty()) return group
        val key = key(cleaned)
        NAMES[key]?.let { return it }
        PATTERNS.firstNotNullOfOrNull { (regex, name) -> regex.find(key)?.let { name(it) } }?.let { return it }
        // Déjà écrit en minuscules (nom propre, ou choisi par le fournisseur) : gardé tel quel.
        if (cleaned.any { it.isLowerCase() }) return cleaned
        return cleaned.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
            .map { part -> part.lowercase().replaceFirstChar { it.titlecase() } }
            .let(::joinFrench)
    }

    /** Catégorie de films ou de séries française (ou sans pays) ; faux pour les films arabes, les séries turques… */
    fun isFrench(group: String?): Boolean {
        if (group.isNullOrBlank()) return true
        if (ARABIC.containsMatchIn(group)) return false
        return !FOREIGN.containsMatchIn(key(group))
    }

    /** « Drame | Histoire » → « Drame et histoire », trois parties ou plus : « A, b et c ». */
    private fun joinFrench(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).mapIndexed { i, p -> if (i == 0) p else p.lowercase() }.joinToString(", ") +
            " et " + parts.last().lowercase()
    }

    /** Sans la liste des plateformes « ( NETFLIX| PRIME | HBO … ) », sans l'arabe, sans « ! ». */
    private fun clean(group: String): String = group
        .replace(PLATFORMS, " ")
        .replace(ARABIC_WORDS, " ")
        .replace('!', ' ')
        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
        .replace(SPACES, " ")
        .trim()

    /** Clé de comparaison : majuscules sans accents, mots séparés par une espace. */
    private fun key(text: String): String =
        Normalizer.normalize(text.uppercase().replace('’', '\''), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_WORD, " ")
            .trim()

    private val PLATFORMS = Regex("""\(\s*(NETFLIX|PRIME|HBO|APPLE|STARZ|PARAMOUNT)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val ARABIC = Regex("""[؀-ۿ]""")
    private val ARABIC_WORDS = Regex("""[؀-ۿ][؀-ۿ\s]*""")
    private val SEPARATORS = Regex("""\s*(\||\s-\s|&|/)\s*""")
    private val SPACES = Regex("""\s+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^A-Z0-9+']+""")

    /** Catégories d'autres pays (clé [key]) : arabes, maghrébines, turques, polonaises, ramadan. */
    private val FOREIGN = Regex("""\b(ARABES?|ARABIC|MAGHREB\w*|TURQUES?|TURKISH|RAMADAN|POLONAISES?)\b|^PL\b""")

    /** Noms rencontrés dans les playlists (clé [key] du nom nettoyé). */
    private val NAMES: Map<String, String> = mapOf(
        // Films
        "100 DOLBY VISION HDR10" to "Dolby Vision HDR10",
        "3D MULTI" to "Films en 3D",
        "ANIMATION FAMILIALE ENFANTS" to "Animation et famille",
        "ARNAQUE" to "Arnaque",
        "ARTS MARTIAUX" to "Arts martiaux",
        "BIOPIC" to "Biopics",
        "BOX OFFICE POPULAIRE EN CE MOMENT" to "Box-office",
        "BRAQUAGE" to "Braquage",
        "CATASTROPHE" to "Catastrophe",
        "CLASSIC" to "Classiques",
        "COMEDIE" to "Comédie",
        "COMEDIE MUSICAL" to "Comédie musicale",
        "COUP DE COEUR" to "Coups de cœur",
        "DANCE" to "Danse",
        "DOCUMENTAIRES EMISSION TV" to "Documentaires et émissions",
        "DRAME HISTOIRE" to "Drame et histoire",
        "ESPIONNAGE POLITIQUE" to "Espionnage et politique",
        "FANTASTIQUE AVENTURE" to "Fantastique et aventure",
        "FILMS DE FIN D'ANNEE" to "Films de fin d'année",
        "FILMS EN 4K HDR" to "Films en 4K HDR",
        "FILMS MAGHREBINS DZ MA TN" to "Films maghrébins",
        "FILMS RECEMMENT AJOUTES" to "Films ajoutés récemment",
        "FRANCAIS" to "Films français",
        "JURIDIQUE" to "Juridique",
        "LEGENDAIRES ANCIENS FILMS" to "Films légendaires",
        "MAFIA GANG" to "Mafia et gangs",
        "MANGAS" to "Mangas",
        "MARITIME" to "Maritime",
        "MUSICAL" to "Musical",
        "MEDIEVALE MOYEN AGE" to "Moyen Âge",
        "POLICIER ACTION CRIME" to "Policier, action et crime",
        "ROMANCE" to "Romance",
        "SC FICTION HORREUR" to "Science-fiction et horreur",
        "SPECTACLES CONCERTS" to "Spectacles et concerts",
        "SURVIVALS FILMS DE SURVIE" to "Survie",
        "THRILLER DRAME GUERRE" to "Thriller, drame et guerre",
        "TUEUR EN SERIE" to "Tueurs en série",
        "TELEFILMS" to "Téléfilms",
        "UHD IMAX" to "UHD IMAX",
        "VENGEANCE" to "Vengeance",
        "VOITURES CARS" to "Voitures",
        "VOST ARABIC" to "Films en VOST arabe",
        "WESTERN HISTORIQUE" to "Western et historique",
        "WORKOUT SPORTS" to "Sport et fitness",
        "ANCIENS FILMS ARABES" to "Anciens films arabes",
        "FILMS ARABES" to "Films arabes",
        // Séries
        "4K HDR MUTLI" to "Séries en 4K HDR",
        "4K HDR MULTI" to "Séries en 4K HDR",
        "70'S 80'S OLD CULTES" to "Séries cultes",
        "ACTION" to "Action",
        "ANIMATION ENFANT" to "Animation et enfants",
        "AVENTURE FANTASTIQUE" to "Aventure et fantastique",
        "BRAQUAGE ARNAQUE" to "Braquage et arnaque",
        "BRUTX ORIGINAL" to "BrutX",
        "COMEDIE ANIMATION" to "Comédie animée",
        "COREENNE KOREA SERIES" to "Séries coréennes",
        "CRIME" to "Crime",
        "DISNEY +" to "Disney+",
        "DISNEY+" to "Disney+",
        "DOCUMENTAIRE BIOPIC" to "Documentaires et biopics",
        "DRAME" to "Drame",
        "HORREUR MYSTERE" to "Horreur et mystère",
        "JEUNESSE" to "Jeunesse",
        "MAGHREBINE RAMADAN" to "Séries maghrébines",
        "MEDECINE" to "Médecine",
        "MEDIEVAL MOYEN AGE" to "Moyen Âge",
        "PARAMOUNT+" to "Paramount+",
        "PL THRILLER ACTION WESTERN WAR HORROR" to "Séries polonaises",
        "POLICIER" to "Policier",
        "PRISON" to "Prison",
        "RECEMMENT AJOUTEES" to "Séries ajoutées récemment",
        "REALITE" to "Téléréalité",
        "SCIENCE FICTION" to "Science-fiction",
        "SPORT" to "Sport",
        "SUPER HEROS" to "Super-héros",
        "THRILLER" to "Thriller",
        "TOP SELCTION" to "Top sélection",
        "TOP SELECTION" to "Top sélection",
        "SERIES TURQUES" to "Séries turques",
        "SERIES ARABES" to "Séries arabes",
    )

    /** Noms qui changent (mois, année) : clé [key] → nom propre. */
    private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""^NOTRE SELECTION DU MOIS\b""") to { _ -> "Sélection du mois" },
        Regex("""^OSCARS? (\d{4})\b""") to { m -> "Oscars ${m.groupValues[1]}" },
        Regex("""\bRAMADAN (\d{4})\b""") to { m -> "Ramadan ${m.groupValues[1]}" },
    )
}
