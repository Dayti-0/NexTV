package tv.nextv.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: Long): Flow<SourceEntity?>

    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE sources SET
            lastAttemptAt = :lastAttemptAt, lastSuccessAt = :lastSuccessAt, channelCount = :channelCount,
            errorCode = :errorCode, errorDetail = :errorDetail, warnings = :warnings, headerEpgUrls = :headerEpgUrls,
            epgLastAttemptAt = :epgLastAttemptAt, epgLastSuccessAt = :epgLastSuccessAt,
            epgProgrammeCount = :epgProgrammeCount, epgMatchedCount = :epgMatchedCount,
            epgErrorCode = :epgErrorCode, epgErrorDetail = :epgErrorDetail
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: Long,
        lastAttemptAt: Long?,
        lastSuccessAt: Long?,
        channelCount: Int,
        errorCode: String?,
        errorDetail: String?,
        warnings: String,
        headerEpgUrls: String,
        epgLastAttemptAt: Long?,
        epgLastSuccessAt: Long?,
        epgProgrammeCount: Int,
        epgMatchedCount: Int,
        epgErrorCode: String?,
        epgErrorDetail: String?,
    )

    @Query("UPDATE sources SET epgGeneration = :generation WHERE id = :id")
    suspend fun setEpgGeneration(id: Long, generation: Long)
}

data class EpgAssignmentRow(val tvgId: String?, val name: String, val epgChannelId: String)

data class EpgRefRow(val id: Long, val tvgId: String?, val tvgName: String?, val name: String)

data class GroupRow(val title: String?, val count: Int)

data class ChannelUrlRow(val id: Long, val streamUrl: String)

/** Chaîne affichée dans les listes (meilleure version), avec le programme en cours s'il est connu. */
data class ChannelListRow(
    val id: Long,
    val name: String,
    val title: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val channelNumber: String?,
    val streamSupport: String,
    val streamIssues: String,
    val quality: String?,
    val variantCount: Int,
    val itemKey: String,
    val nowTitle: String?,
    val nowStart: Long?,
    val nowStop: Long?,
)

/** Film d'une playlist. */
data class MovieRow(
    val id: Long,
    val title: String,
    val year: Int?,
    val logoUrl: String?,
    val groupTitle: String?,
    val itemKey: String,
    val streamSupport: String,
)

/** Série (regroupement de ses épisodes). [id] : premier épisode. */
data class SeriesRow(
    val id: Long,
    val title: String,
    val year: Int?,
    val logoUrl: String?,
    val groupTitle: String?,
    val itemKey: String,
    val episodeCount: Int,
)

data class EpisodeRow(
    val id: Long,
    val name: String,
    val season: Int?,
    val episode: Int?,
    val logoUrl: String?,
    val streamSupport: String,
)

/** Version (qualité) d'une chaîne. */
data class VariantRow(val id: Long, val quality: String?, val position: Int, val streamSupport: String)

/** Chaîne affichée dans la grille du guide. */
data class GuideChannelRow(
    val id: Long,
    val name: String,
    val title: String,
    val logoUrl: String?,
    val channelNumber: String?,
    val epgChannelId: String,
)

@Dao
interface ChannelDao {
    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** Identifiants et URL des chaînes, par pages (conservation des identifiants à l'actualisation). */
    @Query("SELECT id, streamUrl FROM channels WHERE sourceId = :sourceId AND id > :after ORDER BY id LIMIT :limit")
    suspend fun idsAndUrls(sourceId: Long, after: Long, limit: Int): List<ChannelUrlRow>

    @Query("SELECT tvgId, name, epgChannelId FROM channels WHERE sourceId = :sourceId AND epgChannelId IS NOT NULL")
    suspend fun epgAssignments(sourceId: Long): List<EpgAssignmentRow>

    @Query("SELECT id, tvgId, tvgName, name FROM channels WHERE sourceId = :sourceId AND kind = 'LIVE'")
    suspend fun epgRefs(sourceId: Long): List<EpgRefRow>

    @Query("UPDATE channels SET epgChannelId = NULL WHERE sourceId = :sourceId")
    suspend fun clearEpg(sourceId: Long)

    @Query("UPDATE channels SET epgChannelId = :epgChannelId WHERE id = :channelId")
    suspend fun setEpg(channelId: Long, epgChannelId: String)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId AND kind = :kind AND isPrimary = 1")
    suspend fun count(sourceId: Long, kind: String): Int

    @Query("SELECT COUNT(DISTINCT seriesName) FROM channels WHERE sourceId = :sourceId AND kind = 'SERIES'")
    suspend fun seriesCount(sourceId: Long): Int

    /** Groupes d'un type de contenu (chaînes : meilleure version uniquement ; séries : une par série). */
    @Query(
        """
        SELECT groupTitle AS title,
            CASE WHEN :kind = 'SERIES' THEN COUNT(DISTINCT seriesName) ELSE COUNT(*) END AS count
        FROM channels
        WHERE sourceId = :sourceId AND kind = :kind AND isPrimary = 1
        GROUP BY groupTitle ORDER BY MIN(position)
        """,
    )
    fun observeGroups(sourceId: Long, kind: String): Flow<List<GroupRow>>

    /**
     * Chaînes en direct (meilleure version de chacune).
     * @param filterGroup 1 pour ne garder que [group] (`''` = sans groupe), 0 pour tout afficher.
     * @param query texte recherché, déjà échappé pour LIKE (caractère d'échappement `\`).
     */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
            c.quality, c.variantCount, c.itemKey,
            p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
        FROM channels c
        LEFT JOIN programmes p ON p.id = (
            SELECT p2.id FROM programmes p2 JOIN sources s ON s.id = p2.sourceId
            WHERE p2.sourceId = c.sourceId AND p2.generation = s.epgGeneration AND p2.epgChannelId = c.epgChannelId
              AND p2.startMillis <= :now AND p2.stopMillis > :now
            ORDER BY p2.startMillis DESC LIMIT 1
        )
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.isPrimary = 1
          AND (:filterGroup = 0 OR IFNULL(c.groupTitle, '') = :group)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY c.position
        """,
    )
    fun pageChannels(sourceId: Long, filterGroup: Int, group: String, query: String, now: Long): PagingSource<Int, ChannelListRow>

    /** Chaînes favorites, dans l'ordre choisi. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
            c.quality, c.variantCount, c.itemKey,
            p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
        FROM favorites f
        JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND c.isPrimary = 1
        LEFT JOIN programmes p ON p.id = (
            SELECT p2.id FROM programmes p2 JOIN sources s ON s.id = p2.sourceId
            WHERE p2.sourceId = c.sourceId AND p2.generation = s.epgGeneration AND p2.epgChannelId = c.epgChannelId
              AND p2.startMillis <= :now AND p2.stopMillis > :now
            ORDER BY p2.startMillis DESC LIMIT 1
        )
        WHERE f.sourceId = :sourceId AND f.kind = 'LIVE'
        ORDER BY f.position
        """,
    )
    fun observeFavoriteChannels(sourceId: Long, now: Long): Flow<List<ChannelListRow>>

    @Query(
        """
        SELECT id, title, year, logoUrl, groupTitle, itemKey, streamSupport FROM channels
        WHERE sourceId = :sourceId AND kind = 'MOVIE'
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
          AND (:query = '' OR title LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY position
        """,
    )
    fun pageMovies(sourceId: Long, filterGroup: Int, group: String, query: String): PagingSource<Int, MovieRow>

    @Query(
        """
        SELECT c.id, c.title, c.year, c.logoUrl, c.groupTitle, c.itemKey, c.streamSupport
        FROM favorites f JOIN channels c ON c.id = (
            SELECT c2.id FROM channels c2
            WHERE c2.sourceId = f.sourceId AND c2.itemKey = f.itemKey AND c2.kind = 'MOVIE'
            ORDER BY c2.position LIMIT 1
        )
        WHERE f.sourceId = :sourceId AND f.kind = 'MOVIE'
        ORDER BY f.position
        """,
    )
    fun observeFavoriteMovies(sourceId: Long): Flow<List<MovieRow>>

    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, COUNT(*) AS episodeCount, MIN(position) AS position
        FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND seriesName IS NOT NULL
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
          AND (:query = '' OR seriesName LIKE '%' || :query || '%' ESCAPE '\')
        GROUP BY itemKey
        ORDER BY position
        """,
    )
    fun pageSeries(sourceId: Long, filterGroup: Int, group: String, query: String): PagingSource<Int, SeriesRow>

    @Query(
        """
        SELECT c.id, c.seriesName AS title, c.year, c.logoUrl, c.groupTitle, c.itemKey,
            (SELECT COUNT(*) FROM channels e WHERE e.sourceId = c.sourceId AND e.kind = 'SERIES' AND e.itemKey = c.itemKey) AS episodeCount
        FROM favorites f JOIN channels c ON c.id = (
            SELECT c2.id FROM channels c2
            WHERE c2.sourceId = f.sourceId AND c2.itemKey = f.itemKey AND c2.kind = 'SERIES'
            ORDER BY c2.position LIMIT 1
        )
        WHERE f.sourceId = :sourceId AND f.kind = 'SERIES'
        ORDER BY f.position
        """,
    )
    fun observeFavoriteSeries(sourceId: Long): Flow<List<SeriesRow>>

    @Query(
        """
        SELECT id, name, season, episode, logoUrl, streamSupport FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND itemKey = :itemKey
        ORDER BY IFNULL(season, 0), IFNULL(episode, 0), position
        """,
    )
    suspend fun episodes(sourceId: Long, itemKey: String): List<EpisodeRow>

    /** Versions (qualités) d'une chaîne, toutes lignes confondues. */
    @Query(
        """
        SELECT id, quality, position, streamSupport FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND variantKey = :variantKey AND variantKey != ''
        ORDER BY qualityRank DESC, position
        """,
    )
    suspend fun variants(sourceId: Long, variantKey: String): List<VariantRow>

    /** Chaîne suivante (zapping) : meilleure version de la chaîne suivante, dans l'ordre de la playlist. */
    @Query(
        """
        SELECT id FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND isPrimary = 1 AND position > :position
          AND streamSupport != 'UNSUPPORTED'
        ORDER BY position ASC LIMIT 1
        """,
    )
    suspend fun nextChannelId(sourceId: Long, position: Int): Long?

    @Query(
        """
        SELECT id FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND isPrimary = 1 AND position < :position
          AND streamSupport != 'UNSUPPORTED'
        ORDER BY position DESC LIMIT 1
        """,
    )
    suspend fun previousChannelId(sourceId: Long, position: Int): Long?

    /** Rang d'une chaîne dans sa liste (pour placer le focus du menu des chaînes). */
    @Query(
        """
        SELECT COUNT(*) FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND isPrimary = 1 AND position < :position
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
        """,
    )
    suspend fun indexInList(sourceId: Long, filterGroup: Int, group: String, position: Int): Int

    /** Meilleure version de la chaîne (pour l'affichage et le zapping). */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND variantKey = :variantKey AND isPrimary = 1 LIMIT 1
        """,
    )
    suspend fun primaryOf(sourceId: Long, variantKey: String): ChannelEntity?

    /** Chaînes associées au guide, pour la grille des programmes. */
    @Query(
        """
        SELECT id, name, title, logoUrl, channelNumber, epgChannelId FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND isPrimary = 1 AND epgChannelId IS NOT NULL
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
        ORDER BY position LIMIT :limit
        """,
    )
    suspend fun guideChannels(sourceId: Long, filterGroup: Int, group: String, limit: Int): List<GuideChannelRow>

    /** Chaînes favorites associées au guide. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.channelNumber, c.epgChannelId
        FROM favorites f
        JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND c.isPrimary = 1
        WHERE f.sourceId = :sourceId AND f.kind = 'LIVE' AND c.epgChannelId IS NOT NULL
        ORDER BY f.position
        """,
    )
    suspend fun guideFavoriteChannels(sourceId: Long): List<GuideChannelRow>
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId AND kind = :kind ORDER BY position")
    suspend fun list(sourceId: Long, kind: String): List<FavoriteEntity>

    @Query("SELECT itemKey FROM favorites WHERE sourceId = :sourceId")
    fun observeKeys(sourceId: Long): Flow<List<String>>

    @Query("SELECT IFNULL(MAX(position), -1) FROM favorites WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun maxPosition(sourceId: Long, kind: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND kind = :kind AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, kind: String, itemKey: String): Int

    @Query("UPDATE favorites SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)
}

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_cache WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): TmdbCacheEntity?

    @Upsert
    suspend fun put(entry: TmdbCacheEntity)
}

@Dao
interface ProgrammeDao {
    @Insert
    suspend fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("SELECT MAX(generation) FROM programmes WHERE sourceId = :sourceId")
    suspend fun maxGeneration(sourceId: Long): Long?

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND generation != :keep")
    suspend fun deleteOtherGenerations(sourceId: Long, keep: Long)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND generation = :generation")
    suspend fun deleteGeneration(sourceId: Long, generation: Long)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** Programmes en cours et à venir d'une chaîne. */
    @Query(
        """
        SELECT p.* FROM programmes p
        JOIN channels c ON c.sourceId = p.sourceId AND c.epgChannelId = p.epgChannelId
        JOIN sources s ON s.id = p.sourceId AND p.generation = s.epgGeneration
        WHERE c.id = :channelId AND p.stopMillis > :from
        ORDER BY p.startMillis LIMIT :limit
        """,
    )
    suspend fun upcoming(channelId: Long, from: Long, limit: Int): List<ProgrammeEntity>

    /** Programmes visibles d'une source dans la fenêtre [from, to[. */
    @Query(
        """
        SELECT p.* FROM programmes p
        JOIN sources s ON s.id = p.sourceId AND p.generation = s.epgGeneration
        WHERE p.sourceId = :sourceId AND p.stopMillis > :from AND p.startMillis < :to
          AND p.epgChannelId IN (:epgChannelIds)
        ORDER BY p.epgChannelId, p.startMillis
        """,
    )
    suspend fun inWindow(sourceId: Long, epgChannelIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM source_credentials WHERE sourceId = :sourceId")
    suspend fun get(sourceId: Long): CredentialEntity?

    @Upsert
    suspend fun put(credential: CredentialEntity)

    @Query("DELETE FROM source_credentials WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: Long)
}
