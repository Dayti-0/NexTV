package tv.nextv.app.data.catalog

import androidx.room.withTransaction
import tv.nextv.app.data.db.FavoriteEntity
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.TmdbCacheEntity
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.MediaType
import tv.nextv.core.meta.TmdbClient
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.meta.TmdbResult
import tv.nextv.core.model.ContentKind

/** Résultat d'une recherche de fiche, du point de vue de l'interface. */
sealed interface MetadataState {
    data object Loading : MetadataState
    data class Available(val details: MediaDetails) : MetadataState
    data object NotFound : MetadataState
    data class Unavailable(val error: TmdbError) : MetadataState
}

/**
 * Fiches TMDB des films et séries, mises en cache en base : 30 jours pour une fiche trouvée,
 * 7 jours pour une absence de résultat. Les erreurs (réseau, clé) ne sont pas mises en cache.
 */
class MetadataRepository(
    private val database: NexTvDatabase,
    private val client: TmdbClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.tmdbDao()

    suspend fun details(cacheKey: String, type: MediaType, title: String, year: Int?): MetadataState {
        val now = clock()
        dao.get(cacheKey)?.let { cached ->
            val ttl = if (cached.found) FOUND_TTL else NOT_FOUND_TTL
            if (now - cached.fetchedAt < ttl) return if (cached.found) MetadataState.Available(cached.toDetails(type)) else MetadataState.NotFound
        }
        return when (val result = client.lookup(type, title, year)) {
            is TmdbResult.Found -> {
                dao.put(result.details.toEntity(cacheKey, now))
                MetadataState.Available(result.details)
            }
            TmdbResult.NotFound -> {
                dao.put(TmdbCacheEntity(cacheKey = cacheKey, found = false, fetchedAt = now))
                MetadataState.NotFound
            }
            is TmdbResult.Failed -> {
                // Une copie expirée reste préférable à rien en cas de problème réseau.
                dao.get(cacheKey)?.takeIf { it.found }?.let { MetadataState.Available(it.toDetails(type)) }
                    ?: MetadataState.Unavailable(result.error)
            }
        }
    }

    private fun MediaDetails.toEntity(cacheKey: String, now: Long) = TmdbCacheEntity(
        cacheKey = cacheKey,
        found = true,
        fetchedAt = now,
        tmdbId = tmdbId,
        title = title,
        originalTitle = originalTitle,
        overview = overview,
        tagline = tagline,
        year = year,
        rating = rating,
        voteCount = voteCount,
        genres = genres.joinToString("|"),
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        trailerKey = trailerYoutubeKey,
        runtimeMinutes = runtimeMinutes,
        seasonCount = seasonCount,
    )

    private fun TmdbCacheEntity.toDetails(type: MediaType) = MediaDetails(
        tmdbId = tmdbId,
        type = type,
        title = title,
        originalTitle = originalTitle,
        overview = overview,
        tagline = tagline,
        year = year,
        rating = rating,
        voteCount = voteCount,
        genres = genres.split('|').filter { it.isNotBlank() },
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        trailerYoutubeKey = trailerKey,
        runtimeMinutes = runtimeMinutes,
        seasonCount = seasonCount,
    )

    private companion object {
        const val DAY = 24 * 60 * 60_000L
        const val FOUND_TTL = 30 * DAY
        const val NOT_FOUND_TTL = 7 * DAY
    }
}

/** Favoris par playlist et par type (chaînes, films, séries), dans un ordre choisi par l'utilisateur. */
class FavoritesRepository(private val database: NexTvDatabase) {

    private val dao = database.favoriteDao()

    fun observeKeys(sourceId: Long) = dao.observeKeys(sourceId)

    /** Ajoute ou retire un favori. @return vrai s'il est désormais favori. */
    suspend fun toggle(sourceId: Long, kind: ContentKind, itemKey: String, title: String, logoUrl: String?): Boolean =
        database.withTransaction {
            if (dao.delete(sourceId, kind.name, itemKey) > 0) {
                false
            } else {
                val position = dao.maxPosition(sourceId, kind.name) + 1
                dao.insert(FavoriteEntity(sourceId = sourceId, kind = kind.name, itemKey = itemKey, title = title, logoUrl = logoUrl, position = position))
                true
            }
        }

    /** Déplace un favori de [offset] places (négatif : vers le début). */
    suspend fun move(sourceId: Long, kind: ContentKind, itemKey: String, offset: Int) {
        database.withTransaction {
            val items = dao.list(sourceId, kind.name).toMutableList()
            val from = items.indexOfFirst { it.itemKey == itemKey }
            if (from < 0) return@withTransaction
            val to = (from + offset).coerceIn(0, items.lastIndex)
            if (to == from) return@withTransaction
            items.add(to, items.removeAt(from))
            items.forEachIndexed { index, item -> if (item.position != index) dao.setPosition(item.id, index) }
        }
    }
}
