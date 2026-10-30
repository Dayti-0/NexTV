package tv.nextv.app.ui.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nextv.app.AppContainer
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.InvalidSourceInputException
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceInputField
import tv.nextv.core.net.UserAgents
import tv.nextv.core.source.XtreamAccount

/** Lien de playlist, compte Xtream Codes (serveur, identifiant, mot de passe) ou fichier. */
enum class LocationMode { URL, XTREAM, FILE }

/** Suite à donner à l'enregistrement. */
enum class SaveOutcome {
    /** Playlist active (nouvelle ou modifiée) : la recharger avec l'écran de démarrage. */
    RELOAD,

    /** Retour à l'écran précédent (une éventuelle actualisation continue en arrière-plan). */
    CLOSE,
}

data class EditorState(
    val loading: Boolean = false,
    val sourceId: Long? = null,
    val name: String = "",
    val mode: LocationMode = LocationMode.URL,
    val url: String = "",
    val document: SourceLocation.LocalDocument? = null,
    /** Compte Xtream Codes : le lien de la playlist est construit à l'enregistrement. */
    val server: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val documentPersisted: Boolean = true,
    val username: String = "",
    val password: String = "",
    val hasStoredCredentials: Boolean = false,
    val removeStoredCredentials: Boolean = false,
    val userAgent: String = "",
    /** User-Agent saisi à la main (et non automatique, VLC ou navigateur). */
    val customUserAgent: Boolean = false,
    val errors: Map<SourceInputField, SourceError> = emptyMap(),
    val saving: Boolean = false,
    val saved: SaveOutcome? = null,
    /** Prérempli par une commande adb : il ne reste qu'à confirmer. */
    val imported: Boolean = false,
) {
    val isNew get() = sourceId == null
}

/** Change de mode en reprenant ce qui est déjà saisi (lien Xtream ↔ serveur et identifiants). */
fun EditorState.withMode(target: LocationMode): EditorState = when {
    target == LocationMode.XTREAM && server.isBlank() ->
        XtreamAccount.parse(url)?.let { copy(mode = target, server = it.server, xtreamUsername = it.username, xtreamPassword = it.password) }
            ?: copy(mode = target)
    target == LocationMode.URL && url.isBlank() && server.isNotBlank() ->
        copy(mode = target, url = XtreamAccount(server, xtreamUsername.trim(), xtreamPassword).playlistUrl().orEmpty())
    else -> copy(mode = target)
}

class SourceEditorViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {

    private val repository = container.sourceRepository
    private val documents = container.documentAccess
    private val pickedDocuments = mutableSetOf<SourceLocation.LocalDocument>()

    private val mutableState = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = mutableState.asStateFlow()

    init {
        val id = handle.get<Long>(ARG_SOURCE_ID)?.takeIf { it > 0 }
        if (id != null) {
            load(id)
        } else {
            container.playlistImport.getAndUpdate { null }?.let { import ->
                mutableState.update {
                    it.copy(
                        name = import.name ?: Uri.parse(import.url).host.orEmpty(),
                        url = import.url,
                        username = import.username.orEmpty(),
                        password = import.password.orEmpty(),
                        imported = true,
                    )
                }
            }
        }
    }

    private fun load(id: Long) {
        mutableState.update { it.copy(loading = true, sourceId = id) }
        viewModelScope.launch {
            val source = repository.getSource(id)
            if (source == null) {
                mutableState.update { it.copy(loading = false, sourceId = null) }
                return@launch
            }
            val url = (source.location as? SourceLocation.Remote)?.url
            // Lien Xtream (get.php) : affiché comme serveur, identifiant et mot de passe.
            val account = url?.let(XtreamAccount::parse)?.takeIf { !source.hasCredentials }
            mutableState.update {
                it.copy(
                    loading = false,
                    name = source.name,
                    mode = when {
                        account != null -> LocationMode.XTREAM
                        url != null -> LocationMode.URL
                        else -> LocationMode.FILE
                    },
                    url = url.orEmpty(),
                    server = account?.server.orEmpty(),
                    xtreamUsername = account?.username.orEmpty(),
                    xtreamPassword = account?.password.orEmpty(),
                    document = source.location as? SourceLocation.LocalDocument,
                    hasStoredCredentials = source.hasCredentials,
                    userAgent = source.userAgent.orEmpty(),
                    customUserAgent = source.userAgent.let { !it.isNullOrBlank() && it != UserAgents.VLC && it != UserAgents.BROWSER },
                )
            }
        }
    }

    fun edit(transform: (EditorState) -> EditorState) {
        mutableState.update { current -> transform(current).copy(errors = emptyMap()) }
    }

    /** Document choisi avec le sélecteur du système : autorisation persistante et nom d'affichage. */
    fun onDocumentPicked(uri: Uri) {
        viewModelScope.launch {
            val (persisted, name) = withContext(Dispatchers.IO) { documents.persist(uri) to documents.displayName(uri) }
            val document = SourceLocation.LocalDocument(uri.toString(), name)
            pickedDocuments += document
            mutableState.update { state ->
                state.copy(
                    document = document,
                    documentPersisted = persisted,
                    name = state.name.ifBlank { name?.substringBeforeLast('.').orEmpty() },
                    errors = emptyMap(),
                )
            }
        }
    }

    fun save() {
        val current = state.value
        if (current.saving) return
        val location = when (current.mode) {
            LocationMode.URL -> SourceLocation.Remote(current.url)
            LocationMode.XTREAM -> {
                val problems = xtreamProblems(current)
                if (problems.isNotEmpty()) {
                    mutableState.update { it.copy(errors = problems) }
                    return
                }
                val link = XtreamAccount(current.server, current.xtreamUsername.trim(), current.xtreamPassword).playlistUrl().orEmpty()
                // Compte inchangé : lien d'origine conservé (pas de rechargement inutile).
                SourceLocation.Remote(current.url.takeIf { XtreamAccount.parse(it) == XtreamAccount.parse(link) } ?: link)
            }
            LocationMode.FILE -> current.document ?: SourceLocation.LocalDocument("")
        }
        val credentials = when {
            // Identifiants Xtream dans le lien : pas d'authentification HTTP en plus.
            current.mode != LocationMode.URL -> if (current.hasStoredCredentials) CredentialsChange.Remove else CredentialsChange.Keep
            current.username.isNotBlank() || current.password.isNotEmpty() ->
                CredentialsChange.Replace(Credentials(current.username.trim(), current.password))
            current.removeStoredCredentials -> CredentialsChange.Remove
            else -> CredentialsChange.Keep
        }
        val input = SourceInput(
            name = current.name,
            location = location,
            // Le guide des programmes est commun à toutes les playlists (xmltvfr.fr).
            epg = EpgSettings(location = null, useHeaderUrls = false),
            userAgent = current.userAgent,
            credentials = credentials,
        )

        mutableState.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val id = current.sourceId
                val outcome = if (id == null) {
                    // Une playlist ajoutée devient la playlist active et se charge aussitôt.
                    container.settings.setActivePlaylist(repository.add(input))
                    SaveOutcome.RELOAD
                } else {
                    val changed = repository.update(id, input)
                    val active = container.settings.activePlaylistId.first()
                    when {
                        changed && id == active -> SaveOutcome.RELOAD
                        changed -> {
                            container.refreshController.refresh(id)
                            SaveOutcome.CLOSE
                        }
                        else -> SaveOutcome.CLOSE
                    }
                }
                mutableState.update { it.copy(saving = false, saved = outcome) }
            } catch (e: InvalidSourceInputException) {
                mutableState.update { state -> state.copy(saving = false, errors = e.problems.associate { it.field to it.error }) }
            }
        }
    }

    private fun xtreamProblems(state: EditorState): Map<SourceInputField, SourceError> = buildMap {
        when {
            state.server.isBlank() -> put(SourceInputField.LOCATION, SourceError.InvalidUrl(InvalidUrlReason.EMPTY))
            XtreamAccount.serverUrl(state.server) == null -> put(SourceInputField.LOCATION, SourceError.InvalidUrl(InvalidUrlReason.MALFORMED))
        }
        if (state.xtreamUsername.isBlank()) put(SourceInputField.USERNAME, SourceError.InvalidUrl(InvalidUrlReason.EMPTY))
    }

    override fun onCleared() {
        // Documents choisis puis abandonnés : on rend leur autorisation s'ils ne servent à aucune source.
        if (pickedDocuments.isEmpty()) return
        val picked = pickedDocuments.toList()
        container.applicationScope.launch {
            val inUse = container.playlistStore.getSources()
                .flatMap { listOfNotNull(it.location, it.epg.location) }
                .filterIsInstance<SourceLocation.LocalDocument>()
                .map { it.uri }
                .toSet()
            picked.filter { it.uri !in inUse }.forEach(documents::release)
        }
    }

    companion object {
        const val ARG_SOURCE_ID = "sourceId"
    }
}
