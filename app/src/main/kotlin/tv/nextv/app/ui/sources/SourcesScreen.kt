package tv.nextv.app.ui.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.contentPadding
import tv.nextv.app.ui.components.monogram
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.repository.RefreshResult

@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onReload: () -> Unit,
) {
    val viewModel = appViewModel { container, _ -> SourcesViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    var pendingDelete by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.reload.collect { onReload() }
    }
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

    SourcesContent(
        state = state,
        redactor = viewModel.redactor,
        snackbarHostState = snackbar,
        onBack = onBack,
        onAdd = onAdd,
        onActivate = viewModel::activate,
        onRefresh = viewModel::refresh,
        onRefreshAll = viewModel::refreshAll,
        onEdit = onEdit,
        onDelete = { pendingDelete = it },
    )

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
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/**
 * Gestion des playlists : choix de la playlist active, ajout, modification, suppression, actualisation.
 * Sans dépendance au ViewModel (aperçus, captures d'écran).
 */
@Composable
fun SourcesContent(
    state: SourcesUiState,
    redactor: Redactor,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onActivate: (Long) -> Unit,
    onRefresh: (Long) -> Unit,
    onRefreshAll: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val window = rememberWindowInfo()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(R.string.sources_title),
                subtitle = stringResource(R.string.sources_subtitle),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = 12.dp, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    if (state.items.isNotEmpty()) {
                        if (window.isWide) {
                            PillButton(stringResource(R.string.action_refresh_all), onRefreshAll, icon = Icons.Filled.Refresh)
                        } else {
                            IconAction(Icons.Filled.Refresh, stringResource(R.string.action_refresh_all), onRefreshAll)
                        }
                    }
                    if (window.isWide) {
                        PillButton(stringResource(R.string.action_add_source), onAdd, icon = Icons.Filled.Add, style = PillStyle.Primary)
                    } else {
                        IconAction(Icons.Filled.Add, stringResource(R.string.action_add_source), onAdd, tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )

            if (state.loaded && state.items.isEmpty()) {
                EmptyState(
                    icon = NexTvIcons.Tv,
                    title = stringResource(R.string.sources_empty_title),
                    message = stringResource(R.string.sources_empty_message),
                    action = { PillButton(stringResource(R.string.action_add_first_source), onAdd, icon = Icons.Filled.Add, style = PillStyle.Primary) },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (window.isTv) 300.dp else 320.dp),
                    contentPadding = window.contentPadding(top = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.source.id }) { item ->
                        PlaylistCard(
                            item = item,
                            active = item.source.id == state.activeId,
                            redactor = redactor,
                            now = now,
                            onOpen = { onActivate(item.source.id) },
                            onRefresh = { onRefresh(item.source.id) },
                            onEdit = { onEdit(item.source.id) },
                            onDelete = { onDelete(item.source.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    item: SourceItem,
    active: Boolean,
    redactor: Redactor,
    now: Long,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = item.source
    val resources = LocalContext.current.resources
    val (start, end) = NexTvColors.swatchFor(source.name)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            FocusSurface(
                onClick = onOpen,
                shape = MaterialTheme.shapes.large,
                color = Color.Transparent,
                focusedColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusScale = 1f,
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(104.dp)
                            .background(Brush.linearGradient(listOf(start, end))),
                    ) {
                        Text(
                            monogram(source.name),
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                            color = Color.White.copy(alpha = 0.22f),
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (active) StatusPill(stringResource(R.string.sources_active), Color.White)
                            SourceStatusPill(item)
                        }
                    }
                    if (item.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NexTvColors.Live)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                if (source.location is SourceLocation.Remote) R.string.location_kind_remote else R.string.location_kind_local,
                            ) + " · " + resources.describe(source.location, redactor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        SourceStats(source, now)
                        SourceMessages(source)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onRefresh, enabled = !item.refreshing)
                Spacer(Modifier.weight(1f))
                IconAction(Icons.Filled.Edit, stringResource(R.string.action_edit), onEdit)
                IconAction(Icons.Filled.Delete, stringResource(R.string.action_delete), onDelete)
            }
        }
    }
}

@Composable
private fun SourceStatusPill(item: SourceItem) {
    val status = item.source.status
    when {
        item.refreshing -> StatusPill(stringResource(R.string.status_refreshing_short), Color.White)
        status.lastAttemptAtMillis == null -> StatusPill(stringResource(R.string.status_never_loaded), Color.White)
        status.error != null -> StatusPill(stringResource(R.string.status_error), Color(0xFFFFD6D6))
        else -> StatusPill(stringResource(R.string.status_ok), Color.White)
    }
}

@Composable
private fun SourceStats(source: PlaylistSource, now: Long) {
    val status = source.status
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Stat(value = status.channelCount.toString(), label = stringResource(R.string.stat_channels))
        Stat(
            value = if (status.epg.programmeCount > 0) status.epg.matchedChannelCount.toString() else "—",
            label = stringResource(R.string.stat_guide),
        )
        Stat(
            value = status.lastSuccessAtMillis?.let { relativeTime(it, now) } ?: "—",
            label = stringResource(R.string.stat_updated),
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceMessages(source: PlaylistSource) {
    val resources = LocalContext.current.resources
    val status = source.status
    status.error?.let {
        Text(
            resources.describe(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    status.epg.error?.let {
        Text(
            stringResource(R.string.status_epg_error, resources.describe(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
