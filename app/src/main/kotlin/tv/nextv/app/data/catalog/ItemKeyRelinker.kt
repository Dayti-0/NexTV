package tv.nextv.app.data.catalog

import androidx.room.withTransaction
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.model.ContentKind

/**
 * Favoris, reprises de lecture et « Déjà vu » d'un film ou d'une série dont la clé a changé.
 *
 * La clé d'un titre vient de son titre nettoyé (et de son année pour un film). Quand le nettoyage s'améliore
 * (« À la dérive (FR) » devient « À la dérive », un film sans année prend celle de son autre copie), la clé change,
 * et ce qui avait été enregistré sous l'ancienne ne s'afficherait plus. Après chaque lecture de la playlist, ce
 * qui ne correspond plus à aucun titre est rattaché au même titre sans ses mentions de version, s'il existe.
 * Rien n'est touché sinon : un titre retiré de la playlist garde ses favoris pour son retour.
 */
class ItemKeyRelinker(
    private val database: NexTvDatabase,
    private val progress: ProgressRepository,
    private val watched: WatchedRepository,
) {
    private val channels = database.channelDao()
    private val favorites = database.favoriteDao()

    suspend fun relink(sourceId: Long) {
        // Ancienne clé → nouvelle (`null` : rien à changer), cherchée une fois par titre.
        val found = HashMap<String, String?>()
        suspend fun target(key: String): String? {
            if (key !in found) found[key] = newKey(sourceId, key)
            return found[key]
        }

        for (kind in listOf(ContentKind.MOVIE.name, ContentKind.SERIES.name)) {
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
    }

    /** Clé actuelle du titre enregistré sous [key], ou `null` s'il est toujours là ou introuvable. */
    private suspend fun newKey(sourceId: Long, key: String): String? {
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
        if (year.isNotEmpty()) return null
        return channels.itemKeysBetween(sourceId, kind, "$MOVIE_PREFIX$cleaned:", "$MOVIE_PREFIX$cleaned;").singleOrNull()
    }

    private companion object {
        const val MOVIE_PREFIX = "movie:"
        const val SERIES_PREFIX = "series:"
    }
}
