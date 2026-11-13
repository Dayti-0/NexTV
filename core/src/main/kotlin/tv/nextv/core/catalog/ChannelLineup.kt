package tv.nextv.core.catalog

import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.TntChannels
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import java.text.Normalizer

/**
 * Ordre « rangé » des chaînes en direct (réglage « Rassembler les chaînes »), comme sur une télé : la TNT dans l'ordre
 * des numéros (l'édition régionale de France 3 juste après France 3), puis Canal+, le cinéma, les séries et le
 * divertissement, le sport, la découverte, la jeunesse, la musique et l'info ; ensuite les chaînes régionales et
 * d'outre-mer, les chaînes « événement » (matchs en direct seulement), les autres chaînes françaises, et enfin les
 * autres pays, dans l'ordre de la playlist.
 *
 * La rubrique d'une chaîne vient, dans l'ordre : de son nom (TNT, Canal+, chaînes connues, chaînes « événement »), de
 * la rubrique de la playlist où elle est rangée (séparateur « ▀▄ FR ▀▄ CINEMA ▄▀ » qui la précède dans sa catégorie),
 * puis du nom de sa catégorie (« FR SPORTS »). Dans une rubrique, les chaînes connues d'abord, dans l'ordre d'un
 * bouquet, puis les autres dans l'ordre de la playlist. Les versions d'une chaîne (même clé) restent ensemble.
 */
object ChannelLineup {

    /**
     * Rubriques, dans l'ordre des listes, et nom de la catégorie qui les montre quand les chaînes sont rassemblées
     * ([title] ; il sert aussi à masquer la catégorie : ne pas le changer). [GENERAL] : autres chaînes françaises
     * (services gratuits comme Pluto TV, radios, chaînes sans rubrique connue) ; [OTHER] : autres pays et séparateurs,
     * qui gardent la catégorie de la playlist.
     */
    enum class Section(val title: String?) {
        TNT("TNT"),
        CANAL("Canal+"),
        CINEMA("Cinéma"),
        SERIES("Séries et divertissement"),
        SPORT("Sport"),
        DECOUVERTE("Découverte"),
        JEUNESSE("Jeunesse"),
        MUSIQUE("Musique"),
        INFO("Info"),
        REGIONAL("Régions"),
        OUTREMER("Outre-mer"),
        EVENTS("Matchs en direct"),
        GENERAL("Autres chaînes"),
        OTHER(null),
    }

    /**
     * Ce que l'ordre rangé donne à une chaîne : son rang ([lineup]), sa catégorie par thème ([theme], `null` : celle de
     * la playlist) et son nom propre ([name] : « Ciné+ Frisson » pour « FR| CINE FRISSON » ; `null` : celui de la
     * playlist).
     */
    data class Arrangement(val lineup: Int, val theme: String?, val name: String?)

    /** Place d'une chaîne : sa rubrique, puis son rang dans la rubrique ([UNRANKED] : ordre de la playlist). */
    data class Placement(val section: Section, val rank: Int = UNRANKED) : Comparable<Placement> {
        override fun compareTo(other: Placement): Int =
            compareValuesBy(this, other, { it.section.ordinal }, { it.rank })
    }

    /** Rang des chaînes d'une rubrique qui ne sont pas connues par leur nom : après les autres, dans l'ordre de la playlist. */
    const val UNRANKED = 10_000

    /**
     * Séparateur de la playlist, faux nom de chaîne qui annonce une rubrique (« ▀▄ FR ▀▄▀▄ CINEMA ▄▀▄▀▄ »,
     * « ------▼|FR|-SPORTS-|FR|▼------ », « ##### FRANCE ##### », « ★★★ SPORT ★★★ ») : au moins deux symboles de
     * décoration à la suite, ou une longue suite de tirets, de « = », de « # »…
     */
    fun isSeparator(name: String): Boolean = DECORATION.containsMatchIn(name) || FRAMED.matches(name)

    /**
     * Rang, catégorie par thème et nom propre de chaque chaîne en direct, par place dans la playlist ([Channel.position]).
     * Les clés de version ([Channel.variantKey]) doivent déjà être celles des chaînes rassemblées : les versions d'une
     * chaîne ont la même place, la même catégorie, et restent ensemble.
     */
    fun arrange(channels: List<Channel>): Map<Int, Arrangement> {
        val live = channels.filter { it.kind == ContentKind.LIVE }.sortedBy { it.position }
        if (live.isEmpty()) return emptyMap()
        val names = live.associateWith { Names(it.name) }
        val frenchGroups = frenchGroups(live, names)
        val sections = playlistSections(live)
        val families = numberedFamilies(live, names, frenchGroups)

        // Place de chaque chaîne (la meilleure des places de ses versions), et sa première apparition dans la playlist.
        val byKey = HashMap<String, Placement>()
        val firstPosition = HashMap<String, Int>()
        for (channel in live) {
            val placement = placementOf(channel, names.getValue(channel), frenchGroups, sections[channel.position], families)
            val key = groupKey(channel)
            byKey.merge(key, placement) { a, b -> minOf(a, b) }
            firstPosition.merge(key, channel.position, ::minOf)
        }
        val ordered = live.sortedWith(
            compareBy<Channel> { byKey.getValue(groupKey(it)) }
                .thenBy { firstPosition.getValue(groupKey(it)) }
                .thenBy { it.position },
        )
        val leftovers = groupsOfLeftovers(live, byKey)
        val result = HashMap<Int, Arrangement>(ordered.size)
        ordered.forEachIndexed { index, channel ->
            val section = byKey.getValue(groupKey(channel)).section
            val separator = LiveVariants.isSeparatorKey(channel.variantKey)
            val theme = when {
                separator -> null
                // Chaîne sans thème : dans « Autres chaînes » si sa catégorie est presque toute rangée par thème
                // (« FR TV HD » : KTO, NHK World), sinon elle garde la sienne (« PLUTO TV », « BELGES »).
                section == Section.GENERAL || section == Section.OTHER ->
                    Section.GENERAL.title.takeIf { channel.groupTitle.orEmpty() in leftovers }
                else -> section.title
            }
            val name = if (section == Section.OTHER || separator) null else properName(channel, names.getValue(channel))
            result[channel.position] = Arrangement(index, theme, name)
        }
        return result
    }

    /** Rang de chaque chaîne en direct dans l'ordre rangé, par place dans la playlist (voir [arrange]). */
    fun compute(channels: List<Channel>): Map<Int, Int> = arrange(channels).mapValues { it.value.lineup }

    /**
     * Catégories de la playlist dont les chaînes sont pour la plupart rangées par thème : celles qui restent (« Autres
     * chaînes ») ne justifient pas de garder la catégorie pour elles.
     */
    private fun groupsOfLeftovers(live: List<Channel>, byKey: Map<String, Placement>): Set<String> {
        val all = HashMap<String, Int>()
        val themed = HashMap<String, Int>()
        for (channel in live) {
            if (LiveVariants.isSeparatorKey(channel.variantKey)) continue
            val section = byKey.getValue(groupKey(channel)).section
            val group = channel.groupTitle.orEmpty()
            all.merge(group, 1, Int::plus)
            if (section != Section.GENERAL && section != Section.OTHER) themed.merge(group, 1, Int::plus)
        }
        return all.filter { (group, count) -> (themed[group] ?: 0) * 2 >= count }.keys
    }

    /**
     * Nom propre d'une chaîne française : celui de la chaîne connue (« Canal+ Cinéma(s) », « France 3 Alpes »), sinon le
     * nom de la playlist sans le pays (« FR| TURBO 24/24 » → « TURBO 24/24 »).
     */
    private fun properName(channel: Channel, names: Names): String? {
        names.region?.let { return "France 3 ${it.label}" }
        names.tnt?.let { number -> TNT_NAMES[number]?.let { return it } }
        KNOWN_NAMES[names.glued]?.let { return it }
        CANAL_NAMES[names.glued]?.let { return it }
        val title = channel.title
        val prefix = COUNTRY_PREFIX.find(title)?.takeIf { it.groupValues[1].uppercase() in FRENCH_CODES }
        val withoutCountry = prefix?.let { title.substring(it.range.last + 1).trim() }?.ifEmpty { null } ?: title
        // « LIGUE 1+| MATCH 1 » : la barre qui suit le nom du championnat.
        return LEAGUE_BAR.replaceFirst(withoutCountry, "$1 ")
    }

    /** Rubrique et rang d'une chaîne d'après son seul nom et sa catégorie (sans les rubriques de la playlist). */
    fun placementOf(name: String, groupTitle: String? = null): Placement {
        val channel = Channel(position = 0, name = name, stream = tv.nextv.core.model.StreamDescriptor(""), groupTitle = groupTitle)
        val names = Names(name)
        val french = names.prefix?.let { it in FRENCH_CODES } ?: (groupTitle == null || frenchMarker(groupTitle) || names.tnt != null)
        val frenchGroups = if (french) setOf(groupTitle.orEmpty()) else emptySet()
        return placementOf(channel, names, frenchGroups, null, emptySet())
    }

    private fun groupKey(channel: Channel) = channel.variantKey.ifEmpty { "\u0000${channel.position}" }

    private fun placementOf(
        channel: Channel,
        names: Names,
        frenchGroups: Set<String>,
        section: Section?,
        families: Set<String>,
    ): Placement {
        if (LiveVariants.isSeparatorKey(channel.variantKey) || isSeparator(channel.name)) return Placement(Section.OTHER)
        val french = when (val prefix = names.prefix) {
            // Sans pays : catégorie française, ou chaîne française connue par son nom (« beIN Sports 1 » dans « Sport »).
            null -> channel.groupTitle.orEmpty() in frenchGroups || names.tnt != null || names.glued in KNOWN ||
                names.glued.startsWith(CANAL_PLUS)
            else -> prefix in FRENCH_CODES
        }
        if (!french) return Placement(Section.OTHER)
        // Édition régionale de France 3 : juste après France 3 (une seule reste visible, celle des réglages).
        if (names.region != null) return Placement(Section.TNT, FRANCE_3 * TNT_STEP + 1)
        names.tnt?.let { return Placement(Section.TNT, it * TNT_STEP) }
        if (EVENT_WORDS.containsMatchIn(names.folded)) return Placement(Section.EVENTS)
        KNOWN[names.glued]?.let { return it }
        if (inFamily(channel.variantKey, families)) return Placement(Section.EVENTS)
        if (names.glued.startsWith(CANAL_PLUS) && NOT_METROPOLITAN.none { it in names.glued }) {
            return Placement(Section.CANAL, CANAL_RANKS[names.glued] ?: CANAL_OTHER)
        }
        if (ROUND_THE_CLOCK.containsMatchIn(channel.name)) return Placement(Section.SERIES)
        // BFM locales (BFM Marseille, BFM DICI…) : les BFM nationales sont dans la TNT ou parmi les chaînes connues.
        if (names.glued.startsWith("bfm")) return Placement(Section.REGIONAL)
        section?.let { return Placement(it) }
        groupSection(channel.groupTitle)?.let { return Placement(it) }
        if (OVERSEAS.any { it in names.glued }) return Placement(Section.OUTREMER)
        return Placement(Section.GENERAL)
    }

    /** Écritures d'un nom utiles au classement, calculées une fois par chaîne. */
    private class Names(name: String) {
        /** Pays en tête du nom (« |FR| », « FR: », « [BE] »), en majuscules ; `null` sans pays. */
        val prefix: String? = COUNTRY_PREFIX.find(name)?.groupValues?.get(1)?.uppercase()?.takeUnless { QualityParser.isTechnical(it) }

        /** Nom sans pays ni mentions de qualité ou de son, sans parenthèses, en minuscules sans accents ni espaces. */
        val glued: String = EpgImporter.normalizeName(BRACKETS.replace(ContentClassifier.cleanTitle(name), " "))

        /** Nom complet en minuscules sans accents (mentions « LIVE ONLY », « PPV » comprises). */
        val folded: String = fold(name)

        /** Numéro TNT de la chaîne (« FR| FRANCE 2 FHD » → 2), `null` hors de la TNT. */
        val tnt: Int? = TntChannels.numberOfNormalized(glued)

        /** Édition régionale de France 3 (« FRANCE 3 PARIS IDF » → Paris Île-de-France), `null` sinon. */
        val region: FranceRegions.Region? = if (FRANCE_3_HINT.containsMatchIn(folded)) FranceRegions.regionOf(name) else null
    }

    /**
     * Catégories françaises sans pays dans le nom des chaînes : celles dont le nom le dit (« FR », « FRANCE »), et celles
     * qui contiennent une chaîne de la TNT écrite sans pays (« Généralistes » : TF1, France 2…).
     */
    private fun frenchGroups(live: List<Channel>, names: Map<Channel, Names>): Set<String> {
        val groups = HashSet<String>()
        val checked = HashSet<String>()
        for (channel in live) {
            val group = channel.groupTitle.orEmpty()
            if (checked.add(group) && frenchMarker(group)) groups += group
        }
        for (channel in live) {
            val group = channel.groupTitle.orEmpty()
            val channelNames = names.getValue(channel)
            if (group !in groups && channelNames.prefix == null && channelNames.tnt != null) groups += group
        }
        return groups
    }

    private fun frenchMarker(groupTitle: String): Boolean = words(groupTitle).any { it in FRENCH_WORDS }

    /**
     * Rubrique de la playlist de chaque chaîne : celle du dernier séparateur qui la précède dans sa catégorie, s'il
     * annonce une rubrique connue (« CINEMA », « JEUNESSE »… ; « PLUTO TV FILMS » n'est pas une rubrique de la
     * playlist, mais celle d'un service à part).
     */
    private fun playlistSections(live: List<Channel>): Map<Int, Section> {
        val current = HashMap<String, Section?>()
        val result = HashMap<Int, Section>()
        for (channel in live) {
            val group = channel.groupTitle.orEmpty()
            if (LiveVariants.isSeparatorKey(channel.variantKey) || isSeparator(channel.name)) {
                current[group] = separatorSection(channel.name)
                continue
            }
            current[group]?.let { result[channel.position] = it }
        }
        return result
    }

    /** Rubrique annoncée par un séparateur : tous ses mots (hors pays et mots passe-partout) désignent la même rubrique. */
    private fun separatorSection(name: String): Section? {
        val words = words(name).filter { it !in SECTION_NOISE }
        if (words.isEmpty()) return null
        var found: Section? = null
        for (word in words) {
            val section = SECTION_WORDS[word] ?: return null
            if (found != null && found != section) return null
            found = section
        }
        return found
    }

    /** Rubrique désignée par le nom d'une catégorie (« FR SPORTS », « FR | CINEMA », « JEUNESSE »). */
    private fun groupSection(groupTitle: String?): Section? {
        if (groupTitle.isNullOrBlank()) return null
        val words = words(groupTitle).toSet()
        return GROUP_SECTIONS.firstOrNull { (_, keywords) -> keywords.any { it in words } }?.first
    }

    /**
     * Familles de chaînes « événement » numérotées : au moins [MIN_FAMILY] chaînes françaises au même nom suivi d'un
     * numéro (« EUROSPORT 360 1 » à « 26 », « LNB TV 1 » à « 10 »). Les numéros au-delà de 99 n'en font pas partie
     * (« CANAL+ SPORT 360 » n'est pas la 360e « CANAL+ SPORT »).
     */
    private fun numberedFamilies(live: List<Channel>, names: Map<Channel, Names>, frenchGroups: Set<String>): Set<String> {
        val numbers = HashMap<String, MutableSet<Int>>()
        for (channel in live) {
            val prefix = names.getValue(channel).prefix
            val french = if (prefix == null) channel.groupTitle.orEmpty() in frenchGroups else prefix in FRENCH_CODES
            if (!french) continue
            val key = channel.variantKey
            val number = key.substringAfterLast(' ', "").toIntOrNull() ?: continue
            if (number > MAX_FAMILY_NUMBER) continue
            numbers.getOrPut(familyOf(key)) { HashSet() } += number
        }
        return numbers.filterValues { it.size >= MIN_FAMILY }.keys
    }

    private fun familyOf(variantKey: String): String = variantKey.substringBeforeLast(' ', "")

    /** Chaîne d'une famille numérotée : son numéro (jusqu'à 99) suit le nom de la famille. */
    private fun inFamily(variantKey: String, families: Set<String>): Boolean {
        val number = variantKey.substringAfterLast(' ', "").toIntOrNull() ?: return false
        return number <= MAX_FAMILY_NUMBER && familyOf(variantKey) in families
    }

    private fun words(text: String): List<String> = fold(text).split(NON_WORD).filter { it.isNotEmpty() }

    private fun fold(text: String): String {
        val lower = text.lowercase()
        return if (lower.all { it.code < 0x80 }) lower else MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    private const val TNT_STEP = 10
    private const val FRANCE_3 = 3

    /** « France 3 », « France3 », « F3 » dans un nom : seule une telle chaîne peut être une édition régionale. */
    private val FRANCE_3_HINT = Regex("""france\s*3|\bf3\b""")

    /** Début du nom des chaînes Canal+ (« canalplus… » : « + » écrit « plus »). */
    private const val CANAL_PLUS = "canalplus"
    private const val CANAL_OTHER = 50

    /** Chaînes Canal+ dans leur ordre, avec leur nom (les autres viennent ensuite, dans l'ordre de la playlist). */
    private val CANAL_CHANNELS: List<Pair<String, List<String>>> = listOf(
        "Canal+" to listOf("canalplus"),
        "Canal+ Cinéma(s)" to listOf("canalpluscinema", "canalpluscinemas"),
        "Canal+ Sport" to listOf("canalplussport"),
        "Canal+ Foot" to listOf("canalplusfoot"),
        "Canal+ Sport 360" to listOf("canalplussport360"),
        "Canal+ Grand Écran" to listOf("canalplusgrandecran"),
        "Canal+ Box Office" to listOf("canalplusboxoffice"),
        "Canal+ Séries" to listOf("canalplusseries"),
        "Canal+ Kids" to listOf("canalpluskids"),
        "Canal+ Docs" to listOf("canalplusdocs", "canalplusdocumentaires"),
        "Canal+ Premier League" to listOf("canalpluspremierleague"),
        "Canal+ Ligue 1" to listOf("canalplusligue1"),
        "Canal+ Événement" to listOf("canalplusevenement"),
    )

    private val CANAL_RANKS: Map<String, Int> =
        CANAL_CHANNELS.flatMapIndexed { rank, (_, names) -> names.map { it to rank } }.toMap()

    private val CANAL_NAMES: Map<String, String> =
        CANAL_CHANNELS.flatMap { (title, names) -> names.map { it to title } }.toMap()

    /** Nom des chaînes de la TNT, par numéro. */
    private val TNT_NAMES: Map<Int, String> = mapOf(
        1 to "TF1", 2 to "France 2", 3 to "France 3", 4 to "France 4", 5 to "France 5", 6 to "M6", 7 to "Arte", 8 to "LCP",
        9 to "W9", 10 to "TMC", 11 to "TFX", 12 to "Gulli", 13 to "BFMTV", 14 to "CNews", 15 to "LCI", 16 to "franceinfo",
        17 to "CStar", 18 to "T18", 19 to "Novo19", 20 to "TF1 Séries Films", 21 to "L'Équipe", 22 to "6ter", 23 to "RMC Story",
        24 to "RMC Découverte", 25 to "RMC Life",
    )

    /** Lieux d'outre-mer dans un nom (« MARTINIQUE 1ERE », « CANAL+ CARAIBES ») : rubrique Outre-mer. */
    private val OVERSEAS = listOf(
        "caraibe", "antille", "martinique", "guadeloupe", "guyane", "reunion", "mayotte", "polynesie", "caledonie", "tahiti",
        "wallis", "miquelon",
    )

    /** Canal+ d'outre-mer ou d'Afrique (« CANAL+ CARAIBES », « CANAL+ AFRIQUE ») : pas le Canal+ de métropole. */
    private val NOT_METROPOLITAN = OVERSEAS + listOf("afrique", "africa", "madagascar", "maurice", "ocean")

    /** Nom de championnat suivi d'une barre (« LIGUE 1+| MATCH 1 ») : la barre devient une espace. */
    private val LEAGUE_BAR = Regex("""^([^|]{1,12}?)\s*\|\s*""")

    /** Pays écrit en tête du nom (« |FR| », « FR: », « [FR] », « FR - », « |C+AF| ») ; pas « LIGUE 1+| » ni « TF1 HD ». */
    private val COUNTRY_PREFIX = Regex("""^\s*[\[(|]?\s*([A-Za-z][A-Za-z0-9+]{1,4})\s*(?:[\])|:]|\s[-–]\s)""")

    private val FRENCH_CODES = setOf("FR", "FRA", "VF", "VFF")

    /** Mots qui désignent une catégorie française (« FR TV HD », « PLUTO TV (FRANCE) »). */
    private val FRENCH_WORDS = setOf("fr", "fra", "france", "francais", "francaise", "french", "vf", "vff")

    /**
     * Chaînes « événement » d'après leur nom : un match ou une épreuve en direct seulement, l'écran est noir le reste du
     * temps (« CANAL+ LIVE 1 », « LIGUE 1+ MATCH 3 », « DAZN PPV 2 », « … (LIVE ONLY) », « F1TV ONBOARD 4 »).
     */
    private val EVENT_WORDS = Regex(
        """\b(?:live|direct)\s*(?:only|uniquement)\b|\b(?:event|match)s?\s*only\b|\blive\s*(?:event|match)\b|\bppv\b|""" +
            """\bonboard\b|\bmultiplex\b|\b(?:live|event|evenement|match)\s*\d+\b""",
    )

    /** Au moins ce nombre de chaînes numérotées au même nom : une famille de chaînes « événement ». */
    private const val MIN_FAMILY = 4
    private const val MAX_FAMILY_NUMBER = 99

    /** Chaîne qui diffuse un seul programme en boucle (« TURBO 24/24 », « ZONE INTERDITE 24H/24 ») : divertissement. */
    private val ROUND_THE_CLOCK = Regex("""\b24\s*[hH]?\s*/\s*24\b""")

    private val BRACKETS = Regex("""\([^)]*\)?|\[[^\]]*]?""")
    private val NON_WORD = Regex("[^a-z0-9+]+")
    private val MARKS = Regex("\\p{Mn}+")

    /** Décoration d'un séparateur : symboles graphiques à la suite (cadres, blocs, formes, étoiles, flèches), ou tirets… */
    private val DECORATION = Regex("""[─-➿⬀-⯿]{2,}|[«»]{2,}|-{4,}|[=*#~_]{3,}""")

    /** Nom encadré de symboles (« ▼|FR|BASKET LIGUE NATIONAL|FR|▼ ») : un séparateur aussi. */
    private val FRAMED = Regex("""\s*[─-➿⬀-⯿].*[─-➿⬀-⯿]\s*""")

    /** Mots passe-partout d'un séparateur (« ▀▄ FR ▀▄ CHAINES CINEMA HD ▄▀ »). */
    private val SECTION_NOISE = setOf(
        "fr", "fra", "france", "francais", "francaise", "french", "chaine", "chaines", "tv", "les", "des", "et", "de", "la", "le",
        "hd", "fhd", "sd", "uhd", "4k", "vip", "premium", "live",
    )

    /** Mots d'un séparateur et rubrique qu'ils annoncent. */
    private val SECTION_WORDS: Map<String, Section> = buildMap {
        fun add(section: Section, vararg words: String) = words.forEach { put(it, section) }
        add(Section.CANAL, "canal+", "canalplus")
        add(Section.CINEMA, "cinema", "cinemas", "cine", "films", "film", "movies", "movie", "box", "office")
        add(Section.SERIES, "divertissement", "divertissements", "series", "serie", "entertainment", "fiction", "fictions")
        add(Section.SPORT, "sport", "sports", "foot", "football")
        add(Section.DECOUVERTE, "decouverte", "decouvertes", "documentaire", "documentaires", "docs", "doc", "documentary", "nature", "culture")
        add(Section.JEUNESSE, "jeunesse", "enfants", "enfant", "kids", "junior", "dessins", "animes")
        add(Section.MUSIQUE, "musique", "musiques", "music", "clips")
        add(Section.INFO, "info", "infos", "information", "informations", "news", "actualite", "actualites")
        add(Section.GENERAL, "generaliste", "generalistes", "tnt", "nationale", "nationales")
        add(Section.REGIONAL, "regionales", "regionale", "regional", "regions", "region", "locales", "locale", "local")
        add(Section.OUTREMER, "outre", "mer", "outremer", "dom", "tom", "ultramarines")
    }

    /** Rubrique désignée par un mot du nom d'une catégorie, la première trouvée dans cet ordre. */
    private val GROUP_SECTIONS: List<Pair<Section, Set<String>>> = listOf(
        Section.SPORT to setOf(
            "sport", "sports", "foot", "football", "ligue", "liga", "laliga", "dazn", "bein", "nba", "rugby", "tennis", "f1", "golf",
            "basket", "euroleague", "lnb", "ufc", "wwe", "boxe",
        ),
        Section.JEUNESSE to setOf("jeunesse", "enfant", "enfants", "kids", "cartoon", "cartoons", "anime", "animes", "dessins"),
        Section.MUSIQUE to setOf("musique", "musiques", "music", "clips", "concerts"),
        Section.INFO to setOf("info", "infos", "information", "informations", "news", "actualite", "actualites"),
        Section.DECOUVERTE to setOf("decouverte", "documentaire", "documentaires", "docs", "documentary"),
        Section.CINEMA to setOf("cinema", "cinemas", "cine", "films", "film", "movies", "movie"),
        Section.SERIES to setOf("series", "serie", "divertissement", "entertainment"),
        Section.REGIONAL to setOf("regional", "regionales", "regions", "locales", "local"),
        Section.OUTREMER to setOf("outremer", "dom", "antilles"),
    )

    /** Chaîne française connue hors TNT et Canal+ : sa place, et son nom tel que la chaîne l'écrit. */
    private class Known(val placement: Placement, val title: String)

    /**
     * Chaînes françaises connues hors TNT et Canal+, par nom « collé » (sans pays, qualité ni parenthèses, « + » écrit
     * « plus », « & » écrit « et »), dans l'ordre d'un bouquet.
     */
    private val KNOWN_CHANNELS: Map<String, Known> = buildMap {
        var rank = 0
        fun add(section: Section, title: String, vararg names: String) {
            rank++
            val known = Known(Placement(section, rank), title)
            names.forEach { putIfAbsent(it, known) }
        }
        // Cinéma.
        add(Section.CINEMA, "Ciné+ OCS", "cineplusocs", "cineocs")
        add(Section.CINEMA, "Ciné+ Premier", "cinepluspremier", "cinepremier")
        add(Section.CINEMA, "OCS", "ocs", "ocsmax")
        add(Section.CINEMA, "Ciné+ Frisson", "cineplusfrisson", "cinefrisson")
        add(Section.CINEMA, "Ciné+ Émotion", "cineplusemotion", "cineemotion")
        add(Section.CINEMA, "Ciné+ Family", "cineplusfamily", "cinefamily")
        add(Section.CINEMA, "Ciné+ Famiz", "cineplusfamiz", "cinefamiz")
        add(Section.CINEMA, "Ciné+ Classic", "cineplusclassic", "cineclassic")
        add(Section.CINEMA, "Ciné+ Festival", "cineplusfestival", "cinefestival")
        add(Section.CINEMA, "Ciné+ Club", "cineplusclub", "cineclub")
        add(Section.CINEMA, "OCS City", "ocscity")
        add(Section.CINEMA, "OCS Choc", "ocschoc")
        add(Section.CINEMA, "OCS Géants", "ocsgeants")
        add(Section.CINEMA, "TCM Cinéma", "tcmcinema", "tcm")
        add(Section.CINEMA, "Paramount Channel", "paramountchannel", "paramount", "paramountnetwork")
        add(Section.CINEMA, "Action", "action")
        add(Section.CINEMA, "Polar+", "polarplus", "polar")
        add(Section.CINEMA, "Warner TV", "warnertv", "warnerbrostv", "warnerbros")
        add(Section.CINEMA, "Eurochannel", "eurochannel")
        // Séries et divertissement.
        add(Section.SERIES, "Paris Première", "parispremiere")
        add(Section.SERIES, "Téva", "teva")
        add(Section.SERIES, "13e Rue", "13emerue", "13erue", "treiziemerue")
        add(Section.SERIES, "Série Club", "serieclub")
        add(Section.SERIES, "Syfy", "syfy")
        add(Section.SERIES, "Comédie+", "comedieplus", "comedie")
        add(Section.SERIES, "Comedy Central", "comedycentral", "comedycentralfrance")
        add(Section.SERIES, "RTL9", "rtl9")
        add(Section.SERIES, "AB1", "ab1")
        add(Section.SERIES, "AB3", "ab3")
        add(Section.SERIES, "TV Breizh", "tvbreizh")
        add(Section.SERIES, "E!", "e", "eentertainment")
        add(Section.SERIES, "Novelas TV", "novelas", "novelastv")
        add(Section.SERIES, "France TV Séries", "francetvseries")
        add(Section.SERIES, "Star Academy", "staracademy")
        add(Section.SERIES, "MCM", "mcm")
        // Sport.
        add(Section.SPORT, "beIN Sports 1", "beinsports1", "beinsport1")
        add(Section.SPORT, "beIN Sports 2", "beinsports2", "beinsport2")
        add(Section.SPORT, "beIN Sports 3", "beinsports3", "beinsport3")
        add(Section.SPORT, "RMC Sport 1", "rmcsport1")
        add(Section.SPORT, "RMC Sport 2", "rmcsport2")
        add(Section.SPORT, "Eurosport 1", "eurosport1", "eurosport")
        add(Section.SPORT, "Eurosport 2", "eurosport2")
        add(Section.SPORT, "Infosport+", "infosportplus", "infosport")
        add(Section.SPORT, "Ligue 1+", "ligue1pluslachaine", "ligue1plus")
        add(Section.SPORT, "Equidia", "equidia", "equidialive")
        add(Section.SPORT, "Golf+", "golfplus")
        add(Section.SPORT, "Automoto", "automoto")
        add(Section.SPORT, "Sport en France", "sportenfrance")
        // Découverte.
        add(Section.DECOUVERTE, "Planète+", "planeteplus", "planete")
        add(Section.DECOUVERTE, "Planète+ Aventure", "planeteplusaventure", "planeteplusae", "planeteplusaventureexperience")
        add(Section.DECOUVERTE, "Planète+ Crime Investigation", "planetepluscrimeinvestigation", "planeteplusci", "planetepluscrime")
        add(Section.DECOUVERTE, "National Geographic", "nationalgeographic", "nationalgeo", "natgeo")
        add(Section.DECOUVERTE, "Nat Geo Wild", "nationalgeographicwild", "nationalgeowild", "natgeowild")
        add(Section.DECOUVERTE, "Discovery Channel", "discoverychannel", "discovery")
        add(Section.DECOUVERTE, "Discovery Investigation", "discoveryinvestigation", "discoveryinvestigations", "investigationdiscovery")
        add(Section.DECOUVERTE, "TLC", "tlc")
        add(Section.DECOUVERTE, "Ushuaïa TV", "ushuaiatv", "ushuaia")
        add(Section.DECOUVERTE, "Histoire TV", "histoiretv", "histoire")
        add(Section.DECOUVERTE, "Toute l'Histoire", "toutelhistoire")
        add(Section.DECOUVERTE, "Science & Vie TV", "scienceetvie", "scienceetvietv")
        add(Section.DECOUVERTE, "Animaux", "animaux")
        add(Section.DECOUVERTE, "Seasons", "seasons", "season")
        add(Section.DECOUVERTE, "Chasse & Pêche", "chasseetpeche")
        add(Section.DECOUVERTE, "Trek", "trek")
        add(Section.DECOUVERTE, "Museum TV", "museumtv", "museumchannel")
        add(Section.DECOUVERTE, "Museum 4K", "museum")
        add(Section.DECOUVERTE, "Crime District", "crimedistrict")
        add(Section.DECOUVERTE, "Love Nature", "lovenature")
        add(Section.DECOUVERTE, "BBC Earth", "bbcearth", "bbcearthfrance")
        add(Section.DECOUVERTE, "France TV Docs", "francetvdocs")
        add(Section.DECOUVERTE, "BFM Grands Reportages", "bfmgrandsreportages")
        // Jeunesse.
        add(Section.JEUNESSE, "Canal J", "canalj")
        add(Section.JEUNESSE, "TiJi", "tiji")
        add(Section.JEUNESSE, "Piwi+", "piwiplus", "piwi")
        add(Section.JEUNESSE, "Télétoon+", "teletoonplus", "teletoon")
        add(Section.JEUNESSE, "Télétoon+1", "teletoonplus1")
        add(Section.JEUNESSE, "Boomerang", "boomerang")
        add(Section.JEUNESSE, "Boomerang +1", "boomerangplus1")
        add(Section.JEUNESSE, "Cartoon Network", "cartoonnetwork")
        add(Section.JEUNESSE, "Cartoonito", "cartoonito")
        add(Section.JEUNESSE, "Disney Channel", "disneychannel")
        add(Section.JEUNESSE, "Disney Channel +1", "disneychannelplus1")
        add(Section.JEUNESSE, "Disney Junior", "disneyjunior")
        add(Section.JEUNESSE, "Nickelodeon", "nickelodeon")
        add(Section.JEUNESSE, "Nickelodeon +1", "nickelodeonplus1")
        add(Section.JEUNESSE, "Nickelodeon Junior", "nickelodeonjunior", "nickelodeonjr", "nickjunior", "nickjr")
        add(Section.JEUNESSE, "Nickelodeon Teen", "nickelodeon4teen", "nickelodeonteen", "nickteen")
        add(Section.JEUNESSE, "DreamWorks", "dreamworks")
        add(Section.JEUNESSE, "Mangas", "mangas")
        add(Section.JEUNESSE, "Game One", "gameone")
        add(Section.JEUNESSE, "J-One", "jone")
        add(Section.JEUNESSE, "Baby TV", "babytv")
        // Musique.
        add(Section.MUSIQUE, "M6 Music", "m6music")
        add(Section.MUSIQUE, "MTV", "mtv", "mtvfrance")
        add(Section.MUSIQUE, "NRJ Hits", "nrjhits")
        add(Section.MUSIQUE, "CStar Hits", "cstarhits", "cstarhitsfrance")
        add(Section.MUSIQUE, "RFM TV", "rfmtv")
        add(Section.MUSIQUE, "Trace Urban", "traceurban")
        add(Section.MUSIQUE, "MCM Top", "mcmtop")
        add(Section.MUSIQUE, "MCM Pop", "mcmpop")
        add(Section.MUSIQUE, "Mélody", "melody", "telemelody")
        add(Section.MUSIQUE, "Mezzo", "mezzo")
        add(Section.MUSIQUE, "Mezzo Live", "mezzolive")
        add(Section.MUSIQUE, "Djazz", "djazz", "djazztv")
        add(Section.MUSIQUE, "Stingray Classica", "stingrayclassica")
        // Info (les chaînes d'info de la TNT sont dans la TNT).
        add(Section.INFO, "BFM Business", "bfmbusiness")
        add(Section.INFO, "BFM 2", "bfm2")
        add(Section.INFO, "CNews Prime", "cnewsprime")
        add(Section.INFO, "France 24", "france24")
        add(Section.INFO, "Euronews", "euronews")
        add(Section.INFO, "i24News", "i24news", "i24")
        add(Section.INFO, "TV5Monde", "tv5monde", "tv5")
        add(Section.INFO, "TV5Monde Info", "tv5news", "tv5mondeinfo", "tv5info")
        add(Section.INFO, "La Chaîne Météo", "lachainemeteo")
        add(Section.INFO, "Le Figaro TV", "lefigarotv", "lefigaro")
        add(Section.INFO, "Europe 1", "europe1", "europe1tv")
        add(Section.INFO, "RMC Talk Info", "rmctalkinfo")
        add(Section.INFO, "B Smart", "bsmart", "bsmarttv", "bsmart4change")
        add(Section.INFO, "CGTN France", "cgtnfrance")
        add(Section.INFO, "CGTN", "cgtn")
        // Chaînes locales.
        add(Section.REGIONAL, "TV8 Mont-Blanc", "tv8montblanc")
        add(Section.REGIONAL, "Le Figaro TV Île-de-France", "lefigaroidf", "lefigarotvidf")
        add(Section.REGIONAL, "IDF1", "idf1")
        add(Section.REGIONAL, "TV Monaco", "tvmonaco")
        add(Section.REGIONAL, "TVPI", "tvpi", "tvpibasque")
        add(Section.REGIONAL, "Canal 32", "canal32")
        add(Section.REGIONAL, "Puissance TV", "puissancetv")
        add(Section.REGIONAL, "BFM Paris Île-de-France", "bfmparis", "bfmparisidf", "bfmparisiledefrance")
        add(Section.REGIONAL, "BFM Lyon", "bfmlyon")
        add(Section.REGIONAL, "BFM Marseille Provence", "bfmmarseille", "bfmmarseilleprovence")
        add(Section.REGIONAL, "BFM DICI Haute-Provence", "bfmdicihauteprovence")
        add(Section.REGIONAL, "BFM Nice Côte d'Azur", "bfmnice", "bfmnicecotedazur")
        add(Section.REGIONAL, "BFM DICI Alpes du Sud", "bfmdicialpes", "bfmdicialpesdusud")
        add(Section.REGIONAL, "BFM Toulon Var", "bfmtoulon", "bfmtoulonvar")
        add(Section.REGIONAL, "BFM Grand Lille", "bfmgrandlille", "bfmgrandlilletv")
        add(Section.REGIONAL, "BFM Grand Littoral", "bfmgrandlittoral", "bfmlittoral")
        add(Section.REGIONAL, "BFM Normandie", "bfmnormandie")
        add(Section.REGIONAL, "BFM Alsace", "bfmalsace")
    }

    private val KNOWN: Map<String, Placement> = KNOWN_CHANNELS.mapValues { it.value.placement }

    private val KNOWN_NAMES: Map<String, String> = KNOWN_CHANNELS.mapValues { it.value.title }
}
