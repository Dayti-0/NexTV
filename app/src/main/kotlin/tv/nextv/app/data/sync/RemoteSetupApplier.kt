package tv.nextv.app.data.sync

import tv.nextv.app.data.db.HiddenGroupDao
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.PlaylistStore
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.sync.RemoteConfig
import tv.nextv.core.sync.SyncKeys

/**
 * Configuration reçue d'une autre télé (Réglages › Configuration à distance) appliquée à cette télé : playlist ajoutée
 * et choisie (ou mise à jour si la télé a déjà une playlist de ce fournisseur : favoris et reprises gardés), réglages,
 * clés des services et catégories masquées. La configuration rapide n'est plus proposée : la télé est prête.
 */
class RemoteSetupApplier(
    private val playlistStore: PlaylistStore,
    private val sources: SourceRepository,
    private val hiddenGroupDao: HiddenGroupDao,
    private val settings: SettingsRepository,
) {
    /**
     * @return identifiant de la playlist, à charger.
     * @throws tv.nextv.core.repository.InvalidSourceInputException adresse de playlist refusée.
     */
    suspend fun apply(config: RemoteConfig): Long {
        val playlist = config.playlist
        val input = SourceInput(
            name = playlist.name,
            location = SourceLocation.Remote(playlist.url),
            // Le guide des programmes est commun à toutes les playlists (comme à l'ajout d'une playlist).
            epg = EpgSettings(location = null, useHeaderUrls = false),
            userAgent = playlist.userAgent,
            credentials = playlist.username?.let { user -> playlist.password?.let { CredentialsChange.Replace(Credentials(user, it)) } }
                ?: CredentialsChange.Remove,
            mirrors = playlist.mirrors,
        )
        // Même fournisseur déjà sur la télé (code expiré remplacé) : sa playlist est mise à jour.
        val scope = SyncKeys.scopeOfUrl(playlist.url)
        val existing = scope?.let { wanted ->
            playlistStore.getSources().firstOrNull { source ->
                val url = (source.location as? SourceLocation.Remote)?.url ?: return@firstOrNull false
                SyncKeys.scopeOfUrl(url) == wanted || source.mirrors.any { SyncKeys.scopeOfUrl(it) == wanted }
            }
        }
        val id = if (existing != null) {
            sources.update(existing.id, input.copy(name = input.name.ifBlank { existing.name }))
            existing.id
        } else {
            sources.add(input)
        }
        settings.setActivePlaylist(id)

        if (config.settings.isNotEmpty()) settings.applySyncedPreferences(config.settings)
        for ((name, value) in config.serviceKeys) {
            val key = value.trim().takeIf { it.isNotEmpty() } ?: continue
            // Clé illisible pour le coffre de l'appareil : la télé est installée sans elle (saisie possible ensuite).
            runCatching {
                when (name) {
                    TMDB -> settings.setTmdbApiKey(key)
                    FOOTBALL -> settings.setFootballApiKey(key)
                    AUDD -> settings.setAuddApiKey(key)
                }
            }
        }
        config.hidden?.let { hidden ->
            for (kind in HIDDEN_KINDS) {
                hiddenGroupDao.replace(id, kind, hidden.filter { it.kind == kind }.mapTo(LinkedHashSet()) { it.title })
            }
        }
        settings.setQuickSetupDone()
        return id
    }

    companion object {
        const val TMDB = "tmdb"
        const val FOOTBALL = "football"
        const val AUDD = "audd"

        /** Catégories masquées envoyées : chaînes, films, séries (les filtres des réglages suivent les réglages). */
        val HIDDEN_KINDS = listOf(ContentKind.LIVE.name, ContentKind.MOVIE.name, ContentKind.SERIES.name)
    }
}
