package tv.nextv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.BuildConfig
import tv.nextv.app.RefreshController
import tv.nextv.app.player.DecoderOption
import tv.nextv.app.player.VideoDecoders
import tv.nextv.core.model.PlaylistSource

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
    /** Dernier téléchargement du guide commun (xmltvfr.fr). */
    val guideDownloadedAt: Long? = null,
    val proxyEnabled: Boolean = false,
    val proxyAddress: String? = null,
    val version: String = "",
    /** Décodeurs vidéo proposés par l'appareil et choix de l'utilisateur, par codec. */
    val decoders: List<DecoderChoice> = emptyList(),
)

/** @property selected décodeur choisi, `null` : automatique. */
data class DecoderChoice(val mimeType: String, val options: List<DecoderOption>, val selected: String?)

/** Réglages : playlist active, lecture, guide TV, fiches TMDB et réseau. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val repository = container.sourceRepository

    private val activePlaylist: Flow<Pair<PlaylistSource?, Int>> =
        combine(repository.sources, settings.activePlaylistId) { sources, id ->
            sources.firstOrNull { it.id == id } to sources.size
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

    private val preferences = combine(
        settings.autoQuality,
        settings.tmdbApiKey,
        settings.proxySettings,
    ) { autoQuality, tmdbKey, proxy -> Triple(autoQuality, tmdbKey != null, proxy) }

    val state: StateFlow<SettingsUiState> = combine(
        activePlaylist,
        repository.refreshing,
        guideDownloadedAt,
        preferences,
        decoderChoices,
    ) { (playlist, count), refreshing, downloadedAt, (autoQuality, tmdbKeySaved, proxy), decoders ->
        SettingsUiState(
            loaded = true,
            playlist = playlist,
            playlistCount = count,
            refreshing = playlist != null && playlist.id in refreshing,
            autoQuality = autoQuality,
            tmdbKeySaved = tmdbKeySaved,
            tmdbBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank(),
            guideDownloadedAt = downloadedAt,
            proxyEnabled = proxy.enabled,
            proxyAddress = proxy.host.takeIf { proxy.enabled && it.isNotBlank() }?.let { "$it:${proxy.port}" },
            version = BuildConfig.VERSION_NAME,
            decoders = decoders,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun saveTmdbKey(key: String) {
        viewModelScope.launch { settings.setTmdbApiKey(key) }
    }

    fun clearTmdbKey() {
        viewModelScope.launch { settings.setTmdbApiKey(null) }
    }
}
