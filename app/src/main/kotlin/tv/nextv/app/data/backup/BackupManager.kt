package tv.nextv.app.data.backup

import tv.nextv.app.data.catalog.FavoritesRepository
import kotlinx.coroutines.flow.first
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.app.data.db.FavoriteDao
import tv.nextv.app.data.db.FavoriteEntity
import tv.nextv.app.data.db.HiddenGroupDao
import tv.nextv.app.data.db.HiddenGroupEntity
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.core.backup.Backup
import tv.nextv.core.backup.BackupCodec
import tv.nextv.core.backup.FavoriteBackup
import tv.nextv.core.backup.HiddenGroupBackup
import tv.nextv.core.backup.PlaylistBackup
import tv.nextv.core.backup.ProgressBackup
import tv.nextv.core.backup.WatchedBackup
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.InvalidSourceInputException
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceRepository

/** Résultat d'une restauration : playlists ajoutées sur celles de la sauvegarde. */
data class RestoreResult(val added: Int, val total: Int)

/**
 * Sauvegarde et restauration : playlists distantes (avec leurs identifiants), favoris, catégories
 * masquées, reprises de lecture, « Déjà vu » et réglages. Une playlist déjà présente (même adresse) est
 * complétée, pas dupliquée.
 *
 * @param inTransaction exécute un bloc d'écritures de la base des playlists d'un seul tenant (tout ou rien).
 */
class BackupManager(
    private val playlistStore: RoomPlaylistStore,
    private val sources: SourceRepository,
    private val favoriteDao: FavoriteDao,
    private val hiddenGroupDao: HiddenGroupDao,
    private val settings: SettingsRepository,
    private val progress: ProgressRepository,
    private val watched: WatchedRepository,
    private val channelDao: ChannelDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun export(): String {
        val active = settings.activePlaylistId.first()
        val playlists = playlistStore.getSources().mapNotNull { source ->
            // Les fichiers de l'appareil ne se transportent pas d'un appareil à l'autre.
            val url = (source.location as? SourceLocation.Remote)?.url ?: return@mapNotNull null
            PlaylistBackup(
                name = source.name,
                url = url,
                userAgent = source.userAgent,
                mirrors = source.mirrors,
                credentials = if (source.hasCredentials) sources.credentialsFor(source.id) else null,
                active = source.id == active,
                // Favoris de chaque type, et chaînes de « L'essentiel » du guide.
                favorites = (ContentKind.entries.map { it.name } + FavoritesRepository.GUIDE_SELECTION)
                    .flatMap { kind -> favoriteDao.list(source.id, kind) }
                    .map { FavoriteBackup(it.kind, it.itemKey, it.title, it.logoUrl, it.position) },
                hiddenGroups = ContentKind.entries.flatMap { kind ->
                    hiddenGroupDao.observeTitles(source.id, kind.name).first().map { HiddenGroupBackup(kind.name, it) }
                },
                progress = progress.all(source.id).map {
                    ProgressBackup(it.itemKey, it.kind, it.title, it.episodeLabel, it.logoUrl, it.positionMs, it.durationMs, it.updatedAt)
                },
                watched = watched.all(source.id).map { WatchedBackup(it.itemKey, it.watchedAt) },
            )
        }
        return BackupCodec.encode(Backup(clock(), playlists, settings.exportPreferences()))
    }

    /** @throws tv.nextv.core.backup.BackupFormatException si le fichier n'est pas une sauvegarde NexTV. */
    suspend fun restore(text: String): RestoreResult {
        val backup = BackupCodec.decode(text)
        val existing = playlistStore.getSources()
        var added = 0
        var activeId: Long? = null
        for (playlist in backup.playlists) {
            val id = existing.firstOrNull { (it.location as? SourceLocation.Remote)?.url == playlist.url }?.id
                ?: try {
                    sources.add(
                        SourceInput(
                            name = playlist.name,
                            location = SourceLocation.Remote(playlist.url),
                            // Le guide des programmes est commun à toutes les playlists.
                            epg = EpgSettings(location = null, useHeaderUrls = false),
                            userAgent = playlist.userAgent,
                            credentials = playlist.credentials?.let { CredentialsChange.Replace(it) } ?: CredentialsChange.Keep,
                            mirrors = playlist.mirrors,
                        ),
                    ).also { added++ }
                } catch (e: InvalidSourceInputException) {
                    continue
                }
            // Favoris et catégories masquées d'une playlist : d'un seul tenant (tout, ou rien si l'écriture échoue).
            inTransaction {
                for (favorite in playlist.favorites) {
                    favoriteDao.insert(
                        FavoriteEntity(
                            sourceId = id,
                            kind = favorite.kind,
                            itemKey = favorite.itemKey,
                            title = favorite.title,
                            logoUrl = favorite.logoUrl,
                            position = favorite.position,
                        ),
                    )
                }
                playlist.hiddenGroups.forEach { hiddenGroupDao.hide(HiddenGroupEntity(id, it.kind, it.title)) }
            }
            // Reprises : le film ou l'épisode (numéro de ligne, propre à chaque appareil) est retrouvé par sa clé,
            // tout de suite si la playlist est déjà chargée, sinon après son premier chargement.
            for (item in playlist.progress) {
                if (item.kind != ContentKind.MOVIE.name && item.kind != ContentKind.SERIES.name) continue
                progress.restore(
                    ProgressEntity(
                        sourceId = id,
                        itemKey = item.itemKey,
                        kind = item.kind,
                        channelId = UNLINKED,
                        title = item.title,
                        episodeLabel = item.episodeLabel,
                        logoUrl = item.logoUrl,
                        positionMs = item.positionMs,
                        durationMs = item.durationMs,
                        updatedAt = item.updatedAt,
                    ),
                )
            }
            progress.relink(id, channelDao)
            runCatching { playlist.watched.forEach { watched.restore(id, it.itemKey, it.watchedAt) } }
            if (playlist.active) activeId = id
        }
        settings.importPreferences(backup.settings)
        activeId?.let { settings.setActivePlaylist(it) }
        return RestoreResult(added = added, total = backup.playlists.size)
    }

    private companion object {
        /** Reprise restaurée pas encore rattachée à son titre (aucune ligne ne porte ce numéro). */
        const val UNLINKED = 0L
    }
}
