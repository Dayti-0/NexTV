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

/*
 * Filtres des réglages, rangés avec les catégories masquées (sans nouvelle table) :
 * - [HIDDEN_ITEM] : chaîne masquée avec toutes ses versions (France 3 des autres régions), par sa clé ;
 * - [HIDDEN_UHD] : versions 4K masquées (rang de qualité 5, voir StreamQuality.UHD).
 */
internal const val HIDDEN_ITEM = "LIVE_ITEM"
internal const val HIDDEN_UHD = "LIVE_UHD"

/** 4K masquée dans les réglages. */
private const val UHD_HIDDEN = "EXISTS (SELECT 1 FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = '$HIDDEN_UHD')"

/** `c` : ni chaîne masquée par les réglages, ni version 4K masquée. */
private const val VISIBLE_VERSION =
    "c.itemKey NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = '$HIDDEN_ITEM') " +
        "AND NOT (c.qualityRank = 5 AND $UHD_HIDDEN)"

/** Groupe de `c` non masqué, et `c` ni masquée ni 4K masquée. */
private const val NOT_HIDDEN =
    "IFNULL(c.groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'LIVE') " +
        "AND $VISIBLE_VERSION"

/**
 * `c` : version affichée de la chaîne (favoris, chaînes récentes, guide des favoris) : la meilleure version
 * (`isPrimary`), sauf si c'est une 4K masquée dans les réglages : alors la meilleure des autres, avec son logo.
 * Une chaîne qui n'existe qu'en 4K garde sa version 4K.
 */
private const val SHOWN_VERSION =
    "c.id = (SELECT v.id FROM channels v WHERE v.sourceId = c.sourceId AND v.kind = 'LIVE' AND v.itemKey = c.itemKey " +
        "ORDER BY (v.qualityRank = 5 AND $UHD_HIDDEN), v.isPrimary DESC, v.qualityRank DESC, v.position LIMIT 1)"

/** `b` : autre version visible de la même chaîne, mieux classée que `c` (qualité, puis ordre de la playlist). */
private const val BETTER_VERSION =
    "b.sourceId = c.sourceId AND b.kind = 'LIVE' AND b.variantKey = c.variantKey AND b.variantKey != '' " +
        "AND (b.qualityRank > c.qualityRank OR (b.qualityRank = c.qualityRank AND b.position < c.position)) " +
        "AND NOT (b.qualityRank = 5 AND $UHD_HIDDEN)"

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
 * Un film peut exister en plusieurs copies (« À la dérive (Truefrench) » et « À la dérive (FR) », la même en 4K,
 * la même dans une autre catégorie) : elles ont la même clé. Une seule s'affiche : la copie choisie à l'import
 * (`isPrimary`, voir MovieCopies.best) si elle est dans la liste, sinon la première de la liste. Les autres se
 * choisissent sur la fiche du film. Un film en une seule copie est sa propre copie choisie : rien n'est cherché
 * pour lui (le cas de presque tous les films).
 */

/** Catégories de films masquées (sous-requête). */
private const val HIDDEN_MOVIE_GROUPS = "(SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'MOVIE')"

/** Catégorie du film `c` non masquée. */
private const val MOVIE_GROUP_VISIBLE = "IFNULL(c.groupTitle, '') NOT IN $HIDDEN_MOVIE_GROUPS"

/** `b` : autre copie du film `c`, affichée à sa place. */
private const val PREFERRED_COPY =
    "b.sourceId = c.sourceId AND b.kind = 'MOVIE' AND b.itemKey = c.itemKey AND (b.isPrimary = 1 OR b.position < c.position)"

/** Film `c` affiché dans sa catégorie : une ligne par film de la catégorie. */
private const val MOVIE_IN_GROUP =
    "(c.isPrimary = 1 OR NOT EXISTS (SELECT 1 FROM channels b WHERE $PREFERRED_COPY " +
        "AND IFNULL(b.groupTitle, '') = IFNULL(c.groupTitle, '')))"

/**
 * Film `c` affiché hors catégories masquées (« Tous les films », recherche, nouveautés) : une ligne par film.
 * Sans catégorie de films masquée (le cas courant), c'est la copie choisie, sans rien chercher ; sinon, une autre
 * copie la remplace quand elle est dans une catégorie masquée.
 */
private const val MOVIE_VISIBLE =
    "(c.isPrimary = 1 OR (EXISTS $HIDDEN_MOVIE_GROUPS AND NOT EXISTS (SELECT 1 FROM channels b WHERE $PREFERRED_COPY " +
        "AND IFNULL(b.groupTitle, '') NOT IN $HIDDEN_MOVIE_GROUPS)))"

/** Colonnes d'un film dans les listes ([MovieRow]). */
private const val MOVIE_COLUMNS = "c.id, c.title, c.year, c.logoUrl, c.groupTitle, c.itemKey, c.streamSupport, c.quality"

/*
 * Favoris : `favorites f CROSS JOIN channels c` fait lire les quelques favoris d'abord, puis leur
 * ligne par l'index des clés (sans statistiques, SQLite parcourrait sinon toutes les chaînes).
 */

/** `c` affichée dans la liste : catégorie `:group` si `:filterGroup` vaut 1, sinon toutes. */
private const val LISTED =
    "$NOT_HIDDEN AND CASE WHEN :filterGroup = 1 THEN IFNULL(c.groupTitle, '') = :group AND $BEST_IN_GROUP ELSE $BEST_VISIBLE END"

/**
 * Programme en cours (`p`) de la chaîne `c` à l'heure `:now`, dans le guide en vigueur de sa playlist ; absent
 * si le guide n'en connaît pas. Écrit une seule fois pour les trois listes de chaînes qui l'affichent.
 */
private const val NOW_PROGRAMME =
    "LEFT JOIN programmes p ON p.id = (" +
        "SELECT p2.id FROM programmes p2 JOIN sources s ON s.id = p2.sourceId " +
        "WHERE p2.sourceId = c.sourceId AND p2.generation = s.epgGeneration AND p2.epgChannelId = c.epgChannelId " +
        "AND p2.startMillis <= :now AND p2.stopMillis > :now " +
        "ORDER BY p2.startMillis DESC LIMIT 1)"

/**
 * Liste des chaînes avec le programme en cours ([ChannelDao.pageChannels]). Requête brute : les
 * paramètres nommés deviennent des paramètres numérotés (`?1`… dans l'ordre de [PAGE_CHANNELS_PARAMETERS]).
 */
private val PAGE_CHANNELS_PARAMETERS = listOf("sourceId", "filterGroup", "group", "query", "now", "key")

internal val PAGE_CHANNELS: String = numberParameters(
    """
    SELECT c.id, c.name, c.title, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
        c.quality, c.variantCount, c.itemKey,
        p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
    FROM channels c
    $NOW_PROGRAMME
    WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND $LISTED
      AND (:query = '' OR c.name LIKE '%' || :query || '%' ESCAPE '\'
        OR (:key != '' AND c.itemKey LIKE 'live:%' || :key || '%'))
    ORDER BY c.position
    """.trimIndent(),
    PAGE_CHANNELS_PARAMETERS,
)

/**
 * Texte recherché tel qu'il est écrit dans les clés des titres ([ItemKeys.normalize]) : minuscules, sans
 * accents, mots séparés par une espace. Ne contient ni `%` ni `_` (rien à échapper pour LIKE).
 */
internal fun searchKey(query: String): String = ItemKeys.normalize(query)

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

/** Clé et nom d'une version de chaîne. */
data class ItemNameRow(val itemKey: String, val name: String)

/** Groupe d'une playlist, masqué ou non (réglages). */
data class GroupVisibilityRow(val title: String?, val count: Int, val hidden: Boolean)

data class ChannelUrlRow(val id: Long, val streamUrl: String)

/** Identité d'une ligne : sa playlist, son type et sa clé stable (voir [ItemKeys]). */
data class ChannelIdentityRow(val id: Long, val sourceId: Long, val kind: String, val itemKey: String)

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
    /** Qualité annoncée par la playlist ([tv.nextv.core.model.StreamQuality]), si elle l'indique. */
    val quality: String? = null,
)

/** Copie d'un film dans la playlist (une de ses versions). */
data class MovieVersionRow(
    val id: Long,
    val name: String,
    val groupTitle: String?,
    val quality: String?,
    val streamUrl: String,
    val isPrimary: Boolean,
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
    /** Qualité annoncée pour son premier épisode. */
    val quality: String? = null,
)

data class EpisodeRow(
    val id: Long,
    val name: String,
    val season: Int?,
    val episode: Int?,
    val logoUrl: String?,
    val streamSupport: String,
)

/** Programme de la grille du guide : tout sauf la description, lue à la demande ([ProgrammeDao.description]). */
data class GridProgrammeRow(
    val id: Long,
    val epgChannelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String,
    val subtitle: String?,
    val category: String?,
    val year: Int?,
    val imageUrl: String?,
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

/** Chaîne qui peut être une chaîne « événement » (section Foot) ; [hasGuide] : elle a des programmes dans le guide. */
data class EventChannelRow(
    val id: Long,
    val name: String,
    val title: String,
    val logoUrl: String?,
    val hasGuide: Boolean,
    /** Adresse du flux : son identifiant Xtream sert à lire le guide du fournisseur. */
    val streamUrl: String = "",
)

/** Version (qualité) d'une chaîne. */
data class VariantRow(val id: Long, val quality: String?, val position: Int, val streamSupport: String)

/** Version d'une chaîne associée au guide (« L'essentiel », « Ce soir » : une ligne par chaîne du guide). */
data class GuideVersionRow(
    val id: Long,
    val name: String,
    val title: String,
    val logoUrl: String?,
    val epgChannelId: String,
    val position: Int,
    val catchupDays: Int,
    val itemKey: String,
)

/** Chaîne affichée dans la grille du guide. */
data class GuideChannelRow(
    val id: Long,
    val name: String,
    val title: String,
    val logoUrl: String?,
    val channelNumber: String?,
    val epgChannelId: String,
    val catchupDays: Int,
    val itemKey: String,
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

    /**
     * Autres copies du même film (ou du même épisode) dans la playlist, avec un autre flux : essayées quand
     * celle-ci ne peut pas être décodée. Celles de qualité égale ou inférieure d'abord (une version 4K ou 10
     * bits illisible a souvent une version HD qui passe).
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND kind = :kind AND itemKey = :itemKey AND itemKey != ''
          AND IFNULL(season, -1) = IFNULL(:season, -1) AND IFNULL(episode, -1) = IFNULL(:episode, -1)
          AND id != :id AND streamUrl != :streamUrl
        ORDER BY (qualityRank > :rank), qualityRank DESC, position + 0
        LIMIT 5
        """,
    )
    suspend fun otherCopies(sourceId: Long, kind: String, itemKey: String, season: Int?, episode: Int?, id: Long, streamUrl: String, rank: Int): List<ChannelEntity>

    /** Chaînes, films ou séries (une ligne par série : son premier épisode). */
    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId AND kind = :kind AND isPrimary = 1")
    suspend fun count(sourceId: Long, kind: String): Int

    /**
     * Groupes visibles d'un type de contenu (chaînes : une ligne par chaîne de la catégorie ; films : une par
     * film de la catégorie ; séries : une par série). Les groupes masqués n'apparaissent pas.
     */
    @Query(
        """
        SELECT c.groupTitle AS title, COUNT(*) AS count
        FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = :kind
          AND CASE :kind WHEN 'LIVE' THEN $BEST_IN_GROUP AND $VISIBLE_VERSION WHEN 'MOVIE' THEN $MOVIE_IN_GROUP ELSE c.isPrimary = 1 END
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
          AND CASE :kind WHEN 'LIVE' THEN $BEST_IN_GROUP WHEN 'MOVIE' THEN $MOVIE_IN_GROUP ELSE c.isPrimary = 1 END
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
     * @param query texte recherché, déjà échappé pour LIKE (caractère d'échappement `\`). Cherché tel
     *   quel dans le nom, et sans accents ni ponctuation dans la clé de la chaîne ([searchKey]).
     */
    fun pageChannels(sourceId: Long, filterGroup: Int, group: String, query: String, now: Long): PagingSource<Int, ChannelListRow> =
        pageChannels(SimpleSQLiteQuery(PAGE_CHANNELS, arrayOf(sourceId, filterGroup, group, query, now, searchKey(query))))

    @RawQuery(observedEntities = [ChannelEntity::class, SourceEntity::class, HiddenGroupEntity::class])
    fun pageChannels(query: SupportSQLiteQuery): PagingSource<Int, ChannelListRow>

    /** Premières chaînes d'une catégorie ([group] vide : sans groupe), pour les rangées de l'interface « Streaming ». */
    suspend fun channelRow(sourceId: Long, group: String, now: Long, limit: Int): List<ChannelListRow> =
        channelRow(SimpleSQLiteQuery("$PAGE_CHANNELS LIMIT ?6", arrayOf(sourceId, 1, group, "", now, limit)))

    @RawQuery
    suspend fun channelRow(query: SupportSQLiteQuery): List<ChannelListRow>

    /**
     * Premiers films d'une catégorie ([group] `null` : sans groupe). `position + 0` : lecture par
     * l'index des catégories (et non tout l'index des positions jusqu'à trouver la catégorie).
     */
    @Query(
        """
        SELECT $MOVIE_COLUMNS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'MOVIE' AND c.groupTitle IS :group AND $MOVIE_IN_GROUP
        ORDER BY c.position + 0 LIMIT :limit
        """,
    )
    suspend fun movieRow(sourceId: Long, group: String?, limit: Int): List<MovieRow>

    /** Films les plus récents (année de sortie), hors catégories masquées : rangée « Nouveautés ». */
    @Query(
        """
        SELECT $MOVIE_COLUMNS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'MOVIE' AND c.year >= :minYear
          AND $MOVIE_GROUP_VISIBLE AND $MOVIE_VISIBLE
        ORDER BY c.year DESC, c.position LIMIT :limit
        """,
    )
    suspend fun newestMovies(sourceId: Long, minYear: Int, limit: Int): List<MovieRow>

    /** Séries les plus récentes : rangée « Nouveautés ». */
    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, variantCount AS episodeCount, quality FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND isPrimary = 1 AND seriesName IS NOT NULL AND year >= :minYear
          AND IFNULL(groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'SERIES')
        ORDER BY year DESC, position LIMIT :limit
        """,
    )
    suspend fun newestSeries(sourceId: Long, minYear: Int, limit: Int): List<SeriesRow>

    /** Films désignés par leur clé (tendances retrouvées dans la playlist) : la copie affichée de chacun d'abord. */
    @Query(
        """
        SELECT $MOVIE_COLUMNS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'MOVIE' AND c.itemKey IN (:itemKeys)
        ORDER BY c.isPrimary DESC, c.position
        """,
    )
    suspend fun moviesByKey(sourceId: Long, itemKeys: List<String>): List<MovieRow>

    /** Séries désignées par leur clé. */
    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, variantCount AS episodeCount, quality FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND isPrimary = 1 AND seriesName IS NOT NULL AND itemKey IN (:itemKeys)
        """,
    )
    suspend fun seriesByKey(sourceId: Long, itemKeys: List<String>): List<SeriesRow>

    /** Premières séries d'une catégorie ([group] `null` : sans groupe). */
    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, variantCount AS episodeCount, quality FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND isPrimary = 1 AND seriesName IS NOT NULL AND groupTitle IS :group
        ORDER BY position + 0 LIMIT :limit
        """,
    )
    suspend fun seriesRow(sourceId: Long, group: String?, limit: Int): List<SeriesRow>

    /** Chaînes favorites, dans l'ordre choisi. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.groupTitle, c.logoUrl, c.channelNumber, c.streamSupport, c.streamIssues,
            c.quality, c.variantCount, c.itemKey,
            p.title AS nowTitle, p.startMillis AS nowStart, p.stopMillis AS nowStop
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND $SHOWN_VERSION
        $NOW_PROGRAMME
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
        $NOW_PROGRAMME
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.itemKey IN (:itemKeys) AND $SHOWN_VERSION
        """,
    )
    fun observeChannelsByKey(sourceId: Long, itemKeys: List<String>, now: Long): Flow<List<ChannelListRow>>

    @Query(
        """
        SELECT $MOVIE_COLUMNS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'MOVIE' AND $MOVIE_GROUP_VISIBLE
          AND (:query = '' OR c.title LIKE '%' || :query || '%' ESCAPE '\'
            OR (:key != '' AND c.itemKey LIKE 'movie:%' || :key || '%'))
          AND CASE WHEN :filterGroup = 1 THEN IFNULL(c.groupTitle, '') = :group AND $MOVIE_IN_GROUP ELSE $MOVIE_VISIBLE END
        ORDER BY c.position
        """,
    )
    fun pageMovies(sourceId: Long, filterGroup: Int, group: String, query: String, key: String): PagingSource<Int, MovieRow>

    /**
     * Films de la catégorie, une ligne par film : [query] cherché dans le titre, et sans accents dans la clé du
     * film (« a la derive » trouve « À la dérive »).
     */
    fun pageMovies(sourceId: Long, filterGroup: Int, group: String, query: String): PagingSource<Int, MovieRow> =
        pageMovies(sourceId, filterGroup, group, query, searchKey(query))

    @Query(
        """
        SELECT c.id, c.title, c.year, c.logoUrl, c.groupTitle, c.itemKey, c.streamSupport, c.quality
        FROM favorites f JOIN channels c ON c.id = (
            SELECT c2.id FROM channels c2
            WHERE c2.sourceId = f.sourceId AND c2.itemKey = f.itemKey AND c2.kind = 'MOVIE'
            ORDER BY c2.isPrimary DESC, c2.position LIMIT 1
        )
        WHERE f.sourceId = :sourceId AND f.kind = 'MOVIE'
        ORDER BY f.position
        """,
    )
    fun observeFavoriteMovies(sourceId: Long): Flow<List<MovieRow>>

    /** Séries : la ligne de chaque série est son premier épisode, qui porte le nombre d'épisodes. */
    @Query(
        """
        SELECT id, seriesName AS title, year, logoUrl, groupTitle, itemKey, variantCount AS episodeCount, quality
        FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND isPrimary = 1 AND seriesName IS NOT NULL
          AND IFNULL(groupTitle, '') NOT IN (SELECT h.title FROM hidden_groups h WHERE h.sourceId = :sourceId AND h.kind = 'SERIES')
          AND (:filterGroup = 0 OR IFNULL(groupTitle, '') = :group)
          AND (:query = '' OR seriesName LIKE '%' || :query || '%' ESCAPE '\'
            OR (:key != '' AND itemKey LIKE 'series:%' || :key || '%'))
        ORDER BY position
        """,
    )
    fun pageSeries(sourceId: Long, filterGroup: Int, group: String, query: String, key: String): PagingSource<Int, SeriesRow>

    /** Séries de la catégorie : [query] cherché dans le nom, et sans accents dans la clé de la série. */
    fun pageSeries(sourceId: Long, filterGroup: Int, group: String, query: String): PagingSource<Int, SeriesRow> =
        pageSeries(sourceId, filterGroup, group, query, searchKey(query))

    @Query(
        """
        SELECT c.id, c.seriesName AS title, c.year, c.logoUrl, c.groupTitle, c.itemKey, c.variantCount AS episodeCount, c.quality
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'SERIES' AND c.isPrimary = 1
        WHERE f.sourceId = :sourceId AND f.kind = 'SERIES'
        ORDER BY f.position
        """,
    )
    fun observeFavoriteSeries(sourceId: Long): Flow<List<SeriesRow>>

    /** Saisons d'une série présentes dans la playlist (0 : épisodes sans numéro de saison). */
    @Query("SELECT COUNT(DISTINCT season) FROM channels WHERE sourceId = :sourceId AND kind = 'SERIES' AND itemKey = :itemKey")
    suspend fun seasonCount(sourceId: Long, itemKey: String): Int

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

    /** Chaînes dont le nom peut désigner France 3 (éditions régionales comprises) : clé et nom de chaque version. */
    @Query(
        """
        SELECT DISTINCT itemKey, name FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE'
          AND (name LIKE '%france 3%' OR name LIKE '%france3%' OR name LIKE '%f3 %' OR name LIKE '%f3:%' OR name LIKE '%f3|%')
        """,
    )
    suspend fun franceTroisCandidates(sourceId: Long): List<ItemNameRow>

    /**
     * Identité des lignes [ids] encore présentes (au plus quelques centaines) : pour vérifier qu'une reprise de
     * lecture vise toujours le bon titre après une actualisation de la playlist.
     */
    @Query("SELECT id, sourceId, kind, itemKey FROM channels WHERE id IN (:ids)")
    suspend fun identities(ids: List<Long>): List<ChannelIdentityRow>

    /** Film désigné par sa clé : la copie affichée dans les listes, sinon la première de la playlist. */
    @Query("SELECT id FROM channels WHERE sourceId = :sourceId AND kind = 'MOVIE' AND itemKey = :itemKey ORDER BY isPrimary DESC, position + 0 LIMIT 1")
    suspend fun movieIdByKey(sourceId: Long, itemKey: String): Long?

    /**
     * Versions d'un film (ses copies dans la playlist), dans l'ordre de la playlist, hors catégories masquées ;
     * la copie ouverte ([id]) y figure toujours.
     */
    @Query(
        """
        SELECT c.id, c.name, c.groupTitle, c.quality, c.streamUrl, c.isPrimary FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'MOVIE' AND c.itemKey = :itemKey
          AND (c.id = :id OR (c.streamSupport != 'UNSUPPORTED' AND $MOVIE_GROUP_VISIBLE))
        ORDER BY c.position + 0
        LIMIT 40
        """,
    )
    suspend fun movieVersions(sourceId: Long, itemKey: String, id: Long): List<MovieVersionRow>

    /** Un film ou une série de la playlist porte cette clé. */
    @Query("SELECT EXISTS (SELECT 1 FROM channels WHERE sourceId = :sourceId AND kind = :kind AND itemKey = :itemKey)")
    suspend fun hasItem(sourceId: Long, kind: String, itemKey: String): Boolean

    /** Clés de la playlist comprises entre [from] (incluse) et [until] (exclue) : celles d'un titre, toutes années confondues. */
    @Query(
        """
        SELECT DISTINCT itemKey FROM channels
        WHERE sourceId = :sourceId AND kind = :kind AND itemKey >= :from AND itemKey < :until
        LIMIT 10
        """,
    )
    suspend fun itemKeysBetween(sourceId: Long, kind: String, from: String, until: String): List<String>

    /** Épisode d'une série désigné par sa saison ([season] `null` : épisodes sans saison) et son numéro. */
    @Query(
        """
        SELECT id FROM channels
        WHERE sourceId = :sourceId AND kind = 'SERIES' AND itemKey = :itemKey
          AND IFNULL(season, -1) = IFNULL(:season, -1) AND episode = :episode
        ORDER BY position + 0 LIMIT 1
        """,
    )
    suspend fun episodeIdByKey(sourceId: Long, itemKey: String, season: Int?, episode: Int): Long?

    /** Qualités mesurées d'une playlist (reprises à chaque actualisation). */
    @Query("SELECT * FROM measured_quality WHERE sourceId = :sourceId")
    suspend fun measuredQualities(sourceId: Long): List<MeasuredQualityEntity>

    @Query("SELECT * FROM measured_quality WHERE sourceId = :sourceId AND streamUrl = :streamUrl")
    suspend fun measuredQuality(sourceId: Long, streamUrl: String): MeasuredQualityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasuredQuality(entity: MeasuredQualityEntity)

    /** Qualité trouvée d'une ligne qui n'en annonçait pas (son badge apparaît partout). */
    @Query("UPDATE channels SET quality = :quality, qualityRank = :rank WHERE id = :id AND quality IS NULL")
    suspend fun fillQuality(id: Long, quality: String, rank: Int)

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

    /**
     * Meilleure version de la chaîne (pour l'affichage et le zapping) ; 4K masquée dans les réglages : la meilleure
     * des autres, pour ne pas afficher le logo « 4K » sur une image FHD.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND kind = 'LIVE' AND variantKey = :variantKey
        ORDER BY (qualityRank = 5 AND $UHD_HIDDEN), isPrimary DESC, qualityRank DESC, position LIMIT 1
        """,
    )
    suspend fun primaryOf(sourceId: Long, variantKey: String): ChannelEntity?

    /**
     * Chaînes associées au guide, la meilleure version visible d'abord : la première de chaque
     * identifiant de guide est celle qui diffuse ses programmes (section Foot). Une chaîne rangée
     * dans une catégorie masquée vient en dernier : un match qu'elle seule diffuse reste visible.
     */
    @Query(
        """
        SELECT c.epgChannelId, c.id, c.name, c.title, c.logoUrl FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.epgChannelId IS NOT NULL
        ORDER BY NOT ($NOT_HIDDEN), c.qualityRank DESC, c.position
        """,
    )
    suspend fun epgChannels(sourceId: Long): List<EpgChannelRow>

    /**
     * Chaînes dont le nom cite un championnat (« FR| DAZN LALIGA 2 (LIVE MATCH) ») : chaînes « événement »
     * possibles de la section Foot, la meilleure version visible d'abord (celles des catégories masquées
     * en dernier), avec la présence de programmes du guide entre [from] et [to].
     */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, EXISTS (
            SELECT 1 FROM programmes p JOIN sources s ON s.id = p.sourceId AND p.generation = s.epgGeneration
            WHERE p.sourceId = c.sourceId AND p.epgChannelId = c.epgChannelId AND p.stopMillis > :from AND p.startMillis < :to
        ) AS hasGuide, c.streamUrl
        FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE'
          AND (c.name LIKE '%liga%' OR c.name LIKE '%serie a%' OR c.name LIKE '%premier league%')
        ORDER BY NOT ($NOT_HIDDEN), c.qualityRank DESC, c.position
        """,
    )
    suspend fun eventChannelCandidates(sourceId: Long, from: Long, to: Long): List<EventChannelRow>

    /** Chaînes associées au guide, pour la grille des programmes (hors catégories masquées). */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.channelNumber, c.epgChannelId, c.itemKey, $CATCHUP_DAYS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.epgChannelId IS NOT NULL AND $LISTED
        ORDER BY c.position LIMIT :limit
        """,
    )
    suspend fun guideChannels(sourceId: Long, filterGroup: Int, group: String, limit: Int): List<GuideChannelRow>

    /**
     * Versions visibles des chaînes associées au guide, la meilleure d'abord (lisible, puis meilleure
     * qualité) : la première de chaque identifiant de guide représente la chaîne, sans ses doublons
     * SD, HD, FHD ni ceux des autres catégories.
     */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.epgChannelId, c.position, c.itemKey, $CATCHUP_DAYS FROM channels c
        WHERE c.sourceId = :sourceId AND c.kind = 'LIVE' AND c.epgChannelId IS NOT NULL AND $NOT_HIDDEN
        ORDER BY c.streamSupport = 'UNSUPPORTED', c.qualityRank DESC, c.position
        """,
    )
    suspend fun guideVersions(sourceId: Long): List<GuideVersionRow>

    /**
     * Chaînes choisies pour « L'essentiel » (dans l'ordre d'ajout), chacune par sa meilleure version
     * associée au guide : une seule ligne par chaîne, quelle que soit la catégorie d'où elle vient.
     */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.epgChannelId, c.position, c.itemKey, $CATCHUP_DAYS
        FROM favorites f
        JOIN channels c ON c.id = (
            SELECT c2.id FROM channels c2
            WHERE c2.sourceId = f.sourceId AND c2.kind = 'LIVE' AND c2.itemKey = f.itemKey AND c2.epgChannelId IS NOT NULL
            ORDER BY c2.streamSupport = 'UNSUPPORTED', c2.qualityRank DESC, c2.position LIMIT 1
        )
        WHERE f.sourceId = :sourceId AND f.kind = 'GUIDE'
        ORDER BY f.position
        """,
    )
    suspend fun guideSelection(sourceId: Long): List<GuideVersionRow>

    /** Chaînes favorites associées au guide. */
    @Query(
        """
        SELECT c.id, c.name, c.title, c.logoUrl, c.channelNumber, c.epgChannelId, c.itemKey, $CATCHUP_DAYS
        FROM favorites f
        CROSS JOIN channels c ON c.sourceId = f.sourceId AND c.itemKey = f.itemKey AND c.kind = 'LIVE' AND $SHOWN_VERSION
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

    /** Favoris (chaînes, films, séries), sans les chaînes de « L'essentiel » du guide. */
    @Query("SELECT itemKey FROM favorites WHERE sourceId = :sourceId AND kind != 'GUIDE'")
    fun observeKeys(sourceId: Long): Flow<List<String>>

    @Query("SELECT itemKey FROM favorites WHERE sourceId = :sourceId AND kind = :kind")
    fun observeKeys(sourceId: Long, kind: String): Flow<List<String>>

    @Query("SELECT IFNULL(MAX(position), -1) FROM favorites WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun maxPosition(sourceId: Long, kind: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND kind = :kind AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, kind: String, itemKey: String): Int

    @Query("UPDATE favorites SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    /** Change la clé d'un favori. @return 0 si un autre favori porte déjà cette clé (rien n'est changé). */
    @Query("UPDATE OR IGNORE favorites SET itemKey = :itemKey WHERE id = :id")
    suspend fun setKey(id: Long, itemKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideAll(groups: List<HiddenGroupEntity>)

    /** Remplace les éléments masqués d'un type (filtres des réglages) en une seule écriture. */
    @androidx.room.Transaction
    suspend fun replace(sourceId: Long, kind: String, titles: Collection<String>) {
        showAll(sourceId, kind)
        if (titles.isNotEmpty()) hideAll(titles.map { HiddenGroupEntity(sourceId, kind, it) })
    }

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

/** Chaînes du guide absentes de la playlist (section Foot). */
@Dao
interface GuideOnlyChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<GuideOnlyChannelEntity>)

    @Query("DELETE FROM guide_only_channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("SELECT * FROM guide_only_channels WHERE sourceId = :sourceId")
    suspend fun list(sourceId: Long): List<GuideOnlyChannelEntity>
}

/** Affiche TMDB en cache d'un titre (`null` : TMDB ne le connaît pas, ou sans affiche). */
data class CachedPoster(val cacheKey: String, val posterUrl: String?)

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_cache WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): TmdbCacheEntity?

    /** Retire les fiches lues avant [before] (titres qui ne sont plus consultés) ; rend leur nombre. */
    @Query("DELETE FROM tmdb_cache WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Affiches des fiches en cache (et titres que TMDB ne connaît pas : [CachedPoster.posterUrl] vide). */
    @Query("SELECT cacheKey, CASE WHEN found = 1 THEN posterUrl ELSE NULL END AS posterUrl FROM tmdb_cache")
    suspend fun posters(): List<CachedPoster>

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
     * Programmes de la grille du guide dans la fenêtre [from, to[ : comme [inWindow], sans la description. La
     * grille couvre deux jours de centaines de chaînes : leurs descriptions (le gros du texte) pesaient des
     * dizaines de Mo en mémoire pour n'en afficher qu'une à la fois.
     */
    @Query(
        """
        SELECT p.id, p.epgChannelId, p.startMillis, p.stopMillis, p.title, p.subtitle, p.category, p.year, p.imageUrl
        FROM programmes p
        JOIN sources s ON s.id = p.sourceId AND p.generation = s.epgGeneration
        WHERE p.sourceId = :sourceId AND p.stopMillis > :from AND p.startMillis < :to
          AND p.epgChannelId IN (:epgChannelIds)
        ORDER BY p.epgChannelId, p.startMillis
        """,
    )
    suspend fun gridWindow(sourceId: Long, epgChannelIds: List<String>, from: Long, to: Long): List<GridProgrammeRow>

    /** Description d'un programme de la grille (`null` : aucune, ou programme remplacé par un nouvel import du guide). */
    @Query("SELECT description FROM programmes WHERE id = :id")
    suspend fun description(id: Long): String?

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
