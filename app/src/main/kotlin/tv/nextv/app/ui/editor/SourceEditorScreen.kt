package tv.nextv.app.ui.editor

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInputField

@Composable
fun SourceEditorScreen(onClose: () -> Unit, onReload: () -> Unit) {
    val viewModel = appViewModel { container, handle -> SourceEditorViewModel(container, handle) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri)
    }

    LaunchedEffect(state.saved) {
        when (state.saved) {
            SaveOutcome.RELOAD -> onReload()
            SaveOutcome.CLOSE -> onClose()
            null -> Unit
        }
    }

    SourceEditorContent(
        state = state,
        snackbarHostState = snackbar,
        onEdit = viewModel::edit,
        onPick = {
            try {
                // Le type MIME des .m3u / .xml.gz varie selon les fournisseurs : le contenu est vérifié au chargement.
                picker.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.editor_no_picker)) }
            }
        },
        onSave = viewModel::save,
        onBack = onClose,
    )
}

/** Formulaire d'ajout / de modification d'une playlist, sans dépendance au ViewModel. */
@Composable
fun SourceEditorContent(
    state: EditorState,
    snackbarHostState: SnackbarHostState,
    onEdit: ((EditorState) -> EditorState) -> Unit,
    onPick: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val window = rememberWindowInfo()
    val resources = LocalContext.current.resources
    fun errorText(field: SourceInputField): String? = state.errors[field]?.let(resources::describe)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(if (state.isNew) R.string.editor_title_new else R.string.editor_title_edit),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = 12.dp, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    PillButton(
                        stringResource(if (state.isNew) R.string.editor_save_and_load else R.string.action_save),
                        onSave,
                        icon = Icons.Filled.Check,
                        style = PillStyle.Primary,
                        enabled = !state.saving && !state.loading,
                    )
                },
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SectionCard(stringResource(R.string.editor_section_source)) {
                            NexTvTextField(
                                value = state.name,
                                onValueChange = { value -> onEdit { it.copy(name = value) } },
                                label = stringResource(R.string.editor_name),
                                placeholder = stringResource(R.string.editor_name_hint),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceTile(
                                    icon = NexTvIcons.Link,
                                    title = stringResource(R.string.editor_mode_url_short),
                                    subtitle = stringResource(R.string.editor_mode_url_hint),
                                    selected = state.mode == LocationMode.URL,
                                    onClick = { onEdit { it.copy(mode = LocationMode.URL) } },
                                )
                                ChoiceTile(
                                    icon = NexTvIcons.Folder,
                                    title = stringResource(R.string.editor_mode_file_short),
                                    subtitle = stringResource(R.string.editor_mode_file_hint),
                                    selected = state.mode == LocationMode.FILE,
                                    onClick = { onEdit { it.copy(mode = LocationMode.FILE) } },
                                )
                            }
                            when (state.mode) {
                                LocationMode.URL -> NexTvTextField(
                                    value = state.url,
                                    onValueChange = { value -> onEdit { it.copy(url = value) } },
                                    label = stringResource(R.string.editor_url),
                                    placeholder = "https://exemple.com/playlist.m3u8",
                                    error = errorText(SourceInputField.LOCATION),
                                    help = stringResource(R.string.editor_url_help),
                                    keyboardType = KeyboardType.Uri,
                                )
                                LocationMode.FILE -> DocumentField(
                                    document = state.document,
                                    persisted = state.documentPersisted,
                                    error = errorText(SourceInputField.LOCATION),
                                    help = stringResource(R.string.editor_file_help),
                                    onPick = onPick,
                                )
                            }
                        }

                        if (state.mode == LocationMode.URL) {
                            SectionCard(
                                stringResource(R.string.editor_section_credentials),
                                subtitle = stringResource(
                                    if (state.hasStoredCredentials && !state.removeStoredCredentials) {
                                        R.string.editor_credentials_stored
                                    } else {
                                        R.string.editor_credentials_help
                                    },
                                ),
                                icon = Icons.Filled.Lock,
                            ) {
                                CredentialsFields(state, onEdit, errorText(SourceInputField.USERNAME))
                                NexTvTextField(
                                    value = state.userAgent,
                                    onValueChange = { value -> onEdit { it.copy(userAgent = value) } },
                                    label = stringResource(R.string.editor_user_agent),
                                    error = errorText(SourceInputField.USER_AGENT),
                                    help = stringResource(R.string.editor_user_agent_help),
                                )
                            }
                        }

                        PillButton(
                            stringResource(if (state.isNew) R.string.editor_save_and_load else R.string.action_save),
                            onSave,
                            icon = Icons.Filled.Check,
                            style = PillStyle.Primary,
                            enabled = !state.saving,
                            modifier = Modifier.align(Alignment.End).padding(bottom = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsFields(state: EditorState, onEdit: ((EditorState) -> EditorState) -> Unit, usernameError: String?) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    NexTvTextField(
        value = state.username,
        onValueChange = { value -> onEdit { it.copy(username = value) } },
        label = stringResource(R.string.editor_username),
        error = usernameError,
    )
    NexTvTextField(
        value = state.password,
        onValueChange = { value -> onEdit { it.copy(password = value) } },
        label = stringResource(R.string.editor_password),
        keyboardType = KeyboardType.Password,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(stringResource(if (showPassword) R.string.action_hide else R.string.action_show))
            }
        },
    )
    if (state.hasStoredCredentials) {
        CheckRow(stringResource(R.string.editor_credentials_remove), state.removeStoredCredentials) { checked ->
            onEdit { it.copy(removeStoredCredentials = checked, username = "", password = "") }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusScale = 1.01f,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(NexTvIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        document?.let { it.displayName ?: stringResource(R.string.location_local_file) }
                            ?: stringResource(R.string.editor_no_file),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.editor_pick_file), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            document != null && !persisted ->
                Text(stringResource(R.string.editor_file_not_persisted), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Carte de section du formulaire. */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Grande tuile de choix exclusif. */
@Composable
private fun RowScope.ChoiceTile(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        selected = selected,
        focusScale = 1.03f,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** Champ de saisie aux couleurs de l'application. */
@Composable
fun NexTvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    help: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> @Composable { Text(text) } },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: help)?.let { text -> @Composable { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        shape = MaterialTheme.shapes.medium,
        colors = nexTvTextFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun nexTvTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FocusSurface(
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        }
    }
}

/** Option activable : toute la ligne prend le focus et bascule l'interrupteur (télécommande). */
@Composable
fun ToggleRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    FocusSurface(
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        focusScale = 1f,
        enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 16.dp))
        }
    }
}
