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
    indices = [Index(value = ["sourceId", "position"]), Index(value = ["sourceId", "groupTitle"])],
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
