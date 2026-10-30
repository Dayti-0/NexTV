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
    private val LANGUAGE_PREFIX = Regex("""^\s*[\[|]?\s*(?:[A-Z]{2,3}|VOSTFR|MULTI)\s*[\]|]?\s*[:|\-–]\s+""")
    /** Mentions de version ou de langue, à la fin (« - MULTI », « (VOSTFR », « [VF] »). */
    private val TRAILING_NOISE = Regex("""(?i)[\s\-|:(\[]+(?:$VERSION_TAGS)\s*[)\]]?\s*$""")

    /** Les mêmes mentions entre parenthèses ou crochets, n'importe où dans le titre. */
    private val BRACKETED_NOISE = Regex("""(?i)\s*[(\[]\s*(?:$VERSION_TAGS)\s*[)\]]""")
    private const val VERSION_TAGS = "vostfr|vost|vf|vff|vfq|vfi|vo|multi|multi-?subs?|truefrench|subfrench|french|dual"
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
        val (title, year) = extractYear(cleaned)
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
        title = BRACKETED_NOISE.replace(title, "")
        title = TRAILING_NOISE.replace(title, "")
        return QualityParser.parse(title).baseName.ifBlank { title }.trim()
    }

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
