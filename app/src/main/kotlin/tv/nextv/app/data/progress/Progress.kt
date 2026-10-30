package tv.nextv.app.data.progress

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Lecture en cours d'un film, ou de la série (une ligne par série : l'épisode en cours).
 *
 * Base distincte de celle des playlists : l'ajouter ne modifie pas le schéma existant.
 */
@Entity(tableName = "progress", primaryKeys = ["sourceId", "itemKey"])
data class ProgressEntity(
    val sourceId: Long,
    /** Film (`movie:…`) ou série (`series:…`). */
    val itemKey: String,
    val kind: String,
    /** Film ou épisode à reprendre. */
    val channelId: Long,
    val title: String,
    /** « S01E08 » pour un épisode. */
    val episodeLabel: String?,
    val logoUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface ProgressDao {
    @Upsert
    suspend fun put(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun get(sourceId: Long, itemKey: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND itemKey = :itemKey")
    fun observe(sourceId: Long, itemKey: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND kind = :kind ORDER BY updatedAt DESC")
    fun observeAll(sourceId: Long, kind: String): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, itemKey: String)
}

@Database(entities = [ProgressEntity::class], version = 1, exportSchema = false)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        fun create(context: Context): ProgressDatabase =
            Room.databaseBuilder(context.applicationContext, ProgressDatabase::class.java, "progress.db").build()
    }
}

/**
 * Reprise de lecture des films et séries.
 *
 * - Position enregistrée au fil de la lecture ; rien avant [MIN_POSITION_MS] (simple aperçu).
 * - Fin atteinte (moins de [END_MARGIN_MS] restantes ou 95 %) : la lecture n'est plus « en cours ».
 */
class ProgressRepository(database: ProgressDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val dao = database.progressDao()

    suspend fun get(sourceId: Long, itemKey: String): ProgressEntity? = dao.get(sourceId, itemKey)

    fun observe(sourceId: Long, itemKey: String): Flow<ProgressEntity?> = dao.observe(sourceId, itemKey)

    fun observeAll(sourceId: Long, kind: String): Flow<List<ProgressEntity>> = dao.observeAll(sourceId, kind)

    suspend fun save(progress: ProgressEntity) {
        val finished = progress.durationMs > 0 &&
            (progress.durationMs - progress.positionMs < END_MARGIN_MS || progress.positionMs >= progress.durationMs * 95 / 100)
        when {
            finished -> dao.delete(progress.sourceId, progress.itemKey)
            progress.positionMs >= MIN_POSITION_MS -> dao.put(progress.copy(updatedAt = clock()))
        }
    }

    /** Épisode terminé : la série reprendra au début de l'épisode suivant. */
    suspend fun saveNext(progress: ProgressEntity) = dao.put(progress.copy(positionMs = 0, durationMs = 0, updatedAt = clock()))

    suspend fun clear(sourceId: Long, itemKey: String) = dao.delete(sourceId, itemKey)

    companion object {
        const val MIN_POSITION_MS = 30_000L
        const val END_MARGIN_MS = 2 * 60_000L
    }
}
