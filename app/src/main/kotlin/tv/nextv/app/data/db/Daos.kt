package tv.nextv.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/*
 * Une chaîne peut exister en plusieurs versions (FHD, HD, SD…), souvent rangées dans des catégories
 * différentes. Une catégorie affiche chacune de ses chaînes (sa meilleure version dans la catégorie) ;
 * « Toutes », la recherche, le zapping et le guide n'affichent qu'une ligne par chaîne : sa meilleure
 * version hors catégories masquées. Fragments SQL partagés, la ligne examinée étant `c`.
 */

/** Groupe de `c` non masqué. */
private const val NOT_HIDDEN =
    "IFNULL(c.groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'LIVE')"

/** `b` : autre version de la même chaîne, mieux classée que `c` (qualité, puis ordre de la playlist). */
private const val BETTER_VERSION =
    "b.sourceId = c.sourceId AND b.kind = 'LIVE' AND b.variantKey = c.variantKey AND b.variantKey != '' " +
        "AND (b.qualityRank > c.qualityRank OR (b.qualityRank = c.qualityRank AND b.position < c.position))"

/** `c` : meilleure version de la chaîne dans sa catégorie. */
private const val BEST_IN_GROUP =
    "NOT EXISTS (SELECT 1 FROM channels b WHERE $BETTER_VERSION AND IFNULL(b.groupTitle, '') = IFNULL(c.groupTitle, ''))"

/** `c` : meilleure version de la chaîne hors catégories masquées. */
private const val BEST_VISIBLE =
    "NOT EXISTS (SELECT 1 FROM channels b WHERE $BETTER_VERSION " +
        "AND IFNULL(b.groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'LIVE'))"

/** Jours de replay de `c` : ceux de la version qui en a le plus (toutes ne sont pas forcément enregistrées). */
private const val CATCHUP_DAYS =
    "MAX(c.catchupDays, IFNULL((SELECT MAX(v.catchupDays) FROM channels v " +
        "WHERE v.sourceId = c.sourceId AND v.variantKey = c.variantKey AND c.variantKey != ''), 0)) AS catchupDays"

/*
 * Favoris : `favorites f CROSS JOIN channels c` fait lire les quelques favoris d'abord, puis leur
 * ligne par l'index des clés (sans statistiques, SQLite parcourrait sinon toutes les chaînes).
 */

/** `c` affichée dans la liste : catégorie `:group` si `:filterGroup` vaut 1, sinon toutes. */
private const val LISTED =
    "$NOT_HIDDEN AND CASE WHEN :filterGroup = 1 THEN IFNULL(c.groupTitle, '') = :group AND $BEST_IN_GROUP ELSE $BEST_VISIBLE END"

/**
 * Liste des chaînes avec le programme en cours ([ChannelDao.pageChannels]). Requête brute : les
 * paramètres nommés deviennent des paramètres numérotés (`?1`… dans l'ordre de [PAGE_CHANNELS_PARAMETERS]).
 */
private val PAGE_CHANNELS_PARAMETERS = listOf("sourceId", "filterGroup", "group", "query", "now")

internal val PAGE_CHANNELS: String = numberParameters(
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
    WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND $LISTED
      AND (:query = '' OR c.name LIKE '%' || :query || '%' ESCAPE '\')
    ORDER BY c.position
    """.trimIndent(),
    PAGE_CHANNELS_PARAMETERS,
)

/** Remplace `:nom` par `?n`, n étant la place du nom dans [names] (à partir de 1). */
internal fun numberParameters(sql: String, names: List<String>): String =
    Regex(":(\\w+)").replace(sql) { match ->
        val index = names.indexOf(match.groupValues[1])
        require(index >= 0) { "Paramètre inconnu : ${match.value}" }
        "?${index + 1}"
    }

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

/** Groupe d'une playlist, masqué ou non (réglages). */
data class GroupVisibilityRow(val title: String?, val count: Int, val hidden: Boolean)

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

/** Programme du guide susceptible de diffuser un match (section Foot). */
data class FootballProgrammeRow(
    val epgChannelId: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val year: Int?,
    val startMillis: Long,
    val stopMillis: Long,
)

/** Chaîne visible associée au guide (section Foot). */
data class EpgChannelRow(val epgChannelId: String?, val id: Long, val name: String, val title: String, val logoUrl: String?)

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
    val catchupDays: Int,
)

@Dao
interface ChannelDao {
    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Identifiants et URL des chaînes, par pages (conservation des identifiants à l'actualisation).
     * `+sourceId` : parcours dans l'ordre des identifiants, sans trier toute la playlist à chaque page.
     */
    @Query("SELECT id, streamUrl FROM channels WHERE +sourceId = :sourceId AND id > :after ORDER BY id LIMIT :limit")
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

    /** Chaînes, films ou séries (une ligne par série : son premier épisode). */
    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId AND kind = :kind AND isPrimary = 1")
    suspend fun count(sourceId: Long, kind: String): Int

    /**
     * Groupes visibles d'un type de contenu (chaînes : une ligne par chaîne de la catégorie ; séries :
     * une par série). Les groupes masqués n'apparaissent pas.
     */
    @Query(
        """
        SELECT c.groupTitle AS title, COUNT(*) AS count
        FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = :kind
          AND CASE WHEN :kind = 'LIVE' THEN $BEST_IN_GROUP ELSE c.isPrimary = 1 END
          AND IFNULL(c.groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = :kind)
        GROUP BY c.groupTitle ORDER BY MIN(c.position)
        """,
    )
    fun observeGroups(sourceId: Long, kind: String): Flow<List<GroupRow>>

    /** Tous les groupes d'un type de contenu, avec leur visibilité (réglages). */
    @Query(
        """
        SELECT c.groupTitle AS title, COUNT(*) AS count,
            EXISTS (
                SELECT 1 FROM hidden_groups h
                WHERE h.sourceId = :sourceId AND h.kind = :kind AND h.title = IFNULL(c.groupTitle, '')
            ) AS hidden
        FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = :kind
          AND CASE WHEN :kind = 'LIVE' THEN $BEST_IN_GROUP ELSE c.isPrimary = 1 END
        GROUP BY c.groupTitle ORDER BY MIN(c.position)
        """,
    )
    fun observeAllGroups(sourceId: Long, kind: String): Flow<List<GroupVisibilityRow>>

    /**
     * Chaînes en direct, hors catégories masquées : toutes les chaînes de la catégorie [group], ou
     * une ligne par chaîne (sa meilleure version visible).
     *
     * La liste ne se recharge pas à chaque lot de programmes écrit pendant l'import du guide : les
     * nouveaux programmes ne deviennent visibles qu'à la validation de l'import, qui modifie les
     * chaînes et la playlist (tables observées).
     * @param filterGroup 1 pour ne garder que [group] (`''` = sans groupe), 0 pour tout afficher.
     * @param query texte recherché, déjà échappé pour LIKE (caractère d'échappement `\`).
     */
    fun pageChannels(sourceId: Long, filterGroup: Int, group: String, query: String, now: Long): PagingSource<Int, ChannelListRow> =
        pageChannels(SimpleSQLiteQuery(PAGE_CHANNELS, arrayOf(sourceId, filterGroup, group, query, now)))

    @RawQuery(observedEntities = [ChannelEntity::class, SourceEntity::class, HiddenGroupEntity::class])
    fun pageChannels(query: SupportSQLiteQuery): PagingSource<Int, ChannelListRow>

    /** Chaînes favorites, dans l'ordre choisi. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
            c.quality, c.variantCount, c.itemKey,
            p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND c.isPrimary = 1
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

    /** Chaînes désignées par leur clé (chaînes récentes), meilleure version de chacune, dans le désordre. */
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
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.isPrimary = 1 AND c.itemKey IN (:itemKeys)
        """,
    )
    fun observeChannelsByKey(sourceId: Long, itemKeys: List<String>, now: Long): Flow<List<ChannelListRow>>

    @Query(
        """
        SELECT id, title, year, logoUrl, groupTitle, itemKey, streamSupport FROM channels
        WHERE sourceId = :sourceId AND kind = 'MOVIE'
          AND IFNULL(groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'MOVIE')
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

    /** Séries : la ligne de chaque série est son premier épisode, qui porte le nombre d'épisodes. */
    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, variantCount AS episodeCount
        FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND isPrimary = 1 AND seriesName IS NOT NULL
          AND IFNULL(groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'SERIES')
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
          AND (:query = '' OR seriesName LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY position
        """,
    )
    fun pageSeries(sourceId: Long, filterGroup: Int, group: String, query: String): PagingSource<Int, SeriesRow>

    @Query(
        """
        SELECT c.id, c.seriesName AS title, c.year, c.logoUrl, c.groupTitle, c.itemKey, c.variantCount AS episodeCount
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'SERIES' AND c.isPrimary = 1
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

    /**
     * Chaîne suivante (zapping), dans l'ordre de la liste « Toutes » : meilleure version visible de
     * la chaîne suivante.
     */
    @Query(
        """
        SELECT c.id FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.position > :position
          AND c.streamSupport != 'UNSUPPORTED' AND $NOT_HIDDEN AND $BEST_VISIBLE
        ORDER BY c.position ASC LIMIT 1
        """,
    )
    suspend fun nextChannelId(sourceId: Long, position: Int): Long?

    @Query(
        """
        SELECT c.id FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.position < :position
          AND c.streamSupport != 'UNSUPPORTED' AND $NOT_HIDDEN AND $BEST_VISIBLE
        ORDER BY c.position DESC LIMIT 1
        """,
    )
    suspend fun previousChannelId(sourceId: Long, position: Int): Long?

    /** Place d'une chaîne dans la liste « Toutes » (sa meilleure version visible), point de départ du zapping. */
    @Query(
        """
        SELECT c.position FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.variantKey = :variantKey AND $NOT_HIDDEN
        ORDER BY c.qualityRank DESC, c.position LIMIT 1
        """,
    )
    suspend fun listedPosition(sourceId: Long, variantKey: String): Int?

    /** Rang d'une chaîne dans sa liste (pour placer le focus du menu des chaînes). */
    @Query(
        """
        SELECT COUNT(*) FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.position < :position AND $LISTED
        """,
    )
    suspend fun indexInList(sourceId: Long, filterGroup: Int, group: String, position: Int): Int

    /** Meilleure version d'une chaîne désignée par sa clé (chaîne précédente, dernière chaîne regardée). */
    @Query("SELECT id FROM channels WHERE sourceId = :sourceId AND kind = 'LIVE' AND isPrimary = 1 AND itemKey = :itemKey LIMIT 1")
    suspend fun primaryIdByKey(sourceId: Long, itemKey: String): Long?

    /** Nombre de chaînes de la liste « Toutes ». */
    @Query("SELECT COUNT(*) FROM channels c WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND $NOT_HIDDEN AND $BEST_VISIBLE")
    fun observeListedCount(sourceId: Long): Flow<Int>

    /** Meilleure version de la chaîne que le serveur enregistre (replay). */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND variantKey = :variantKey AND variantKey != '' AND catchupDays > 0
        ORDER BY qualityRank DESC, position LIMIT 1
        """,
    )
    suspend fun recordedVersion(sourceId: Long, variantKey: String): ChannelEntity?

    /** Meilleure version de la chaîne (pour l'affichage et le zapping). */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND variantKey = :variantKey AND isPrimary = 1 LIMIT 1
        """,
    )
    suspend fun primaryOf(sourceId: Long, variantKey: String): ChannelEntity?

    /**
     * Chaînes visibles associées au guide, la meilleure version d'abord : la première de chaque
     * identifiant de guide est celle qui diffuse ses programmes (section Foot).
     */
    @Query(
        """
        SELECT c.epgChannelId, c.id, c.name, c.title, c.logoUrl FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.epgChannelId IS NOT NULL AND $NOT_HIDDEN
        ORDER BY c.qualityRank DESC, c.position
        """,
    )
    suspend fun epgChannels(sourceId: Long): List<EpgChannelRow>

    /** Chaînes associées au guide, pour la grille des programmes (hors catégories masquées). */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.channelNumber, c.epgChannelId, $CATCHUP_DAYS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.epgChannelId IS NOT NULL AND $LISTED
        ORDER BY c.position LIMIT :limit
        """,
    )
    suspend fun guideChannels(sourceId: Long, filterGroup: Int, group: String, limit: Int): List<GuideChannelRow>

    /** Chaînes favorites associées au guide. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.channelNumber, c.epgChannelId, $CATCHUP_DAYS
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND c.isPrimary = 1
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

/** Catégories masquées (les favoris restent toujours visibles). */
@Dao
interface HiddenGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(group: HiddenGroupEntity)

    @Query("DELETE FROM hidden_groups WHERE sourceId = :sourceId AND kind = :kind AND title = :title")
    suspend fun show(sourceId: Long, kind: String, title: String)

    @Query("DELETE FROM hidden_groups WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun showAll(sourceId: Long, kind: String)

    @Query("SELECT EXISTS (SELECT 1 FROM hidden_groups WHERE sourceId = :sourceId AND kind = :kind AND title = :title)")
    suspend fun isHidden(sourceId: Long, kind: String, title: String): Boolean

    @Query("SELECT title FROM hidden_groups WHERE sourceId = :sourceId AND kind = :kind")
    fun observeTitles(sourceId: Long, kind: String): Flow<List<String>>

    /** Catégories masquées encore présentes dans la playlist. */
    @Query(
        """
        SELECT COUNT(*) FROM hidden_groups h
        WHERE h.sourceId = :sourceId AND h.kind = :kind AND EXISTS (
            SELECT 1 FROM channels c WHERE c.sourceId = h.sourceId AND c.kind = h.kind AND IFNULL(c.groupTitle, '') = h.title
        )
        """,
    )
    fun observeCount(sourceId: Long, kind: String): Flow<Int>
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

    /**
     * Programmes qui peuvent être des matchs entre [from] et [to] : genre « Football », compétition
     * citée, ou programme sportif dont le titre oppose deux équipes. Le tri fin est fait par
     * [tv.nextv.core.football.FootballMatches] et [tv.nextv.core.football.BroadcastFinder].
     */
    @Query(
        """
        SELECT p.epgChannelId, p.title, p.subtitle, p.description, p.category, p.year, p.startMillis, p.stopMillis
        FROM programmes p
        JOIN sources s ON s.id = p.sourceId AND p.generation = s.epgGeneration
        WHERE p.sourceId = :sourceId AND p.stopMillis > :from AND p.startMillis < :to
          AND (p.category LIKE '%foot%'
            OR p.title LIKE '%foot%' OR p.title LIKE '%ligue%' OR p.title LIKE '%league%' OR p.title LIKE '%liga%'
            OR p.title LIKE '%serie a%' OR p.title LIKE '%uefa%' OR p.title LIKE '%coupe%' OR p.title LIKE '%euro%'
            OR p.title LIKE '%nations%'
            OR p.subtitle LIKE '%foot%' OR p.subtitle LIKE '%ligue%' OR p.subtitle LIKE '%league%' OR p.subtitle LIKE '%liga%'
            OR p.subtitle LIKE '%serie a%' OR p.subtitle LIKE '%uefa%' OR p.subtitle LIKE '%coupe%' OR p.subtitle LIKE '%euro%'
            OR p.subtitle LIKE '%nations%'
            OR (p.category LIKE '%sport%' AND (p.title LIKE '% / %' OR p.title LIKE '% vs%' OR p.subtitle LIKE '% / %')))
        """,
    )
    suspend fun footballCandidates(sourceId: Long, from: Long, to: Long): List<FootballProgrammeRow>
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
