package tv.nextv.app.ui.settings

import tv.nextv.app.player.AdUploads
import tv.nextv.app.player.AdRadios
import tv.nextv.app.data.settings.AdSound
import tv.nextv.app.player.allFilesAccessIntent
import tv.nextv.app.player.describeStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.nextv.app.player.AdRecordings
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.BuildConfig
import tv.nextv.app.CrashLog
import tv.nextv.app.RefreshController
import tv.nextv.app.data.backup.RestoreResult
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.data.settings.PlaylistRefresh
import tv.nextv.app.data.settings.PreviewVolume
import tv.nextv.app.data.update.UpdateState
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.stream.QualityTarget
import tv.nextv.core.catalog.FranceRegions
import tv.nextv.app.player.DecoderOption
import tv.nextv.app.player.VideoDecoders
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.source.XtreamAccount
import tv.nextv.core.source.XtreamAccountInfo
import tv.nextv.core.update.AppRelease

/** Rubriques de sport de l'accueil affichées. */
data class SportSections(val football: Boolean = true, val mma: Boolean = true)

data class SettingsUiState(
    val loaded: Boolean = false,
    /** Playlist affichée à l'accueil. */
    val playlist: PlaylistSource? = null,
    val playlistCount: Int = 0,
    val refreshing: Boolean = false,
    val autoQuality: Boolean = true,
    /** Clé TMDB saisie dans les réglages (jamais affichée). */
    val tmdbKeySaved: Boolean = false,
    /** Clé TMDB fournie à la compilation, utilisée à défaut de clé saisie. */
    val tmdbBuiltIn: Boolean = false,
    /** Clé football-data.org saisie dans les réglages (jamais affichée), ou fournie à la compilation. */
    val footballKeySaved: Boolean = false,
    val footballBuiltIn: Boolean = false,
    /** Dernier téléchargement du guide commun (xmltvfr.fr). */
    val guideDownloadedAt: Long? = null,
    val proxyEnabled: Boolean = false,
    val proxyAddress: String? = null,
    val version: String = "",
    /** Décodeurs vidéo proposés par l'appareil et choix de l'utilisateur, par codec. */
    val decoders: List<DecoderChoice> = emptyList(),
    /** Chaînes et affiches par ligne à l'accueil. */
    val gridColumns: GridColumns = GridColumns(),
    val dns: DnsProvider = DnsProvider.SYSTEM,
    /** Catégories masquées dans la playlist active, par type de contenu. */
    val hiddenGroups: Map<ContentKind, Int> = emptyMap(),
    /** Aperçu vidéo de la chaîne sélectionnée à l'accueil. */
    val channelPreview: Boolean = true,
    /** Passage sans coupure de l'aperçu au plein écran, et retour (Android 10 et plus). */
    val previewHandoff: Boolean = true,
    /** Bande-annonce en fond de la grande affiche (Streaming). */
    val trailerAutoplay: Boolean = true,
    /** Bande-annonce avec le son dès son lancement. */
    val trailerSound: Boolean = false,
    /** Délai avant la bande-annonce, en secondes. */
    val trailerDelaySeconds: Int = 3,
    /** Lecture directe de la dernière chaîne regardée à l'ouverture. */
    val startOnLastChannel: Boolean = false,
    /** Son baissé pendant les publicités. */
    val lowerAdVolume: Boolean = false,
    /** Thème de l'interface. */
    val theme: ThemeStyle = ThemeStyle.NIGHT,
    /** Interface de l'accueil. */
    val homeLayout: HomeLayout = HomeLayout.DEFAULT,
    /** Qualité visée à l'ouverture d'une chaîne. */
    val qualityTarget: QualityTarget = QualityTarget.BEST,
    /** Son de l'aperçu vidéo de l'accueil. */
    val previewVolume: PreviewVolume = PreviewVolume.LOW,
    /** Fréquence de mise à jour automatique de la playlist. */
    val playlistRefresh: PlaylistRefresh = PlaylistRefresh.HOURS_12,
    /** Dernier plantage de l'application, à signaler. */
    val lastCrash: CrashLog.Entry? = null,
    /** Chaînes 4K masquées. */
    val hideUhd: Boolean = false,
    /** Son Dolby / DTS envoyé tel quel à l'ampli (sinon décodé par l'appareil). */
    val audioPassthrough: Boolean = false,
    /** Région de France 3 gardée (clé), `null` : toutes (France 3 seule si les chaînes sont rassemblées). */
    val france3Region: String? = null,
    /** « Rassembler les chaînes » : une ligne par chaîne, listes rangées comme sur une télé. */
    val mergeChannels: Boolean = true,
    val frenchOnly: Boolean = false,
    /** Éditions régionales de France 3 de la playlist active. */
    val france3Regions: List<FranceRegions.Region> = emptyList(),
)

/** Abonnement Xtream de la playlist active. */
sealed interface AccountState {
    data object Loading : AccountState

    data class Loaded(val info: XtreamAccountInfo) : AccountState

    /** Le serveur n'a pas répondu. */
    data object Unavailable : AccountState
}

/** @property selected décodeur choisi, `null` : automatique. */
data class DecoderChoice(val mimeType: String, val options: List<DecoderOption>, val selected: String?)

/** Réglages : playlist active, lecture, guide TV, fiches TMDB et réseau. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val repository = container.sourceRepository

    /** Catégories masquées de la playlist active, par type de contenu. */
    private val hiddenGroups: Flow<Map<ContentKind, Int>> = settings.activePlaylistId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyMap())
        } else {
            val dao = container.hiddenGroupDao
            combine(
                dao.observeCount(id, ContentKind.LIVE.name),
                dao.observeCount(id, ContentKind.MOVIE.name),
                dao.observeCount(id, ContentKind.SERIES.name),
            ) { live, movies, series -> mapOf(ContentKind.LIVE to live, ContentKind.MOVIE to movies, ContentKind.SERIES to series) }
        }
    }

    /** Playlist active, nombre de playlists et catégories masquées. */
    private val activePlaylist: Flow<Triple<PlaylistSource?, Int, Map<ContentKind, Int>>> =
        combine(repository.sources, settings.activePlaylistId, hiddenGroups) { sources, id, hidden ->
            Triple(sources.firstOrNull { it.id == id }, sources.size, hidden)
        }

    /** Relu à chaque fin d'actualisation : le fichier du guide a pu être retéléchargé. */
    private val guideDownloadedAt: Flow<Long?> = repository.refreshing
        .map { container.epgFeed.cached()?.fetchedAtMillis }
        .flowOn(Dispatchers.IO)

    /** Liste des décodeurs (interroge le système une fois, hors du fil principal). */
    private val availableDecoders: Flow<Map<String, List<DecoderOption>>> =
        flow { emit(VideoDecoders.CHOOSABLE.associateWith { VideoDecoders.list(it) }) }.flowOn(Dispatchers.IO)

    private val decoderChoices: Flow<List<DecoderChoice>> =
        combine(availableDecoders, settings.preferredDecoders) { available, preferred ->
            VideoDecoders.CHOOSABLE.map { mime -> DecoderChoice(mime, available[mime].orEmpty(), preferred[mime]) }
        }

    private data class Preferences(
        val autoQuality: Boolean,
        val channelPreview: Boolean,
        val startOnLastChannel: Boolean,
        val tmdbKeySaved: Boolean,
        val footballKeySaved: Boolean,
        val proxy: ProxySettings,
        val grid: GridColumns,
        val dns: DnsProvider,
        val lowerAdVolume: Boolean,
        val theme: ThemeStyle,
        val homeLayout: HomeLayout,
        val qualityTarget: QualityTarget,
    )

    private val preferences = combine(
        combine(settings.autoQuality, settings.channelPreview, settings.startOnLastChannel, settings.qualityTarget) { autoQuality, preview, startLast, target ->
            Triple(autoQuality, preview, startLast) to target
        },
        combine(settings.tmdbApiKey, settings.footballApiKey, settings.lowerAdVolume) { tmdb, football, ads -> Triple(tmdb != null, football != null, ads) },
        settings.proxySettings,
        settings.gridColumns,
        combine(settings.dnsProvider, settings.themeStyle, settings.homeLayout, ::Triple),
    ) { (playback, target), (tmdbKey, footballKey, ads), proxy, grid, (dns, theme, layout) ->
        val (autoQuality, preview, startLast) = playback
        Preferences(autoQuality, preview, startLast, tmdbKey, footballKey, proxy, grid, dns, ads, theme, layout, target)
    }

    private val lastCrash = MutableStateFlow(container.crashLog?.last())

    /** Éditions régionales de France 3 de la playlist active, relues après chaque mise à jour. */
    private val france3Regions: Flow<List<FranceRegions.Region>> = combine(
        settings.activePlaylistId,
        repository.sources.map { sources -> sources.map { it.id to it.status?.lastSuccessAtMillis } }.distinctUntilChanged(),
        ::Pair,
    ).mapLatest { (id, _) -> id?.let { container.channelFilters.france3Regions(it) }.orEmpty() }

    private data class Extras(
        val previewVolume: PreviewVolume,
        val playlistRefresh: PlaylistRefresh,
        val crash: CrashLog.Entry?,
        val hideUhd: Boolean,
        val audioPassthrough: Boolean,
        val france3Region: String?,
        val france3Regions: List<FranceRegions.Region>,
        val trailerAutoplay: Boolean,
        val trailerSound: Boolean,
        val trailerDelaySeconds: Int,
        val previewHandoff: Boolean,
        val mergeChannels: Boolean,
        val frenchOnly: Boolean,
    )

    private data class Toggles(
        val hideUhd: Boolean,
        val audioPassthrough: Boolean,
        val trailerAutoplay: Boolean,
        val mergeChannels: Boolean,
        val frenchOnly: Boolean,
    )

    /** Réglages ajoutés depuis : son de l'aperçu, fréquence de mise à jour, dernier plantage, filtres des chaînes. */
    private val extras = combine(
        combine(settings.previewVolume, settings.playlistRefresh, lastCrash, ::Triple),
        combine(settings.hideUhd, settings.audioPassthrough, settings.trailerAutoplay, settings.mergeChannels, settings.frenchOnly, ::Toggles),
        combine(settings.france3Region, france3Regions, settings.trailerSound, settings.trailerDelaySeconds, settings.previewHandoff) { region, regions, sound, delay, handoff ->
            listOf(region, regions, sound, delay, handoff)
        },
    ) { (volume, refresh, crash), toggles, more ->
        @Suppress("UNCHECKED_CAST")
        Extras(
            volume, refresh, crash, toggles.hideUhd, toggles.audioPassthrough, more[0] as String?, more[1] as List<FranceRegions.Region>,
            toggles.trailerAutoplay, more[2] as Boolean, more[3] as Int, more[4] as Boolean, toggles.mergeChannels,
            toggles.frenchOnly,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        combine(activePlaylist, extras, ::Pair),
        repository.refreshing,
        guideDownloadedAt,
        preferences,
        decoderChoices,
    ) { (active, extra), refreshing, downloadedAt, prefs, decoders ->
        val (playlist, count, hidden) = active
        val proxy = prefs.proxy
        SettingsUiState(
            loaded = true,
            playlist = playlist,
            playlistCount = count,
            refreshing = playlist != null && playlist.id in refreshing,
            autoQuality = prefs.autoQuality,
            tmdbKeySaved = prefs.tmdbKeySaved,
            tmdbBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank(),
            footballKeySaved = prefs.footballKeySaved,
            footballBuiltIn = BuildConfig.FOOTBALL_API_KEY.isNotBlank(),
            guideDownloadedAt = downloadedAt,
            proxyEnabled = proxy.enabled,
            proxyAddress = proxy.host.takeIf { proxy.enabled && it.isNotBlank() }?.let { "$it:${proxy.port}" },
            version = BuildConfig.VERSION_NAME,
            decoders = decoders,
            gridColumns = prefs.grid,
            dns = prefs.dns,
            hiddenGroups = hidden,
            channelPreview = prefs.channelPreview,
            startOnLastChannel = prefs.startOnLastChannel,
            lowerAdVolume = prefs.lowerAdVolume,
            theme = prefs.theme,
            homeLayout = prefs.homeLayout,
            qualityTarget = prefs.qualityTarget,
            previewVolume = extra.previewVolume,
            playlistRefresh = extra.playlistRefresh,
            lastCrash = extra.crash,
            hideUhd = extra.hideUhd,
            audioPassthrough = extra.audioPassthrough,
            france3Region = extra.france3Region,
            france3Regions = extra.france3Regions,
            mergeChannels = extra.mergeChannels,
            frenchOnly = extra.frenchOnly,
            trailerAutoplay = extra.trailerAutoplay,
            trailerSound = extra.trailerSound,
            trailerDelaySeconds = extra.trailerDelaySeconds,
            previewHandoff = extra.previewHandoff,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Avancement de la mise à jour de la playlist active (étape, part faite), `null` hors mise à jour ; lu 4 fois par seconde. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val refreshProgress: StateFlow<tv.nextv.core.io.LoadProgress?> = combine(activePlaylist, repository.progress) { (playlist, _, _), progress ->
        playlist?.let { progress[it.id] }
    }
        .sample(250)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Abonnement de la playlist active si c'est un compte Xtream, demandé au serveur à l'ouverture des réglages. */
    val account: StateFlow<AccountState?> = activePlaylist
        .map { (playlist, _, _) -> playlist?.takeIf { (it.location as? SourceLocation.Remote)?.url?.let(XtreamAccount::parse) != null } }
        .distinctUntilChanged { old, new -> old?.id == new?.id && old?.location == new?.location && old?.mirrors == new?.mirrors }
        .transformLatest { playlist ->
            if (playlist == null) {
                emit(null)
            } else {
                emit(AccountState.Loading)
                emit(container.xtreamApi.accountInfo(playlist)?.let(AccountState::Loaded) ?: AccountState.Unavailable)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Mise à jour de l'application. */
    val update: StateFlow<UpdateState> = container.updater.state

    /** Enregistrements pour l'analyse des pubs gardés sur l'appareil. */
    val adRecordings: StateFlow<List<AdRecordings.Recording>> = container.adRecordings.files

    // Premier affichage avec ce qui est déjà connu, sans toucher aux clés USB : sur la box, créer un dossier sur la clé
    // depuis le fil principal a bloqué l'ouverture des réglages plus de 5 s (ANR). Relu en arrière-plan ([refreshAdPlaces]).
    private val mutableAdPlace = MutableStateFlow(adPlace(container.adRecordings.selected, fresh = false))

    /** Emplacement des enregistrements choisi (et s'il est branché). */
    val adPlace: StateFlow<AdPlace> = mutableAdPlace

    /**
     * État de l'emplacement [key]. [fresh] : clés relues (hors du fil principal seulement) ; sinon, la dernière lecture,
     * sans la place libre d'une clé ni la description des stockages.
     */
    private fun adPlace(key: String?, fresh: Boolean = true): AdPlace {
        val locations = if (fresh) container.adRecordings.locations() else container.adRecordings.knownLocations()
        val location = locations.firstOrNull { it.key == key }
        return AdPlace(
            usb = key != null,
            label = location?.label,
            freeBytes = location?.takeIf { it.access == AdRecordings.Access.READY && (fresh || it.key == null) }?.freeBytes,
            missing = location == null && fresh,
            access = location?.access ?: AdRecordings.Access.READY,
            storage = if (fresh) describeStorage(container.appContext) else "",
            recordingMinutes = container.adRecordings.current?.takeIf { it.active }?.let { (it.recordedMillis / 60_000).toInt() },
        )
    }

    /** Relit les emplacements (clé USB branchée ou retirée) et les enregistrements. */
    fun refreshAdPlaces() {
        // Réglages ouverts : un envoi raté (réseau coupé) est retenté.
        container.adUploads.sendPending()
        viewModelScope.launch {
            val key = settings.adRecordingsLocation.first()
            container.adRecordings.selected = key
            mutableAdPlace.value = withContext(Dispatchers.IO) {
                container.adRecordings.refresh()
                container.adMusic.refresh()
                adPlace(key)
            }
        }
    }

    /** Arrête l'enregistrement d'analyse en cours (il continue même lecteur fermé) ; la liste est relue ensuite. */
    fun stopAdRecording() {
        container.adRecordings.stop()
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            refreshAdPlaces()
        }
    }

    /** Emplacement suivant : mémoire de l'appareil, puis chaque clé USB branchée. */
    fun cycleAdPlace() {
        viewModelScope.launch {
            val locations = withContext(Dispatchers.IO) { container.adRecordings.locations() }
            val index = locations.indexOfFirst { it.key == container.adRecordings.selected }
            val next = locations[(index + 1) % locations.size]
            container.adRecordings.selected = next.key
            settings.setAdRecordingsLocation(next.key)
            mutableAdPlace.value = withContext(Dispatchers.IO) { adPlace(next.key) }
        }
    }

    /** Partage des enregistrements sur le réseau local, tant que le résultat n'est pas fermé ; `null` sans réseau. */
    fun shareAdMusic(): AdRecordings.Share? = container.adRecordings.share(container.adMusic)

    /** Clé AudD enregistrée (reconnaissance des morceaux des radios). */
    val auddKeySaved: StateFlow<Boolean> = settings.auddApiKey.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveAuddKey(key: String) {
        viewModelScope.launch { settings.setAuddApiKey(key) }
    }

    fun clearAuddKey() {
        viewModelScope.launch { settings.setAuddApiKey(null) }
    }

    /** Animation des radios (son sans image). */
    val radioVisual: StateFlow<tv.nextv.app.data.settings.RadioVisual> = settings.radioVisual
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tv.nextv.app.data.settings.RadioVisual.DEFAULT)

    fun setRadioVisual(visual: tv.nextv.app.data.settings.RadioVisual) {
        viewModelScope.launch { settings.setRadioVisual(visual) }
    }

    /** Pendant les pubs : mode, radio choisie, morceaux sur l'appareil. */
    val adSound: StateFlow<AdSoundUi> = combine(settings.adSound, settings.adRadio, container.adMusic.count, settings.adMusicLevel) { mode, radio, tracks, level ->
        AdSoundUi(mode, AdRadios.of(radio).name, tracks, AdRadios.of(radio).key, level)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdSoundUi())

    /** Son baissé, puis une radio, puis la musique de l'appareil. */
    fun cycleAdSound() {
        viewModelScope.launch {
            val modes = listOf(AdSound.LOWER, AdSound.RADIO, AdSound.MUSIC)
            val next = modes[(modes.indexOf(settings.adSound.first()).coerceAtLeast(0) + 1) % modes.size]
            settings.setAdSound(next)
            withContext(Dispatchers.IO) { container.adMusic.refresh() }
        }
    }

    /** Volume de la musique des pubs : niveau suivant (10, 20, 35, 60 %, puis 10 %). */
    fun cycleAdMusicLevel() {
        viewModelScope.launch { settings.setAdMusicLevel(tv.nextv.app.data.settings.AdMusicLevel.next(settings.adMusicLevel.first())) }
    }

    /** Radio choisie dans la liste (Radio France et Web radios). */
    fun setAdRadio(key: String) {
        viewModelScope.launch { settings.setAdRadio(AdRadios.of(key).key) }
    }

    /** Supprime la musique envoyée depuis le téléphone. */
    fun deleteAdMusic() {
        viewModelScope.launch(Dispatchers.IO) { container.adMusic.deleteUploads() }
    }

    fun deleteAdRecordings() {
        viewModelScope.launch(Dispatchers.IO) { container.adRecordings.deleteAll() }
    }

    /** Envoi des enregistrements sur filebin : activé, page de l'appareil, état des envois. */
    val adUpload: StateFlow<AdUploadUi> = combine(settings.adUploadBin, container.adUploads.status) { bin, status ->
        AdUploadUi(bin, status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdUploadUi())

    /** Supprime un seul enregistrement (nom de son `.zip`). */
    fun deleteAdRecording(name: String) {
        viewModelScope.launch(Dispatchers.IO) { container.adRecordings.delete(name) }
    }

    fun checkUpdate() = container.updater.check()

    fun installUpdate(release: AppRelease) = container.updater.install(release)

    fun installPermissionIntent(): Intent? = container.updater.permissionIntent()

    /** Résultats des actualisations de la playlist active. */
    val refreshEvents: Flow<RefreshController.Event> = container.refreshController.events
        .filter { it.sourceId == state.value.playlist?.id }

    fun refreshPlaylist() {
        state.value.playlist?.let { container.refreshController.refresh(it.id) }
    }

    /** Retélécharge le guide même s'il est récent. */
    fun refreshGuide() {
        state.value.playlist?.let { container.refreshController.refreshEpg(it.id, force = true) }
    }

    fun setHideUhd(enabled: Boolean) {
        viewModelScope.launch { settings.setHideUhd(enabled) }
    }

    fun setAudioPassthrough(enabled: Boolean) {
        viewModelScope.launch { settings.setAudioPassthrough(enabled) }
    }

    fun setFrance3Region(region: String?) {
        viewModelScope.launch { settings.setFrance3Region(region) }
    }

    fun setMergeChannels(enabled: Boolean) {
        viewModelScope.launch { settings.setMergeChannels(enabled) }
    }

    fun setFrenchOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setFrenchOnly(enabled) }
    }

    fun setAutoQuality(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoQuality(enabled) }
    }

    /** Passe au décodeur suivant pour ce codec : automatique, puis chaque décodeur proposé. */
    fun cycleDecoder(mimeType: String) {
        val choice = state.value.decoders.firstOrNull { it.mimeType == mimeType } ?: return
        val values = listOf<String?>(null) + choice.options.map { it.name }
        val next = values[(values.indexOf(choice.selected).coerceAtLeast(0) + 1) % values.size]
        viewModelScope.launch { settings.setPreferredDecoder(mimeType, next) }
    }

    /** Passe au résolveur suivant : système, Quad9, Cloudflare, Google. */
    fun cycleDns() {
        val values = DnsProvider.entries
        val next = values[(values.indexOf(state.value.dns) + 1) % values.size]
        viewModelScope.launch { settings.setDnsProvider(next) }
    }

    /** Contenu du fichier de sauvegarde. */
    /** Page « Télés reliées ». */
    val tvLink = TvLinkController(viewModelScope, container.tvSync)

    suspend fun exportBackup(): String = container.backup.export()

    /** @throws tv.nextv.core.backup.BackupFormatException si ce n'est pas une sauvegarde NexTV. */
    suspend fun restoreBackup(text: String): RestoreResult = container.backup.restore(text)

    /** Qualité visée suivante : meilleure, HD maximum, économie. */
    /** Son de l'aperçu suivant : coupé, faible, moyen. */
    fun cyclePreviewVolume() {
        val values = PreviewVolume.entries
        val next = values[(values.indexOf(state.value.previewVolume) + 1) % values.size]
        viewModelScope.launch { settings.setPreviewVolume(next) }
    }

    /** Fréquence de mise à jour suivante : à chaque démarrage, 12 h, 24 h, 2 à 7 jours. */
    fun cyclePlaylistRefresh() {
        val values = PlaylistRefresh.entries
        val next = values[(values.indexOf(state.value.playlistRefresh) + 1) % values.size]
        viewModelScope.launch { settings.setPlaylistRefresh(next) }
    }

    fun clearCrash() {
        container.crashLog?.clear()
        lastCrash.value = null
    }

    fun cycleQualityTarget() {
        val values = QualityTarget.entries
        val next = values[(values.indexOf(state.value.qualityTarget) + 1) % values.size]
        viewModelScope.launch { settings.setQualityTarget(next) }
    }

    /** Chaînes de l'accueil en mosaïque ou en liste. */
    fun toggleChannelList() {
        val grid = state.value.gridColumns
        viewModelScope.launch { settings.setGridColumns(grid.copy(channelList = !grid.channelList)) }
    }

    fun setLowerAdVolume(enabled: Boolean) {
        viewModelScope.launch { settings.setLowerAdVolume(enabled) }
    }

    fun setStartOnLastChannel(enabled: Boolean) {
        viewModelScope.launch { settings.setStartOnLastChannel(enabled) }
    }

    /** Rubriques Foot et MMA affichées à l'accueil. */
    val sportSections: StateFlow<SportSections> = combine(settings.footballSection, settings.mmaSection, ::SportSections)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportSections())

    fun setFootballSection(enabled: Boolean) {
        viewModelScope.launch { settings.setFootballSection(enabled) }
    }

    fun setMmaSection(enabled: Boolean) {
        viewModelScope.launch { settings.setMmaSection(enabled) }
    }

    fun setChannelPreview(enabled: Boolean) {
        viewModelScope.launch { settings.setChannelPreview(enabled) }
    }

    fun setPreviewHandoff(enabled: Boolean) {
        viewModelScope.launch { settings.setPreviewHandoff(enabled) }
    }

    fun setTrailerAutoplay(enabled: Boolean) {
        viewModelScope.launch { settings.setTrailerAutoplay(enabled) }
    }

    fun setTrailerSound(enabled: Boolean) {
        viewModelScope.launch { settings.setTrailerSound(enabled) }
    }

    fun cycleTrailerDelay() {
        viewModelScope.launch { settings.cycleTrailerDelay() }
    }


    fun saveFootballKey(key: String) {
        viewModelScope.launch { settings.setFootballApiKey(key) }
    }

    fun clearFootballKey() {
        viewModelScope.launch { settings.setFootballApiKey(null) }
    }

    fun saveTmdbKey(key: String) {
        viewModelScope.launch { settings.setTmdbApiKey(key) }
    }

    fun clearTmdbKey() {
        viewModelScope.launch { settings.setTmdbApiKey(null) }
    }
}

/**
 * Emplacement des enregistrements pour l'analyse des pubs (Réglages › Son).
 * @property usb une clé USB est choisie (sinon la mémoire de l'appareil).
 * @property label nom de la clé donné par le système.
 * @property missing la clé choisie n'est pas branchée.
 * @property access peut-on y écrire (sinon : autorisation à donner, clé illisible…).
 */
data class AdPlace(
    val usb: Boolean = false,
    val label: String? = null,
    val freeBytes: Long? = null,
    val missing: Boolean = false,
    val access: AdRecordings.Access = AdRecordings.Access.READY,
    /** Stockages annoncés par le système (pour la photo quand une clé n'est pas proposée). */
    val storage: String = "",
    /** Enregistrement d'analyse en cours (minutes), même lecteur fermé ; `null` sans enregistrement. */
    val recordingMinutes: Int? = null,
)

/** Écran du système qui accorde l'accès aux clés USB (« Accès à tous les fichiers »), s'il existe. */
fun SettingsViewModel.allFilesAccess(context: android.content.Context): android.content.Intent? = allFilesAccessIntent(context)

/**
 * Pendant les pubs (Réglages › Son) : son baissé, radio ou musique de l'appareil.
 * @property radio nom de la radio choisie ; @property tracks morceaux de musique sur l'appareil.
 */
data class AdSoundUi(
    val mode: String = AdSound.LOWER,
    val radio: String = AdRadios.all.first().name,
    val tracks: Int = 0,
    /** Clé de la radio choisie (voir AdRadios). */
    val radioKey: String = AdRadios.all.first().key,
    /** Volume de la musique, en pourcentage (voir AdMusicLevel). */
    val level: Int = tv.nextv.app.data.settings.AdMusicLevel.DEFAULT,
)

/** Envoi des enregistrements d'analyse sur filebin (Réglages › Son). */
data class AdUploadUi(val bin: String? = null, val status: AdUploads.Status = AdUploads.Status()) {
    /** Adresse complète de la page filebin (pour le QR code), `null` tant qu'elle n'est pas tirée. */
    val url: String? get() = bin?.let { "https://filebin.net/$it" }
}
