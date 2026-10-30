package tv.nextv.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.CheckRow
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxyType

@Composable
fun ProxySettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { container, _ -> ProxySettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) snackbar.showSnackbar(resources.getString(R.string.settings_saved))
    }

    ProxySettingsContent(
        state = state,
        snackbarHostState = snackbar,
        onEdit = viewModel::edit,
        onToggleScope = viewModel::toggleScope,
        onSave = viewModel::save,
        onBack = onBack,
    )
}

@Composable
fun ProxySettingsContent(
    state: ProxyFormState,
    snackbarHostState: SnackbarHostState,
    onEdit: ((ProxyFormState) -> ProxyFormState) -> Unit,
    onToggleScope: (ProxyScope, Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val window = rememberWindowInfo()
    val resources = LocalContext.current.resources
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = 12.dp, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    PillButton(stringResource(R.string.action_save), onSave, icon = Icons.Filled.Check, style = PillStyle.Primary, enabled = !state.loading)
                },
            )
            Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionCard(stringResource(R.string.proxy_section), icon = NexTvIcons.Cloud) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.proxy_enable), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.proxy_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = { checked -> onEdit { it.copy(enabled = checked) } },
                                enabled = !state.loading,
                            )
                        }

                        if (state.enabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProxyType.entries.forEach { type ->
                                    FocusSurface(
                                        onClick = { onEdit { it.copy(type = type) } },
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (state.type == type) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        selected = state.type == type,
                                    ) {
                                        Text(
                                            stringResource(if (type == ProxyType.HTTP) R.string.proxy_type_http else R.string.proxy_type_socks),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NexTvTextField(
                                    value = state.host,
                                    onValueChange = { value -> onEdit { it.copy(host = value) } },
                                    label = stringResource(R.string.proxy_host),
                                    placeholder = "proxy.exemple.com",
                                    keyboardType = KeyboardType.Uri,
                                    modifier = Modifier.weight(2f),
                                )
                                NexTvTextField(
                                    value = state.port,
                                    onValueChange = { value -> onEdit { it.copy(port = value.filter(Char::isDigit).take(5)) } },
                                    label = stringResource(R.string.proxy_port),
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (state.type == ProxyType.HTTP) {
                                NexTvTextField(
                                    value = state.username,
                                    onValueChange = { value -> onEdit { it.copy(username = value) } },
                                    label = stringResource(R.string.proxy_username),
                                )
                                NexTvTextField(
                                    value = state.password,
                                    onValueChange = { value -> onEdit { it.copy(password = value) } },
                                    label = stringResource(R.string.proxy_password),
                                    keyboardType = KeyboardType.Password,
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            } else {
                                Text(stringResource(R.string.proxy_socks_no_auth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (state.enabled) {
                        SectionCard(stringResource(R.string.proxy_scopes)) {
                            CheckRow(stringResource(R.string.proxy_scope_playlists), ProxyScope.PLAYLISTS in state.scopes) {
                                onToggleScope(ProxyScope.PLAYLISTS, it)
                            }
                            CheckRow(stringResource(R.string.proxy_scope_epg), ProxyScope.EPG in state.scopes) {
                                onToggleScope(ProxyScope.EPG, it)
                            }
                            CheckRow(stringResource(R.string.proxy_scope_streams), ProxyScope.STREAMS in state.scopes) {
                                onToggleScope(ProxyScope.STREAMS, it)
                            }
                            CheckRow(stringResource(R.string.proxy_bypass_local), state.bypassLocal) { checked ->
                                onEdit { it.copy(bypassLocal = checked) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.proxy_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    state.problems.forEach { problem ->
                        Text(resources.describe(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
