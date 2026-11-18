package tv.nextv.app.data.sync

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.InvalidSourceInputException
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.sync.SyncApplied
import tv.nextv.core.sync.SyncKeys
import tv.nextv.core.sync.SyncLocal
import tv.nextv.core.sync.SyncSnapshot

/**
 * Éléments de la télé partagés avec les télés reliées, lus et écrits dans les bases et les réglages : réglages (sauf
 * ceux propres à l'appareil), clés des services, playlists (pour une télé qui n'a encore rien de ce fournisseur),
 * favoris (et « L'essentiel » du guide), catégories masquées, « Déjà vu » et reprises de lecture.
 *
 * Les éléments d'une playlist sont rangés sous la portée de son fournisseur ([SyncKeys.scopeOfUrl]) : chaque télé
 * garde son propre compte (une seule connexion par abonnement), mais deux comptes du même fournisseur partagent
 * favoris et « Déjà vu ». Une playlist d'un fichier de l'appareil n'est pas partagée.
 */
class RoomSyncLocal(
    private val playlistStore: RoomPlaylistStore,
    private val sources: SourceRepository,
    private val favoriteDao: FavoriteDao,
    private val hiddenGroupDao: HiddenGroupDao,
    private val settings: SettingsRepository,
    private val progress: ProgressRepository,
    private val watched: WatchedRepository,
    private val channelDao: ChannelDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : SyncLocal {

    /** Playlist distante de la télé pour chaque fournisseur (la playlist active d'abord, s'il y en a plusieurs). */
    private suspend fun localSources(): Map<String, PlaylistSource> {
        val active = settings.activePlaylistId.first()
        val result = LinkedHashMap<String, PlaylistSource>()
        for (source in playlistStore.getSources().sortedBy { if (it.id == active) 0 else 1 }) {
            val url = (source.location as? SourceLocation.Remote)?.url ?: continue
            val scope = SyncKeys.scopeOfUrl(url) ?: continue
            result.putIfAbsent(scope, source)
        }
        return result
    }

    override suspend fun snapshot(): SyncSnapshot {
        val values = HashMap<String, String>()
        settings.syncedPreferences().forEach { (name, value) -> values[SyncKeys.setting(name)] = value }
        serviceKeys().forEach { (name, value) -> values[SyncKeys.serviceKey(name)] = value }
        val locals = localSources()
        for ((scope, source) in locals) {
            values[SyncKeys.playlist(scope)] = playlistJson(source)
            for (favorite in favoriteDao.all(source.id)) {
                values[SyncKeys.favorite(scope, favorite.kind, favorite.itemKey)] =
                    JSONObject().put("t", favorite.title).put("l", favorite.logoUrl ?: JSONObject.NULL).put("p", favorite.position).toString()
            }
            for (group in hiddenGroupDao.all(source.id)) values[SyncKeys.hidden(scope, group.kind, group.title)] = "1"
            for (item in watched.all(source.id)) values[SyncKeys.watched(scope, item.itemKey)] = item.watchedAt.toString()
            for (item in progress.all(source.id)) values[SyncKeys.resume(scope, item.itemKey)] = progressJson(item)
        }
        return SyncSnapshot(values, locals.keys)
    }

    override suspend fun apply(target: Map<String, String>, current: SyncSnapshot, ignoredScopes: Set<String>): SyncApplied {
        var applied = addPlaylists(target, ignoredScopes)
        val locals = localSources()

        // Réglages et clés des services.
        val settingChanges = changes(target, current.values) { SyncKeys.parts(it)[0] == SyncKeys.SETTING }
        val settingCount = settings.applySyncedPreferences(settingChanges.mapKeys { (key, _) -> SyncKeys.parts(key)[1] })
        val keyChanges = changes(target, current.values) { SyncKeys.parts(it)[0] == SyncKeys.SERVICE_KEY && SyncKeys.parts(it)[1] in SERVICES }
        for ((key, value) in keyChanges) setServiceKey(SyncKeys.parts(key)[1], value?.removePrefix("s:"))
        applied += SyncApplied(changes = settingCount + keyChanges.size)

        // Éléments de chaque playlist présente ici.
        for ((scope, source) in locals) {
            val inScope = { key: String -> SyncKeys.scopeOf(key) == scope }
            val favorites = changes(target, current.values) { inScope(it) && SyncKeys.parts(it)[0] == SyncKeys.FAVORITE }
            val hidden = changes(target, current.values) { inScope(it) && SyncKeys.parts(it)[0] == SyncKeys.HIDDEN }
            if (favorites.isNotEmpty() || hidden.isNotEmpty()) {
                inTransaction {
                    for ((key, value) in favorites) {
                        val (_, _, kind, itemKey) = SyncKeys.parts(key)
                        favoriteDao.delete(source.id, kind, itemKey)
                        val json = value?.let(::parse) ?: continue
                        favoriteDao.insert(
                            FavoriteEntity(
                                sourceId = source.id,
                                kind = kind,
                                itemKey = itemKey,
                                title = json.optString("t"),
                                logoUrl = json.optStringOrNull("l"),
                                position = json.optInt("p"),
                            ),
                        )
                    }
                    for ((key, value) in hidden) {
                        val (_, _, kind, title) = SyncKeys.parts(key)
                        if (value != null) hiddenGroupDao.hide(HiddenGroupEntity(source.id, kind, title)) else hiddenGroupDao.show(source.id, kind, title)
                    }
                }
            }
            val seen = changes(target, current.values) { inScope(it) && SyncKeys.parts(it)[0] == SyncKeys.WATCHED }
            for ((key, value) in seen) {
                val itemKey = SyncKeys.parts(key)[2]
                val at = value?.toLongOrNull()
                if (at != null) watched.put(source.id, itemKey, at) else watched.unmark(source.id, itemKey)
            }
            val resumes = changes(target, current.values) { inScope(it) && SyncKeys.parts(it)[0] == SyncKeys.RESUME }
            for ((key, value) in resumes) {
                val itemKey = SyncKeys.parts(key)[2]
                val entity = value?.let { progressOf(source.id, itemKey, it) }
                if (entity != null) progress.replace(entity) else progress.clear(source.id, itemKey)
            }
            // Reprise venue d'ailleurs : son film ou son épisode retrouvé dans la playlist de cette télé.
            if (resumes.isNotEmpty()) progress.relink(source.id, channelDao)
            applied += SyncApplied(changes = favorites.size + hidden.size + seen.size + resumes.size)
        }
        return applied
    }

    /** Playlist d'un fournisseur que la télé n'a pas encore (télé neuve) : ajoutée, et choisie s'il n'y en avait pas. */
    private suspend fun addPlaylists(target: Map<String, String>, ignoredScopes: Set<String>): SyncApplied {
        val locals = localSources()
        val localHosts = locals.values.flatMap(::hostsOf).toSet()
        var added = 0
        var activated: Long? = null
        for ((key, value) in target) {
            if (!SyncKeys.isPlaylist(key)) continue
            val scope = SyncKeys.parts(key)[1]
            if (scope in locals || scope in ignoredScopes) continue
            val json = parse(value) ?: continue
            val url = json.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mirrors = json.optJSONArray("mirrors").strings()
            // Même fournisseur sous une autre adresse (serveur de secours) : la télé a déjà le sien.
            if ((listOf(url) + mirrors).mapNotNull(SyncKeys::scopeOfUrl).any { it in localHosts }) continue
            val user = json.optStringOrNull("user")
            val password = json.optStringOrNull("pass")
            val id = try {
                sources.add(
                    SourceInput(
                        name = json.optString("name").ifBlank { scope },
                        location = SourceLocation.Remote(url),
                        // Le guide des programmes est commun à toutes les playlists.
                        epg = EpgSettings(location = null, useHeaderUrls = false),
                        userAgent = json.optStringOrNull("ua"),
                        credentials = if (user != null && password != null) CredentialsChange.Replace(Credentials(user, password)) else CredentialsChange.Keep,
                        mirrors = mirrors,
                    ),
                )
            } catch (e: InvalidSourceInputException) {
                continue
            }
            added++
            val active = settings.activePlaylistId.first()
            if (active == null || playlistStore.getSource(active) == null) {
                settings.setActivePlaylist(id)
                activated = id
            }
        }
        return SyncApplied(playlistsAdded = added, activatedPlaylist = activated)
    }

    private suspend fun playlistJson(source: PlaylistSource): String {
        val credentials = if (source.hasCredentials) sources.credentialsFor(source.id) else null
        return JSONObject()
            .put("name", source.name)
            .put("url", (source.location as SourceLocation.Remote).url)
            .put("ua", source.userAgent ?: JSONObject.NULL)
            .put("mirrors", JSONArray(source.mirrors))
            .put("user", credentials?.username ?: JSONObject.NULL)
            .put("pass", credentials?.password ?: JSONObject.NULL)
            .toString()
    }

    private fun hostsOf(source: PlaylistSource): List<String> =
        (listOfNotNull((source.location as? SourceLocation.Remote)?.url) + source.mirrors).mapNotNull(SyncKeys::scopeOfUrl)

    private fun progressJson(item: ProgressEntity): String = JSONObject()
        .put("k", item.kind)
        .put("t", item.title)
        .put("e", item.episodeLabel ?: JSONObject.NULL)
        .put("l", item.logoUrl ?: JSONObject.NULL)
        .put("p", item.positionMs)
        .put("d", item.durationMs)
        .put("u", item.updatedAt)
        .toString()

    private fun progressOf(sourceId: Long, itemKey: String, text: String): ProgressEntity? {
        val json = parse(text) ?: return null
        return ProgressEntity(
            sourceId = sourceId,
            itemKey = itemKey,
            kind = json.optString("k"),
            channelId = UNLINKED,
            title = json.optString("t"),
            episodeLabel = json.optStringOrNull("e"),
            logoUrl = json.optStringOrNull("l"),
            positionMs = json.optLong("p"),
            durationMs = json.optLong("d"),
            updatedAt = json.optLong("u"),
        )
    }

    private suspend fun serviceKeys(): Map<String, String> = buildMap {
        settings.tmdbApiKey.first()?.let { put(TMDB, "s:$it") }
        settings.footballApiKey.first()?.let { put(FOOTBALL, "s:$it") }
        settings.auddApiKey.first()?.let { put(AUDD, "s:$it") }
    }

    private suspend fun setServiceKey(name: String, value: String?) {
        when (name) {
            TMDB -> settings.setTmdbApiKey(value)
            FOOTBALL -> settings.setFootballApiKey(value)
            AUDD -> settings.setAuddApiKey(value)
        }
    }

    private companion object {
        const val TMDB = "tmdb"
        const val FOOTBALL = "football"
        const val AUDD = "audd"
        val SERVICES = setOf(TMDB, FOOTBALL, AUDD)

        /** Reprise pas encore rattachée à son titre (aucune ligne ne porte ce numéro). */
        const val UNLINKED = 0L

        /** Clés de [target] dont la valeur diffère de [current], et clés de [current] absentes de [target] (`null`). */
        fun changes(target: Map<String, String>, current: Map<String, String>, accept: (String) -> Boolean): Map<String, String?> {
            val result = HashMap<String, String?>()
            for ((key, value) in target) if (accept(key) && current[key] != value) result[key] = value
            for (key in current.keys) if (accept(key) && key !in target) result[key] = null
            return result
        }

        fun parse(text: String): JSONObject? = try {
            JSONObject(text)
        } catch (e: JSONException) {
            null
        }

        fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { has(name) }

        fun JSONArray?.strings(): List<String> = this?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
    }
}
