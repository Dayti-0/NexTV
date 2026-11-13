package tv.nextv.app.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.backup.SettingBackup
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType
import tv.nextv.core.stream.QualityTarget

/** Fichier illisible (coupure de courant pendant une écriture…) : réglages par défaut, plutôt qu'un plantage à chaque lancement. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Réglages de l'application. Les identifiants du proxy sont chiffrés comme ceux des sources. */
class SettingsRepository(
    context: Context,
    private val cipher: KeystoreSecretCipher,
) {
    private val dataStore = context.applicationContext.settingsDataStore

    /** Réglages enregistrés ; ceux par défaut si le fichier ne peut pas être lu (jamais d'exception pour qui les observe). */
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error !is IOException) throw error
        Log.w(TAG, "Réglages illisibles", error)
        emit(emptyPreferences())
    }

    /** Modifie les réglages ; sans effet (et sans plantage) si l'écriture est impossible, stockage plein par exemple. */
    private suspend fun edit(transform: suspend (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (e: IOException) {
            Log.w(TAG, "Réglages non enregistrés", e)
        }
    }

    /**
     * Appareil à petite mémoire : les bandes-annonces automatiques (un navigateur en arrière-plan) sont
     * désactivées par défaut ; elles restent activables dans les réglages.
     */
    private val lowMemoryDevice: Boolean = context.getSystemService(android.app.ActivityManager::class.java)
        ?.let { it.isLowRamDevice || it.memoryClass <= 128 } ?: false

    val proxySettings: Flow<ProxySettings> = preferences
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
    val activePlaylistId: Flow<Long?> = preferences.map { it[ACTIVE_PLAYLIST] }.distinctUntilChanged()

    suspend fun setActivePlaylist(id: Long?) {
        edit { prefs ->
            if (id != null) {
                prefs[ACTIVE_PLAYLIST] = id
            } else {
                prefs.remove(ACTIVE_PLAYLIST)
            }
        }
    }

    /** Basculement automatique de qualité (FHD → HD → SD → LOW et retour). */
    val autoQuality: Flow<Boolean> = preferences.map { it[AUTO_QUALITY] ?: true }.distinctUntilChanged()

    suspend fun setAutoQuality(enabled: Boolean) {
        edit { it[AUTO_QUALITY] = enabled }
    }

    /** Qualité visée : la meilleure, au plus HD, ou l'économie (SD). */
    val qualityTarget: Flow<QualityTarget> = preferences
        .map { prefs -> prefs[QUALITY_TARGET]?.let { name -> QualityTarget.entries.firstOrNull { it.name == name } } ?: QualityTarget.BEST }
        .distinctUntilChanged()

    suspend fun setQualityTarget(target: QualityTarget) {
        edit { it[QUALITY_TARGET] = target.name }
    }

    /**
     * Décodeur vidéo choisi par type de flux (`video/hevc`, `video/avc`…) ; absent : choix automatique
     * (matériel en priorité, repli sur un autre décodeur en cas d'échec).
     */
    val preferredDecoders: Flow<Map<String, String>> = preferences
        .map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val mime = key.name.removePrefix(DECODER_PREFIX).takeIf { key.name.startsWith(DECODER_PREFIX) }
                if (mime != null && value is String) mime to value else null
            }.toMap()
        }
        .distinctUntilChanged()

    suspend fun setPreferredDecoder(mimeType: String, decoderName: String?) {
        val key = stringPreferencesKey(DECODER_PREFIX + mimeType)
        edit { prefs ->
            if (decoderName != null) {
                prefs[key] = decoderName
            } else {
                prefs.remove(key)
            }
        }
    }

    /** Vignettes par ligne des grilles de l'accueil (TV, tablette). */
    val gridColumns: Flow<GridColumns> = preferences
        .map { prefs ->
            GridColumns(
                perRow = (prefs[TILES_PER_ROW] ?: GridColumns.DEFAULT_PER_ROW).coerceIn(GridColumns.PER_ROW),
                channelList = prefs[CHANNEL_LIST] ?: false,
                liveCenterMosaic = prefs[LIVE_CENTER_MOSAIC] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setGridColumns(columns: GridColumns) {
        edit { prefs ->
            prefs[TILES_PER_ROW] = columns.perRow.coerceIn(GridColumns.PER_ROW)
            prefs[CHANNEL_LIST] = columns.channelList
            prefs[LIVE_CENTER_MOSAIC] = columns.liveCenterMosaic
        }
    }

    /** Thème de l'interface. */
    val themeStyle: Flow<ThemeStyle> = preferences
        .map { prefs -> prefs[THEME_STYLE]?.let { name -> ThemeStyle.entries.firstOrNull { it.name == name } } ?: ThemeStyle.NIGHT }
        .distinctUntilChanged()

    /** Palette choisie avant l'actuelle (« Revenir à … » de l'écran Apparence), `null` : aucune. */
    val previousThemeStyle: Flow<ThemeStyle?> = preferences
        .map { prefs -> prefs[PREVIOUS_THEME_STYLE]?.let { name -> ThemeStyle.entries.firstOrNull { it.name == name } } }
        .distinctUntilChanged()

    /** Nouvelle palette ; l'ancienne devient la précédente. */
    suspend fun setThemeStyle(style: ThemeStyle) {
        edit { prefs ->
            val current = prefs[THEME_STYLE] ?: ThemeStyle.DEFAULT.name
            if (current != style.name) prefs[PREVIOUS_THEME_STYLE] = current
            prefs[THEME_STYLE] = style.name
        }
    }

    /** Dernière catégorie du guide TV (« Ce soir », « L'essentiel », un groupe…), `null` : toutes les chaînes. */
    val guideCategory: Flow<String?> = preferences.map { it[GUIDE_CATEGORY] }.distinctUntilChanged()

    suspend fun setGuideCategory(category: String?) {
        edit { prefs -> if (category == null) prefs.remove(GUIDE_CATEGORY) else prefs[GUIDE_CATEGORY] = category }
    }

    /** Version des règles d'import du dernier guide importé ([tv.nextv.core.epg.EpgImporter.VERSION]). */
    val epgImportVersion: Flow<Int> = preferences.map { it[EPG_IMPORT_VERSION] ?: 0 }.distinctUntilChanged()

    suspend fun setEpgImportVersion(version: Int) {
        edit { it[EPG_IMPORT_VERSION] = version }
    }

    /** Version des règles de regroupement des chaînes de la dernière playlist lue ([tv.nextv.core.catalog.QualityParser.VERSION]). */
    val catalogImportVersion: Flow<Int> = preferences.map { it[CATALOG_IMPORT_VERSION] ?: 0 }.distinctUntilChanged()

    suspend fun setCatalogImportVersion(version: Int) {
        edit { it[CATALOG_IMPORT_VERSION] = version }
    }

    /** Son de l'aperçu vidéo de l'accueil (faible par défaut). */
    val previewVolume: Flow<PreviewVolume> = preferences
        .map { prefs -> prefs[PREVIEW_VOLUME]?.let { name -> PreviewVolume.entries.firstOrNull { it.name == name } } ?: PreviewVolume.LOW }
        .distinctUntilChanged()

    suspend fun setPreviewVolume(volume: PreviewVolume) {
        edit { it[PREVIEW_VOLUME] = volume.name }
    }

    /** Fréquence de mise à jour automatique de la playlist (12 h par défaut). */
    val playlistRefresh: Flow<PlaylistRefresh> = preferences
        .map { prefs -> prefs[PLAYLIST_REFRESH]?.let { name -> PlaylistRefresh.entries.firstOrNull { it.name == name } } ?: PlaylistRefresh.HOURS_12 }
        .distinctUntilChanged()

    suspend fun setPlaylistRefresh(refresh: PlaylistRefresh) {
        edit { it[PLAYLIST_REFRESH] = refresh.name }
    }

    /** Interface de l'accueil : classique, streaming ou box TV. */
    val homeLayout: Flow<HomeLayout> = preferences
        .map { prefs -> HomeLayout.fromStored(prefs[HOME_LAYOUT]) }
        .distinctUntilChanged()

    suspend fun setHomeLayout(layout: HomeLayout) {
        edit { it[HOME_LAYOUT] = layout.name }
    }

    /** Format de l'image dans le lecteur. */
    val aspectMode: Flow<AspectMode> = preferences
        .map { prefs -> prefs[ASPECT_MODE]?.let { name -> AspectMode.entries.firstOrNull { it.name == name } } ?: AspectMode.FIT }
        .distinctUntilChanged()

    suspend fun setAspectMode(mode: AspectMode) {
        edit { it[ASPECT_MODE] = mode.name }
    }

    /** Langue audio choisie en dernier (« fr », « en »…), proposée en premier aux lectures suivantes. */
    val audioLanguage: Flow<String?> = preferences.map { it[AUDIO_LANGUAGE] }.distinctUntilChanged()

    suspend fun setAudioLanguage(language: String?) {
        edit { prefs -> if (language != null) prefs[AUDIO_LANGUAGE] = language else prefs.remove(AUDIO_LANGUAGE) }
    }

    /** Langue des sous-titres choisie en dernier ; `null` : sous-titres désactivés. */
    val subtitleLanguage: Flow<String?> = preferences.map { it[SUBTITLE_LANGUAGE] }.distinctUntilChanged()

    suspend fun setSubtitleLanguage(language: String?) {
        edit { prefs -> if (language != null) prefs[SUBTITLE_LANGUAGE] = language else prefs.remove(SUBTITLE_LANGUAGE) }
    }

    /** Son baissé pendant les publicités (repérées à leur niveau sonore). */
    val lowerAdVolume: Flow<Boolean> = preferences.map { it[LOWER_AD_VOLUME] ?: false }.distinctUntilChanged()

    suspend fun setLowerAdVolume(enabled: Boolean) {
        edit { it[LOWER_AD_VOLUME] = enabled }
    }

    /** Chaînes 4K (UHD, HDR) masquées partout et jamais choisies : l'appareil ne les lit pas bien. */
    val hideUhd: Flow<Boolean> = preferences.map { it[HIDE_UHD] ?: false }.distinctUntilChanged()

    suspend fun setHideUhd(enabled: Boolean) {
        edit { it[HIDE_UHD] = enabled }
    }

    /**
     * « Rassembler les chaînes » (activé d'office) : une seule ligne par chaîne, ses versions double audio ou Dolby
     * réunies, sans les séparateurs de la playlist ni les éditions régionales de France 3 autres que celle choisie, et
     * des listes rangées comme sur une télé (TNT, Canal+, cinéma…). Coupé : les chaînes telles que la playlist les
     * présente.
     */
    val mergeChannels: Flow<Boolean> = preferences.map { it[MERGE_CHANNELS] ?: true }.distinctUntilChanged()

    suspend fun setMergeChannels(enabled: Boolean) {
        edit { it[MERGE_CHANNELS] = enabled }
    }

    /**
     * Région de France 3 gardée dans les listes (« Alpes », clé de [tv.nextv.core.catalog.FranceRegions]) ;
     * les autres éditions régionales sont masquées. `null` : toutes affichées (seulement France 3 si les chaînes
     * sont rassemblées).
     */
    val france3Region: Flow<String?> = preferences
        // Choix fait quand la région portait une autre écriture (« paris idf ») : la clé de son antenne.
        .map { prefs -> prefs[FRANCE3_REGION]?.let(tv.nextv.core.catalog.FranceRegions::keyOf) }
        .distinctUntilChanged()

    suspend fun setFrance3Region(region: String?) {
        edit { prefs -> if (region != null) prefs[FRANCE3_REGION] = region else prefs.remove(FRANCE3_REGION) }
    }

    /**
     * Son Dolby (AC3, E-AC3) et DTS envoyé tel quel à l'ampli ou à la barre de son. Désactivé (par
     * défaut), il est décodé par l'appareil : le son sort aussi sur un écran ou un moniteur qui ne
     * sait pas le lire.
     */
    val audioPassthrough: Flow<Boolean> = preferences.map { it[AUDIO_PASSTHROUGH] ?: false }.distinctUntilChanged()

    suspend fun setAudioPassthrough(enabled: Boolean) {
        edit { it[AUDIO_PASSTHROUGH] = enabled }
    }

    /** Au lancement, lecture directe de la dernière chaîne regardée (comme une télévision). */
    val startOnLastChannel: Flow<Boolean> = preferences.map { it[START_ON_LAST_CHANNEL] ?: false }.distinctUntilChanged()

    suspend fun setStartOnLastChannel(enabled: Boolean) {
        edit { it[START_ON_LAST_CHANNEL] = enabled }
    }

    /** Aperçu vidéo (sans le son) de la chaîne sélectionnée à l'accueil. */
    val channelPreview: Flow<Boolean> = preferences.map { it[CHANNEL_PREVIEW] ?: true }.distinctUntilChanged()

    suspend fun setChannelPreview(enabled: Boolean) {
        edit { it[CHANNEL_PREVIEW] = enabled }
    }

    /**
     * Passage sans coupure de l'aperçu au plein écran, et retour (Android 10 et plus) : le direct garde sa connexion et
     * sa couche vidéo ([tv.nextv.app.player.VideoLayers]). Coupé : la chaîne est rouverte à chaque passage, comme avant.
     */
    val previewHandoff: Flow<Boolean> = preferences.map { it[PREVIEW_HANDOFF] ?: true }.distinctUntilChanged()

    suspend fun setPreviewHandoff(enabled: Boolean) {
        edit { it[PREVIEW_HANDOFF] = enabled }
    }

    /** Bande-annonce en fond de la grande affiche (Streaming) : plus de connexion, peut ralentir l'appareil. */
    val trailerAutoplay: Flow<Boolean> = preferences.map { it[TRAILER_AUTOPLAY] ?: !lowMemoryDevice }.distinctUntilChanged()

    suspend fun setTrailerAutoplay(enabled: Boolean) {
        edit { it[TRAILER_AUTOPLAY] = enabled }
    }

    /** Bande-annonce automatique avec le son (sinon muette, le bouton du bandeau rend le son). */
    val trailerSound: Flow<Boolean> = preferences.map { it[TRAILER_SOUND] ?: false }.distinctUntilChanged()

    suspend fun setTrailerSound(enabled: Boolean) {
        edit { it[TRAILER_SOUND] = enabled }
    }

    /** Temps passé sur un même contenu avant que sa bande-annonce démarre (secondes). */
    val trailerDelaySeconds: Flow<Int> = preferences.map { it[TRAILER_DELAY] ?: TRAILER_DELAY_DEFAULT }.distinctUntilChanged()

    /** Délai suivant : 2, 3, puis 5 secondes. */
    suspend fun cycleTrailerDelay() {
        edit {
            val current = it[TRAILER_DELAY] ?: TRAILER_DELAY_DEFAULT
            it[TRAILER_DELAY] = TRAILER_DELAYS.getOrElse(TRAILER_DELAYS.indexOf(current) + 1) { TRAILER_DELAYS.first() }
        }
    }

    /**
     * Configuration rapide déjà proposée : une seule fois, à la première ouverture (y compris pour qui
     * avait l'application avant qu'elle existe), puis seulement depuis les réglages.
     */
    val quickSetupDone: Flow<Boolean> = preferences.map { it[QUICK_SETUP_DONE] ?: false }.distinctUntilChanged()

    suspend fun setQuickSetupDone() {
        edit { it[QUICK_SETUP_DONE] = true }
    }

    /** Chaînes regardées récemment dans une playlist (clés des chaînes, la plus récente en premier). */
    fun recentChannels(sourceId: Long): Flow<List<String>> = preferences
        .map { prefs -> prefs[recentKey(sourceId)]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty() }
        .distinctUntilChanged()

    suspend fun addRecentChannel(sourceId: Long, itemKey: String) {
        val key = recentKey(sourceId)
        edit { prefs ->
            val previous = prefs[key]?.split('\n')?.filter { it.isNotEmpty() && it != itemKey }.orEmpty()
            prefs[key] = (listOf(itemKey) + previous).take(MAX_RECENT_CHANNELS).joinToString("\n")
        }
    }

    /**
     * Chaînes récentes dont la clé a changé (versions d'une chaîne regroupées sous un autre nom) : [rename] donne la
     * nouvelle clé, ou `null` pour garder l'ancienne.
     */
    suspend fun renameRecentChannels(sourceId: Long, rename: suspend (String) -> String?) {
        val key = recentKey(sourceId)
        val current = preferences.first()[key]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        val renamed = current.map { rename(it) ?: it }.distinct()
        if (renamed != current) edit { prefs -> prefs[key] = renamed.joinToString("\n") }
    }

    private fun recentKey(sourceId: Long) = stringPreferencesKey(RECENT_PREFIX + sourceId)

    /**
     * Réglages transportables d'un appareil à l'autre (sauvegarde) : tous sauf les secrets chiffrés
     * avec une clé de l'appareil, les décodeurs, et ce qui dépend des numéros de playlist ou de chaîne.
     */
    suspend fun exportPreferences(): List<SettingBackup> = preferences.first().asMap()
        .filterKeys { isPortable(it.name) }
        .map { (key, value) -> SettingBackup(key.name, value) }

    suspend fun importPreferences(values: List<SettingBackup>) {
        edit { prefs ->
            for (setting in values) {
                if (!isPortable(setting.key)) continue
                // Réglage inconnu de cette version, ou d'un autre type que celui attendu (fichier modifié ou
                // abîmé) : ignoré. Enregistré tel quel, il ferait planter l'application à chaque lecture.
                val type = TYPES[setting.key] ?: continue
                val value = setting.value
                when {
                    type == Boolean::class && value is Boolean -> prefs[booleanPreferencesKey(setting.key)] = value
                    type == Int::class && value is Int -> prefs[intPreferencesKey(setting.key)] = value
                    type == Long::class && value is Long -> prefs[longPreferencesKey(setting.key)] = value
                    // Un entier court relu d'un fichier peut être donné comme entier simple.
                    type == Long::class && value is Int -> prefs[longPreferencesKey(setting.key)] = value.toLong()
                    type == String::class && value is String -> prefs[stringPreferencesKey(setting.key)] = value
                    type == Set::class && value is Set<*> -> prefs[stringSetPreferencesKey(setting.key)] = value.filterIsInstance<String>().toSet()
                }
            }
        }
    }

    private fun isPortable(name: String): Boolean =
        name !in NOT_PORTABLE && NOT_PORTABLE_PREFIXES.none { name.startsWith(it) }

    /** Équipes suivies dans la section Foot (noms donnés par football-data.org ou le guide). */
    val favoriteTeams: Flow<Set<String>> = preferences
        .map { prefs -> prefs[FAVORITE_TEAMS]?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty() }
        .distinctUntilChanged()

    suspend fun toggleFavoriteTeam(name: String) {
        if (name.isBlank() || '\n' in name) return
        edit { prefs ->
            val teams = prefs[FAVORITE_TEAMS]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
            prefs[FAVORITE_TEAMS] = (if (name in teams) teams - name else teams + name).joinToString("\n")
        }
    }

    /**
     * Qualité qui a joué sans problème pour chaque chaîne d'une playlist (clé de version → nom de
     * [tv.nextv.core.model.StreamQuality]) : la lecture suivante commence directement par elle.
     */
    suspend fun workingQuality(sourceId: Long, variantKey: String): String? =
        preferences.first()[qualityKey(sourceId)]?.lineSequence()
            ?.firstOrNull { it.substringBefore('\t') == variantKey }
            ?.substringAfter('\t', "")
            ?.takeIf { it.isNotEmpty() }

    suspend fun rememberWorkingQuality(sourceId: Long, variantKey: String, quality: String) {
        if (variantKey.isEmpty() || '\t' in variantKey || '\n' in variantKey) return
        val key = qualityKey(sourceId)
        edit { prefs ->
            val others = prefs[key]?.lineSequence()?.filter { it.isNotEmpty() && it.substringBefore('\t') != variantKey }?.toList().orEmpty()
            prefs[key] = (listOf("$variantKey\t$quality") + others).take(MAX_REMEMBERED_QUALITIES).joinToString("\n")
        }
    }

    private fun qualityKey(sourceId: Long) = stringPreferencesKey(QUALITY_PREFIX + sourceId)

    /** Résolution des noms (DNS du système ou résolveur public en DNS sur HTTPS). */
    val dnsProvider: Flow<DnsProvider> = preferences
        .map { prefs -> prefs[DNS_PROVIDER]?.let { name -> DnsProvider.entries.firstOrNull { it.name == name } } ?: DnsProvider.SYSTEM }
        .distinctUntilChanged()

    suspend fun setDnsProvider(provider: DnsProvider) {
        edit { it[DNS_PROVIDER] = provider.name }
    }

    /** Clé TMDB saisie par l'utilisateur (chiffrée sur l'appareil). */
    val tmdbApiKey: Flow<String?> = preferences
        .map { prefs -> prefs[TMDB_KEY]?.let(cipher::decrypt) }
        .distinctUntilChanged()

    suspend fun setTmdbApiKey(key: String?) {
        val encrypted = key?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        edit { prefs ->
            if (encrypted != null) {
                prefs[TMDB_KEY] = encrypted
            } else {
                prefs.remove(TMDB_KEY)
            }
        }
    }

    /** Clé football-data.org saisie par l'utilisateur (chiffrée sur l'appareil). */
    val footballApiKey: Flow<String?> = preferences
        .map { prefs -> prefs[FOOTBALL_KEY]?.let(cipher::decrypt) }
        .distinctUntilChanged()

    suspend fun setFootballApiKey(key: String?) {
        val encrypted = key?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        edit { prefs ->
            if (encrypted != null) {
                prefs[FOOTBALL_KEY] = encrypted
            } else {
                prefs.remove(FOOTBALL_KEY)
            }
        }
    }

    suspend fun saveProxySettings(settings: ProxySettings) {
        val encryptedCredentials = settings.credentials?.let(cipher::encryptCredentials)
        edit { prefs ->
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
        const val TAG = "NixTV/Settings"

        /** Type de chaque réglage connu, par nom : vérifié à la restauration d'une sauvegarde. */
        val TYPES = HashMap<String, KClass<*>>()

        fun booleanKey(name: String) = booleanPreferencesKey(name).also { TYPES[name] = Boolean::class }
        fun intKey(name: String) = intPreferencesKey(name).also { TYPES[name] = Int::class }
        fun longKey(name: String) = longPreferencesKey(name).also { TYPES[name] = Long::class }
        fun stringKey(name: String) = stringPreferencesKey(name).also { TYPES[name] = String::class }
        fun stringSetKey(name: String) = stringSetPreferencesKey(name).also { TYPES[name] = Set::class }

        val PROXY_ENABLED = booleanKey("proxy_enabled")
        val PROXY_TYPE = stringKey("proxy_type")
        val PROXY_HOST = stringKey("proxy_host")
        val PROXY_PORT = intKey("proxy_port")
        val PROXY_CREDENTIALS = stringKey("proxy_credentials")
        val PROXY_SCOPES = stringSetKey("proxy_scopes")
        val PROXY_BYPASS_LOCAL = booleanKey("proxy_bypass_local")
        val ACTIVE_PLAYLIST = longKey("active_playlist")
        val AUTO_QUALITY = booleanKey("auto_quality")
        val QUALITY_TARGET = stringKey("quality_target")
        val TMDB_KEY = stringKey("tmdb_key")
        val FOOTBALL_KEY = stringKey("football_key")
        val TILES_PER_ROW = intKey("tiles_per_row")
        val DNS_PROVIDER = stringKey("dns_provider")
        val CHANNEL_PREVIEW = booleanKey("channel_preview")
        val PREVIEW_HANDOFF = booleanKey("preview_handoff")
        val TRAILER_AUTOPLAY = booleanKey("trailer_autoplay")
        val TRAILER_SOUND = booleanKey("trailer_sound")
        val TRAILER_DELAY = intKey("trailer_delay")
        const val TRAILER_DELAY_DEFAULT = 3
        val TRAILER_DELAYS = listOf(2, 3, 5)
        val QUICK_SETUP_DONE = booleanKey("quick_setup_done")
        val ASPECT_MODE = stringKey("aspect_mode")
        val AUDIO_LANGUAGE = stringKey("audio_language")
        val SUBTITLE_LANGUAGE = stringKey("subtitle_language")
        val START_ON_LAST_CHANNEL = booleanKey("start_on_last_channel")
        val FAVORITE_TEAMS = stringKey("favorite_teams")
        val LOWER_AD_VOLUME = booleanKey("lower_ad_volume")
        val THEME_STYLE = stringKey("theme_style")
        val PREVIOUS_THEME_STYLE = stringKey("previous_theme_style")
        val HOME_LAYOUT = stringKey("home_layout")
        val PREVIEW_VOLUME = stringKey("preview_volume")
        val PLAYLIST_REFRESH = stringKey("playlist_refresh")
        val GUIDE_CATEGORY = stringKey("guide_category")
        val EPG_IMPORT_VERSION = intKey("epg_import_version")
        val CATALOG_IMPORT_VERSION = intKey("catalog_import_version")
        val CHANNEL_LIST = booleanKey("channel_list")
        val LIVE_CENTER_MOSAIC = booleanKey("live_center_mosaic")
        val HIDE_UHD = booleanKey("hide_uhd")
        val FRANCE3_REGION = stringKey("france3_region")
        val MERGE_CHANNELS = booleanKey("merge_channels")
        val AUDIO_PASSTHROUGH = booleanKey("audio_passthrough")

        /** Réglages de la version 0.2.10 retirés depuis (rappels, code parental). */
        val OBSOLETE = setOf("reminders", "parental_pin")

        /** Secrets chiffrés pour cet appareil, playlist active (numéro local), réglages retirés. */
        val NOT_PORTABLE = setOf(PROXY_CREDENTIALS, TMDB_KEY, FOOTBALL_KEY, ACTIVE_PLAYLIST, EPG_IMPORT_VERSION, CATALOG_IMPORT_VERSION).map { it.name }.toSet() + OBSOLETE
        val NOT_PORTABLE_PREFIXES = listOf(DECODER_PREFIX, RECENT_PREFIX, QUALITY_PREFIX)
        const val DECODER_PREFIX = "decoder_"
        const val RECENT_PREFIX = "recent_channels_"
        const val MAX_RECENT_CHANNELS = 30
        const val QUALITY_PREFIX = "working_quality_"
        const val MAX_REMEMBERED_QUALITIES = 300
    }
}

/** Format de l'image : adaptée à l'écran (bandes noires), étirée, ou agrandie (bords coupés). */
enum class AspectMode { FIT, FILL, ZOOM }

/** Éléments par ligne des grilles de l'accueil sur TV et tablette (le téléphone garde sa disposition). */
data class GridColumns(
    /** Chaînes, films et séries par ligne : exactement ce nombre à l'écran (TV, tablette). */
    val perRow: Int = DEFAULT_PER_ROW,
    /** Chaînes en liste (logo, nom, programme en cours) plutôt qu'en mosaïque. */
    val channelList: Boolean = false,
    /** Live Center : chaînes en mosaïque de vignettes plutôt qu'en liste. */
    val liveCenterMosaic: Boolean = false,
) {
    companion object {
        val PER_ROW = 2..5
        const val DEFAULT_PER_ROW = 4
    }
}
