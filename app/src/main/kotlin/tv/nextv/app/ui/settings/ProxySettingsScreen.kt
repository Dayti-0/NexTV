package tv.nextv.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.editor.CheckRow
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxyType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { container, _ -> ProxySettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) snackbar.showSnackbar(resources.getString(R.string.settings_saved))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.proxy_enable), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.proxy_description), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { checked -> viewModel.edit { it.copy(enabled = checked) } },
                    enabled = !state.loading,
                )
            }

            if (state.enabled) {
                Text(stringResource(R.string.proxy_type), style = MaterialTheme.typography.titleSmall)
                ProxyType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = state.type == type, onClick = { viewModel.edit { it.copy(type = type) } }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.type == type, onClick = null)
                        Text(
                            stringResource(if (type == ProxyType.HTTP) R.string.proxy_type_http else R.string.proxy_type_socks),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = state.host,
                    onValueChange = { value -> viewModel.edit { it.copy(host = value) } },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    placeholder = { Text("proxy.exemple.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { value -> viewModel.edit { it.copy(port = value.filter(Char::isDigit).take(5)) } },
                    label = { Text(stringResource(R.string.proxy_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.type == ProxyType.HTTP) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
                        label = { Text(stringResource(R.string.proxy_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
                        label = { Text(stringResource(R.string.proxy_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.proxy_socks_no_auth), style = MaterialTheme.typography.bodySmall)
                }

                Text(stringResource(R.string.proxy_scopes), style = MaterialTheme.typography.titleSmall)
                CheckRow(stringResource(R.string.proxy_scope_playlists), ProxyScope.PLAYLISTS in state.scopes) {
                    viewModel.toggleScope(ProxyScope.PLAYLISTS, it)
                }
                CheckRow(stringResource(R.string.proxy_scope_epg), ProxyScope.EPG in state.scopes) {
                    viewModel.toggleScope(ProxyScope.EPG, it)
                }
                CheckRow(stringResource(R.string.proxy_scope_streams), ProxyScope.STREAMS in state.scopes) {
                    viewModel.toggleScope(ProxyScope.STREAMS, it)
                }
                CheckRow(stringResource(R.string.proxy_bypass_local), state.bypassLocal) { checked ->
                    viewModel.edit { it.copy(bypassLocal = checked) }
                }
                Text(stringResource(R.string.proxy_limits), style = MaterialTheme.typography.bodySmall)
            }

            state.problems.forEach { problem ->
                Text(resources.describe(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = viewModel::save, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
