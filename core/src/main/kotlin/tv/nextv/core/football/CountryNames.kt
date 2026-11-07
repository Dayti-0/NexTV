package tv.nextv.core.football

/**
 * Noms français des sélections nationales (« Italy » → « Italie ») : le calendrier les donne en
 * anglais, le guide des programmes et l'affichage les veulent en français.
 */
object CountryNames {

    /** Nom français d'une sélection d'après son nom anglais (ou français), `null` si inconnue. */
    fun french(name: String): String? {
        val key = FootballText.fold(name).replace(NON_WORD, " ").trim()
        return FRENCH[key] ?: key.takeIf { it in KNOWN_FRENCH }?.let { name.trim() }
    }

    private val NON_WORD = Regex("[^a-z0-9]+")

    private val FRENCH: Map<String, String> = mapOf(
        "france" to "France", "italy" to "Italie", "germany" to "Allemagne", "spain" to "Espagne", "england" to "Angleterre",
        "portugal" to "Portugal", "netherlands" to "Pays-Bas", "holland" to "Pays-Bas", "belgium" to "Belgique",
        "switzerland" to "Suisse", "croatia" to "Croatie", "denmark" to "Danemark", "norway" to "Norvège", "sweden" to "Suède",
        "poland" to "Pologne", "austria" to "Autriche", "scotland" to "Écosse", "wales" to "Pays de Galles",
        "republic of ireland" to "Irlande", "ireland" to "Irlande", "northern ireland" to "Irlande du Nord",
        "czechia" to "Tchéquie", "czech republic" to "Tchéquie", "hungary" to "Hongrie", "greece" to "Grèce",
        "turkey" to "Turquie", "turkiye" to "Turquie", "ukraine" to "Ukraine", "serbia" to "Serbie", "romania" to "Roumanie",
        "slovakia" to "Slovaquie", "slovenia" to "Slovénie", "bosnia and herzegovina" to "Bosnie-Herzégovine",
        "albania" to "Albanie", "iceland" to "Islande", "finland" to "Finlande", "georgia" to "Géorgie", "israel" to "Israël",
        "russia" to "Russie", "cyprus" to "Chypre", "armenia" to "Arménie", "azerbaijan" to "Azerbaïdjan",
        "lithuania" to "Lituanie", "latvia" to "Lettonie", "estonia" to "Estonie", "moldova" to "Moldavie",
        "north macedonia" to "Macédoine du Nord", "montenegro" to "Monténégro", "belarus" to "Biélorussie",
        "faroe islands" to "Îles Féroé", "andorra" to "Andorre", "malta" to "Malte", "san marino" to "Saint-Marin",
        "bulgaria" to "Bulgarie", "luxembourg" to "Luxembourg", "kosovo" to "Kosovo", "kazakhstan" to "Kazakhstan",
        "liechtenstein" to "Liechtenstein", "gibraltar" to "Gibraltar", "morocco" to "Maroc", "algeria" to "Algérie",
        "tunisia" to "Tunisie", "senegal" to "Sénégal", "ivory coast" to "Côte d'Ivoire", "cote d ivoire" to "Côte d'Ivoire",
        "cameroon" to "Cameroun", "egypt" to "Égypte", "nigeria" to "Nigeria", "ghana" to "Ghana", "mali" to "Mali",
        "brazil" to "Brésil", "argentina" to "Argentine", "uruguay" to "Uruguay", "colombia" to "Colombie", "chile" to "Chili",
        "mexico" to "Mexique", "united states" to "États-Unis", "usa" to "États-Unis", "canada" to "Canada",
        "japan" to "Japon", "south korea" to "Corée du Sud", "korea republic" to "Corée du Sud", "australia" to "Australie",
        "saudi arabia" to "Arabie saoudite", "qatar" to "Qatar", "iran" to "Iran",
    )

    /** Noms déjà français (le guide les écrit ainsi). */
    private val KNOWN_FRENCH: Set<String> = FRENCH.values.mapTo(HashSet()) { FootballText.fold(it).replace(NON_WORD, " ").trim() }
}
