package tv.nextv.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.channelCount
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport

@Composable
fun ChannelsScreen(onBack: () -> Unit, onPlay: (Long) -> Unit, onGuide: (Long) -> Unit) {
    val viewModel = appViewModel { container, handle -> ChannelsViewModel(container, handle) }
    val source by viewModel.source.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    ChannelsContent(
        sourceName = source?.name.orEmpty(),
        sourceError = source?.status?.error,
        loaded = source != null,
        hasGuide = (source?.status?.epg?.programmeCount ?: 0) > 0,
        groups = groups,
        selectedGroup = selectedGroup,
        query = query,
        channels = channels,
        refreshing = refreshing,
        now = now,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onGuide = { onGuide(viewModel.sourceId) },
        onQueryChange = { viewModel.query.value = it },
        onSelectGroup = { viewModel.selectedGroup.value = it },
        onPlay = onPlay,
    )
}

/** Liste des chaînes : deux volets (groupes + grille) sur TV et tablette, une colonne sur téléphone. */
@Composable
fun ChannelsContent(
    sourceName: String,
    sourceError: SourceError?,
    loaded: Boolean,
    hasGuide: Boolean,
    groups: List<GroupRow>,
    selectedGroup: String?,
    query: String,
    channels: LazyPagingItems<ChannelListRow>,
    refreshing: Boolean,
    now: Long,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGuide: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val window = rememberWindowInfo()
    var unsupported by remember { mutableStateOf<ChannelListRow?>(null) }
    val onChannelClick: (ChannelListRow) -> Unit = { channel ->
        if (channel.streamSupport == StreamSupport.UNSUPPORTED.name) unsupported = channel else onPlay(channel.id)
    }
    val total = groups.sumOf { it.count }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (window.isWide) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                GroupPane(
                    window = window,
                    sourceName = sourceName,
                    total = total,
                    groups = groups,
                    selectedGroup = selectedGroup,
                    query = query,
                    onBack = onBack,
                    onQueryChange = onQueryChange,
                    onSelectGroup = onSelectGroup,
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ScreenHeader(
                        title = groupTitle(selectedGroup),
                        subtitle = channelCount(groups.countFor(selectedGroup)),
                        modifier = Modifier.padding(start = 32.dp, end = window.horizontalPadding, top = 24.dp, bottom = 16.dp),
                        actions = {
                            if (hasGuide) PillButton(stringResource(R.string.action_open_guide), onGuide, icon = NexTvIcons.Guide)
                            PillButton(stringResource(R.string.action_refresh), onRefresh, icon = Icons.Filled.Refresh, enabled = !refreshing)
                        },
                    )
                    if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NexTvColors.Live)
                    ChannelsBody(channels, loaded, refreshing, sourceError, query, onRefresh) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 176.dp),
                            contentPadding = PaddingValues(start = 32.dp, end = window.horizontalPadding, bottom = 32.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                                channels[index]?.let { ChannelCard(it, now) { onChannelClick(it) } }
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenHeader(
                    title = sourceName,
                    subtitle = channelCount(total),
                    modifier = Modifier.padding(start = 4.dp, end = window.horizontalPadding, top = 8.dp, bottom = 8.dp),
                    navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                    actions = {
                        if (hasGuide) IconAction(NexTvIcons.Guide, stringResource(R.string.action_open_guide), onGuide)
                        IconAction(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onRefresh, enabled = !refreshing)
                    },
                )
                SearchField(query, onQueryChange, Modifier.padding(horizontal = window.horizontalPadding))
                if (groups.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { GroupChip(stringResource(R.string.channels_all_short), total, selectedGroup == null) { onSelectGroup(null) } }
                        items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                            GroupChip(group.title ?: stringResource(R.string.channels_no_group), group.count, selectedGroup == group.title.orEmpty()) {
                                onSelectGroup(group.title.orEmpty())
                            }
                        }
                    }
                }
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NexTvColors.Live)
                ChannelsBody(channels, loaded, refreshing, sourceError, query, onRefresh) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = window.horizontalPadding - 8.dp, end = window.horizontalPadding - 8.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                            channels[index]?.let { ChannelRow(it, now) { onChannelClick(it) } }
                        }
                    }
                }
            }
        }
    }

    unsupported?.let { channel ->
        AlertDialog(
            onDismissRequest = { unsupported = null },
            title = { Text(channel.name) },
            text = { Text(issuesText(channel).ifEmpty { stringResource(R.string.stream_unsupported) }) },
            confirmButton = { TextButton(onClick = { unsupported = null }) { Text(stringResource(R.string.action_ok)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun groupTitle(selectedGroup: String?) = when (selectedGroup) {
    null -> stringResource(R.string.channels_all_title)
    "" -> stringResource(R.string.channels_no_group)
    else -> selectedGroup
}

private fun List<GroupRow>.countFor(selected: String?): Int =
    if (selected == null) sumOf { it.count } else firstOrNull { it.title.orEmpty() == selected }?.count ?: 0

@Composable
private fun GroupPane(
    window: WindowInfo,
    sourceName: String,
    total: Int,
    groups: List<GroupRow>,
    selectedGroup: String?,
    query: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectGroup: (String?) -> Unit,
) {
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = window.horizontalPadding - 12.dp, end = 16.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Column {
                Text(sourceName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    channelCount(total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SearchField(query, onQueryChange, Modifier.padding(start = 12.dp))
        Text(
            stringResource(R.string.channels_groups),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { GroupListItem(stringResource(R.string.channels_all_short), total, selectedGroup == null) { onSelectGroup(null) } }
            items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                GroupListItem(group.title ?: stringResource(R.string.channels_no_group), group.count, selectedGroup == group.title.orEmpty()) {
                    onSelectGroup(group.title.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun GroupListItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupChip(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.channels_search)) },
        singleLine = true,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Contenu principal, ou état vide / erreur lorsque la liste est vide. */
@Composable
private fun ChannelsBody(
    channels: LazyPagingItems<ChannelListRow>,
    loaded: Boolean,
    refreshing: Boolean,
    error: SourceError?,
    query: String,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val resources = LocalContext.current.resources
    val loading = channels.loadState.refresh is LoadState.Loading
    if (channels.itemCount == 0 && loaded && !refreshing && !loading) {
        EmptyState(
            icon = NexTvIcons.Tv,
            title = when {
                query.isNotBlank() -> stringResource(R.string.channels_no_match)
                error != null -> stringResource(R.string.channels_error_title)
                else -> stringResource(R.string.channels_empty)
            },
            message = when {
                query.isNotBlank() -> stringResource(R.string.channels_no_match_hint)
                error != null -> resources.describe(error)
                else -> stringResource(R.string.channels_empty_hint)
            },
            action = if (query.isBlank()) {
                @Composable { PillButton(stringResource(R.string.action_refresh), onRefresh, icon = Icons.Filled.Refresh) }
            } else {
                null
            },
        )
    } else {
        content()
    }
}

@Composable
private fun ChannelCard(channel: ChannelListRow, now: Long, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = MaterialTheme.shapes.medium, focusScale = 1.06f) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(64.dp), monogramSize = 22.dp)
                channel.channelNumber?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
                SupportBadge(channel, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    channel.nowTitle ?: stringResource(R.string.channels_no_epg),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (channel.nowTitle != null) MaterialTheme.colorScheme.onSurfaceVariant else NexTvColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val start = channel.nowStart
                val stop = channel.nowStop
                if (start != null && stop != null) {
                    Spacer(Modifier.height(4.dp))
                    LiveProgress(start, stop, now)
                } else {
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelListRow, now: Long, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        focusScale = 1.01f,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 72.dp, height = 48.dp), monogramSize = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(channel.channelNumber, channel.name).joinToString("  "),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                channel.nowTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val start = channel.nowStart
                val stop = channel.nowStop
                if (start != null && stop != null) LiveProgress(start, stop, now, Modifier.padding(top = 4.dp))
            }
            SupportBadge(channel)
        }
    }
}

@Composable
private fun SupportBadge(channel: ChannelListRow, modifier: Modifier = Modifier) {
    when (StreamSupport.entries.firstOrNull { it.name == channel.streamSupport } ?: StreamSupport.SUPPORTED) {
        StreamSupport.SUPPORTED -> Unit
        StreamSupport.LIMITED -> Badge(stringResource(R.string.stream_limited), modifier, color = NexTvColors.Warning)
        StreamSupport.UNSUPPORTED -> Badge(stringResource(R.string.stream_unsupported_short), modifier, color = NexTvColors.Error)
    }
}

@Composable
private fun issuesText(channel: ChannelListRow): String {
    val resources = LocalContext.current.resources
    return channel.streamIssues.split(',')
        .mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }
        .joinToString("\n") { resources.describe(it) }
}

private const val NO_GROUP_KEY = "\u0000"
