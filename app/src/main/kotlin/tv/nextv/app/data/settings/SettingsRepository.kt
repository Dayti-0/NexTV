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
import tv.nextv.core.backup.SettingBackup
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType
import java.security.MessageDigest

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

    /** Format de l'image dans le lecteur. */
    val aspectMode: Flow<AspectMode> = dataStore.data
        .map { prefs -> prefs[ASPECT_MODE]?.let { name -> AspectMode.entries.firstOrNull { it.name == name } } ?: AspectMode.FIT }
        .distinctUntilChanged()

    suspend fun setAspectMode(mode: AspectMode) {
        dataStore.edit { it[ASPECT_MODE] = mode.name }
    }

    /** Langue audio choisie en dernier (« fr », « en »…), proposée en premier aux lectures suivantes. */
    val audioLanguage: Flow<String?> = dataStore.data.map { it[AUDIO_LANGUAGE] }.distinctUntilChanged()

    suspend fun setAudioLanguage(language: String?) {
        dataStore.edit { prefs -> if (language != null) prefs[AUDIO_LANGUAGE] = language else prefs.remove(AUDIO_LANGUAGE) }
    }

    /** Langue des sous-titres choisie en dernier ; `null` : sous-titres désactivés. */
    val subtitleLanguage: Flow<String?> = dataStore.data.map { it[SUBTITLE_LANGUAGE] }.distinctUntilChanged()

    suspend fun setSubtitleLanguage(language: String?) {
        dataStore.edit { prefs -> if (language != null) prefs[SUBTITLE_LANGUAGE] = language else prefs.remove(SUBTITLE_LANGUAGE) }
    }

    /** Au lancement, lecture directe de la dernière chaîne regardée (comme une télévision). */
    val startOnLastChannel: Flow<Boolean> = dataStore.data.map { it[START_ON_LAST_CHANNEL] ?: false }.distinctUntilChanged()

    suspend fun setStartOnLastChannel(enabled: Boolean) {
        dataStore.edit { it[START_ON_LAST_CHANNEL] = enabled }
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

    /** Code parental : demandé pour réafficher les catégories masquées. Seule son empreinte est gardée. */
    val parentalPinSet: Flow<Boolean> = dataStore.data.map { it[PARENTAL_PIN] != null }.distinctUntilChanged()

    /** `null` : code retiré. */
    suspend fun setParentalPin(pin: String?) {
        dataStore.edit { prefs -> if (pin == null) prefs.remove(PARENTAL_PIN) else prefs[PARENTAL_PIN] = pinHash(pin) }
    }

    /** Vrai si [pin] est le code parental (ou s'il n'y en a pas). */
    suspend fun checkParentalPin(pin: String): Boolean = dataStore.data.first()[PARENTAL_PIN].let { it == null || it == pinHash(pin) }

    private fun pinHash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("nextv-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Réglages transportables d'un appareil à l'autre (sauvegarde) : tous sauf les secrets chiffrés
     * avec une clé de l'appareil, les décodeurs, et ce qui dépend des numéros de playlist ou de chaîne.
     */
    suspend fun exportPreferences(): List<SettingBackup> = dataStore.data.first().asMap()
        .filterKeys { isPortable(it.name) }
        .map { (key, value) -> SettingBackup(key.name, value) }

    suspend fun importPreferences(values: List<SettingBackup>) {
        dataStore.edit { prefs ->
            for (setting in values) {
                if (!isPortable(setting.key)) continue
                when (val value = setting.value) {
                    is Boolean -> prefs[booleanPreferencesKey(setting.key)] = value
                    is Int -> prefs[intPreferencesKey(setting.key)] = value
                    is Long -> prefs[longPreferencesKey(setting.key)] = value
                    is String -> prefs[stringPreferencesKey(setting.key)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(setting.key)] = value.filterIsInstance<String>().toSet()
                }
            }
        }
    }

    private fun isPortable(name: String): Boolean =
        name !in NOT_PORTABLE && NOT_PORTABLE_PREFIXES.none { name.startsWith(it) }

    /** Équipes suivies dans la section Foot (noms donnés par football-data.org ou le guide). */
    val favoriteTeams: Flow<Set<String>> = dataStore.data
        .map { prefs -> prefs[FAVORITE_TEAMS]?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty() }
        .distinctUntilChanged()

    suspend fun toggleFavoriteTeam(name: String) {
        if (name.isBlank() || '\n' in name) return
        dataStore.edit { prefs ->
            val teams = prefs[FAVORITE_TEAMS]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
            prefs[FAVORITE_TEAMS] = (if (name in teams) teams - name else teams + name).joinToString("\n")
        }
    }

    /** Rappels programmés (programmes du guide et matchs), du plus proche au plus lointain. */
    val reminders: Flow<List<Reminder>> = dataStore.data
        .map { prefs -> prefs[REMINDERS]?.split('\n')?.mapNotNull(Reminder::decode).orEmpty().sortedBy { it.startMillis } }
        .distinctUntilChanged()

    /** Ajoute le rappel, ou le retire s'il existe déjà. Les rappels passés depuis une heure sont oubliés. */
    suspend fun toggleReminder(reminder: Reminder, now: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val current = prefs[REMINDERS]?.split('\n')?.mapNotNull(Reminder::decode).orEmpty()
                .filter { it.startMillis > now - REMINDER_RETENTION_MILLIS }
            val updated = if (current.any { it.key == reminder.key }) current.filterNot { it.key == reminder.key } else current + reminder
            prefs[REMINDERS] = updated.joinToString("\n") { it.encode() }
        }
    }

    suspend fun removeReminder(key: String) {
        dataStore.edit { prefs ->
            prefs[REMINDERS] = prefs[REMINDERS]?.split('\n')?.mapNotNull(Reminder::decode).orEmpty()
                .filterNot { it.key == key }
                .joinToString("\n") { it.encode() }
        }
    }

    /**
     * Qualité qui a joué sans problème pour chaque chaîne d'une playlist (clé de version → nom de
     * [tv.nextv.core.model.StreamQuality]) : la lecture suivante commence directement par elle.
     */
    suspend fun workingQuality(sourceId: Long, variantKey: String): String? =
        dataStore.data.first()[qualityKey(sourceId)]?.lineSequence()
            ?.firstOrNull { it.substringBefore('\t') == variantKey }
            ?.substringAfter('\t', "")
            ?.takeIf { it.isNotEmpty() }

    suspend fun rememberWorkingQuality(sourceId: Long, variantKey: String, quality: String) {
        if (variantKey.isEmpty() || '\t' in variantKey || '\n' in variantKey) return
        val key = qualityKey(sourceId)
        dataStore.edit { prefs ->
            val others = prefs[key]?.lineSequence()?.filter { it.isNotEmpty() && it.substringBefore('\t') != variantKey }?.toList().orEmpty()
            prefs[key] = (listOf("$variantKey\t$quality") + others).take(MAX_REMEMBERED_QUALITIES).joinToString("\n")
        }
    }

    private fun qualityKey(sourceId: Long) = stringPreferencesKey(QUALITY_PREFIX + sourceId)

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

    /** Clé football-data.org saisie par l'utilisateur (chiffrée sur l'appareil). */
    val footballApiKey: Flow<String?> = dataStore.data
        .map { prefs -> prefs[FOOTBALL_KEY]?.let(cipher::decrypt) }
        .distinctUntilChanged()

    suspend fun setFootballApiKey(key: String?) {
        val encrypted = key?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        dataStore.edit { prefs ->
            if (encrypted != null) {
                prefs[FOOTBALL_KEY] = encrypted
            } else {
                prefs.remove(FOOTBALL_KEY)
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
        val FOOTBALL_KEY = stringPreferencesKey("football_key")
        val CHANNEL_COLUMNS = intPreferencesKey("channel_columns")
        val DNS_PROVIDER = stringPreferencesKey("dns_provider")
        val POSTER_COLUMNS = intPreferencesKey("poster_columns")
        val CHANNEL_PREVIEW = booleanPreferencesKey("channel_preview")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val START_ON_LAST_CHANNEL = booleanPreferencesKey("start_on_last_channel")
        val FAVORITE_TEAMS = stringPreferencesKey("favorite_teams")
        val REMINDERS = stringPreferencesKey("reminders")
        val PARENTAL_PIN = stringPreferencesKey("parental_pin")

        /** Secrets chiffrés pour cet appareil, playlist active (numéro local), rappels (numéros de chaîne). */
        val NOT_PORTABLE = setOf(PROXY_CREDENTIALS, TMDB_KEY, FOOTBALL_KEY, ACTIVE_PLAYLIST, REMINDERS).map { it.name }.toSet()
        val NOT_PORTABLE_PREFIXES = listOf(DECODER_PREFIX, RECENT_PREFIX, QUALITY_PREFIX)
        const val REMINDER_RETENTION_MILLIS = 60 * 60_000L
        const val DECODER_PREFIX = "decoder_"
        const val RECENT_PREFIX = "recent_channels_"
        const val MAX_RECENT_CHANNELS = 30
        const val QUALITY_PREFIX = "working_quality_"
        const val MAX_REMEMBERED_QUALITIES = 300
    }
}

/**
 * Rappel d'un programme ou d'un match : un bandeau le signale peu avant le début.
 * @property key identifiant du programme (chaîne et heure de début, ou match).
 * @property channelId chaîne à ouvrir depuis le bandeau, si elle est connue.
 */
data class Reminder(
    val key: String,
    val startMillis: Long,
    val title: String,
    val channelId: Long? = null,
    val channelName: String? = null,
) {
    /** Une ligne, champs séparés par des tabulations. */
    fun encode(): String = listOf(key, startMillis.toString(), channelId?.toString().orEmpty(), channelName.orEmpty(), title)
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    companion object {
        fun decode(line: String): Reminder? {
            val fields = line.split('\t')
            if (fields.size < 5) return null
            return Reminder(
                key = fields[0].takeIf { it.isNotEmpty() } ?: return null,
                startMillis = fields[1].toLongOrNull() ?: return null,
                title = fields[4],
                channelId = fields[2].toLongOrNull(),
                channelName = fields[3].takeIf { it.isNotEmpty() },
            )
        }

        /** Clé d'un programme du guide. */
        fun keyOf(channelId: Long, startMillis: Long) = "programme-$channelId-$startMillis"
    }
}

/** Format de l'image : adaptée à l'écran (bandes noires), étirée, ou agrandie (bords coupés). */
enum class AspectMode { FIT, FILL, ZOOM }

/** Éléments par ligne des grilles de l'accueil sur TV et tablette (le téléphone garde sa disposition). */
data class GridColumns(val channels: Int = DEFAULT_CHANNELS, val posters: Int = DEFAULT_POSTERS) {
    companion object {
        val CHANNELS = 3..9
        val POSTERS = 4..10
        const val DEFAULT_CHANNELS = 5
        const val DEFAULT_POSTERS = 6
    }
}
