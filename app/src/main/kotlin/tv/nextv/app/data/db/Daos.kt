package tv.nextv.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
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

/** Ligne de la liste des chaînes, avec le programme en cours s'il est connu. */
data class ChannelListRow(
    val id: Long,
    val name: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val channelNumber: String?,
    val streamSupport: String,
    val streamIssues: String,
    val nowTitle: String?,
    val nowStart: Long?,
    val nowStop: Long?,
)

/** Chaîne affichée dans la grille du guide. */
data class GuideChannelRow(
    val id: Long,
    val name: String,
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

    @Query("SELECT tvgId, name, epgChannelId FROM channels WHERE sourceId = :sourceId AND epgChannelId IS NOT NULL")
    suspend fun epgAssignments(sourceId: Long): List<EpgAssignmentRow>

    @Query("SELECT id, tvgId, tvgName, name FROM channels WHERE sourceId = :sourceId")
    suspend fun epgRefs(sourceId: Long): List<EpgRefRow>

    @Query("UPDATE channels SET epgChannelId = NULL WHERE sourceId = :sourceId")
    suspend fun clearEpg(sourceId: Long)

    @Query("UPDATE channels SET epgChannelId = :epgChannelId WHERE id = :channelId")
    suspend fun setEpg(channelId: Long, epgChannelId: String)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query(
        """
        SELECT groupTitle AS title, COUNT(*) AS count FROM channels
        WHERE sourceId = :sourceId GROUP BY groupTitle ORDER BY MIN(position)
        """,
    )
    fun observeGroups(sourceId: Long): Flow<List<GroupRow>>

    /**
     * @param filterGroup 1 pour ne garder que [group] (`''` = chaînes sans groupe), 0 pour tout afficher.
     * @param query texte recherché, déjà échappé pour LIKE (caractère d'échappement `\`).
     */
    @Query(
        """
        SELECT c.id, c.name, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
            p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
        FROM channels c
        LEFT JOIN programmes p ON p.id = (
            SELECT p2.id FROM programmes p2 JOIN sources s ON s.id = p2.sourceId
            WHERE p2.sourceId = c.sourceId AND p2.generation = s.epgGeneration AND p2.epgChannelId = c.epgChannelId
              AND p2.startMillis <= :now AND p2.stopMillis > :now
            ORDER BY p2.startMillis DESC LIMIT 1
        )
        WHERE c.sourceId = :sourceId
          AND (:filterGroup = 0 OR IFNULL(c.groupTitle, '') = :group)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY c.position
        """,
    )
    fun pageChannels(sourceId: Long, filterGroup: Int, group: String, query: String, now: Long): PagingSource<Int, ChannelListRow>

    /** Chaîne lisible suivante (zapping), dans l'ordre de la playlist. */
    @Query(
        """
        SELECT id FROM channels
        WHERE sourceId = :sourceId AND position > :position AND streamSupport != 'UNSUPPORTED'
        ORDER BY position ASC LIMIT 1
        """,
    )
    suspend fun nextChannelId(sourceId: Long, position: Int): Long?

    @Query(
        """
        SELECT id FROM channels
        WHERE sourceId = :sourceId AND position < :position AND streamSupport != 'UNSUPPORTED'
        ORDER BY position DESC LIMIT 1
        """,
    )
    suspend fun previousChannelId(sourceId: Long, position: Int): Long?

    /** Chaînes associées au guide, pour la grille des programmes. */
    @Query(
        """
        SELECT id, name, logoUrl, channelNumber, epgChannelId FROM channels
        WHERE sourceId = :sourceId AND epgChannelId IS NOT NULL
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
        ORDER BY position LIMIT :limit
        """,
    )
    suspend fun guideChannels(sourceId: Long, filterGroup: Int, group: String, limit: Int): List<GuideChannelRow>
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
