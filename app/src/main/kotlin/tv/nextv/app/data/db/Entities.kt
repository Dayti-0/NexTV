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
        Index(value = ["sourceId", "kind", "seriesName"]),
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
    /** Clé commune aux versions (qualités) d'une même chaîne. */
    val variantKey: String = "",
    /** [tv.nextv.core.model.StreamQuality] annoncée par le nom, si présente. */
    val quality: String? = null,
    val year: Int? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Rang de qualité ([tv.nextv.core.model.StreamQuality.rankOf]) pour choisir la meilleure version. */
    val qualityRank: Int = 2,
    /** Version affichée dans les listes (la meilleure de sa chaîne). */
    val isPrimary: Boolean = true,
    /** Nombre de versions (qualités) de la chaîne. */
    val variantCount: Int = 1,
    /** Clé stable (favoris, cache TMDB) : ne change pas d'une actualisation à l'autre. */
    val itemKey: String = "",
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
