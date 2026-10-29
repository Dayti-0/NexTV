package tv.nextv.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(onBack: () -> Unit, onPlay: (Long) -> Unit) {
    val viewModel = appViewModel { container, handle -> ChannelsViewModel(container, handle) }
    val source by viewModel.source.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val resources = LocalContext.current.resources
    var unsupported by remember { mutableStateOf<ChannelListRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.channels_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (groups.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { viewModel.selectedGroup.value = null },
                            label = { Text(stringResource(R.string.channels_all, groups.sumOf { it.count })) },
                        )
                    }
                    items(groups, key = { it.title ?: NO_GROUP_KEY }) { group ->
                        val key = group.title.orEmpty()
                        FilterChip(
                            selected = selectedGroup == key,
                            onClick = { viewModel.selectedGroup.value = key },
                            label = {
                                Text("${group.title ?: stringResource(R.string.channels_no_group)} (${group.count})")
                            },
                        )
                    }
                }
            }

            val currentSource = source
            val error = currentSource?.status?.error
            if (channels.itemCount == 0 && currentSource != null && !refreshing) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            error != null -> resources.describe(error)
                            query.isNotBlank() -> stringResource(R.string.channels_no_match)
                            else -> stringResource(R.string.channels_empty)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (query.isBlank()) {
                        Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.action_refresh))
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index] ?: return@items
                        ChannelRow(channel) {
                            if (channel.streamSupport == StreamSupport.UNSUPPORTED.name) unsupported = channel else onPlay(channel.id)
                        }
                        HorizontalDivider()
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
        )
    }
}

@Composable
private fun issuesText(channel: ChannelListRow): String {
    val resources = LocalContext.current.resources
    return parseIssues(channel.streamIssues).joinToString("\n") { resources.describe(it) }
}

private const val NO_GROUP_KEY = "\u0000"

private fun parseIssues(encoded: String): List<StreamIssue> =
    encoded.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }

@Composable
private fun ChannelRow(channel: ChannelListRow, onClick: () -> Unit) {
    val support = StreamSupport.entries.firstOrNull { it.name == channel.streamSupport } ?: StreamSupport.SUPPORTED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Text(channel.name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(channel.channelNumber, channel.name).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel.nowTitle?.let {
                Text(
                    stringResource(R.string.channels_now, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (support) {
            StreamSupport.SUPPORTED -> Unit
            StreamSupport.LIMITED -> Badge(stringResource(R.string.stream_limited), MaterialTheme.colorScheme.tertiary)
            StreamSupport.UNSUPPORTED -> Badge(stringResource(R.string.stream_unsupported_short), MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}
