package tv.nextv.app.ui.editor

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInputField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditorScreen(onDone: () -> Unit) {
    val viewModel = appViewModel { container, handle -> SourceEditorViewModel(container, handle) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickTarget by rememberSaveable { mutableStateOf(PickTarget.PLAYLIST) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, pickTarget)
    }
    fun pick(target: PickTarget) {
        pickTarget = target
        try {
            // Le type MIME des .m3u / .xml.gz varie selon les fournisseurs : le contenu est vérifié au chargement.
            picker.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.editor_no_picker)) }
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    fun errorText(field: SourceInputField): String? = state.errors[field]?.let(resources::describe)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.editor_title_new else R.string.editor_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving && !state.loading) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { value -> viewModel.edit { it.copy(name = value) } },
                label = { Text(stringResource(R.string.editor_name)) },
                placeholder = { Text(stringResource(R.string.editor_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.editor_section_source))
            ChoiceRow(stringResource(R.string.editor_mode_url), state.mode == LocationMode.URL) {
                viewModel.edit { it.copy(mode = LocationMode.URL) }
            }
            ChoiceRow(stringResource(R.string.editor_mode_file), state.mode == LocationMode.FILE) {
                viewModel.edit { it.copy(mode = LocationMode.FILE) }
            }

            when (state.mode) {
                LocationMode.URL -> {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = { value -> viewModel.edit { it.copy(url = value) } },
                        label = { Text(stringResource(R.string.editor_url)) },
                        placeholder = { Text("https://exemple.com/playlist.m3u8") },
                        singleLine = true,
                        isError = errorText(SourceInputField.LOCATION) != null,
                        supportingText = { Text(errorText(SourceInputField.LOCATION) ?: stringResource(R.string.editor_url_help)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CredentialsSection(state, viewModel, errorText(SourceInputField.USERNAME))
                    OutlinedTextField(
                        value = state.userAgent,
                        onValueChange = { value -> viewModel.edit { it.copy(userAgent = value) } },
                        label = { Text(stringResource(R.string.editor_user_agent)) },
                        singleLine = true,
                        isError = errorText(SourceInputField.USER_AGENT) != null,
                        supportingText = { Text(errorText(SourceInputField.USER_AGENT) ?: stringResource(R.string.editor_user_agent_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LocationMode.FILE -> DocumentField(
                    document = state.document,
                    persisted = state.documentPersisted,
                    error = errorText(SourceInputField.LOCATION),
                    help = stringResource(R.string.editor_file_help),
                    onPick = { pick(PickTarget.PLAYLIST) },
                )
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.editor_section_epg))
            ChoiceRow(stringResource(R.string.editor_epg_none), state.epgMode == EpgMode.NONE) {
                viewModel.edit { it.copy(epgMode = EpgMode.NONE) }
            }
            ChoiceRow(stringResource(R.string.editor_epg_url), state.epgMode == EpgMode.URL) {
                viewModel.edit { it.copy(epgMode = EpgMode.URL) }
            }
            ChoiceRow(stringResource(R.string.editor_epg_file), state.epgMode == EpgMode.FILE) {
                viewModel.edit { it.copy(epgMode = EpgMode.FILE) }
            }
            when (state.epgMode) {
                EpgMode.NONE -> Unit
                EpgMode.URL -> OutlinedTextField(
                    value = state.epgUrl,
                    onValueChange = { value -> viewModel.edit { it.copy(epgUrl = value) } },
                    label = { Text(stringResource(R.string.editor_epg_url_label)) },
                    placeholder = { Text("https://exemple.com/guide.xml.gz") },
                    singleLine = true,
                    isError = errorText(SourceInputField.EPG_LOCATION) != null,
                    supportingText = errorText(SourceInputField.EPG_LOCATION)?.let { message -> @Composable { Text(message) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                EpgMode.FILE -> DocumentField(
                    document = state.epgDocument,
                    persisted = state.epgDocumentPersisted,
                    error = errorText(SourceInputField.EPG_LOCATION),
                    help = stringResource(R.string.editor_epg_file_help),
                    onPick = { pick(PickTarget.EPG) },
                )
            }
            CheckRow(stringResource(R.string.editor_epg_header), state.useHeaderEpg) { checked ->
                viewModel.edit { it.copy(useHeaderEpg = checked) }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.isNew) R.string.editor_save_and_load else R.string.action_save))
            }
        }
    }
}

@Composable
private fun CredentialsSection(state: EditorState, viewModel: SourceEditorViewModel, usernameError: String?) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.editor_section_credentials))
    Text(
        stringResource(
            if (state.hasStoredCredentials && !state.removeStoredCredentials) R.string.editor_credentials_stored else R.string.editor_credentials_help,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = state.username,
        onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
        label = { Text(stringResource(R.string.editor_username)) },
        singleLine = true,
        isError = usernameError != null,
        supportingText = usernameError?.let { message -> @Composable { Text(message) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
        label = { Text(stringResource(R.string.editor_password)) },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(stringResource(if (showPassword) R.string.action_hide else R.string.action_show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.hasStoredCredentials) {
        CheckRow(stringResource(R.string.editor_credentials_remove), state.removeStoredCredentials) { checked ->
            viewModel.edit { it.copy(removeStoredCredentials = checked, username = "", password = "") }
        }
    }
}

@Composable
private fun DocumentField(
    document: SourceLocation.LocalDocument?,
    persisted: Boolean,
    error: String?,
    help: String,
    onPick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPick) { Text(stringResource(R.string.editor_pick_file)) }
            Text(
                document?.let { it.displayName ?: stringResource(R.string.location_local_file) }
                    ?: stringResource(R.string.editor_no_file),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            document != null && !persisted ->
                Text(stringResource(R.string.editor_file_not_persisted), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            else -> Text(help, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
