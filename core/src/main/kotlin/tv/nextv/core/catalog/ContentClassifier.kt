package tv.nextv.core.catalog

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private val EPISODE = Regex(
        """(?iu)^(.*?)[\s._\-:|]*(?:S(\d{1,2})[\s._\-]*E(\d{1,3})|(\d{1,2})x(\d{2,3})|saison\s*(\d{1,2})[\s._\-,]*(?:é|e)pisode\s*(\d{1,3}))\b.*$""",
    )
    private val YEAR = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
    private val LANGUAGE_PREFIX = Regex("""^\s*[\[|]?\s*(?:[A-Z]{2,3}|VOSTFR|MULTI)\s*[\]|]?\s*[:|\-–]\s+""")
    private val TRAILING_NOISE = Regex("""(?i)[\s\-|:]+(?:vostfr|vf|vo|multi|truefrench|french)\s*$""")

    fun classify(
        name: String,
        url: String,
        groupTitle: String?,
        attributes: Map<String, String> = emptyMap(),
    ): Result {
        val kind = detectKind(name, url, groupTitle, attributes)
        if (kind == ContentKind.LIVE) return Result(ContentKind.LIVE, name.trim(), null, null)

        val cleaned = cleanTitle(name)
        val episodeMatch = EPISODE.matchEntire(cleaned)
        if (kind == ContentKind.SERIES || episodeMatch != null) {
            if (episodeMatch != null) {
                val groups = episodeMatch.groupValues
                val season = (groups[2].ifEmpty { groups[4] }.ifEmpty { groups[6] }).toIntOrNull()
                val episode = (groups[3].ifEmpty { groups[5] }.ifEmpty { groups[7] }).toIntOrNull()
                val (seriesTitle, year) = extractYear(groups[1])
                val seriesName = seriesTitle.ifBlank { cleaned }
                return Result(ContentKind.SERIES, seriesName, year, EpisodeInfo(seriesName, season, episode))
            }
            val (title, year) = extractYear(cleaned)
            return Result(ContentKind.SERIES, title, year, EpisodeInfo(title, null, null))
        }
        val (title, year) = extractYear(cleaned)
        return Result(ContentKind.MOVIE, title, year, null)
    }

    private fun detectKind(name: String, url: String, groupTitle: String?, attributes: Map<String, String>): ContentKind {
        when (attributes["tvg-type"]?.lowercase() ?: attributes["type"]?.lowercase()) {
            "movie", "movies", "vod", "film" -> return ContentKind.MOVIE
            "series", "serie", "show" -> return ContentKind.SERIES
            "live", "channel", "stream" -> return ContentKind.LIVE
        }

        val segments = url.toHttpUrlOrNull()?.pathSegments?.map { it.lowercase() }.orEmpty()
        when {
            segments.any { it == "movie" || it == "movies" || it == "vod" } -> return ContentKind.MOVIE
            segments.any { it == "series" || it == "serie" } -> return ContentKind.SERIES
            segments.any { it == "live" } -> return ContentKind.LIVE
        }

        val group = groupTitle?.let { fold(it).uppercase() }.orEmpty()
        val groupWords = group.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }.toSet()
        if (SERIES_GROUP_WORDS.any { it in groupWords || (" " in it && it in group) }) return ContentKind.SERIES
        if (MOVIE_GROUP_WORDS.any { it in groupWords }) {
            return if (EPISODE.matches(cleanTitle(name))) ContentKind.SERIES else ContentKind.MOVIE
        }

        val extension = segments.lastOrNull()?.substringAfterLast('.', "").orEmpty()
        if (extension in VOD_EXTENSIONS) {
            return if (EPISODE.matches(cleanTitle(name))) ContentKind.SERIES else ContentKind.MOVIE
        }
        return ContentKind.LIVE
    }

    /** « FR: Avatar (2009) VOSTFR » → « Avatar (2009) ». */
    fun cleanTitle(name: String): String {
        var title = name.trim()
        repeat(2) { title = LANGUAGE_PREFIX.replace(title, "") }
        title = TRAILING_NOISE.replace(title, "")
        return QualityParser.parse(title).baseName.ifBlank { title }.trim()
    }

    /** Sépare l'année (entre parenthèses ou en fin de titre) du titre. */
    private fun extractYear(title: String): Pair<String, Int?> {
        val match = YEAR.findAll(title).lastOrNull() ?: return title.trim().trimSeparators() to null
        val year = match.groupValues[1].toInt()
        val withoutYear = title.removeRange(match.range).replace(Regex("\\s{2,}"), " ")
        return withoutYear.trimSeparators().ifBlank { title.trim() } to year
    }

    private fun String.trimSeparators() = trim().trim('-', '|', ':', '.', '_', '(', ')', '[', ']', ' ').trim()

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
