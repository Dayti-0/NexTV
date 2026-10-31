package tv.nextv.app.ui.update

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import tv.nextv.app.BuildConfig
import tv.nextv.app.R
import tv.nextv.app.data.update.UpdateFailure
import tv.nextv.app.data.update.UpdateState
import tv.nextv.app.ui.common.appContainer
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.update.AppRelease

/** Étapes de la proposition de mise à jour, une fois par lancement de l'application. */
private enum class PromptStep { CHECK, ASK, DONE }

/**
 * Au lancement de l'application : si une nouvelle version est publiée, demande s'il faut
 * l'installer (téléchargement, puis confirmation dans la fenêtre du système). Silencieux si
 * NexTV est à jour ou si la vérification échoue (pas de réseau…).
 */
@Composable
fun LaunchUpdatePrompt() {
    val updater = appContainer().updater
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(PromptStep.CHECK) }
    LaunchedEffect(Unit) {
        if (step != PromptStep.CHECK) return@LaunchedEffect
        // L'écran de démarrage s'affiche d'abord.
        delay(CHECK_DELAY_MILLIS)
        if (updater.state.value is UpdateState.Idle) updater.check()
        val result = updater.state.first { it !is UpdateState.Idle && it !is UpdateState.Checking }
        step = if (result is UpdateState.Available) PromptStep.ASK else PromptStep.DONE
    }
    if (step != PromptStep.ASK) return
    val state by updater.state.collectAsStateWithLifecycle()
    // Retour du réglage « Installer des applis inconnues » : l'installation reprend d'elle-même.
    LifecycleResumeEffect(state) {
        (state as? UpdateState.NeedsPermission)?.let { if (updater.canInstall()) updater.install(it.release) }
        onPauseOrDispose { }
    }
    val manualPermission = stringResource(R.string.settings_update_permission_manual)
    UpdateDialog(
        state = state,
        onInstall = updater::install,
        onAllow = {
            val intent = updater.permissionIntent()
            val opened = intent != null && try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
            if (!opened) Toast.makeText(context, manualPermission, Toast.LENGTH_LONG).show()
        },
        onClose = { step = PromptStep.DONE },
    )
}

/** Fenêtre de la mise à jour : proposition, téléchargement, autorisation ou erreur. */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: (AppRelease) -> Unit,
    onAllow: () -> Unit,
    onClose: () -> Unit,
) {
    val main = remember { FocusRequester() }
    LaunchedEffect(state::class) { runCatching { main.requestFocus() } }
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is UpdateState.Available ->
                        Text(stringResource(R.string.update_prompt_message, state.release.version, BuildConfig.VERSION_NAME))
                    is UpdateState.Downloading -> {
                        Text(stringResource(R.string.settings_update_downloading, state.release.version, megabytes(state.downloaded), megabytes(state.total)))
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) state.downloaded.toFloat() / state.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = NexTvColors.Live,
                        )
                    }
                    is UpdateState.Installing -> Text(stringResource(R.string.settings_update_installing, state.release.version))
                    is UpdateState.NeedsPermission -> Text(stringResource(R.string.update_prompt_permission))
                    is UpdateState.Failed -> Text(stringResource(failureText(state.error)), color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.settings_update_checking))
                }
            }
        },
        confirmButton = {
            when {
                state is UpdateState.Available -> TextButton(onClick = { onInstall(state.release) }, modifier = Modifier.focusRequester(main)) {
                    Text(stringResource(R.string.update_prompt_install))
                }
                state is UpdateState.NeedsPermission -> TextButton(onClick = onAllow, modifier = Modifier.focusRequester(main)) {
                    Text(stringResource(R.string.settings_update_allow))
                }
                state is UpdateState.Failed && release != null -> TextButton(onClick = { onInstall(release) }, modifier = Modifier.focusRequester(main)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        },
        dismissButton = {
            val waiting = state is UpdateState.Available || state is UpdateState.NeedsPermission
            // Pendant le téléchargement, il continue en arrière-plan : la fenêtre se ferme seulement.
            val focus = if (state is UpdateState.Available || state is UpdateState.NeedsPermission || (state is UpdateState.Failed && release != null)) {
                Modifier
            } else {
                Modifier.focusRequester(main)
            }
            TextButton(onClick = onClose, modifier = focus) {
                Text(stringResource(if (waiting) R.string.update_prompt_later else R.string.action_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.ignoreHeldOk(),
    )
}

private fun failureText(error: UpdateFailure): Int = when (error) {
    UpdateFailure.NOT_PUBLISHED -> R.string.settings_update_not_published
    UpdateFailure.NETWORK -> R.string.settings_update_network
    UpdateFailure.SERVER -> R.string.settings_update_server
    UpdateFailure.CORRUPTED -> R.string.settings_update_corrupted
    UpdateFailure.INSTALL -> R.string.settings_update_install_failed
}

/** « 12,4 » (Mo). */
private fun megabytes(bytes: Long): String = String.format(java.util.Locale.FRANCE, "%.1f", bytes / 1_000_000.0)

/** Attente avant la vérification : l'application finit de s'ouvrir. */
private const val CHECK_DELAY_MILLIS = 2_000L
