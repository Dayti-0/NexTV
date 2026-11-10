package tv.nextv.app.data.progress

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.core.model.ContentKind

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

    /**
     * Films ou séries en cours. Une position sans durée vient d'un direct enregistré comme épisode par
     * une version précédente (« DAZN 1 » dans les séries) : écartée. L'épisode suivant d'une série,
     * à reprendre au début, n'a ni durée ni position.
     */
    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND kind = :kind AND NOT (durationMs <= 0 AND positionMs > 0) ORDER BY updatedAt DESC")
    fun observeAll(sourceId: Long, kind: String): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, itemKey: String)

    /** Toutes les reprises d'une playlist (sauvegarde, rattachement après une actualisation). */
    @Query("SELECT * FROM progress WHERE sourceId = :sourceId")
    suspend fun all(sourceId: Long): List<ProgressEntity>

    @Query("UPDATE progress SET channelId = :channelId WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun setChannel(sourceId: Long, itemKey: String, channelId: Long)
}

@Database(entities = [ProgressEntity::class], version = 1, exportSchema = true)
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
 * - Position enregistrée au fil de la lecture ; rien avant [MIN_POSITION_MS] (simple aperçu), ni
 *   sans durée connue (flux en direct).
 * - Fin atteinte (moins de [END_MARGIN_MS] restantes ou 95 %) : la lecture n'est plus « en cours ».
 */
class ProgressRepository(database: ProgressDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val dao = database.progressDao()

    suspend fun get(sourceId: Long, itemKey: String): ProgressEntity? = dao.get(sourceId, itemKey)

    fun observe(sourceId: Long, itemKey: String): Flow<ProgressEntity?> = dao.observe(sourceId, itemKey)

    fun observeAll(sourceId: Long, kind: String): Flow<List<ProgressEntity>> = dao.observeAll(sourceId, kind)

    suspend fun save(progress: ProgressEntity) {
        // Durée inconnue : flux en direct (même rangé avec les films ou les séries), rien à reprendre.
        if (progress.durationMs <= 0) return
        val finished = progress.durationMs - progress.positionMs < END_MARGIN_MS || progress.positionMs >= progress.durationMs * 95 / 100
        write {
            when {
                finished -> dao.delete(progress.sourceId, progress.itemKey)
                progress.positionMs >= MIN_POSITION_MS -> dao.put(progress.copy(updatedAt = clock()))
            }
        }
    }

    /** Épisode terminé : la série reprendra au début de l'épisode suivant. */
    suspend fun saveNext(progress: ProgressEntity) = write { dao.put(progress.copy(positionMs = 0, durationMs = 0, updatedAt = clock())) }

    suspend fun clear(sourceId: Long, itemKey: String) = write { dao.delete(sourceId, itemKey) }

    /** Toutes les reprises d'une playlist (sauvegarde). */
    suspend fun all(sourceId: Long): List<ProgressEntity> = dao.all(sourceId)

    /**
     * Reprise venue d'une sauvegarde : gardée si aucune plus récente n'existe déjà. Son film ou son épisode
     * ([ProgressEntity.channelId]) est retrouvé ensuite par [relink], une fois la playlist chargée.
     */
    suspend fun restore(progress: ProgressEntity) = write {
        val existing = dao.get(progress.sourceId, progress.itemKey)
        if (existing == null || existing.updatedAt < progress.updatedAt) dao.put(progress)
    }

    /** La reprise vise encore un titre de la playlist, et le bon (même clé). */
    suspend fun isLinked(progress: ProgressEntity, channels: ChannelDao): Boolean =
        channels.identities(listOf(progress.channelId)).any { it.sourceId == progress.sourceId && it.itemKey == progress.itemKey }

    /**
     * Rattache les reprises de la playlist à leurs films et épisodes. Une reprise retient le numéro de ligne du
     * titre ([ProgressEntity.channelId]), qui change quand l'adresse de son flux change (autre serveur, autre mot
     * de passe, sauvegarde restaurée) : « Reprendre » ouvrait alors un titre introuvable. Le titre est retrouvé
     * par sa clé (la même que celle des favoris), et l'épisode par sa saison et son numéro.
     *
     * Une reprise dont le titre a disparu de la playlist est laissée telle quelle : elle sera rattachée s'il
     * revient.
     */
    suspend fun relink(sourceId: Long, channels: ChannelDao) = write {
        val rows = dao.all(sourceId)
        if (rows.isEmpty()) return@write
        val present = rows.map { it.channelId }.distinct().chunked(LOOKUP_CHUNK).flatMap { channels.identities(it) }.associateBy { it.id }
        for (row in rows) {
            val current = present[row.channelId]
            if (current != null && current.sourceId == sourceId && current.itemKey == row.itemKey) continue
            val target = when (row.kind) {
                ContentKind.MOVIE.name -> channels.movieIdByKey(sourceId, row.itemKey)
                else -> episodeOf(row.episodeLabel)?.let { (season, episode) -> channels.episodeIdByKey(sourceId, row.itemKey, season, episode) }
            }
            if (target != null && target != row.channelId) dao.setChannel(sourceId, row.itemKey, target)
        }
    }

    /**
     * Écriture de la reprise : faite toutes les quelques secondes pendant un film, elle ne doit jamais le faire
     * planter (stockage plein, base illisible) ; la position est simplement perdue cette fois-là.
     */
    private suspend fun write(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reprise de lecture non enregistrée", e)
        }
    }

    companion object {
        private const val TAG = "NixTV/Progress"
        private const val LOOKUP_CHUNK = 500
        private val EPISODE_LABEL = Regex("^(?:S(\\d+))?E(\\d+)$")

        /** Saison (`null` : sans saison) et numéro d'un épisode d'après son libellé (« S01E08 », « E08 »). */
        internal fun episodeOf(label: String?): Pair<Int?, Int>? {
            val match = EPISODE_LABEL.matchEntire(label ?: return null) ?: return null
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return match.groupValues[1].toIntOrNull() to episode
        }
        const val MIN_POSITION_MS = 30_000L
        const val END_MARGIN_MS = 2 * 60_000L
    }
}
