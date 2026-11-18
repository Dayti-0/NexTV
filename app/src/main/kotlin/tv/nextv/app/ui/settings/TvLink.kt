package tv.nextv.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.data.sync.TvSync
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.core.sync.JoinResult
import tv.nextv.core.sync.SyncInvite
import tv.nextv.core.sync.SyncPairing
import tv.nextv.core.sync.SyncStatus
import java.io.IOException

/** Dernier résultat affiché sur la page « Télés reliées ». */
sealed interface TvLinkMessage {
    /** L'autre télé a saisi le code. */
    data class Linked(val name: String) : TvLinkMessage

    /** Cette télé a rejoint celle qui affichait le code. */
    data class Joined(val from: String, val playlistsAdded: Int, val synced: Boolean) : TvLinkMessage
    data object UnknownCode : TvLinkMessage
    data object InvalidCode : TvLinkMessage
    data object AlreadyLinked : TvLinkMessage
    data object Offline : TvLinkMessage
    data object Expired : TvLinkMessage
    data class Synced(val changes: Int) : TvLinkMessage
    data object Unlinked : TvLinkMessage
}

/** Page « Télés reliées » : état de la synchronisation, code affiché, opération en cours, dernier résultat. */
data class TvLinkUi(
    val status: SyncStatus = SyncStatus(),
    val invite: SyncInvite? = null,
    val busy: Boolean = false,
    val message: TvLinkMessage? = null,
)

/** Actions de la page « Télés reliées » (dans le ViewModel des réglages). */
class TvLinkController(private val scope: CoroutineScope, private val sync: TvSync) {
    private data class Local(val invite: SyncInvite? = null, val busy: Boolean = false, val message: TvLinkMessage? = null)

    private val local = MutableStateFlow(Local())
    private var inviteJob: Job? = null

    private val mutableReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Playlist venue de l'autre télé, choisie parce que celle-ci n'en avait pas : l'accueil est à recharger. */
    val reload: SharedFlow<Unit> = mutableReload

    val state: StateFlow<TvLinkUi> = combine(sync.status, local) { status, local -> TvLinkUi(status, local.invite, local.busy, local.message) }
        .stateIn(scope, SharingStarted.Eagerly, TvLinkUi(sync.status.value))

    fun showCode() {
        if (local.value.busy) return
        inviteJob?.cancel()
        inviteJob = scope.launch {
            local.value = Local(busy = true)
            val invite = try {
                sync.createInvite()
            } catch (e: IOException) {
                local.value = Local(message = TvLinkMessage.Offline)
                return@launch
            }
            local.value = Local(invite = invite)
            val name = sync.awaitAnswer(invite)
            local.value = Local(message = name?.let(TvLinkMessage::Linked) ?: TvLinkMessage.Expired)
        }
    }

    /** Code retiré de l'écran (et de filebin). */
    fun hideCode() {
        inviteJob?.cancel()
        inviteJob = null
        local.update { it.copy(invite = null) }
    }

    fun join(input: String) {
        val code = SyncPairing.normalize(input) ?: run {
            local.update { it.copy(message = TvLinkMessage.InvalidCode) }
            return
        }
        if (local.value.busy) return
        hideCode()
        scope.launch {
            local.value = Local(busy = true)
            val message = when (val result = sync.join(code)) {
                is JoinResult.Joined -> {
                    if (result.applied.activatedPlaylist != null) mutableReload.tryEmit(Unit)
                    TvLinkMessage.Joined(result.from, result.applied.playlistsAdded, result.synced)
                }
                JoinResult.UnknownCode -> TvLinkMessage.UnknownCode
                JoinResult.AlreadyLinked -> TvLinkMessage.AlreadyLinked
                JoinResult.Offline -> TvLinkMessage.Offline
            }
            local.value = Local(message = message)
        }
    }

    fun syncNow() {
        if (local.value.busy || sync.status.value.running) return
        scope.launch {
            local.value = Local(busy = true)
            val applied = sync.syncNow()
            if (applied?.activatedPlaylist != null) mutableReload.tryEmit(Unit)
            local.value = Local(message = if (sync.status.value.failedAt != 0L) TvLinkMessage.Offline else TvLinkMessage.Synced(applied?.changes ?: 0))
        }
    }

    fun unlink() {
        if (local.value.busy) return
        hideCode()
        scope.launch {
            local.value = Local(busy = true)
            sync.leave()
            local.value = Local(message = TvLinkMessage.Unlinked)
        }
    }
}

/** Actions de la page, regroupées pour ne pas allonger la liste des paramètres des réglages. */
class TvLinkActions(
    val onShowCode: () -> Unit = {},
    val onHideCode: () -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onUnlink: () -> Unit = {},
)

/** Résumé sous le nom de la page : nombre d'autres télés reliées. */
@Composable
internal fun tvLinkSummary(link: TvLinkUi): String = if (link.status.linked) {
    pluralStringResource(R.plurals.settings_link_summary, link.status.peers.size, link.status.peers.size)
} else {
    stringResource(R.string.settings_link_summary_none)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvLinkPage(link: TvLinkUi, actions: TvLinkActions, now: Long) {
    val status = link.status
    val codeButton = remember { FocusRequester() }
    SectionCard(stringResource(R.string.settings_link), subtitle = stringResource(R.string.settings_link_help)) {
        val state = when {
            !status.linked -> stringResource(R.string.settings_link_none)
            status.peers.isEmpty() -> stringResource(R.string.settings_link_waiting)
            else -> stringResource(
                R.string.settings_link_peers,
                status.peers.joinToString(", ") { peer ->
                    if (peer.at > 0) "${peer.name} (${relativeTime(peer.at, now).lowercase()})" else peer.name
                },
            )
        }
        Text(state, style = MaterialTheme.typography.bodyMedium)
        if (status.linked) {
            val last = when {
                status.running -> stringResource(R.string.settings_link_running)
                status.failedAt != 0L -> stringResource(R.string.settings_link_failed)
                status.lastSyncAt > 0 -> stringResource(R.string.settings_link_last, relativeTime(status.lastSyncAt, now).lowercase())
                else -> null
            }
            last?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (link.busy) {
            Text(stringResource(R.string.settings_link_busy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        link.message?.let { message ->
            val error = message is TvLinkMessage.UnknownCode || message is TvLinkMessage.InvalidCode || message is TvLinkMessage.Offline ||
                message is TvLinkMessage.Expired
            Text(
                messageText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        // Boutons jamais grisés ni retirés sous la sélection : une sélection perdue partirait en haut à gauche de l'écran.
        var wasLinked by remember { mutableStateOf(status.linked) }
        LaunchedEffect(status.linked) {
            // « Ne plus relier » vient de disparaître : la sélection passe sur « Afficher un code ».
            if (wasLinked && !status.linked) runCatching { codeButton.requestFocus() }
            wasLinked = status.linked
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                stringResource(if (link.invite == null) R.string.settings_link_show_code else R.string.settings_link_new_code),
                actions.onShowCode,
                modifier = Modifier.focusRequester(codeButton),
                icon = Icons.Filled.Share,
                style = PillStyle.Primary,
            )
            if (status.linked) {
                PillButton(stringResource(R.string.settings_link_sync_now), actions.onSyncNow, icon = Icons.Filled.Refresh)
                PillButton(stringResource(R.string.settings_link_unlink), actions.onUnlink, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
            }
        }
    }

    link.invite?.let { invite ->
        SectionCard(stringResource(R.string.settings_link_code_title)) {
            Text(
                SyncPairing.display(invite.code),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.settings_link_code_help, relativeTime(invite.expiresAt, now).lowercase()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PillButton(
                stringResource(R.string.settings_link_code_close),
                {
                    // La carte disparaît avec le bouton : la sélection revient sur « Afficher un code ».
                    runCatching { codeButton.requestFocus() }
                    actions.onHideCode()
                },
                icon = Icons.Filled.Close,
                style = PillStyle.Ghost,
            )
        }
    }

    SectionCard(stringResource(R.string.settings_link_join), subtitle = stringResource(R.string.settings_link_join_help)) {
        var code by rememberSaveable { mutableStateOf("") }
        NexTvTextField(
            value = code,
            onValueChange = { typed -> code = typed.filter { it.isDigit() }.take(SyncPairing.CODE_DIGITS) },
            label = stringResource(R.string.settings_link_join_code),
            keyboardType = KeyboardType.NumberPassword,
        )
        PillButton(stringResource(R.string.settings_link_join_button), { actions.onJoin(code) }, icon = Icons.Filled.Check)
    }
}

@Composable
private fun messageText(message: TvLinkMessage): String = when (message) {
    is TvLinkMessage.Linked -> stringResource(R.string.settings_link_linked, message.name)
    is TvLinkMessage.Joined -> when {
        !message.synced -> stringResource(R.string.settings_link_joined_later, message.from)
        message.playlistsAdded > 0 -> stringResource(R.string.settings_link_joined_playlist, message.from)
        else -> stringResource(R.string.settings_link_joined, message.from)
    }
    TvLinkMessage.UnknownCode -> stringResource(R.string.settings_link_unknown_code)
    TvLinkMessage.InvalidCode -> stringResource(R.string.settings_link_invalid_code)
    TvLinkMessage.AlreadyLinked -> stringResource(R.string.settings_link_already)
    TvLinkMessage.Offline -> stringResource(R.string.settings_link_offline)
    TvLinkMessage.Expired -> stringResource(R.string.settings_link_expired)
    is TvLinkMessage.Synced -> if (message.changes > 0) {
        pluralStringResource(R.plurals.settings_link_synced, message.changes, message.changes)
    } else {
        stringResource(R.string.settings_link_synced_none)
    }
    TvLinkMessage.Unlinked -> stringResource(R.string.settings_link_unlinked)
}
