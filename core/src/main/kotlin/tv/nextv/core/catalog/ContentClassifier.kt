package tv.nextv.core.catalog

import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpisodeInfo
import java.text.Normalizer

/**
 * Classe les entrées d'une playlist en chaînes en direct, films et épisodes de séries,
 * à partir des indices réellement présents : attribut `tvg-type`, chemin de l'URL
 * (`/movie/`, `/series/`, `/live/` des serveurs Xtream), nom du groupe, motif « S01E02 »,
 * extension de fichier vidéo. Sans indice, l'entrée est une chaîne en direct.
 */
object ContentClassifier {

    data class Result(
        val kind: ContentKind,
        /** Titre nettoyé : sans préfixe de langue, mention de qualité, année ni numéro d'épisode. */
        val title: String,
        val year: Int?,
        val episode: EpisodeInfo?,
    )

    private val XTREAM_STREAM = Regex("""\d+(\.(ts|m3u8))?""")
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "mpg", "mpeg", "webm")
    private val MOVIE_GROUP_WORDS = setOf("VOD", "FILM", "FILMS", "MOVIE", "MOVIES", "CINEMAS VOD")
    private val SERIES_GROUP_WORDS = setOf("SERIE", "SERIES", "TVSHOWS", "TV SHOWS", "SHOWS")

    /**
     * Marqueur d'épisode. Cherché (et non appliqué à tout le titre) : la première occurrence donne
     * le même découpage qu'un préfixe minimal, sans retours en arrière coûteux.
     */
    private val EPISODE = Regex(
        """(?iu)[\s._\-:|]*(?:S(\d{1,2})[\s._\-]*E(\d{1,3})|(\d{1,2})x(\d{2,3})|saison\s*(\d{1,2})[\s._\-,]*(?:é|e)pisode\s*(\d{1,3}))\b""",
    )
    private val YEAR = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
    private val LANGUAGE_PREFIX = Regex("""^\s*[\[|]?\s*((?:[A-Z]{2,3}|VOSTFR|MULTI))\s*[\]|]?\s*[:|\-–]\s+""")
    /** Mentions de version ou de langue, à la fin (« - MULTI », « (VOSTFR », « [VF] »). */
    private val TRAILING_NOISE = Regex("""(?i)[\s\-|:(\[]+(?:$VERSION_TAGS)\s*[)\]]?\s*$""")

    /** Contenu d'une paire de parenthèses ou de crochets, n'importe où dans le titre. */
    private val BRACKET = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")
    private const val VERSION_TAGS = "vostfr|vost|stfr|vf|vff|vfq|vfi|vf2|vo|multi|multi-?subs?|true\\s?french|true\\s?fr|subfrench|french|dual"

    /** Film en version française désignée par le seul code du pays (« Titre (FR) », « Titre - FR »). */
    private val MOVIE_COUNTRY = Regex("""(?i)\s*[(\[]\s*FR\s*[)\]]|\s+[\-|:]\s*FR\s*$""")

    /** Mots d'une mention de version entre parenthèses (« (Truefrench HDLight 1080p) ») : langue, puis source de la copie. */
    private val VERSION_WORDS = setOf(
        "VOSTFR", "VOST", "STFR", "VF", "VFF", "VFQ", "VFI", "VF2", "VO", "MULTI", "MULTISUB", "MULTISUBS", "TRUEFRENCH",
        "TRUEFR", "SUBFRENCH", "FRENCH", "DUAL",
    )
    private val RELEASE_WORDS = setOf(
        "HDLIGHT", "LIGHT", "BLURAY", "BDRIP", "BRRIP", "WEBRIP", "WEBDL", "WEB", "DL", "DVDRIP", "HDRIP", "REMUX", "CAM", "HDCAM",
        "TS", "HDTS", "MD", "AC3", "DTS", "AAC", "10BIT", "TRUE",
    )

    /** Sources de copie sans autre sens possible : la parenthèse est retirée même sans mention de langue (« (BluRay 1080p) »). */
    private val RELEASE_ONLY_WORDS = setOf("HDLIGHT", "BLURAY", "BDRIP", "BRRIP", "WEBRIP", "WEBDL", "DVDRIP", "HDRIP", "REMUX", "HDCAM", "HDTS")
    private val WORD = Regex("[\\p{L}\\p{Nd}+]+")
    private val YEAR_WORD = Regex("(?:19|20)\\d{2}")

    /** Langues étrangères en préfixe (« EN: Avatar ») : la copie passe après la version française. */
    private val FOREIGN_PREFIXES = setOf(
        "EN", "ENG", "US", "UK", "GB", "DE", "GER", "ES", "ESP", "IT", "ITA", "PT", "BR", "AR", "ARA", "TR", "TUR", "NL", "PL", "RU",
        "RO", "GR", "SE", "DK", "NO", "FI", "IN", "HI", "JP", "KR", "CN", "AL", "VO",
    )
    private val NON_WORD = Regex("[^A-Z0-9]+")
    private val SPACES = Regex("\\s{2,}")
    private val MARKS = Regex("\\p{Mn}+")

    /** Type déduit du nom de groupe, mémorisé : une playlist répète les mêmes groupes des milliers de fois. */
    private enum class GroupHint { SERIES, MOVIE, NONE }

    private val groupHints = java.util.concurrent.ConcurrentHashMap<String, GroupHint>()

    fun classify(
        name: String,
        url: String,
        groupTitle: String?,
        attributes: Map<String, String> = emptyMap(),
    ): Result {
        // Titre nettoyé calculé une seule fois (la détection du type peut en avoir besoin).
        val cleanedTitle = lazy(LazyThreadSafetyMode.NONE) { cleanTitle(name) }
        val kind = detectKind(url, groupTitle, attributes, cleanedTitle)
        if (kind == ContentKind.LIVE) return Result(ContentKind.LIVE, name.trim(), null, null)

        val cleaned = cleanedTitle.value
        val episodeMatch = EPISODE.find(cleaned)
        if (kind == ContentKind.SERIES || episodeMatch != null) {
            if (episodeMatch != null) {
                val groups = episodeMatch.groupValues
                val season = (groups[1].ifEmpty { groups[3] }.ifEmpty { groups[5] }).toIntOrNull()
                val episode = (groups[2].ifEmpty { groups[4] }.ifEmpty { groups[6] }).toIntOrNull()
                val (seriesTitle, year) = extractYear(cleaned.substring(0, episodeMatch.range.first))
                val seriesName = seriesTitle.ifBlank { cleaned }
                return Result(ContentKind.SERIES, seriesName, year, EpisodeInfo(seriesName, season, episode))
            }
            val (title, year) = extractYear(cleaned)
            return Result(ContentKind.SERIES, title, year, EpisodeInfo(title, null, null))
        }
        // Film : « (FR) » désigne la version française, pas le titre.
        val (title, year) = extractYear(MOVIE_COUNTRY.replace(cleaned, "").ifBlank { cleaned })
        return Result(ContentKind.MOVIE, title, year, null)
    }

    private fun detectKind(url: String, groupTitle: String?, attributes: Map<String, String>, cleaned: Lazy<String>): ContentKind {
        when (attributes["tvg-type"]?.lowercase() ?: attributes["type"]?.lowercase()) {
            "movie", "movies", "vod", "film" -> return ContentKind.MOVIE
            "series", "serie", "show" -> return ContentKind.SERIES
            "live", "channel", "stream" -> return ContentKind.LIVE
        }

        val segments = pathSegments(url)
        when {
            segments.any { it == "movie" || it == "movies" || it == "vod" } -> return ContentKind.MOVIE
            segments.any { it == "series" || it == "serie" } -> return ContentKind.SERIES
            segments.any { it == "live" } -> return ContentKind.LIVE
            // Flux en direct Xtream sans « /live/ » (« /identifiant/mot-de-passe/1234 » ou « …/1234.ts ») :
            // les chaînes 24h/24 rangées dans un groupe « Séries » ou « Films » restent des chaînes.
            segments.size == 3 && XTREAM_STREAM.matches(segments[2]) -> return ContentKind.LIVE
        }

        when (groupHint(groupTitle)) {
            GroupHint.SERIES -> return ContentKind.SERIES
            GroupHint.MOVIE -> return if (EPISODE.containsMatchIn(cleaned.value)) ContentKind.SERIES else ContentKind.MOVIE
            GroupHint.NONE -> Unit
        }

        val extension = segments.lastOrNull()?.substringAfterLast('.', "").orEmpty()
        if (extension in VOD_EXTENSIONS) {
            return if (EPISODE.containsMatchIn(cleaned.value)) ContentKind.SERIES else ContentKind.MOVIE
        }
        return ContentKind.LIVE
    }

    private fun groupHint(groupTitle: String?): GroupHint {
        if (groupTitle.isNullOrEmpty()) return GroupHint.NONE
        groupHints[groupTitle]?.let { return it }
        val group = fold(groupTitle).uppercase()
        val groupWords = group.split(NON_WORD).filter { it.isNotEmpty() }.toSet()
        val hint = when {
            SERIES_GROUP_WORDS.any { it in groupWords || (" " in it && it in group) } -> GroupHint.SERIES
            MOVIE_GROUP_WORDS.any { it in groupWords } -> GroupHint.MOVIE
            else -> GroupHint.NONE
        }
        // Borné : une playlist anormale (un groupe par entrée) ne fait pas grossir le cache indéfiniment.
        if (groupHints.size < MAX_CACHED_GROUPS) groupHints[groupTitle] = hint
        return hint
    }

    private const val MAX_CACHED_GROUPS = 10_000

    /** Segments du chemin d'une URL HTTP(S), en minuscules (sans analyse complète : appelé pour chaque entrée). */
    private fun pathSegments(url: String): List<String> {
        val schemeEnd = when {
            url.startsWith("https://", ignoreCase = true) -> 8
            url.startsWith("http://", ignoreCase = true) -> 7
            else -> return emptyList()
        }
        val pathStart = url.indexOf('/', schemeEnd)
        if (pathStart < 0) return emptyList()
        var end = url.length
        for (i in pathStart until url.length) {
            if (url[i] == '?' || url[i] == '#') {
                end = i
                break
            }
        }
        return url.substring(pathStart + 1, end).split('/').map { it.lowercase() }
    }

    /** « FR: Avatar (2009) VOSTFR » → « Avatar (2009) ». */
    fun cleanTitle(name: String): String {
        var title = name.trim()
        repeat(2) { title = LANGUAGE_PREFIX.replace(title, "") }
        title = stripVersionBrackets(title)
        title = TRAILING_NOISE.replace(title, "")
        title = QualityParser.parse(title).baseName.ifBlank { title }.trim()
        // « Avatar VOSTFR 4K » : la mention de version précédait celle de qualité.
        return TRAILING_NOISE.replace(title, "").ifBlank { title }.trim()
    }

    /**
     * Retire les parenthèses et crochets qui ne contiennent que des mentions de version (« (Truefrench) »,
     * « [VOSTFR] », « (True French HDLight 1080p) »). Une année écrite avec elles est gardée (« (Truefrench 2018) »
     * → « (2018) »). Les autres parenthèses font partie du titre (« Rick and Morty (US) »).
     */
    private fun stripVersionBrackets(title: String): String {
        if ('(' !in title && '[' !in title) return title
        return BRACKET.replace(title) { match ->
            var version = false
            var year: String? = null
            var previous: String? = null
            for (found in WORD.findAll(match.groupValues[1])) {
                val word = found.value.uppercase()
                when {
                    word in VERSION_WORDS || word in RELEASE_ONLY_WORDS -> version = true
                    // « (True FR) » : Truefrench écrit en abrégé.
                    word == "FR" && previous == "TRUE" -> version = true
                    word in RELEASE_WORDS || QualityParser.isTechnical(word) -> Unit
                    year == null && YEAR_WORD.matches(word) -> year = word
                    else -> return@replace match.value
                }
                previous = word
            }
            when {
                !version -> match.value
                year != null -> " ($year)"
                else -> ""
            }
        }
    }

    /**
     * Version d'un film d'après son nom dans la playlist (« À la dérive (Truefrench) », « EN: Avatar VOSTFR »).
     *
     * @property tag mention à afficher (« TRUEFRENCH », « VOSTFR », « FR »…), `null` si le nom n'en porte pas.
     * @property rank préférence pour l'affiche du film : version française (ou sans mention) d'abord, puis
     *   plusieurs langues, version sous-titrée, version originale ou autre langue.
     */
    data class Version(val tag: String?, val rank: Int)

    fun versionOf(name: String): Version = versionOf(name, MOVIE_COUNTRY.replace(cleanTitle(name), ""))

    /** @param title titre déjà nettoyé de [name] (celui du film) : il n'est pas nettoyé à nouveau pour chaque copie. */
    fun versionOf(name: String, title: String): Version {
        // Rien n'a été retiré du nom : pas de mention.
        if (name == title) return Version(null, RANK_FRENCH)
        val prefix = LANGUAGE_PREFIX.find(name.trim())?.groupValues?.get(1)?.uppercase()?.takeUnless { QualityParser.isTechnical(it) }
        // Seuls les mots retirés du titre comptent : « The French Dispatch » n'est pas une version « FRENCH ».
        val kept = WORD.findAll(title).mapTo(ArrayList()) { it.value.uppercase() }
        val removed = WORD.findAll(name).map { it.value.uppercase() }.filterNot { kept.remove(it) }.toList()
        val tag = when {
            // « True French » en deux mots, « TrueFR », « True FR ».
            "TRUEFRENCH" in removed || "TRUEFR" in removed ||
                removed.zipWithNext().any { (a, b) -> a == "TRUE" && (b == "FRENCH" || b == "FR") } -> "TRUEFRENCH"
            else -> VERSION_ORDER.firstOrNull { it in removed }
                ?: prefix?.takeIf { it == "FR" || it in FOREIGN_PREFIXES }
                ?: "FR".takeIf { "FR" in removed }
        }
        val foreign = prefix != null && prefix in FOREIGN_PREFIXES
        val rank = when (tag) {
            "VOSTFR", "VOST", "STFR", "SUBFRENCH" -> RANK_SUBTITLED
            "MULTI", "MULTISUB", "MULTISUBS", "DUAL" -> RANK_MULTI
            "VFQ" -> if (foreign) RANK_FOREIGN else RANK_FRENCH - 1
            null, "TRUEFRENCH", "VFF", "VF", "VFI", "VF2", "FRENCH", "FR" -> if (foreign) RANK_FOREIGN else RANK_FRENCH
            else -> RANK_FOREIGN
        }
        return Version(tag, rank)
    }

    /**
     * Titre déjà réduit à ses mots en minuscules (clé d'un favori ou d'une reprise), sans les mentions de version
     * qui le terminent : « a la derive truefrench » et « a la derive fr » → « a la derive ». Sert à retrouver un
     * titre enregistré avant que ces mentions soient retirées des titres.
     *
     * @param movie film : « fr » en fin de titre désigne la version française.
     */
    fun withoutVersionWords(words: String, movie: Boolean): String {
        val kept = words.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (kept.isNotEmpty()) {
            val last = kept.last().uppercase()
            val version = last in VERSION_WORDS || last in RELEASE_WORDS || QualityParser.isTechnical(last) || (movie && last == "FR")
            if (!version) break
            kept.removeAt(kept.lastIndex)
        }
        return kept.joinToString(" ")
    }

    private const val RANK_FRENCH = 4
    private const val RANK_MULTI = 2
    private const val RANK_SUBTITLED = 1
    private const val RANK_FOREIGN = 0

    /** Mentions de langue, de la plus précise à la plus vague : la première trouvée dans le nom est affichée. */
    private val VERSION_ORDER = listOf(
        "VFF", "VFQ", "VFI", "VF2", "VF", "VOSTFR", "SUBFRENCH", "STFR", "VOST", "MULTISUBS", "MULTISUB", "MULTI", "DUAL", "VO", "FRENCH",
    )

    /** Sépare l'année (entre parenthèses ou en fin de titre) du titre. */
    private fun extractYear(title: String): Pair<String, Int?> {
        val match = YEAR.findAll(title).lastOrNull() ?: return title.trim().trimSeparators() to null
        val year = match.groupValues[1].toInt()
        val withoutYear = title.removeRange(match.range).replace(SPACES, " ")
        return withoutYear.trimSeparators().ifBlank { title.trim() } to year
    }

    /**
     * Retire les séparateurs aux extrémités, et les parenthèses ou crochets orphelins (« Titre ( »),
     * jamais une paire complète qui fait partie du titre (« Rick and Morty (US) »).
     */
    private fun String.trimSeparators(): String {
        var text = trim().trim('-', '|', ':', '.', '_', ' ')
        while (text.isNotEmpty()) {
            val before = text
            val first = text.first()
            val last = text.last()
            text = when {
                first == '(' && ')' !in text || first == '[' && ']' !in text || first == ')' || first == ']' -> text.drop(1)
                last == ')' && '(' !in text || last == ']' && '[' !in text || last == '(' || last == '[' -> text.dropLast(1)
                else -> text
            }.trim().trim('-', '|', ':', '.', '_', ' ')
            if (text == before) break
        }
        return text
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
}
