package tv.nextv.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val locationKind: String,
    val location: String,
    val locationName: String?,
    val epgLocationKind: String?,
    val epgLocation: String?,
    val epgLocationName: String?,
    val epgUseHeaderUrls: Boolean,
    val userAgent: String?,
    val hasCredentials: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val channelCount: Int = 0,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    /** Noms de [tv.nextv.core.model.SourceWarning] séparés par des virgules. */
    val warnings: String = "",
    /** URL séparées par des retours à la ligne. */
    val headerEpgUrls: String = "",
    val epgLastAttemptAt: Long? = null,
    val epgLastSuccessAt: Long? = null,
    val epgProgrammeCount: Int = 0,
    val epgMatchedCount: Int = 0,
    val epgErrorCode: String? = null,
    val epgErrorDetail: String? = null,
    /** Génération de programmes actuellement visible (voir [ProgrammeEntity.generation]). */
    val epgGeneration: Long = 0,
    /** Serveurs de secours (« http://hôte:port ») séparés par des retours à la ligne. */
    val mirrors: String = "",
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["sourceId", "position"]),
        Index(value = ["sourceId", "kind", "groupTitle"]),
        Index(value = ["sourceId", "variantKey"]),
        Index(value = ["sourceId", "kind", "itemKey", "position"]),
        Index(value = ["sourceId", "listOrder"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val position: Int,
    val name: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val tvgId: String?,
    val tvgName: String?,
    val channelNumber: String?,
    val streamUrl: String,
    /** En-têtes encodés par [HeaderCodec]. */
    val streamHeaders: String,
    val streamFormat: String,
    val streamSupport: String,
    /** Noms de [tv.nextv.core.model.StreamIssue] séparés par des virgules. */
    val streamIssues: String,
    val epgChannelId: String? = null,
    /** [tv.nextv.core.model.ContentKind] : LIVE, MOVIE ou SERIES. */
    val kind: String = "LIVE",
    /** Titre nettoyé (sans qualité, année ni préfixe de langue). */
    val title: String = name,
    /**
     * Clé commune aux versions (qualités) d'une même chaîne. Chaînes rassemblées (réglage « Rassembler les chaînes »)
     * : celle de la clé stable ([itemKey] sans « live: ») ; sinon, une version double audio ou Dolby ([audio]) a la
     * sienne (voir LiveVariants.splitKey).
     */
    val variantKey: String = "",
    /** [tv.nextv.core.model.StreamQuality] annoncée par le nom, si présente. */
    val quality: String? = null,
    val year: Int? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Rang de qualité ([tv.nextv.core.model.StreamQuality.rankOf]) pour choisir la meilleure version. */
    val qualityRank: Int = 2,
    /** Version affichée dans les listes (la meilleure de sa chaîne ; pour une série, son premier épisode). */
    val isPrimary: Boolean = true,
    /** Nombre de versions (qualités) de la chaîne ; pour le premier épisode d'une série, nombre d'épisodes. */
    val variantCount: Int = 1,
    /** Clé stable (favoris, cache TMDB) : ne change pas d'une actualisation à l'autre. */
    val itemKey: String = "",
    /** Jours de programmes passés gardés par le serveur pour le replay (0 : pas de replay). */
    val catchupDays: Int = 0,
    /** Son annoncé par le nom d'une chaîne : 2 double audio, 1 Dolby, 0 sinon (version 12 du schéma). */
    val audio: Int = 0,
    /** Rang de la chaîne dans l'ordre rangé (TNT, Canal+, cinéma… voir ChannelLineup). */
    val lineup: Int = 0,
    /**
     * Rang dans les listes de chaînes : [lineup] quand les chaînes sont rassemblées, sinon [position] (ordre de la
     * playlist). Les films et les épisodes gardent leur [position].
     */
    val listOrder: Int = 0,
    /** Catégorie par thème de la chaîne quand les chaînes sont rassemblées (« Cinéma »), `null` : [groupTitle] (schéma 13). */
    val theme: String? = null,
    /**
     * Catégorie où la chaîne est rangée dans les listes : [theme] (ou à défaut [groupTitle]) quand les chaînes sont
     * rassemblées, sinon [groupTitle]. Les films et les épisodes gardent leur [groupTitle].
     */
    val listGroup: String? = groupTitle,
    /** Nom propre de la chaîne quand les chaînes sont rassemblées (« Ciné+ Frisson »), `null` : [baseTitle]. */
    val niceTitle: String? = null,
    /** Nom de la chaîne tel que la playlist l'écrit, sans mention de qualité ([title] quand elles ne sont pas rassemblées). */
    val baseTitle: String? = title,
)

/**
 * Favori. La clé ([itemKey]) ne dépend pas de l'identifiant de ligne, qui change à chaque
 * actualisation : clé de version pour une chaîne, titre (et année) pour un film, nom pour une série.
 */
@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sourceId", "kind", "itemKey"], unique = true), Index(value = ["sourceId", "kind", "position"])],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val kind: String,
    val itemKey: String,
    val title: String,
    val logoUrl: String?,
    val position: Int,
)

/**
 * Chaîne du guide sans chaîne de la playlist associée, dont des matchs ont été importés (version 8
 * du schéma) : la section Foot indique sur quelle chaîne passe un match, même absente de la playlist.
 */
@Entity(
    tableName = "guide_only_channels",
    primaryKeys = ["sourceId", "epgChannelId"],
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class GuideOnlyChannelEntity(
    val sourceId: Long,
    val epgChannelId: String,
    val name: String,
)

/**
 * Catégorie masquée (version 4 du schéma). [title] : nom du groupe, `''` pour les éléments sans
 * groupe ; il ne dépend pas des identifiants de ligne et reste valable après une actualisation.
 */
@Entity(
    tableName = "hidden_groups",
    primaryKeys = ["sourceId", "kind", "title"],
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class HiddenGroupEntity(
    val sourceId: Long,
    val kind: String,
    val title: String,
)

/** Fiche TMDB en cache (ou absence de résultat, [found] faux). */
@Entity(tableName = "tmdb_cache")
data class TmdbCacheEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val fetchedAt: Long,
    val tmdbId: Long = 0,
    val title: String = "",
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val voteCount: Int = 0,
    /** Genres séparés par « | ». */
    val genres: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val trailerKey: String? = null,
    /** Langue de la bande-annonce (« fr », « en »…), vide si inconnue, `null` : fiche lue avant qu'elle soit retenue. */
    val trailerLanguage: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonCount: Int? = null,
)

/**
 * Programme EPG. Un import écrit une nouvelle génération, rendue visible d'un coup au commit
 * (via [SourceEntity.epgGeneration]) : un import interrompu ne laisse jamais un guide à moitié remplacé.
 */
@Entity(
    tableName = "programmes",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sourceId", "generation", "epgChannelId", "startMillis"])],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val generation: Long,
    val epgChannelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    /** Année de production (version 3 du schéma). */
    val year: Int? = null,
    /** Image du programme donnée par le guide (version 9 du schéma). */
    val imageUrl: String? = null,
)

/** Identifiants chiffrés (AES-GCM, clé dans l'Android Keystore). */
@Entity(
    tableName = "source_credentials",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CredentialEntity(
    @PrimaryKey val sourceId: Long,
    val ciphertext: String,
)

/** Encodage `Nom: valeur` par ligne ; les valeurs sont déjà filtrées (pas de retour à la ligne). */
object HeaderCodec {
    fun encode(headers: Map<String, String>): String = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    fun decode(encoded: String): Map<String, String> = encoded.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .toMap()
}

/**
 * Qualité d'un film ou d'un épisode dont le nom n'en dit rien, mesurée (fichier lu) ou lue sur sa fiche du serveur
 * Xtream, par flux. Gardée à part : chaque actualisation de la playlist réécrit les chaînes et la reprend
 * ([RoomPlaylistStore.replaceChannels]). [quality] vide : le serveur a répondu sans la donner (pas redemandé).
 */
@Entity(
    tableName = "measured_quality",
    primaryKeys = ["sourceId", "streamUrl"],
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MeasuredQualityEntity(
    val sourceId: Long,
    val streamUrl: String,
    /** Nom de [tv.nextv.core.model.StreamQuality], ou vide si inconnue. */
    val quality: String,
    /** Mesurée en lisant le fichier (plus sûr que la fiche du serveur, qu'elle remplace). */
    val fromPlayback: Boolean,
    val measuredAt: Long,
)
