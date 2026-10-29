package tv.nextv.app

import android.content.Context
import android.os.Build
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.local.AndroidDocumentAccess
import tv.nextv.app.data.local.ContentResolverOpener
import tv.nextv.app.data.security.EncryptedCredentialStore
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.app.player.PlayerFactory
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.log.Logger
import tv.nextv.core.log.RedactingLogger
import tv.nextv.core.log.Redactor
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.repository.RefreshResult
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.source.M3uPlaylistLoader
import tv.nextv.core.source.PlaylistLoaderRegistry

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

    private val cipher = KeystoreSecretCipher()
    val settings = SettingsRepository(appContext, cipher)
    val documentAccess = AndroidDocumentAccess(appContext.contentResolver, logger)

    private val contentOpener = CompositeContentOpener(
        listOf(
            RemoteContentOpener(httpClients, logger),
            ContentResolverOpener(appContext.contentResolver, logger),
        ),
    )

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
    )

    val refreshController = RefreshController(applicationScope, sourceRepository)

    val playerFactory = PlayerFactory(appContext, httpClients)

    /** Applique les réglages enregistrés et prépare le masquage des secrets. */
    fun start() {
        applicationScope.launch {
            settings.proxySettings.collect { proxy ->
                proxy.credentials?.let {
                    redactor.registerSecret(it.username)
                    redactor.registerSecret(it.password)
                }
                httpClients.update(proxySettings = proxy)
            }
        }
        applicationScope.launch { sourceRepository.registerStoredSecrets() }
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

    fun refreshEpg(sourceId: Long) {
        scope.launch { mutableEvents.emit(Event(sourceId, repository.refreshEpg(sourceId), epgOnly = true)) }
    }

    fun refreshAll() {
        scope.launch {
            repository.refreshAll().forEach { (id, result) -> mutableEvents.emit(Event(id, result)) }
        }
    }
}
