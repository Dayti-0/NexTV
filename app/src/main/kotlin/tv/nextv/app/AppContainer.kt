package tv.nextv.app

import android.content.Context
import android.os.Build
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tv.nextv.app.data.backup.BackupManager
import tv.nextv.app.data.catalog.FavoritesRepository
import tv.nextv.app.data.progress.ProgressDatabase
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.reminders.ReminderWatcher
import tv.nextv.app.data.catalog.MetadataRepository
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.local.AndroidDocumentAccess
import tv.nextv.app.data.local.ContentResolverOpener
import tv.nextv.app.data.security.EncryptedCredentialStore
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.app.data.update.AppUpdater
import tv.nextv.app.player.PlayerFactory
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.log.Logger
import tv.nextv.core.log.RedactingLogger
import tv.nextv.core.log.Redactor
import tv.nextv.core.football.FootballDataClient
import tv.nextv.core.meta.TmdbClient
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.repository.RefreshResult
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.source.M3uPlaylistLoader
import tv.nextv.core.source.PlaylistLoaderRegistry
import tv.nextv.core.source.XtreamApi
import tv.nextv.core.update.UpdateChecker
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Assemblage manuel des dépendances de l'application. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Portée des tâches qui doivent survivre à la navigation (actualisations). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val redactor = Redactor()
    val logger: Logger = RedactingLogger(AndroidLogger(verbose = BuildConfig.DEBUG), redactor)

    val httpClients = HttpClientProvider(
        baseClient = OkHttpClient(),
        logger = logger,
        networkSettings = NetworkSettings(
            defaultUserAgent = "NexTV/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE})",
        ),
    )

    private val database = NexTvDatabase.create(appContext)
    val playlistStore = RoomPlaylistStore(database)
    val channelDao = database.channelDao()
    val programmeDao = database.programmeDao()
    val hiddenGroupDao = database.hiddenGroupDao()

    private val cipher = KeystoreSecretCipher()
    val settings = SettingsRepository(appContext, cipher)

    /** Rappels des programmes et des matchs (bandeau 5 minutes avant le début). */
    val reminders = ReminderWatcher(settings, applicationScope)
    val documentAccess = AndroidDocumentAccess(appContext.contentResolver, logger)

    private val contentOpener = CompositeContentOpener(
        listOf(
            RemoteContentOpener(httpClients, logger),
            ContentResolverOpener(appContext.contentResolver, logger),
        ),
    )

    /** Guide des programmes commun à toutes les playlists, mis en cache sur l'appareil. */
    val epgFeed = HttpEpgFeedProvider(
        clients = httpClients,
        url = HttpEpgFeedProvider.XMLTV_FR_URL.toHttpUrl(),
        directory = File(appContext.filesDir, "epg"),
        logger = logger,
    )

    /** Comptes Xtream Codes : abonnement (date de fin, connexions simultanées) et chaînes avec replay. */
    val xtreamApi = XtreamApi(httpClients, logger)

    val sourceRepository = SourceRepository(
        store = playlistStore,
        credentialStore = EncryptedCredentialStore(database.credentialDao(), cipher, logger),
        loaders = PlaylistLoaderRegistry(
            listOf(M3uPlaylistLoader(contentOpener, usesProxy = httpClients::usesProxy)),
        ),
        epgLoader = EpgLoader(
            opener = contentOpener,
            importer = EpgImporter(parserFactory = { Xml.newPullParser() }),
            usesProxy = httpClients::usesProxy,
        ),
        redactor = redactor,
        logger = logger,
        documentAccess = documentAccess,
        epgFeed = epgFeed,
        catchupDays = xtreamApi::archiveDays,
    )

    val refreshController = RefreshController(applicationScope, sourceRepository)

    val playerFactory = PlayerFactory(appContext, httpClients)

    /** Clé TMDB : celle saisie dans les réglages, sinon celle fournie à la compilation (secret CI). */
    private val tmdbKey: StateFlow<String?> = settings.tmdbApiKey
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    val metadata = MetadataRepository(
        database = database,
        client = TmdbClient(
            clients = httpClients,
            apiKey = { tmdbKey.value ?: BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() } },
            logger = logger,
        ),
    )

    /** Clé football-data.org : celle saisie dans les réglages, sinon celle fournie à la compilation (secret CI). */
    private val footballKey: StateFlow<String?> = settings.footballApiKey
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /** Matchs à venir de la section Foot (football-data.org, résultats gardés 30 min). */
    val footballData = FootballDataClient(
        clients = httpClients,
        apiKey = { footballKey.value ?: BuildConfig.FOOTBALL_API_KEY.takeIf { it.isNotBlank() } },
        logger = logger,
    )

    val favorites = FavoritesRepository(database)

    /** Sauvegarde et restauration des playlists, favoris, catégories masquées et réglages. */
    val backup = BackupManager(playlistStore, sourceRepository, database.favoriteDao(), hiddenGroupDao, settings)

    /** Reprise de lecture des films et séries (base distincte, sans migration de celle des playlists). */
    val progress = ProgressRepository(ProgressDatabase.create(appContext))

    /** Mises à jour de l'application (versions publiées sur GitHub). */
    val updater = AppUpdater(
        appContext,
        UpdateChecker(httpClients, BuildConfig.UPDATE_REPOSITORY, logger = logger),
        applicationScope,
        logger,
    )

    /** Playlist transmise par adb, en attente de confirmation dans l'éditeur. */
    val playlistImport = MutableStateFlow<PlaylistImport?>(null)

    fun hasTmdbKey(): Boolean = tmdbKey.value != null || BuildConfig.TMDB_API_KEY.isNotBlank()

    /** La dernière chaîne a déjà été relancée à l'ouverture (une seule fois par lancement). */
    private var lastChannelResumed = false

    /**
     * Dernière chaîne regardée, à relancer à l'ouverture de l'application si le réglage est actif
     * (une seule fois par lancement : pas après le rechargement d'une playlist).
     */
    suspend fun lastChannelToResume(): Long? {
        if (lastChannelResumed) return null
        lastChannelResumed = true
        if (!settings.startOnLastChannel.first()) return null
        val sourceId = settings.activePlaylistId.first() ?: return null
        val key = settings.recentChannels(sourceId).first().firstOrNull() ?: return null
        return channelDao.primaryIdByKey(sourceId, key)
    }

    private val serverTimeZones = ConcurrentHashMap<Long, TimeZone>()

    /**
     * Fuseau horaire du serveur Xtream d'une playlist, dans lequel s'expriment les heures du replay ;
     * celui de l'appareil si le serveur ne l'indique pas.
     */
    suspend fun serverTimeZone(source: PlaylistSource): TimeZone {
        serverTimeZones[source.id]?.let { return it }
        val id = xtreamApi.accountInfo(source)?.serverTimeZone ?: return TimeZone.getDefault()
        // Identifiant inconnu : TimeZone le remplace par GMT.
        val zone = TimeZone.getTimeZone(id).takeIf { it.id == id } ?: return TimeZone.getDefault()
        serverTimeZones[source.id] = zone
        return zone
    }

    /** Applique les réglages enregistrés, prépare le masquage des secrets et les actualisations périodiques. */
    fun start() {
        applicationScope.launch {
            combine(settings.proxySettings, settings.dnsProvider, ::Pair).collect { (proxy, dns) ->
                proxy.credentials?.let {
                    redactor.registerSecret(it.username)
                    redactor.registerSecret(it.password)
                }
                httpClients.update(proxySettings = proxy, networkSettings = httpClients.networkSettings.copy(dns = dns))
            }
        }
        applicationScope.launch {
            settings.tmdbApiKey.collect { redactor.registerSecret(it) }
        }
        redactor.registerSecret(BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() })
        applicationScope.launch {
            sourceRepository.registerStoredSecrets()
            sourceRepository.cleanStoredNames()
        }

        // Actualisations automatiques tant que l'application tourne (les TV restent souvent allumées).
        applicationScope.launch {
            delay(PERIODIC_FIRST_DELAY_MILLIS)
            var elapsed = 0L
            while (true) {
                val active = settings.activePlaylistId.firstOrNull()
                if (active != null) {
                    if (elapsed % PLAYLIST_REFRESH_MILLIS == 0L && elapsed > 0) {
                        refreshController.refresh(active)
                    } else {
                        // Ne retélécharge le guide que s'il a plus de 12 h.
                        refreshController.refreshEpg(active)
                    }
                }
                delay(PERIODIC_CHECK_MILLIS)
                elapsed += PERIODIC_CHECK_MILLIS
            }
        }
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val PERIODIC_FIRST_DELAY_MILLIS = 3 * HOUR
        const val PERIODIC_CHECK_MILLIS = 3 * HOUR
        const val PLAYLIST_REFRESH_MILLIS = 12 * HOUR
    }
}

/**
 * Lance les actualisations dans la portée de l'application : quitter un écran ne les interrompt pas.
 * Les résultats sont diffusés pour être signalés à l'utilisateur.
 */
class RefreshController(
    private val scope: CoroutineScope,
    private val repository: SourceRepository,
) {
    data class Event(val sourceId: Long, val result: RefreshResult, val epgOnly: Boolean = false)

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    fun refresh(sourceId: Long) {
        scope.launch { mutableEvents.emit(Event(sourceId, repository.refresh(sourceId))) }
    }

    fun refreshEpg(sourceId: Long, force: Boolean = false) {
        scope.launch { mutableEvents.emit(Event(sourceId, repository.refreshEpg(sourceId, force), epgOnly = true)) }
    }

    fun refreshAll() {
        scope.launch {
            repository.refreshAll().forEach { (id, result) -> mutableEvents.emit(Event(id, result)) }
        }
    }
}
