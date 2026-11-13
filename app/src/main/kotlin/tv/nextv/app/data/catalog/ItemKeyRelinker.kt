package tv.nextv.app.data.catalog

import androidx.room.withTransaction
import tv.nextv.app.data.db.CachedMovieTitle
import tv.nextv.app.data.db.METADATA_CACHE_PREFIX
import tv.nextv.app.data.db.MovieCopies
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.catalog.LiveVariants
import tv.nextv.core.catalog.QualityParser
import tv.nextv.core.model.ContentKind

/**
 * Favoris, reprises de lecture, « Déjà vu » et chaînes récentes d'un titre ou d'une chaîne dont la clé a changé.
 *
 * La clé d'un titre vient de son titre nettoyé (et de son année pour un film), celle d'une chaîne de son nom sans
 * mention de qualité. Quand ces règles s'améliorent (« À la dérive (FR) » devient « À la dérive », un film sans
 * année prend celle de son autre copie, « The Virtuoso » est rangé sous « Le Virtuose », « France 3 Paris IDF »
 * rejoint « France 3 Paris Île-de-France »), la clé change, et ce qui avait été enregistré sous l'ancienne ne
 * s'afficherait plus. Après chaque lecture de la playlist, ce qui ne correspond plus à rien est rattaché au même titre
 * ou à la même chaîne sous sa nouvelle clé, s'il existe. Rien n'est touché sinon : un titre retiré de la playlist garde ses favoris pour son retour.
 */
class ItemKeyRelinker(
    private val database: NexTvDatabase,
    private val progress: ProgressRepository,
    private val watched: WatchedRepository,
    private val settings: SettingsRepository? = null,
) {
    private val channels = database.channelDao()
    private val favorites = database.favoriteDao()
    private val tmdb = database.tmdbDao()

    suspend fun relink(sourceId: Long) {
        // Ancienne clé → nouvelle (`null` : rien à changer), cherchée une fois par titre.
        val found = HashMap<String, String?>()
        suspend fun target(key: String): String? {
            if (key !in found) found[key] = newKey(sourceId, key)
            return found[key]
        }

        // Favoris (films, séries, chaînes) et chaînes de « L'essentiel » du guide.
        for (kind in listOf(ContentKind.MOVIE.name, ContentKind.SERIES.name, ContentKind.LIVE.name, FavoritesRepository.GUIDE_SELECTION)) {
            for (favorite in favorites.list(sourceId, kind)) {
                val key = target(favorite.itemKey) ?: continue
                database.withTransaction {
                    // Deux favoris du même film (ses deux versions) : un seul reste.
                    if (favorites.setKey(favorite.id, key) == 0) favorites.deleteById(favorite.id)
                }
            }
        }
        for (row in progress.all(sourceId)) {
            val key = target(row.itemKey) ?: continue
            // La reprise la plus récente est gardée si le titre en a déjà une sous sa nouvelle clé.
            progress.restore(row.copy(itemKey = key))
            progress.clear(sourceId, row.itemKey)
        }
        for (row in watched.all(sourceId)) {
            // Épisode : « clé de la série|S1E2 ».
            val item = row.itemKey.substringBefore('|')
            val key = target(item) ?: continue
            watched.restore(sourceId, key + row.itemKey.substring(item.length), row.watchedAt)
            watched.unmark(sourceId, row.itemKey)
        }
        settings?.renameRecentChannels(sourceId) { target(it) }
    }

    /** Clé actuelle du titre enregistré sous [key], ou `null` s'il est toujours là ou introuvable. */
    private suspend fun newKey(sourceId: Long, key: String): String? {
        if (key.startsWith(LIVE_PREFIX)) return newLiveKey(sourceId, key)
        val movie = key.startsWith(MOVIE_PREFIX)
        if (!movie && !key.startsWith(SERIES_PREFIX)) return null
        val kind = if (movie) ContentKind.MOVIE.name else ContentKind.SERIES.name
        if (channels.hasItem(sourceId, kind, key)) return null
        if (!movie) {
            val title = key.removePrefix(SERIES_PREFIX)
            val cleaned = ContentClassifier.withoutVersionWords(title, movie = false)
            return (SERIES_PREFIX + cleaned).takeIf { cleaned.isNotEmpty() && cleaned != title && channels.hasItem(sourceId, kind, it) }
        }
        val body = key.removePrefix(MOVIE_PREFIX)
        val year = body.substringAfterLast(':', "")
        val cleaned = ContentClassifier.withoutVersionWords(body.substringBeforeLast(':'), movie = true)
        if (cleaned.isEmpty()) return null
        val exact = "$MOVIE_PREFIX$cleaned:$year"
        if (exact != key && channels.hasItem(sourceId, kind, exact)) return exact
        // Enregistré sans année : le seul film de ce titre dans la playlist (il a pris l'année d'une autre copie).
        if (year.isEmpty()) {
            channels.itemKeysBetween(sourceId, kind, "$MOVIE_PREFIX$cleaned:", "$MOVIE_PREFIX$cleaned;").singleOrNull()?.let { return it }
        }
        return otherTitle(sourceId, key)
    }

    /**
     * Film enregistré sous un titre que la playlist range maintenant sous un autre (« The Virtuoso » sous « Le Virtuose »,
     * voir MovieCopies.mergeTitles) : le titre de la playlist dont la fiche TMDB est la même, si les deux sont bien des
     * titres de ce film (pas une fiche trouvée par défaut).
     */
    private suspend fun otherTitle(sourceId: Long, key: String): String? {
        val saved = runCatching { tmdb.get(METADATA_CACHE_PREFIX + key) }.getOrNull()?.takeIf { it.found && it.tmdbId > 0 } ?: return null
        val film = CachedMovieTitle(METADATA_CACHE_PREFIX + key, saved.tmdbId, saved.title, saved.originalTitle, saved.year)
        if (!film.names(key)) return null
        return runCatching { tmdb.movieTitlesOf(saved.tmdbId) }.getOrDefault(emptyList())
            .filter { it.cacheKey != film.cacheKey }
            .map { it.cacheKey.removePrefix(METADATA_CACHE_PREFIX) to it }
            .firstOrNull { (other, entry) -> entry.names(other) && channels.hasItem(sourceId, ContentKind.MOVIE.name, other) }
            ?.first
    }

    /** La fiche désigne bien le film de la clé [key] (« movie:titre:année ») : son titre et son année. */
    private fun CachedMovieTitle.names(key: String): Boolean {
        val body = key.removePrefix(MOVIE_PREFIX)
        return MovieCopies.sameFilm(body.substringBeforeLast(':'), body.substringAfterLast(':', "").toIntOrNull(), this)
    }

    /**
     * Chaîne enregistrée sous une clé qui n'existe plus : la même chaîne sous la clé que les règles en vigueur lui
     * donnent (région de France 3 au nom de son antenne), puis sans « tv » (« Le Figaro TV IDF » et « Le Figaro IDF »).
     */
    private suspend fun newLiveKey(sourceId: Long, key: String): String? {
        val kind = ContentKind.LIVE.name
        if (channels.hasItem(sourceId, kind, key)) return null
        val written = key.removePrefix(LIVE_PREFIX)
        val current = LiveVariants.key(QualityParser.parse(written).variantKey)
        return listOfNotNull(current.takeIf { it != written }, LiveVariants.withoutGeneric(current))
            .map { LIVE_PREFIX + it }
            .firstOrNull { channels.hasItem(sourceId, kind, it) }
    }

    private companion object {
        const val MOVIE_PREFIX = "movie:"
        const val SERIES_PREFIX = "series:"
        const val LIVE_PREFIX = "live:"
    }
}
