package tv.nextv.app.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.nextv.app.data.db.ItemKeys
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.XtreamSeries

/**
 * Durée des épisodes d'une série d'après le serveur Xtream : celle de chaque fichier, que le serveur connaît
 * (`get_series_info`), même pour une série trop récente pour que TMDB la donne.
 *
 * La playlist ne dit pas quelle série du serveur est la sienne : elle est retrouvée par sa catégorie (le groupe de la
 * playlist, `get_series_categories`), puis par son titre parmi les séries de cette catégorie (`get_series`, tout le
 * catalogue si la catégorie n'y est pas), et retenue si ses épisodes sont bien ceux de la playlist (mêmes identifiants
 * de flux). Résultats gardés en mémoire pendant la session : la fiche rouverte ne redemande rien.
 */
class EpisodeDurations(
    private val source: suspend (Long) -> PlaylistSource?,
    private val categories: suspend (PlaylistSource) -> Map<String, String>?,
    private val series: suspend (PlaylistSource, String?) -> List<XtreamSeries>?,
    private val durations: suspend (PlaylistSource, String) -> Map<String, Int>?,
) {
    private val lock = Mutex()

    /** Durées trouvées (identifiant de flux → secondes), par série de la playlist. */
    private val found = HashMap<String, Map<String, Int>>()

    /** Catégories de séries, puis catalogue entier, par playlist. */
    private val categoriesBySource = HashMap<Long, Map<String, String>>()
    private val catalogBySource = HashMap<Long, List<XtreamSeries>>()

    /**
     * Durée de chaque épisode, en secondes, par adresse de flux ([episodeUrls]) ; vide si ce n'est pas une playlist
     * Xtream, si la série n'est pas retrouvée sur le serveur ou s'il ne répond pas.
     *
     * @param seriesKey clé de la série sans « series: » (titre en minuscules, voir [ItemKeys.normalize]).
     * @param group groupe de la playlist où la série est rangée.
     */
    suspend fun of(sourceId: Long, seriesKey: String, group: String?, episodeUrls: List<String>): Map<String, Int> {
        // Épisodes servis par le serveur Xtream (« …/series/identifiant/mot de passe/12345.mkv »).
        val ids = episodeUrls.mapNotNull { url -> if ("/series/" in url) Catchup.streamId(url)?.let { it to url } else null }.toMap()
        if (ids.isEmpty() || seriesKey.isEmpty()) return emptyMap()
        val cacheKey = "$sourceId|$seriesKey|${group.orEmpty()}"
        val known = lock.withLock { found[cacheKey] } ?: lookUp(sourceId, seriesKey, group, ids.keys)?.also { result ->
            lock.withLock { found[cacheKey] = result }
        } ?: return emptyMap()
        return ids.mapNotNull { (id, url) -> known[id]?.let { url to it } }.toMap()
    }

    private suspend fun lookUp(sourceId: Long, seriesKey: String, group: String?, ids: Set<String>): Map<String, Int>? {
        val playlist = source(sourceId) ?: return null
        val wanted = ItemKeys.normalize(group.orEmpty())
        val categoryId = if (wanted.isEmpty()) {
            null
        } else {
            val all = lock.withLock { categoriesBySource[sourceId] } ?: categories(playlist)?.also { lock.withLock { categoriesBySource[sourceId] = it } }
            all?.entries?.firstOrNull { ItemKeys.normalize(it.value) == wanted }?.key
        }
        // Séries de la catégorie, puis, si la série n'y est pas, tout le catalogue.
        if (categoryId != null) {
            val inCategory = series(playlist, categoryId) ?: return null
            matching(playlist, inCategory, seriesKey, ids)?.let { return it }
        }
        val catalog = lock.withLock { catalogBySource[sourceId] } ?: series(playlist, null)?.also { lock.withLock { catalogBySource[sourceId] = it } } ?: return null
        return matching(playlist, catalog, seriesKey, ids) ?: emptyMap()
    }

    /** Durées de la série de [list] qui porte ce titre et contient ces épisodes (la même en VF et en VOSTFR : la bonne). */
    private suspend fun matching(playlist: PlaylistSource, list: List<XtreamSeries>, seriesKey: String, ids: Set<String>): Map<String, Int>? {
        val candidates = list.filter { titleKey(it.name) == seriesKey }.take(MAX_CANDIDATES)
        for (candidate in candidates) {
            val episodes = durations(playlist, candidate.id) ?: continue
            if (episodes.keys.any { it in ids }) return episodes
        }
        return null
    }

    private companion object {
        /** Séries du même titre essayées (versions VF, VOSTFR, 4K…). */
        const val MAX_CANDIDATES = 4

        /** Clé du titre d'une série telle que le serveur l'écrit (« L'impératrice remariée (MULTI) FHD »). */
        fun titleKey(name: String): String = ItemKeys.normalize(ContentClassifier.seriesTitle(name))
    }
}
