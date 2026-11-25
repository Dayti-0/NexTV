package tv.nextv.app

import tv.nextv.core.net.RequestPurpose
import android.content.Context
import tv.nextv.app.data.football.EventTurnStore
import android.os.Build
import android.util.Xml
import androidx.room.withTransaction
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tv.nextv.app.data.backup.BackupManager
import tv.nextv.app.data.catalog.MeasuredQualities
import tv.nextv.app.data.catalog.ChannelFilters
import tv.nextv.app.data.catalog.FavoritesRepository
import tv.nextv.app.data.catalog.MetadataRepository
import tv.nextv.app.data.db.NexTvDatabase
import tv.nextv.app.data.db.RoomPlaylistStore
import tv.nextv.app.data.local.AndroidDocumentAccess
import tv.nextv.app.data.local.ContentResolverOpener
import tv.nextv.app.data.progress.ProgressDatabase
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.app.data.progress.WatchedDatabase
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.app.data.security.EncryptedCredentialStore
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.app.data.settings.SettingsRepository
import tv.nextv.app.data.update.AppUpdater
import tv.nextv.app.player.AdMarksStore
import tv.nextv.app.player.AdPrintStore
import tv.nextv.app.player.AdRecordings
import tv.nextv.app.player.AdTestedChannels
import tv.nextv.app.player.AdUploads
import tv.nextv.app.player.AdMusicLibrary
import tv.nextv.app.player.removableMusicFolders
import tv.nextv.app.player.PlayerFactory
import tv.nextv.app.player.PreviewHandoffSlot
import tv.nextv.app.player.TIMESHIFT_FILE
import tv.nextv.app.player.smallMemoryDevice
import tv.nextv.app.player.removableRecordingPlaces
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.home.WatchedPlace
import tv.nextv.core.audio.AdPrintLibrary
import tv.nextv.core.catalog.ChannelLineup
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.football.FootballDataClient
import tv.nextv.core.football.HalfTimes
import tv.nextv.core.football.LiveScoreClient
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.log.Logger
import tv.nextv.core.log.RedactingLogger
import tv.nextv.core.log.Redactor
import tv.nextv.core.meta.TmdbClient
import tv.nextv.core.meta.Translator
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.repository.RefreshResult
import tv.nextv.core.repository.SourceRepository
import tv.nextv.core.source.M3uPlaylistLoader
import tv.nextv.core.source.PlaylistLoaderRegistry
import tv.nextv.core.source.XtreamApi
import tv.nextv.core.update.UpdateChecker

/** Assemblage manuel des dépendances de l'application. */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Dernier plantage enregistré (absent hors de l'application, dans les tests). */
    val crashLog: CrashLog? get() = (appContext as? NexTvApplication)?.crashLog

    /**
     * Tâche de fond en erreur (stockage plein, base illisible…) : notée dans le journal et dans « Dernier
     * incident », sans fermer l'application (une actualisation automatique ne doit pas interrompre un film).
     */
    private val backgroundErrors = CoroutineExceptionHandler { _, error ->
        runCatching { logger.error("App", "Tâche de fond interrompue", error) }
        crashLog?.record(error, fatal = false)
    }

    /** Portée des tâches qui doivent survivre à la navigation (actualisations). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + backgroundErrors)

    val redactor = Redactor()
    val logger: Logger = RedactingLogger(AndroidLogger(verbose = BuildConfig.DEBUG), redactor)

    val httpClients = HttpClientProvider(
        baseClient = OkHttpClient(),
        logger = logger,
        networkSettings = NetworkSettings(
            defaultUserAgent = "NixTV/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE})",
        ),
    )

    private val database = NexTvDatabase.create(appContext)
    // Lu à chaque enregistrement d'une playlist (après la création des réglages, plus bas).
    val playlistStore = RoomPlaylistStore(database) { settings.mergeChannels.first() }
    val channelDao = database.channelDao()
    val programmeDao = database.programmeDao()
    val hiddenGroupDao = database.hiddenGroupDao()
    val guideOnlyChannelDao = database.guideOnlyChannelDao()
    val tmdbDao = database.tmdbDao()

    private val cipher = KeystoreSecretCipher()
    val settings = SettingsRepository(appContext, cipher)
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

    /** Guides des fournisseurs (comptes Xtream), un par playlist, gardés pour leur cache et leur verrou. */
    private val providerGuides = java.util.concurrent.ConcurrentHashMap<String, HttpEpgFeedProvider>()

    /** Guide du fournisseur d'une playlist Xtream (`xmltv.php`, mêmes identifiants que ses chaînes), `null` pour une autre playlist. */
    private fun providerGuide(source: tv.nextv.core.model.PlaylistSource): HttpEpgFeedProvider? {
        val url = (source.location as? tv.nextv.core.model.SourceLocation.Remote)?.url
            ?.let(tv.nextv.core.source.XtreamAccount::parse)?.xmltvUrl()?.toHttpUrlOrNull() ?: return null
        return providerGuides.getOrPut("${source.id}|$url") {
            HttpEpgFeedProvider(
                clients = httpClients,
                url = url,
                directory = File(appContext.filesDir, "epg_source_${source.id}"),
                logger = logger,
                label = "du fournisseur",
                userAgent = source.userAgent,
            )
        }
    }

    /** Guides d'appoint (Pluto TV, Plex, Samsung TV Plus, epgshare) pour les chaînes que le guide français ne couvre pas. */
    private val extraEpgFeeds = HttpEpgFeedProvider.EXTRA_GUIDES.map { (folder, url) ->
        HttpEpgFeedProvider(
            clients = httpClients,
            url = url.toHttpUrl(),
            directory = File(appContext.filesDir, "epg_$folder"),
            maxAgeMillis = HttpEpgFeedProvider.EXTRA_GUIDE_MAX_AGE_MILLIS[folder] ?: HttpEpgFeedProvider.DEFAULT_MAX_AGE_MILLIS,
            logger = logger,
        )
    }

    /** Comptes Xtream Codes : abonnement (date de fin, connexions simultanées) et chaînes avec replay. */
    val xtreamApi = XtreamApi(httpClients, logger)

    /** Qualité des films et épisodes sans mention dans leur nom : mesurée à la lecture, ou lue sur le serveur. */
    val measuredQualities = MeasuredQualities(channelDao, { playlistStore.getSource(it) }, xtreamApi::vodVideo)

    /** Durée des épisodes d'après le serveur Xtream (fiche d'une série). */
    val episodeDurations = tv.nextv.app.data.catalog.EpisodeDurations(
        source = { playlistStore.getSource(it) },
        categories = xtreamApi::seriesCategories,
        series = xtreamApi::series,
        info = xtreamApi::seriesInfo,
    )

    /** Section Foot : tour des chaînes « événement » (LALIGA 1 à 5…) retenu d'un jour à l'autre. */
    val eventTurns = EventTurnStore(appContext.getSharedPreferences("event_turns", Context.MODE_PRIVATE))

    val sourceRepository = SourceRepository(
        store = playlistStore,
        credentialStore = EncryptedCredentialStore(database.credentialDao(), cipher, logger),
        loaders = PlaylistLoaderRegistry(
            listOf(M3uPlaylistLoader(contentOpener, ioDispatcher = LoadingThreads.dispatcher, usesProxy = httpClients::usesProxy, internetRadios = true)),
        ),
        epgLoader = EpgLoader(
            opener = contentOpener,
            importer = EpgImporter(parserFactory = { Xml.newPullParser() }),
            ioDispatcher = LoadingThreads.dispatcher,
            usesProxy = httpClients::usesProxy,
        ),
        redactor = redactor,
        logger = logger,
        documentAccess = documentAccess,
        epgFeed = epgFeed,
        extraEpgFeeds = extraEpgFeeds,
        sourceEpgFeed = ::providerGuide,
        catchupDays = xtreamApi::archiveDays,
    )

    /** Lecteurs plein écran ouverts (un seul en pratique ; deux le temps de passer d'un titre à l'autre). */
    private val openPlayers = MutableStateFlow(0)

    /** Vrai tant qu'un film ou une chaîne se lit en plein écran : les actualisations automatiques attendent. */
    val playbackOpen: StateFlow<Boolean> = openPlayers.map { it > 0 }.stateIn(applicationScope, SharingStarted.Eagerly, false)

    fun playerOpened() = openPlayers.update { it + 1 }

    fun playerClosed() = openPlayers.update { (it - 1).coerceAtLeast(0) }

    val refreshController = RefreshController(applicationScope, sourceRepository, BackgroundWork(playbackOpen))

    val playerFactory = PlayerFactory(appContext, httpClients)

    /** « Baisser le son des pubs » : logo et carton de chaque chaîne, appris une fois et gardés sur l'appareil. */
    val adMarks = AdMarksStore(File(appContext.filesDir, "ad_marks"), applicationScope)

    /** Enregistrements pour l'analyse des pubs (options du lecteur), envoyés sur filebin ([adUploads]). */
    private val adRecordingsLazy = lazy { createAdRecordings() }
    val adRecordings: AdRecordings by adRecordingsLazy

    /**
     * Enregistrement pour l'analyse des pubs en cours (il survit à la fermeture du lecteur) : les chaînes s'ouvrent alors
     * dans leur moins bonne version (voir [tv.nextv.core.stream.QualityTarget.LOWEST]).
     */
    val analysisRecording: Boolean get() = adRecordingsLazy.isInitialized() && adRecordings.current != null

    private fun createAdRecordings(): AdRecordings =
        AdRecordings(File(appContext.filesDir, "ad_recordings"), BuildConfig.VERSION_NAME, removable = { removableRecordingPlaces(appContext) }).also { recordings ->
            applicationScope.launch { settings.adRecordingsLocation.collect { recordings.selected = it } }
            // Clés USB lues en arrière-plan : sur la box, créer un dossier sur la clé a bloqué l'appli plus de 5 s (ANR).
            applicationScope.launch(Dispatchers.IO) { recordings.load() }
            // Chaque enregistrement terminé part sur filebin.
            recordings.onSaved = { adUploads.sendPending() }
        }

    /** Temps déjà enregistré pour l'analyse des pubs, par chaîne : l'enregistrement automatique va aux moins connues. */
    val adTested: AdTestedChannels by lazy { AdTestedChannels(File(appContext.filesDir, "ad_tested.tsv")) }

    /** Page filebin où partent les enregistrements d'analyse ; `null` tant qu'elle n'est pas tirée au hasard. */
    @Volatile
    private var adUploadPage: String? = null

    /** Envoi des enregistrements d'analyse sur filebin.net, pour que Claude aille les y chercher. */
    val adUploads: AdUploads by lazy {
        AdUploads(adRecordings, httpClients.client(RequestPurpose.METADATA), applicationScope, bin = { adUploadPage }).also { uploads ->
            applicationScope.launch {
                settings.adUploadBin.collect { page ->
                    adUploadPage = page
                    // Page tirée au hasard la première fois ; ensuite (et à chaque lancement) ce qui n'est pas encore
                    // parti part maintenant.
                    if (page == null) settings.ensureAdUploadBin(AdUploads::newBin) else uploads.sendPending()
                }
            }
        }
    }

    private val readingUsb = AtomicBoolean(false)

    /**
     * Relit les clés USB en arrière-plan (emplacements des enregistrements, musique des pubs), prêtes quand on lancera un
     * enregistrement ou qu'une pub commencera. Rien si une lecture est déjà en cours (clé lente).
     */
    fun refreshAdPlacesInBackground() {
        if (!readingUsb.compareAndSet(false, true)) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                adRecordings.locations()
                adMusic.refresh()
            } finally {
                readingUsb.set(false)
            }
        }
    }

    /** Musique jouée pendant les pubs : envoyée depuis le téléphone (Réglages › Son), ou dossier « NixTV/musique » d'une clé USB. */
    val adMusic by lazy {
        AdMusicLibrary(File(appContext.filesDir, "ad_music")) { removableMusicFolders(appContext) }.also { music ->
            applicationScope.launch(Dispatchers.IO) { music.refresh() }
        }
    }

    /** « Baisser le son des pubs » : pubs déjà entendues (empreintes du son), reconnues sur toutes les chaînes. */
    val adPrints by lazy {
        AdPrintStore(
            File(appContext.filesDir, "ad_prints/library.bin"),
            applicationScope,
            // Appareil d'environ 1 Go (Fire TV d'entrée de gamme) : moitié moins de pubs gardées (~2 Mo en mémoire).
            if (smallMemoryDevice(appContext)) AdPrintLibrary.DEFAULT_MAX_PRINTS / 2 else AdPrintLibrary.DEFAULT_MAX_PRINTS,
        )
    }

    /** Aperçu de l'accueil confié au lecteur plein écran, quand on ouvre la chaîne de l'aperçu. */
    val previewHandoff = PreviewHandoffSlot(applicationScope)

    /**
     * Clé de la dernière chaîne lue en plein écran (après un zapping, celle sur laquelle on s'est arrêté) : au retour,
     * l'accueil se place sur elle plutôt que sur celle ouverte au départ.
     */
    val lastLiveChannel = MutableStateFlow<String?>(null)

    /**
     * Catégorie du menu des chaînes du lecteur où la chaîne regardée a été choisie (`null` : aucune, ouverte depuis
     * l'accueil ou atteinte en zappant) : l'accueil y passe pour que le retour tombe sur elle.
     */
    val lastLiveCategory = MutableStateFlow<Category?>(null)

    /**
     * Match choisi dans la section Foot, pris par le lecteur qui s'ouvre sur l'une de ses chaînes : ▲ ▼ passent d'une
     * chaîne du match à l'autre, et le menu des chaînes s'ouvre sur sa catégorie.
     */
    val pendingMatch = MutableStateFlow<tv.nextv.app.ui.player.MenuMatch?>(null)

    /**
     * Place de la chaîne regardée ([lastLiveChannel]) dans la liste des chaînes que l'accueil montrera au retour
     * (catégorie et rang, lus dans la base par HomeViewModel.followWatchedChannel) : la liste se charge jusqu'à elle.
     */
    val lastLivePlace = MutableStateFlow<WatchedPlace?>(null)

    /**
     * Qualité choisie à la main pour une chaîne dans les options du lecteur (nom de StreamQuality, "" : version sans
     * mention de qualité), par chaîne ([lockedQualityKey]). Gardée en mémoire seulement : jusqu'à la fermeture de
     * l'application, la chaîne s'ouvre dans cette qualité, sans qualité automatique.
     */
    val lockedQualities = ConcurrentHashMap<String, String>()

    fun lockedQualityKey(sourceId: Long, itemKey: String) = "$sourceId|$itemKey"

    /** Clé TMDB : celle saisie dans les réglages, sinon celle fournie à la compilation (secret CI). */
    private val tmdbKey: StateFlow<String?> = settings.tmdbApiKey
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /** Traduction des résumés TMDB en anglais et des descriptions du guide dans une autre langue. */
    val translator = Translator(httpClients, logger)

    val metadata = MetadataRepository(
        database = database,
        client = TmdbClient(
            clients = httpClients,
            apiKey = { tmdbKey.value ?: BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() } },
            logger = logger,
            fallbackApiKey = { BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() } },
            translator = translator,
        ),
    )

    /** Clé football-data.org : celle saisie dans les réglages, sinon celle fournie à la compilation (secret CI). */
    private val footballKey: StateFlow<String?> = settings.footballApiKey
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /** Scores et minute de jeu en direct (tableau des scores public d'ESPN), partagés par la section Foot et le lecteur. */
    val liveScores = LiveScoreClient(httpClients, logger = logger)

    /** Début des mi-temps vues (temps restant avant la reprise dans le bandeau du lecteur). */
    val halfTimes = HalfTimes()

    /** Météo de l'écran des pubs (Open-Meteo, sans clé). */
    val weather = tv.nextv.core.weather.WeatherClient(httpClients, logger = logger)

    /** Durée habituelle des pubs de chaque chaîne (« Fin prévue dans… » de l'écran des pubs), gardée sur l'appareil. */
    val adBreakDurations: tv.nextv.core.audio.AdBreakDurations by lazy {
        tv.nextv.core.audio.AdBreakDurations.decode(adBreakPrefs.getString(AD_BREAKS_KEY, null))
    }

    private val adBreakPrefs by lazy { appContext.getSharedPreferences("ad_breaks", Context.MODE_PRIVATE) }

    /** Page de pub terminée sur [channel] : sa durée compte pour la durée habituelle de la chaîne. */
    fun noteAdBreak(channel: String, millis: Long) {
        if (adBreakDurations.add(channel, millis)) adBreakPrefs.edit().putString(AD_BREAKS_KEY, adBreakDurations.encode()).apply()
    }

    /** Galas de la section MMA (calendrier public d'ESPN : UFC, PFL, Cage Warriors). */
    val mmaSchedule = tv.nextv.core.mma.MmaScheduleClient(httpClients, logger = logger)

    /** Matchs à venir de la section Foot (football-data.org, résultats gardés 30 min). */
    val footballData = FootballDataClient(
        clients = httpClients,
        apiKey = { footballKey.value ?: BuildConfig.FOOTBALL_API_KEY.takeIf { it.isNotBlank() } },
        logger = logger,
        liveScores = liveScores,
    )

    val favorites = FavoritesRepository(database)

    /** Filtres des réglages appliqués aux listes de chaînes (4K masquée, région de France 3). */
    val channelFilters = ChannelFilters(channelDao, hiddenGroupDao)

    /** Reprise de lecture des films et séries (base distincte, sans migration de celle des playlists). */
    private val progressDatabase = ProgressDatabase.create(appContext)
    val progress = ProgressRepository(progressDatabase)

    /** Films et épisodes regardés jusqu'au bout (badge « Déjà vu »). */
    private val watchedDatabase = WatchedDatabase.create(appContext)
    val watched = WatchedRepository(watchedDatabase)

    /** Favoris, reprises, « Déjà vu » et chaînes récentes dont la clé a changé (versions regroupées sous un autre nom). */
    val itemKeyRelinker = tv.nextv.app.data.catalog.ItemKeyRelinker(database, progress, watched, settings)

    /** Sauvegarde et restauration des playlists, favoris, catégories masquées, reprises, « Déjà vu » et réglages. */
    val backup = BackupManager(
        playlistStore = playlistStore,
        sources = sourceRepository,
        favoriteDao = database.favoriteDao(),
        hiddenGroupDao = hiddenGroupDao,
        settings = settings,
        progress = progress,
        watched = watched,
        channelDao = channelDao,
        inTransaction = { block -> database.withTransaction { block() } },
    )

    /**
     * Télés reliées (Réglages › Télés reliées) : favoris, « Déjà vu », reprises, catégories masquées et réglages
     * partagés par un document chiffré déposé sur filebin, liaison par un code à 6 chiffres.
     */
    val tvSync: tv.nextv.app.data.sync.TvSync by lazy {
        val engine = tv.nextv.core.sync.TvSyncEngine(
            relay = tv.nextv.app.data.sync.FilebinRelay(httpClients.client(RequestPurpose.METADATA)),
            local = tv.nextv.app.data.sync.RoomSyncLocal(
                playlistStore = playlistStore,
                sources = sourceRepository,
                favoriteDao = database.favoriteDao(),
                hiddenGroupDao = hiddenGroupDao,
                settings = settings,
                progress = progress,
                watched = watched,
                channelDao = channelDao,
                inTransaction = { block -> database.withTransaction { block() } },
            ),
            store = tv.nextv.app.data.sync.EncryptedSyncStateStore(java.io.File(appContext.filesDir, "sync"), cipher::encrypt, cipher::decrypt),
            deviceName = { deviceName() },
            appVersion = BuildConfig.VERSION_NAME,
            logger = logger,
        )
        val changes = kotlinx.coroutines.flow.merge(
            database.invalidationTracker.createFlow("favorites", "hidden_groups", "sources", emitInitialState = false).map { },
            watchedDatabase.invalidationTracker.createFlow("watched", emitInitialState = false).map { },
            progressDatabase.invalidationTracker.createFlow("progress", emitInitialState = false).map { },
            settings.syncedChanges,
        )
        tv.nextv.app.data.sync.TvSync(engine, applicationScope, playbackOpen, changes, logger) { id ->
            // Télé sans playlist : celle venue d'une autre télé est chargée tout de suite.
            refreshController.refresh(id, background = true)
        }
    }

    /** Configuration à distance : code affiché sur une télé, configuration envoyée par une autre (filebin, chiffrée). */
    val remoteSetupLink: tv.nextv.core.sync.RemoteSetupLink by lazy {
        tv.nextv.core.sync.RemoteSetupLink(tv.nextv.app.data.sync.FilebinRelay(httpClients.client(RequestPurpose.METADATA)), { deviceName() })
    }

    /** Configuration reçue d'une autre télé : playlist, réglages, clés et catégories masquées installés sur celle-ci. */
    val remoteSetupApplier: tv.nextv.app.data.sync.RemoteSetupApplier by lazy {
        tv.nextv.app.data.sync.RemoteSetupApplier(playlistStore, sourceRepository, hiddenGroupDao, settings)
    }

    /** Code de configuration à distance de cette télé (écran de bienvenue, réglages). Rien n'est déposé pendant les tests. */
    val remoteSetupReceiver: tv.nextv.app.data.sync.RemoteSetupReceiver by lazy {
        tv.nextv.app.data.sync.RemoteSetupReceiver(
            link = remoteSetupLink,
            install = { config -> remoteSetupApplier.apply(config) },
            scope = applicationScope,
            logger = logger,
            enabled = Build.FINGERPRINT != "robolectric",
        )
    }

    /** Nom de la télé montré aux autres télés reliées : celui donné dans les paramètres de l'appareil, sinon son modèle. */
    private fun deviceName(): String {
        val named = runCatching { android.provider.Settings.Global.getString(appContext.contentResolver, "device_name") }.getOrNull()
        return named?.takeIf { it.isNotBlank() } ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    /** Mises à jour de l'application (versions publiées sur GitHub). */
    val updater = AppUpdater(
        appContext,
        // APK du processeur de l'appareil (deux fois plus léger que l'APK universel) quand il est publié.
        UpdateChecker(httpClients, BuildConfig.UPDATE_REPOSITORY, logger = logger, supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()),
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
        tvSync.start()
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
        // Clé football-data.org : masquée dans les journaux comme les autres secrets.
        applicationScope.launch {
            settings.footballApiKey.collect { redactor.registerSecret(it) }
        }
        redactor.registerSecret(BuildConfig.FOOTBALL_API_KEY.takeIf { it.isNotBlank() })
        // Clé AudD (reconnaissance des morceaux des radios) : masquée de même.
        applicationScope.launch {
            settings.auddApiKey.collect { redactor.registerSecret(it) }
        }
        redactor.registerSecret(BuildConfig.AUDD_API_KEY.takeIf { it.isNotBlank() })
        // Différé resté sur le disque (application fermée pendant une pause du direct) : jusqu'à plusieurs Go.
        applicationScope.launch(Dispatchers.IO) {
            runCatching { File(appContext.cacheDir, TIMESHIFT_FILE).delete() }
            // Fichier d'une mise à jour déjà installée (ou d'un téléchargement abandonné) : plusieurs dizaines de Mo.
            runCatching { File(appContext.cacheDir, AppUpdater.DOWNLOAD_FOLDER).listFiles()?.forEach { it.delete() } }
            safely("Cache des fiches") { metadata.purge() }
        }
        applicationScope.launch {
            safely("Masquage des identifiants") { sourceRepository.registerStoredSecrets() }
            safely("Noms des playlists") { sourceRepository.cleanStoredNames() }
        }

        // Filtres des réglages (4K masquée, région de France 3, chaînes rassemblées, chaînes françaises seulement) : à
        // chaque changement, et après chaque mise à jour d'une playlist (nouvelles chaînes).
        applicationScope.launch {
            combine(
                settings.hideUhd,
                settings.france3Region,
                settings.mergeChannels,
                settings.frenchOnly,
                sourceRepository.sources.map { sources -> sources.map { it.id to it.status?.lastSuccessAtMillis } }.distinctUntilChanged(),
            ) { hideUhd, region, merge, frenchOnly, sources -> ChannelFilters.Settings(hideUhd, region, merge, frenchOnly) to sources }
                .collectLatest { (filters, sources) ->
                    // Nouvelles règles de rangement (catégories par thème, chaînes françaises) : appliquées tout de suite aux
                    // chaînes enregistrées, sans attendre la relecture de la playlist.
                    safely("Rangement des chaînes") {
                        if (settings.lineupVersion.first() < ChannelLineup.VERSION && sources.isNotEmpty()) {
                            sources.forEach { (id, _) -> channelFilters.rearrange(id) }
                            settings.setLineupVersion(ChannelLineup.VERSION)
                        }
                    }
                    // Une écriture impossible (stockage plein) n'arrête pas la surveillance : nouvel essai au changement suivant.
                    safely("Filtres des chaînes") { sources.forEach { (id, _) -> channelFilters.apply(id, filters) } }
                }
        }

        // Films et séries déjà vus (et pas en cours) : copiés dans la base des chaînes, qui les range en fin de liste.
        applicationScope.launch {
            val seen = database.seenTitleDao()
            settings.activePlaylistId.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                combine(
                    watched.observeKeys(id),
                    progress.observeAll(id, tv.nextv.core.model.ContentKind.MOVIE.name),
                    progress.observeAll(id, tv.nextv.core.model.ContentKind.SERIES.name),
                ) { keys, movies, series -> tv.nextv.app.data.progress.SeenTitles.of(keys, (movies + series).map { it.itemKey }) }
                    .distinctUntilChanged()
                    .collect { keys ->
                        // Écrit seulement ce qui change : chaque écriture fait recharger les listes de films et de séries.
                        safely("Déjà vu en fin de liste") { if (seen.keys(id).toSet() != keys) seen.replace(id, keys) }
                    }
            }
        }

        // Reprises de lecture : rattachées à leurs films et épisodes au lancement et après chaque mise à jour d'une
        // playlist (leur numéro de ligne change quand l'adresse du flux change).
        applicationScope.launch {
            sourceRepository.sources.map { sources -> sources.map { it.id to it.status?.lastSuccessAtMillis } }.distinctUntilChanged()
                .collect { sources ->
                    sources.forEach { (id, _) ->
                        // D'abord les clés des titres renommés par un meilleur nettoyage (favoris, reprises, « Déjà vu »).
                        safely("Favoris et reprises des titres renommés") { itemKeyRelinker.relink(id) }
                        safely("Reprises de lecture") { progress.relink(id, channelDao) }
                    }
                }
        }

        // Actualisations automatiques tant que l'application tourne (les TV restent souvent allumées) :
        // la playlist selon la fréquence choisie dans les réglages, le guide dès qu'il a vieilli.
        applicationScope.launch {
            while (true) {
                delay(PERIODIC_CHECK_MILLIS)
                safely("Actualisation périodique") {
                    val active = settings.activePlaylistId.firstOrNull() ?: return@safely
                    val lastSuccess = sourceRepository.getSource(active)?.status?.lastSuccessAtMillis
                    if (settings.playlistRefresh.first().dueWhileRunning(lastSuccess, System.currentTimeMillis())) {
                        // Playlist puis guide.
                        refreshController.refresh(active, background = true)
                    } else {
                        // Ne retélécharge le guide que s'il a vieilli.
                        refreshController.refreshEpg(active, background = true)
                    }
                }
            }
        }
    }

    /** Étape d'une tâche de fond permanente : une erreur est notée, la tâche continue. */
    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("App", "$what : échec", e)
        }
    }

    private companion object {
        const val AD_BREAKS_KEY = "durations"
        const val HOUR = 60 * 60_000L
        const val PERIODIC_CHECK_MILLIS = 3 * HOUR
    }
}

/**
 * Lance les actualisations dans la portée de l'application : quitter un écran ne les interrompt pas.
 * Les résultats sont diffusés pour être signalés à l'utilisateur.
 */
class RefreshController(
    private val scope: CoroutineScope,
    private val repository: SourceRepository,
    /** Actualisations automatiques : jamais pendant une lecture ([BackgroundWork]). */
    private val background: BackgroundWork? = null,
) {
    data class Event(val sourceId: Long, val result: RefreshResult, val epgOnly: Boolean = false)

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    /**
     * @param background actualisation automatique (au lancement, périodique) et non demandée à l'instant : elle
     *   attend la fin d'une lecture en cours.
     * @param onResult appelé avec le résultat, avant qu'il ne soit diffusé (suites à donner à une réussite).
     */
    fun refresh(sourceId: Long, background: Boolean = false, onResult: suspend (RefreshResult) -> Unit = {}) {
        scope.launch {
            val result = quietly(background) { repository.refresh(sourceId) }
            onResult(result)
            mutableEvents.emit(Event(sourceId, result))
        }
    }

    fun refreshEpg(sourceId: Long, force: Boolean = false, background: Boolean = false, onResult: suspend (RefreshResult) -> Unit = {}) {
        scope.launch {
            val result = quietly(background) { repository.refreshEpg(sourceId, force) }
            onResult(result)
            mutableEvents.emit(Event(sourceId, result, epgOnly = true))
        }
    }

    fun refreshAll() {
        scope.launch {
            repository.refreshAll().forEach { (id, result) -> mutableEvents.emit(Event(id, result)) }
        }
    }

    private suspend fun quietly(wait: Boolean, block: suspend () -> RefreshResult): RefreshResult =
        if (wait && background != null) background.run(block) else block()
}
