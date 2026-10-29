package tv.nextv.core.source

import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.model.Channel
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceType
import tv.nextv.core.model.SourceWarning

/** Résultat du chargement d'une source de chaînes. */
data class LoadedPlaylist(
    val channels: List<Channel>,
    /** Guides EPG annoncés par la source elle-même. */
    val epgUrls: List<String> = emptyList(),
    val warnings: Set<SourceWarning> = emptySet(),
)

/**
 * Charge les chaînes d'un [SourceType] donné.
 *
 * Point d'extension pour de futurs types de sources (Xtream Codes, Stalker, fichiers JSON…) :
 * il suffit d'implémenter cette interface et de l'enregistrer dans le [PlaylistLoaderRegistry].
 */
interface PlaylistLoader {
    val type: SourceType

    /** @throws SourceException */
    suspend fun load(source: PlaylistSource, credentials: Credentials?): LoadedPlaylist
}

class PlaylistLoaderRegistry(loaders: Iterable<PlaylistLoader>) {

    private val byType = loaders.associateBy { it.type }

    val supportedTypes: Set<SourceType> get() = byType.keys

    fun get(type: SourceType): PlaylistLoader =
        byType[type] ?: throw SourceException(SourceError.UnsupportedSourceType(type.id))
}

/** Limites de taille appliquées au téléchargement puis au contenu décompressé. */
data class ContentLimits(
    val maxDownloadBytes: Long,
    val maxDecodedBytes: Long,
) {
    companion object {
        val PLAYLIST = ContentLimits(maxDownloadBytes = 200L * MB, maxDecodedBytes = 400L * MB)
        val EPG = ContentLimits(maxDownloadBytes = 500L * MB, maxDecodedBytes = 2048L * MB)
        private const val MB = 1024L * 1024L
    }
}
