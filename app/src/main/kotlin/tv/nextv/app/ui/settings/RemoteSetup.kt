package tv.nextv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupVisibilityRow
import tv.nextv.app.data.sync.RemoteReceiveState
import tv.nextv.app.data.sync.RemoteSetupApplier
import tv.nextv.app.ui.common.appContainer
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceInputValidator
import tv.nextv.core.source.XtreamAccount
import tv.nextv.core.sync.RemoteConfig
import tv.nextv.core.sync.RemoteHiddenGroup
import tv.nextv.core.sync.RemotePlaylist
import tv.nextv.core.sync.RemoteSetupFind
import tv.nextv.core.sync.RemoteSetupLink
import tv.nextv.core.sync.SyncKeys
import tv.nextv.core.sync.SyncPairing
import java.io.IOException

// ---------------------------------------------------------------------------------------------------------------
// Télé à configurer : le code.
// ---------------------------------------------------------------------------------------------------------------

/** Code de configuration à distance de cette télé, tiré tant que l'écran qui l'appelle est affiché. */
@Composable
fun rememberRemoteSetupCode(): RemoteReceiveState {
    val receiver = appContainer().remoteSetupReceiver
    DisposableEffect(receiver) {
        receiver.acquire()
        onDispose { receiver.release() }
    }
    val state by receiver.state.collectAsStateWithLifecycle()
    return state
}

/** Carte du code : à donner à la personne qui configure cette télé de chez elle. */
@Composable
fun RemoteCodeCard(state: RemoteReceiveState, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis()) {
    Box(modifier) {
        SectionCard(stringResource(R.string.remote_code_title), icon = Icons.AutoMirrored.Filled.Send) {
            val offer = (state as? RemoteReceiveState.Waiting)?.offer
            Text(
                offer?.let { SyncPairing.display(it.code) } ?: "––– –––",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
                color = if (offer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.remote_code_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val (status, error) = when (state) {
                RemoteReceiveState.Idle, RemoteReceiveState.Opening -> stringResource(R.string.remote_code_opening) to false
                RemoteReceiveState.Offline -> stringResource(R.string.remote_code_offline) to true
                is RemoteReceiveState.Waiting -> if (state.refused) {
                    stringResource(R.string.remote_code_refused) to true
                } else {
                    stringResource(R.string.remote_code_waiting, relativeTime(state.offer.expiresAt, now).lowercase()) to false
                }
                is RemoteReceiveState.Installing -> stringResource(R.string.remote_code_installing, state.from) to false
                is RemoteReceiveState.Installed -> stringResource(R.string.remote_code_installed) to false
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is RemoteReceiveState.Installing || state is RemoteReceiveState.Installed) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Réglages › Configuration à distance › « Afficher le code de cette télé ». */
@Composable
fun RemoteReceiveScreen(onBack: () -> Unit) {
    val state = rememberRemoteSetupCode()
    RemoteReceiveContent(state, onBack)
}

@Composable
fun RemoteReceiveContent(state: RemoteReceiveState, onBack: () -> Unit, now: Long = System.currentTimeMillis()) {
    val window = rememberWindowInfo()
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(R.string.remote_setup),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
                navigation = {
                    IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack, modifier = Modifier.focusRequester(back))
                },
            )
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                RemoteCodeCard(state, Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp), now)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Télé qui configure : l'envoi.
// ---------------------------------------------------------------------------------------------------------------

/** Où en est l'envoi de la configuration. */
sealed interface RemoteSendPhase {
    data object Idle : RemoteSendPhase
    data object Searching : RemoteSendPhase
    data class Sending(val name: String) : RemoteSendPhase
    data class Waiting(val name: String) : RemoteSendPhase
    data class Done(val name: String) : RemoteSendPhase
    data class NotInstalled(val name: String) : RemoteSendPhase
    data class NoAnswer(val name: String) : RemoteSendPhase
    data object UnknownCode : RemoteSendPhase
    data object Offline : RemoteSendPhase
}

enum class RemoteSendField { CODE, SERVER }

/** Formulaire « Configurer une autre télé ». */
data class RemoteSendForm(
    val code: String = "",
    /** Serveur du compte Xtream (prérempli avec celui de la playlist de cette télé), ou lien M3U complet. */
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val copySettings: Boolean = true,
    val shareKeys: Boolean = false,
    /** Catégories à masquer chez l'autre, par type (au départ, celles masquées ici). */
    val hidden: Map<ContentKind, Set<String>> = emptyMap(),
    /** Liste des catégories ouverte pour ce type. */
    val picking: ContentKind? = null,
    val phase: RemoteSendPhase = RemoteSendPhase.Idle,
    val errors: Set<RemoteSendField> = emptySet(),
) {
    val busy: Boolean get() = phase is RemoteSendPhase.Searching || phase is RemoteSendPhase.Sending || phase is RemoteSendPhase.Waiting

    override fun toString(): String = "RemoteSendForm(phase=$phase)"
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSendViewModel(private val container: AppContainer, private val link: RemoteSetupLink = container.remoteSetupLink) : ViewModel() {
    private val mutableForm = MutableStateFlow(RemoteSendForm())
    val form: StateFlow<RemoteSendForm> = mutableForm.asStateFlow()

    private val activeId: StateFlow<Long?> = container.settings.activePlaylistId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Catégories de la playlist de cette télé pour le type ouvert, cochées selon le formulaire. */
    val groups: StateFlow<List<GroupVisibilityRow>?> = combine(activeId, mutableForm.map { it.picking }.distinctUntilChanged(), ::Pair)
        .flatMapLatest { (id, kind) ->
            if (id == null || kind == null) flowOf(emptyList()) else container.channelDao.observeAllGroups(id, kind.name)
        }
        .combine(mutableForm) { rows, form ->
            val hidden = form.picking?.let { form.hidden[it] }.orEmpty()
            rows.map { it.copy(hidden = it.title.orEmpty() in hidden) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Playlist de cette télé (même fournisseur, en général) : nom, serveurs de secours et agent repris. */
    private var own: tv.nextv.core.model.PlaylistSource? = null

    init {
        viewModelScope.launch {
            val id = container.settings.activePlaylistId.first() ?: return@launch
            val source = container.playlistStore.getSource(id) ?: return@launch
            own = source
            val server = ((source.location as? SourceLocation.Remote)?.url)?.let(XtreamAccount::parse)?.server.orEmpty()
            val hidden = KINDS.associateWith { kind -> container.hiddenGroupDao.titles(id, kind.name).toSet() }
            mutableForm.update { form -> form.copy(server = form.server.ifEmpty { server }, hidden = hidden) }
        }
    }

    fun edit(change: (RemoteSendForm) -> RemoteSendForm) = mutableForm.update { change(it).copy(errors = emptySet()) }

    fun pick(kind: ContentKind?) = mutableForm.update { it.copy(picking = kind) }

    fun setHidden(title: String, visible: Boolean) = mutableForm.update { form ->
        val kind = form.picking ?: return@update form
        val current = form.hidden[kind].orEmpty()
        form.copy(hidden = form.hidden + (kind to if (visible) current - title else current + title))
    }

    fun showAll() = mutableForm.update { form -> form.picking?.let { form.copy(hidden = form.hidden + (it to emptySet())) } ?: form }

    fun send() {
        val form = mutableForm.value
        if (form.busy) return
        val code = SyncPairing.normalize(form.code)
        val url = playlistUrl(form)
        val errors = buildSet {
            if (code == null) add(RemoteSendField.CODE)
            if (url == null) add(RemoteSendField.SERVER)
        }
        if (code == null || url == null) {
            mutableForm.update { it.copy(errors = errors, phase = RemoteSendPhase.Idle) }
            return
        }
        mutableForm.update { it.copy(phase = RemoteSendPhase.Searching) }
        viewModelScope.launch {
            val name = when (val found = link.find(code)) {
                RemoteSetupFind.UnknownCode -> return@launch setPhase(RemoteSendPhase.UnknownCode)
                RemoteSetupFind.Offline -> return@launch setPhase(RemoteSendPhase.Offline)
                is RemoteSetupFind.Found -> found.name
            }
            setPhase(RemoteSendPhase.Sending(name))
            try {
                link.send(code, config(form, url))
            } catch (e: IOException) {
                return@launch setPhase(RemoteSendPhase.Offline)
            }
            setPhase(RemoteSendPhase.Waiting(name))
            val reply = link.awaitReply(code)
            setPhase(
                when {
                    reply == null -> RemoteSendPhase.NoAnswer(name)
                    reply.installed -> RemoteSendPhase.Done(reply.name)
                    else -> RemoteSendPhase.NotInstalled(reply.name)
                },
            )
        }
    }

    private fun setPhase(phase: RemoteSendPhase) = mutableForm.update { it.copy(phase = phase) }

    private suspend fun config(form: RemoteSendForm, url: String): RemoteConfig {
        val settings = container.settings
        val source = own
        // Même fournisseur que la playlist de cette télé : son nom, ses serveurs de secours et son agent.
        val sameProvider = source != null && SyncKeys.scopeOfUrl(url).let { scope ->
            scope != null && ((source.location as? SourceLocation.Remote)?.url?.let(SyncKeys::scopeOfUrl) == scope ||
                source.mirrors.any { SyncKeys.scopeOfUrl(it) == scope })
        }
        val keys = if (form.shareKeys) {
            buildMap {
                settings.tmdbApiKey.first()?.let { put(RemoteSetupApplier.TMDB, it) }
                settings.footballApiKey.first()?.let { put(RemoteSetupApplier.FOOTBALL, it) }
                settings.auddApiKey.first()?.let { put(RemoteSetupApplier.AUDD, it) }
            }
        } else {
            emptyMap()
        }
        return RemoteConfig(
            playlist = RemotePlaylist(
                name = if (sameProvider) source!!.name else "",
                url = url,
                userAgent = if (sameProvider) source!!.userAgent else null,
                mirrors = if (sameProvider) source!!.mirrors else emptyList(),
            ),
            settings = if (form.copySettings) settings.remoteSetupPreferences() else emptyMap(),
            serviceKeys = keys,
            hidden = form.hidden.flatMap { (kind, titles) -> titles.map { RemoteHiddenGroup(kind.name, it) } },
        )
    }

    companion object {
        val KINDS = listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES)

        /**
         * Lien de la playlist : compte Xtream (serveur + identifiant + mot de passe), ou lien M3U complet si aucun
         * identifiant n'est saisi. `null` si l'adresse ne convient pas.
         */
        fun playlistUrl(form: RemoteSendForm): String? {
            val user = form.username.trim()
            val url = if (user.isNotEmpty()) {
                XtreamAccount(form.server.trim(), user, form.password).playlistUrl()
            } else {
                form.server.trim().takeIf { it.isNotEmpty() }
            } ?: return null
            val valid = SourceInputValidator.validate(SourceInput(name = "", location = SourceLocation.Remote(url)))
            return ((valid as? SourceInputValidator.Result.Valid)?.input?.location as? SourceLocation.Remote)?.url
        }
    }
}

/** Réglages › Configuration à distance › « Configurer une autre télé ». */
@Composable
fun RemoteSendScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { container, _ -> RemoteSendViewModel(container) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    form.picking?.let { kind ->
        BackHandler { viewModel.pick(null) }
        ChannelGroupsContent(
            groups = groups,
            onVisibility = viewModel::setHidden,
            onShowAll = viewModel::showAll,
            onBack = { viewModel.pick(null) },
            kind = kind,
        )
        return
    }
    RemoteSendContent(form, onEdit = viewModel::edit, onPick = viewModel::pick, onSend = viewModel::send, onBack = onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteSendContent(
    form: RemoteSendForm,
    onEdit: ((RemoteSendForm) -> RemoteSendForm) -> Unit,
    onPick: (ContentKind) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    val window = rememberWindowInfo()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(R.string.remote_send_title),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
            Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionCard(stringResource(R.string.remote_send_code_section), subtitle = stringResource(R.string.remote_send_code_help)) {
                        NexTvTextField(
                            value = form.code,
                            onValueChange = { typed -> onEdit { it.copy(code = typed.filter(Char::isDigit).take(SyncPairing.CODE_DIGITS)) } },
                            label = stringResource(R.string.remote_send_code),
                            keyboardType = KeyboardType.NumberPassword,
                            error = if (RemoteSendField.CODE in form.errors) stringResource(R.string.remote_send_error_code) else null,
                        )
                    }
                    SectionCard(stringResource(R.string.remote_send_playlist_section), subtitle = stringResource(R.string.remote_send_playlist_help), icon = NexTvIcons.Tv) {
                        NexTvTextField(
                            value = form.server,
                            onValueChange = { value -> onEdit { it.copy(server = value) } },
                            label = stringResource(R.string.remote_send_server),
                            placeholder = "http://serveur.exemple:8080",
                            keyboardType = KeyboardType.Uri,
                            error = if (RemoteSendField.SERVER in form.errors) stringResource(R.string.remote_send_error_server) else null,
                        )
                        NexTvTextField(
                            value = form.username,
                            onValueChange = { value -> onEdit { it.copy(username = value) } },
                            label = stringResource(R.string.remote_send_username),
                        )
                        NexTvTextField(
                            value = form.password,
                            onValueChange = { value -> onEdit { it.copy(password = value) } },
                            // En clair : on voit ce qu'on tape à la télécommande.
                            label = stringResource(R.string.remote_send_password),
                        )
                    }
                    SectionCard(stringResource(R.string.remote_send_options)) {
                        ToggleRow(
                            title = stringResource(R.string.remote_send_copy_settings),
                            description = stringResource(R.string.remote_send_copy_settings_help),
                            checked = form.copySettings,
                            onChange = { checked -> onEdit { it.copy(copySettings = checked) } },
                        )
                        ToggleRow(
                            title = stringResource(R.string.remote_send_keys),
                            description = stringResource(R.string.remote_send_keys_help),
                            checked = form.shareKeys,
                            onChange = { checked -> onEdit { it.copy(shareKeys = checked) } },
                        )
                    }
                    SectionCard(stringResource(R.string.remote_send_hidden), subtitle = stringResource(R.string.remote_send_hidden_help)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            RemoteSendViewModel.KINDS.forEach { kind ->
                                val label = stringResource(
                                    when (kind) {
                                        ContentKind.MOVIE -> R.string.remote_send_hidden_movies
                                        ContentKind.SERIES -> R.string.remote_send_hidden_series
                                        else -> R.string.remote_send_hidden_live
                                    },
                                )
                                val count = form.hidden[kind].orEmpty().size
                                PillButton(
                                    if (count == 0) {
                                        stringResource(R.string.remote_send_hidden_none, label)
                                    } else {
                                        pluralStringResource(R.plurals.remote_send_hidden_count, count, label, count)
                                    },
                                    { onPick(kind) },
                                    icon = NexTvIcons.Grid,
                                )
                            }
                        }
                    }
                    SendStatus(form.phase)
                    PillButton(
                        stringResource(R.string.remote_send_button),
                        onSend,
                        icon = Icons.AutoMirrored.Filled.Send,
                        style = PillStyle.Primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SendStatus(phase: RemoteSendPhase) {
    val (text, error) = when (phase) {
        RemoteSendPhase.Idle -> return
        RemoteSendPhase.Searching -> stringResource(R.string.remote_send_searching) to false
        is RemoteSendPhase.Sending -> stringResource(R.string.remote_send_sending, phase.name) to false
        is RemoteSendPhase.Waiting -> stringResource(R.string.remote_send_waiting, phase.name) to false
        is RemoteSendPhase.Done -> stringResource(R.string.remote_send_done, phase.name) to false
        is RemoteSendPhase.NotInstalled -> stringResource(R.string.remote_send_not_installed, phase.name) to true
        is RemoteSendPhase.NoAnswer -> stringResource(R.string.remote_send_no_answer, phase.name) to true
        RemoteSendPhase.UnknownCode -> stringResource(R.string.remote_send_unknown) to true
        RemoteSendPhase.Offline -> stringResource(R.string.remote_send_offline) to true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = when {
                error -> MaterialTheme.colorScheme.error
                phase is RemoteSendPhase.Done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (phase is RemoteSendPhase.Searching || phase is RemoteSendPhase.Sending || phase is RemoteSendPhase.Waiting) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Page « Configuration à distance » des réglages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteSetupPage(onSend: () -> Unit, onReceive: () -> Unit) {
    SectionCard(stringResource(R.string.remote_setup), subtitle = stringResource(R.string.remote_setup_help), icon = Icons.AutoMirrored.Filled.Send) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.remote_setup_send), onSend, icon = Icons.AutoMirrored.Filled.Send, style = PillStyle.Primary)
            PillButton(stringResource(R.string.remote_setup_receive), onReceive, icon = NexTvIcons.Tv)
        }
        Text(stringResource(R.string.remote_setup_receive_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
