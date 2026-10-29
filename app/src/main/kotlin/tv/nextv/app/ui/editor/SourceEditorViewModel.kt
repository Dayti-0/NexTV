package tv.nextv.app.ui.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nextv.app.AppContainer
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.CredentialsChange
import tv.nextv.core.repository.InvalidSourceInputException
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceInputField

enum class LocationMode { URL, FILE }

enum class EpgMode { NONE, URL, FILE }

enum class PickTarget { PLAYLIST, EPG }

data class EditorState(
    val loading: Boolean = false,
    val sourceId: Long? = null,
    val name: String = "",
    val mode: LocationMode = LocationMode.URL,
    val url: String = "",
    val document: SourceLocation.LocalDocument? = null,
    val documentPersisted: Boolean = true,
    val username: String = "",
    val password: String = "",
    val hasStoredCredentials: Boolean = false,
    val removeStoredCredentials: Boolean = false,
    val userAgent: String = "",
    val epgMode: EpgMode = EpgMode.NONE,
    val epgUrl: String = "",
    val epgDocument: SourceLocation.LocalDocument? = null,
    val epgDocumentPersisted: Boolean = true,
    val useHeaderEpg: Boolean = true,
    val errors: Map<SourceInputField, SourceError> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val isNew get() = sourceId == null
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
        if (id != null) load(id)
    }

    private fun load(id: Long) {
        mutableState.update { it.copy(loading = true, sourceId = id) }
        viewModelScope.launch {
            val source = repository.getSource(id)
            if (source == null) {
                mutableState.update { it.copy(loading = false, sourceId = null) }
                return@launch
            }
            val epg = source.epg.location
            mutableState.update {
                it.copy(
                    loading = false,
                    name = source.name,
                    mode = if (source.location is SourceLocation.Remote) LocationMode.URL else LocationMode.FILE,
                    url = (source.location as? SourceLocation.Remote)?.url.orEmpty(),
                    document = source.location as? SourceLocation.LocalDocument,
                    hasStoredCredentials = source.hasCredentials,
                    userAgent = source.userAgent.orEmpty(),
                    epgMode = when (epg) {
                        null -> EpgMode.NONE
                        is SourceLocation.Remote -> EpgMode.URL
                        is SourceLocation.LocalDocument -> EpgMode.FILE
                    },
                    epgUrl = (epg as? SourceLocation.Remote)?.url.orEmpty(),
                    epgDocument = epg as? SourceLocation.LocalDocument,
                    useHeaderEpg = source.epg.useHeaderUrls,
                )
            }
        }
    }

    fun edit(transform: (EditorState) -> EditorState) {
        mutableState.update { current -> transform(current).copy(errors = emptyMap()) }
    }

    /** Document choisi avec le sélecteur du système : autorisation persistante et nom d'affichage. */
    fun onDocumentPicked(uri: Uri, target: PickTarget) {
        viewModelScope.launch {
            val (persisted, name) = withContext(Dispatchers.IO) { documents.persist(uri) to documents.displayName(uri) }
            val document = SourceLocation.LocalDocument(uri.toString(), name)
            pickedDocuments += document
            mutableState.update { state ->
                when (target) {
                    PickTarget.PLAYLIST -> state.copy(
                        document = document,
                        documentPersisted = persisted,
                        name = state.name.ifBlank { name?.substringBeforeLast('.').orEmpty() },
                    )
                    PickTarget.EPG -> state.copy(epgDocument = document, epgDocumentPersisted = persisted)
                }.copy(errors = emptyMap())
            }
        }
    }

    fun save() {
        val current = state.value
        if (current.saving) return
        val location = when (current.mode) {
            LocationMode.URL -> SourceLocation.Remote(current.url)
            LocationMode.FILE -> current.document ?: SourceLocation.LocalDocument("")
        }
        val credentials = when {
            current.mode == LocationMode.FILE -> if (current.hasStoredCredentials) CredentialsChange.Remove else CredentialsChange.Keep
            current.username.isNotBlank() || current.password.isNotEmpty() ->
                CredentialsChange.Replace(Credentials(current.username.trim(), current.password))
            current.removeStoredCredentials -> CredentialsChange.Remove
            else -> CredentialsChange.Keep
        }
        val epgLocation = when (current.epgMode) {
            EpgMode.NONE -> null
            EpgMode.URL -> SourceLocation.Remote(current.epgUrl)
            EpgMode.FILE -> current.epgDocument ?: SourceLocation.LocalDocument("")
        }
        val input = SourceInput(
            name = current.name,
            location = location,
            epg = EpgSettings(location = epgLocation, useHeaderUrls = current.useHeaderEpg),
            userAgent = current.userAgent,
            credentials = credentials,
        )

        mutableState.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val id = current.sourceId
                if (id == null) {
                    container.refreshController.refresh(repository.add(input))
                } else if (repository.update(id, input)) {
                    container.refreshController.refresh(id)
                }
                mutableState.update { it.copy(saving = false, saved = true) }
            } catch (e: InvalidSourceInputException) {
                mutableState.update { state -> state.copy(saving = false, errors = e.problems.associate { it.field to it.error }) }
            }
        }
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
