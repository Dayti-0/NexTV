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
     * Rubriques, dans l'ordre des listes. [GENERAL] : autres chaînes françaises (services gratuits comme Pluto TV,
     * radios, chaînes sans rubrique connue) ; [OTHER] : autres pays et séparateurs.
     */
    enum class Section { TNT, CANAL, CINEMA, SERIES, SPORT, DECOUVERTE, JEUNESSE, MUSIQUE, INFO, REGIONAL, OUTREMER, EVENTS, GENERAL, OTHER }

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
     * Rang de chaque chaîne en direct dans l'ordre rangé, par place dans la playlist ([Channel.position]). Les clés de
     * version ([Channel.variantKey]) doivent déjà être celles des chaînes rassemblées : les versions d'une chaîne ont
     * la même place et restent ensemble.
     */
    fun compute(channels: List<Channel>): Map<Int, Int> {
        val live = channels.filter { it.kind == ContentKind.LIVE }.sortedBy { it.position }
        if (live.isEmpty()) return emptyMap()
        val names = live.associateWith { Names(it.name) }
        val frenchGroups = frenchGroups(live, names)
        val sections = playlistSections(live)
        val families = numberedFamilies(live, names, frenchGroups)

        // Place de chaque chaîne (la meilleure des places de ses versions), et sa première apparition dans la playlist.
        val byKey = HashMap<String, Placement>()
        val firstPosition = HashMap<String, Int>()
        val placements = HashMap<Int, Placement>(live.size)
        for (channel in live) {
            val placement = placementOf(channel, names.getValue(channel), frenchGroups, sections[channel.position], families)
            placements[channel.position] = placement
            val key = groupKey(channel)
            byKey.merge(key, placement) { a, b -> minOf(a, b) }
            firstPosition.merge(key, channel.position, ::minOf)
        }
        val ordered = live.sortedWith(
            compareBy<Channel> { byKey.getValue(groupKey(it)) }
                .thenBy { firstPosition.getValue(groupKey(it)) }
                .thenBy { it.position },
        )
        val result = HashMap<Int, Int>(ordered.size)
        ordered.forEachIndexed { index, channel -> result[channel.position] = index }
        return result
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
        if (FRANCE_3_HINT.containsMatchIn(names.folded) && FranceRegions.regionOf(channel.name) != null) {
            return Placement(Section.TNT, FRANCE_3 * TNT_STEP + 1)
        }
        names.tnt?.let { return Placement(Section.TNT, it * TNT_STEP) }
        if (EVENT_WORDS.containsMatchIn(names.folded)) return Placement(Section.EVENTS)
        KNOWN[names.glued]?.let { return it }
        if (inFamily(channel.variantKey, families)) return Placement(Section.EVENTS)
        if (names.glued.startsWith(CANAL_PLUS) && OVERSEAS.none { it in names.glued }) {
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

    /** Ordre des chaînes Canal+ (les autres viennent ensuite, dans l'ordre de la playlist). */
    private val CANAL_RANKS: Map<String, Int> = listOf(
        listOf("canalplus"),
        listOf("canalpluscinema", "canalpluscinemas"),
        listOf("canalplussport"),
        listOf("canalplusfoot"),
        listOf("canalplussport360"),
        listOf("canalplusgrandecran"),
        listOf("canalplusboxoffice"),
        listOf("canalplusseries"),
        listOf("canalpluskids"),
        listOf("canalplusdocs", "canalplusdocumentaires"),
        listOf("canalpluspremierleague"),
        listOf("canalplusligue1"),
        listOf("canalplusevenement"),
    ).flatMapIndexed { rank, names -> names.map { it to rank } }.toMap()

    /** Mots d'outre-mer ou d'Afrique (« CANAL+ CARAIBES », « CANAL+ AFRIQUE ») : pas le Canal+ de métropole. */
    private val OVERSEAS = listOf(
        "caraibe", "antille", "martinique", "guadeloupe", "guyane", "reunion", "mayotte", "polynesie", "caledonie", "tahiti",
        "wallis", "miquelon", "afrique", "africa", "madagascar", "maurice", "ocean",
    )

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

    /**
     * Chaînes françaises connues hors TNT et Canal+, par nom « collé » (sans pays, qualité ni parenthèses, « + » écrit
     * « plus », « & » écrit « et »), dans l'ordre d'un bouquet.
     */
    private val KNOWN: Map<String, Placement> = buildMap {
        var rank = 0
        fun add(section: Section, vararg names: String) {
            rank++
            names.forEach { putIfAbsent(it, Placement(section, rank)) }
        }
        // Cinéma.
        add(Section.CINEMA, "cineplusocs", "cineocs", "cinepluspremier", "cinepremier", "ocs", "ocsmax")
        add(Section.CINEMA, "cineplusfrisson", "cinefrisson")
        add(Section.CINEMA, "cineplusemotion", "cineemotion")
        add(Section.CINEMA, "cineplusfamily", "cinefamily", "cineplusfamiz", "cinefamiz")
        add(Section.CINEMA, "cineplusclassic", "cineclassic")
        add(Section.CINEMA, "cineplusfestival", "cinefestival")
        add(Section.CINEMA, "cineplusclub", "cineclub")
        add(Section.CINEMA, "ocscity")
        add(Section.CINEMA, "ocschoc")
        add(Section.CINEMA, "ocsgeants")
        add(Section.CINEMA, "tcmcinema", "tcm")
        add(Section.CINEMA, "paramountchannel", "paramount", "paramountnetwork")
        add(Section.CINEMA, "action")
        add(Section.CINEMA, "polarplus", "polar")
        add(Section.CINEMA, "warnertv", "warnerbrostv", "warnerbros")
        add(Section.CINEMA, "eurochannel")
        // Séries et divertissement.
        add(Section.SERIES, "parispremiere")
        add(Section.SERIES, "teva")
        add(Section.SERIES, "13emerue", "13erue", "treiziemerue")
        add(Section.SERIES, "serieclub")
        add(Section.SERIES, "syfy")
        add(Section.SERIES, "comedieplus", "comedie")
        add(Section.SERIES, "comedycentral", "comedycentralfrance")
        add(Section.SERIES, "rtl9")
        add(Section.SERIES, "ab1")
        add(Section.SERIES, "ab3")
        add(Section.SERIES, "tvbreizh")
        add(Section.SERIES, "e", "eentertainment")
        add(Section.SERIES, "novelas", "novelastv")
        add(Section.SERIES, "francetvseries")
        add(Section.SERIES, "staracademy")
        add(Section.SERIES, "mcm")
        // Sport.
        add(Section.SPORT, "beinsports1", "beinsport1")
        add(Section.SPORT, "beinsports2", "beinsport2")
        add(Section.SPORT, "beinsports3", "beinsport3")
        add(Section.SPORT, "rmcsport1")
        add(Section.SPORT, "rmcsport2")
        add(Section.SPORT, "eurosport1", "eurosport")
        add(Section.SPORT, "eurosport2")
        add(Section.SPORT, "infosportplus", "infosport")
        add(Section.SPORT, "ligue1pluslachaine", "ligue1plus")
        add(Section.SPORT, "equidia", "equidialive")
        add(Section.SPORT, "golfplus")
        add(Section.SPORT, "automoto")
        add(Section.SPORT, "sportenfrance")
        // Découverte.
        add(Section.DECOUVERTE, "planeteplus", "planete")
        add(Section.DECOUVERTE, "planeteplusaventure", "planeteplusae", "planeteplusaventureexperience")
        add(Section.DECOUVERTE, "planetepluscrimeinvestigation", "planeteplusci", "planetepluscrime")
        add(Section.DECOUVERTE, "nationalgeographic", "nationalgeo", "natgeo")
        add(Section.DECOUVERTE, "nationalgeographicwild", "nationalgeowild", "natgeowild")
        add(Section.DECOUVERTE, "discoverychannel", "discovery")
        add(Section.DECOUVERTE, "discoveryinvestigation", "discoveryinvestigations", "investigationdiscovery")
        add(Section.DECOUVERTE, "tlc")
        add(Section.DECOUVERTE, "ushuaiatv", "ushuaia")
        add(Section.DECOUVERTE, "histoiretv", "histoire")
        add(Section.DECOUVERTE, "toutelhistoire")
        add(Section.DECOUVERTE, "scienceetvie", "scienceetvietv")
        add(Section.DECOUVERTE, "animaux")
        add(Section.DECOUVERTE, "seasons", "season")
        add(Section.DECOUVERTE, "chasseetpeche")
        add(Section.DECOUVERTE, "trek")
        add(Section.DECOUVERTE, "museumtv", "museum", "museumchannel")
        add(Section.DECOUVERTE, "crimedistrict")
        add(Section.DECOUVERTE, "lovenature")
        add(Section.DECOUVERTE, "bbcearth", "bbcearthfrance")
        add(Section.DECOUVERTE, "francetvdocs")
        add(Section.DECOUVERTE, "bfmgrandsreportages")
        // Jeunesse.
        add(Section.JEUNESSE, "canalj")
        add(Section.JEUNESSE, "tiji")
        add(Section.JEUNESSE, "piwiplus", "piwi")
        add(Section.JEUNESSE, "teletoonplus", "teletoon")
        add(Section.JEUNESSE, "teletoonplus1")
        add(Section.JEUNESSE, "boomerang")
        add(Section.JEUNESSE, "boomerangplus1")
        add(Section.JEUNESSE, "cartoonnetwork")
        add(Section.JEUNESSE, "cartoonito")
        add(Section.JEUNESSE, "disneychannel")
        add(Section.JEUNESSE, "disneychannelplus1")
        add(Section.JEUNESSE, "disneyjunior")
        add(Section.JEUNESSE, "nickelodeon")
        add(Section.JEUNESSE, "nickelodeonplus1")
        add(Section.JEUNESSE, "nickelodeonjunior", "nickelodeonjr", "nickjunior", "nickjr")
        add(Section.JEUNESSE, "nickelodeon4teen", "nickelodeonteen", "nickteen")
        add(Section.JEUNESSE, "dreamworks")
        add(Section.JEUNESSE, "mangas")
        add(Section.JEUNESSE, "gameone")
        add(Section.JEUNESSE, "jone")
        add(Section.JEUNESSE, "babytv")
        // Musique.
        add(Section.MUSIQUE, "m6music")
        add(Section.MUSIQUE, "mtv", "mtvfrance")
        add(Section.MUSIQUE, "nrjhits")
        add(Section.MUSIQUE, "cstarhits", "cstarhitsfrance")
        add(Section.MUSIQUE, "rfmtv")
        add(Section.MUSIQUE, "traceurban")
        add(Section.MUSIQUE, "mcmtop")
        add(Section.MUSIQUE, "mcmpop")
        add(Section.MUSIQUE, "melody", "telemelody")
        add(Section.MUSIQUE, "mezzo")
        add(Section.MUSIQUE, "mezzolive")
        add(Section.MUSIQUE, "djazz", "djazztv")
        add(Section.MUSIQUE, "stingrayclassica")
        // Info (les chaînes d'info de la TNT sont dans la TNT).
        add(Section.INFO, "bfmbusiness")
        add(Section.INFO, "bfm2")
        add(Section.INFO, "cnewsprime")
        add(Section.INFO, "france24")
        add(Section.INFO, "euronews")
        add(Section.INFO, "i24news", "i24")
        add(Section.INFO, "tv5monde", "tv5")
        add(Section.INFO, "tv5news", "tv5mondeinfo", "tv5info")
        add(Section.INFO, "lachainemeteo")
        add(Section.INFO, "lefigarotv", "lefigaro")
        add(Section.INFO, "europe1", "europe1tv")
        add(Section.INFO, "rmctalkinfo")
        add(Section.INFO, "bsmart", "bsmarttv", "bsmart4change")
        add(Section.INFO, "cgtnfrance", "cgtn")
        // Chaînes locales.
        add(Section.REGIONAL, "tv8montblanc")
        add(Section.REGIONAL, "lefigaroidf", "lefigarotvidf")
        add(Section.REGIONAL, "idf1")
        add(Section.REGIONAL, "tvmonaco")
        add(Section.REGIONAL, "tvpi", "tvpibasque")
        add(Section.REGIONAL, "canal32")
        add(Section.REGIONAL, "puissancetv")
    }
}
