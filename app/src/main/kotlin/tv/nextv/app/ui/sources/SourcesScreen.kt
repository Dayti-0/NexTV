package tv.nextv.app.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.repository.RefreshResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val viewModel = appViewModel { container, _ -> SourcesViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    var pendingDelete by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.refreshEvents.collect { event ->
            val name = viewModel.sourceName(event.sourceId) ?: return@collect
            val message = when (val result = event.result) {
                is RefreshResult.Success -> if (event.epgOnly) {
                    resources.getString(R.string.refresh_epg_success, name, result.epg.programmeCount)
                } else {
                    resources.getString(R.string.refresh_success, name, result.channelCount)
                }
                is RefreshResult.Failure -> resources.getString(R.string.refresh_failure, name, resources.describe(result.error))
                RefreshResult.NotFound -> return@collect
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = viewModel::refreshAll) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh_all))
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_source)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            EmptySources(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.source.id }) { item ->
                    SourceCard(
                        item = item,
                        redactor = viewModel.redactor,
                        onOpen = { onOpen(item.source.id) },
                        onRefresh = { viewModel.refresh(item.source.id) },
                        onRefreshEpg = { viewModel.refreshEpg(item.source.id) },
                        onEdit = { onEdit(item.source.id) },
                        onDelete = { pendingDelete = item.source.id },
                    )
                }
            }
        }
    }

    pendingDelete?.let { id ->
        val name = viewModel.sourceName(id).orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun EmptySources(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.sources_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.sources_empty_message), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceCard(
    item: SourceItem,
    redactor: Redactor,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshEpg: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = item.source
    val resources = LocalContext.current.resources
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(
                    if (source.location is SourceLocation.Remote) R.string.location_kind_remote else R.string.location_kind_local,
                ) + " · " + resources.describe(source.location, redactor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (item.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.status_refreshing), style = MaterialTheme.typography.bodySmall)
            } else {
                SourceStatusText(source)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRefreshEpg, enabled = !item.refreshing && source.status.channelCount > 0) {
                    Text(stringResource(R.string.action_refresh_epg))
                }
                IconButton(onClick = onRefresh, enabled = !item.refreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun SourceStatusText(source: PlaylistSource) {
    val status = source.status
    val resources = LocalContext.current.resources
    val error = status.error
    if (status.lastAttemptAtMillis == null) {
        Text(stringResource(R.string.status_never_loaded), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (error != null) {
        Text(resources.describe(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    status.lastSuccessAtMillis?.let { success ->
        Text(
            stringResource(R.string.status_channels, status.channelCount, relativeTime(success)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    status.warnings.forEach { warning ->
        Text(
            stringResource(
                when (warning) {
                    SourceWarning.TRUNCATED -> R.string.warning_truncated
                    SourceWarning.SINGLE_STREAM -> R.string.warning_single_stream
                    SourceWarning.UNSUPPORTED_STREAMS -> R.string.warning_unsupported_streams
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    val epg = status.epg
    val epgError = epg.error
    if (epg.lastSuccessAtMillis != null) {
        Text(
            stringResource(R.string.status_epg, epg.programmeCount, epg.matchedChannelCount),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (epgError != null) {
        Text(
            stringResource(R.string.status_epg_error, resources.describe(epgError)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
