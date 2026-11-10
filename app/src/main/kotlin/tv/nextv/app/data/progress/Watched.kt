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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Film ou épisode regardé jusqu'au bout (badge « Déjà vu »). Clé : celle du film, ou celle de l'épisode
 * ([WatchedRepository.episodeKey]) : elle survit au rechargement de la playlist (les numéros des lignes
 * changent, pas le titre ni la saison et l'épisode).
 *
 * Base distincte : l'ajouter ne modifie le schéma d'aucune base existante.
 */
@Entity(tableName = "watched", primaryKeys = ["sourceId", "itemKey"])
data class WatchedEntity(
    val sourceId: Long,
    val itemKey: String,
    val watchedAt: Long,
)

@Dao
interface WatchedDao {
    @Upsert
    suspend fun put(watched: WatchedEntity)

    @Query("SELECT itemKey FROM watched WHERE sourceId = :sourceId")
    fun observeKeys(sourceId: Long): Flow<List<String>>

    @Query("DELETE FROM watched WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, itemKey: String)
}

@Database(entities = [WatchedEntity::class], version = 1, exportSchema = true)
abstract class WatchedDatabase : RoomDatabase() {
    abstract fun watchedDao(): WatchedDao

    companion object {
        fun create(context: Context): WatchedDatabase =
            Room.databaseBuilder(context.applicationContext, WatchedDatabase::class.java, "watched.db").build()
    }
}

/** Films et épisodes déjà vus. */
class WatchedRepository(database: WatchedDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = database.watchedDao()

    suspend fun mark(sourceId: Long, key: String) = dao.put(WatchedEntity(sourceId, key, clock()))

    suspend fun unmark(sourceId: Long, key: String) = dao.delete(sourceId, key)

    fun observeKeys(sourceId: Long): Flow<Set<String>> = dao.observeKeys(sourceId).map { it.toSet() }.distinctUntilChanged()

    companion object {
        /** Clé d'un épisode : la série, puis la saison et l'épisode (sinon son nom). */
        fun episodeKey(seriesKey: String, season: Int?, episode: Int?, name: String): String =
            if (episode != null) "$seriesKey|S${season ?: 0}E$episode" else "$seriesKey|$name"
    }
}
