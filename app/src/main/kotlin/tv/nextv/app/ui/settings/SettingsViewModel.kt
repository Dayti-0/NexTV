package tv.nextv.app.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.BuildConfig
import tv.nextv.app.RefreshController
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.data.update.UpdateState
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
    /** Catégories de chaînes masquées dans la playlist active. */
    val hiddenChannelGroups: Int = 0,
    /** Aperçu vidéo de la chaîne sélectionnée à l'accueil. */
    val channelPreview: Boolean = true,
    /** Lecture directe de la dernière chaîne regardée à l'ouverture. */
    val startOnLastChannel: Boolean = false,
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

    private val hiddenChannelGroups: Flow<Int> = settings.activePlaylistId.flatMapLatest { id ->
        if (id == null) flowOf(0) else container.hiddenGroupDao.observeCount(id, ContentKind.LIVE.name)
    }

    /** Playlist active, nombre de playlists et catégories de chaînes masquées. */
    private val activePlaylist: Flow<Triple<PlaylistSource?, Int, Int>> =
        combine(repository.sources, settings.activePlaylistId, hiddenChannelGroups) { sources, id, hidden ->
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
    )

    private val preferences = combine(
        combine(settings.autoQuality, settings.channelPreview, settings.startOnLastChannel) { autoQuality, preview, startLast ->
            Triple(autoQuality, preview, startLast)
        },
        combine(settings.tmdbApiKey, settings.footballApiKey) { tmdb, football -> (tmdb != null) to (football != null) },
        settings.proxySettings,
        settings.gridColumns,
        settings.dnsProvider,
    ) { (autoQuality, preview, startLast), (tmdbKey, footballKey), proxy, grid, dns ->
        Preferences(autoQuality, preview, startLast, tmdbKey, footballKey, proxy, grid, dns)
    }

    val state: StateFlow<SettingsUiState> = combine(
        activePlaylist,
        repository.refreshing,
        guideDownloadedAt,
        preferences,
        decoderChoices,
    ) { (playlist, count, hidden), refreshing, downloadedAt, prefs, decoders ->
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
            hiddenChannelGroups = hidden,
            channelPreview = prefs.channelPreview,
            startOnLastChannel = prefs.startOnLastChannel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun setStartOnLastChannel(enabled: Boolean) {
        viewModelScope.launch { settings.setStartOnLastChannel(enabled) }
    }

    fun setChannelPreview(enabled: Boolean) {
        viewModelScope.launch { settings.setChannelPreview(enabled) }
    }

    fun setGridColumns(columns: GridColumns) {
        viewModelScope.launch { settings.setGridColumns(columns) }
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
