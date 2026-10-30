package tv.nextv.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Réglages de l'application. Les identifiants du proxy sont chiffrés comme ceux des sources. */
class SettingsRepository(
    context: Context,
    private val cipher: KeystoreSecretCipher,
) {
    private val dataStore = context.applicationContext.settingsDataStore

    val proxySettings: Flow<ProxySettings> = dataStore.data
        .map { prefs ->
            ProxySettings(
                enabled = prefs[PROXY_ENABLED] ?: false,
                type = prefs[PROXY_TYPE]?.let { name -> ProxyType.entries.firstOrNull { it.name == name } } ?: ProxyType.HTTP,
                host = prefs[PROXY_HOST].orEmpty(),
                port = prefs[PROXY_PORT] ?: ProxySettings.DEFAULT_PORT,
                credentials = prefs[PROXY_CREDENTIALS]?.let(cipher::decryptCredentials),
                scopes = prefs[PROXY_SCOPES]
                    ?.mapNotNull { name -> ProxyScope.entries.firstOrNull { it.name == name } }
                    ?.toSet()
                    ?: ProxyScope.entries.toSet(),
                bypassLocalAddresses = prefs[PROXY_BYPASS_LOCAL] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun currentProxySettings(): ProxySettings = proxySettings.first()

    /** Playlist chargée au démarrage (la dernière utilisée). */
    val activePlaylistId: Flow<Long?> = dataStore.data.map { it[ACTIVE_PLAYLIST] }.distinctUntilChanged()

    suspend fun setActivePlaylist(id: Long?) {
        dataStore.edit { prefs ->
            if (id != null) {
                prefs[ACTIVE_PLAYLIST] = id
            } else {
                prefs.remove(ACTIVE_PLAYLIST)
            }
        }
    }

    /** Basculement automatique de qualité (FHD → HD → SD → LOW et retour). */
    val autoQuality: Flow<Boolean> = dataStore.data.map { it[AUTO_QUALITY] ?: true }.distinctUntilChanged()

    suspend fun setAutoQuality(enabled: Boolean) {
        dataStore.edit { it[AUTO_QUALITY] = enabled }
    }

    /**
     * Décodeur vidéo choisi par type de flux (`video/hevc`, `video/avc`…) ; absent : choix automatique
     * (matériel en priorité, repli sur un autre décodeur en cas d'échec).
     */
    val preferredDecoders: Flow<Map<String, String>> = dataStore.data
        .map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val mime = key.name.removePrefix(DECODER_PREFIX).takeIf { key.name.startsWith(DECODER_PREFIX) }
                if (mime != null && value is String) mime to value else null
            }.toMap()
        }
        .distinctUntilChanged()

    suspend fun setPreferredDecoder(mimeType: String, decoderName: String?) {
        val key = stringPreferencesKey(DECODER_PREFIX + mimeType)
        dataStore.edit { prefs ->
            if (decoderName != null) {
                prefs[key] = decoderName
            } else {
                prefs.remove(key)
            }
        }
    }

    /** Éléments par ligne des grilles de l'accueil (TV, tablette). */
    val gridColumns: Flow<GridColumns> = dataStore.data
        .map { prefs ->
            GridColumns(
                channels = (prefs[CHANNEL_COLUMNS] ?: GridColumns.DEFAULT_CHANNELS).coerceIn(GridColumns.CHANNELS),
                posters = (prefs[POSTER_COLUMNS] ?: GridColumns.DEFAULT_POSTERS).coerceIn(GridColumns.POSTERS),
            )
        }
        .distinctUntilChanged()

    suspend fun setGridColumns(columns: GridColumns) {
        dataStore.edit { prefs ->
            prefs[CHANNEL_COLUMNS] = columns.channels.coerceIn(GridColumns.CHANNELS)
            prefs[POSTER_COLUMNS] = columns.posters.coerceIn(GridColumns.POSTERS)
        }
    }

    /** Aperçu vidéo (sans le son) de la chaîne sélectionnée à l'accueil. */
    val channelPreview: Flow<Boolean> = dataStore.data.map { it[CHANNEL_PREVIEW] ?: true }.distinctUntilChanged()

    suspend fun setChannelPreview(enabled: Boolean) {
        dataStore.edit { it[CHANNEL_PREVIEW] = enabled }
    }

    /** Chaînes regardées récemment dans une playlist (clés des chaînes, la plus récente en premier). */
    fun recentChannels(sourceId: Long): Flow<List<String>> = dataStore.data
        .map { prefs -> prefs[recentKey(sourceId)]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty() }
        .distinctUntilChanged()

    suspend fun addRecentChannel(sourceId: Long, itemKey: String) {
        val key = recentKey(sourceId)
        dataStore.edit { prefs ->
            val previous = prefs[key]?.split('\n')?.filter { it.isNotEmpty() && it != itemKey }.orEmpty()
            prefs[key] = (listOf(itemKey) + previous).take(MAX_RECENT_CHANNELS).joinToString("\n")
        }
    }

    private fun recentKey(sourceId: Long) = stringPreferencesKey(RECENT_PREFIX + sourceId)

    /** Résolution des noms (DNS du système ou résolveur public en DNS sur HTTPS). */
    val dnsProvider: Flow<DnsProvider> = dataStore.data
        .map { prefs -> prefs[DNS_PROVIDER]?.let { name -> DnsProvider.entries.firstOrNull { it.name == name } } ?: DnsProvider.SYSTEM }
        .distinctUntilChanged()

    suspend fun setDnsProvider(provider: DnsProvider) {
        dataStore.edit { it[DNS_PROVIDER] = provider.name }
    }

    /** Clé TMDB saisie par l'utilisateur (chiffrée sur l'appareil). */
    val tmdbApiKey: Flow<String?> = dataStore.data
        .map { prefs -> prefs[TMDB_KEY]?.let(cipher::decrypt) }
        .distinctUntilChanged()

    suspend fun setTmdbApiKey(key: String?) {
        val encrypted = key?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        dataStore.edit { prefs ->
            if (encrypted != null) {
                prefs[TMDB_KEY] = encrypted
            } else {
                prefs.remove(TMDB_KEY)
            }
        }
    }

    suspend fun saveProxySettings(settings: ProxySettings) {
        val encryptedCredentials = settings.credentials?.let(cipher::encryptCredentials)
        dataStore.edit { prefs ->
            if (encryptedCredentials != null) {
                prefs[PROXY_CREDENTIALS] = encryptedCredentials
            } else {
                prefs.remove(PROXY_CREDENTIALS)
            }
            prefs[PROXY_ENABLED] = settings.enabled
            prefs[PROXY_TYPE] = settings.type.name
            prefs[PROXY_HOST] = settings.host.trim()
            prefs[PROXY_PORT] = settings.port
            prefs[PROXY_SCOPES] = settings.scopes.map { it.name }.toSet()
            prefs[PROXY_BYPASS_LOCAL] = settings.bypassLocalAddresses
        }
    }

    private companion object {
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_CREDENTIALS = stringPreferencesKey("proxy_credentials")
        val PROXY_SCOPES = stringSetPreferencesKey("proxy_scopes")
        val PROXY_BYPASS_LOCAL = booleanPreferencesKey("proxy_bypass_local")
        val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist")
        val AUTO_QUALITY = booleanPreferencesKey("auto_quality")
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val CHANNEL_COLUMNS = intPreferencesKey("channel_columns")
        val DNS_PROVIDER = stringPreferencesKey("dns_provider")
        val POSTER_COLUMNS = intPreferencesKey("poster_columns")
        val CHANNEL_PREVIEW = booleanPreferencesKey("channel_preview")
        const val DECODER_PREFIX = "decoder_"
        const val RECENT_PREFIX = "recent_channels_"
        const val MAX_RECENT_CHANNELS = 30
    }
}

/** Éléments par ligne des grilles de l'accueil sur TV et tablette (le téléphone garde sa disposition). */
data class GridColumns(val channels: Int = DEFAULT_CHANNELS, val posters: Int = DEFAULT_POSTERS) {
    companion object {
        val CHANNELS = 3..9
        val POSTERS = 4..10
        const val DEFAULT_CHANNELS = 5
        const val DEFAULT_POSTERS = 6
    }
}
