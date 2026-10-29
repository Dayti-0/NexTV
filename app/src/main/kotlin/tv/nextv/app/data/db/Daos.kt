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
            (SELECT p.title FROM programmes p JOIN sources s ON s.id = p.sourceId
             WHERE p.sourceId = c.sourceId AND p.generation = s.epgGeneration AND p.epgChannelId = c.epgChannelId
               AND p.startMillis <= :now AND p.stopMillis > :now
             ORDER BY p.startMillis DESC LIMIT 1) AS nowTitle
        FROM channels c
        WHERE c.sourceId = :sourceId
          AND (:filterGroup = 0 OR IFNULL(c.groupTitle, '') = :group)
          AND (:query = '' OR c.name LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY c.position
        """,
    )
    fun pageChannels(sourceId: Long, filterGroup: Int, group: String, query: String, now: Long): PagingSource<Int, ChannelListRow>
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
