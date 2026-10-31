package tv.nextv.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupVisibilityRow
import tv.nextv.app.data.db.HiddenGroupEntity
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.ContentKind

/** Catégories de chaînes, de films ou de séries ([kind]) de la playlist active, à afficher ou masquer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelGroupsViewModel(container: AppContainer, private val kind: ContentKind) : ViewModel() {

    private val hiddenGroups = container.hiddenGroupDao

    private val sourceId: StateFlow<Long?> = container.settings.activePlaylistId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** `null` pendant le chargement. */
    val groups: StateFlow<List<GroupVisibilityRow>?> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.channelDao.observeAllGroups(id, kind.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [title] vide : chaînes sans groupe. */
    fun setVisible(title: String, visible: Boolean) {
        val id = sourceId.value ?: return
        viewModelScope.launch {
            if (visible) {
                hiddenGroups.show(id, kind.name, title)
            } else {
                hiddenGroups.hide(HiddenGroupEntity(id, kind.name, title))
            }
        }
    }

    fun showAll() {
        val id = sourceId.value ?: return
        viewModelScope.launch { hiddenGroups.showAll(id, kind.name) }
    }
}

@Composable
fun ChannelGroupsScreen(kind: ContentKind, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "groups-${kind.name}") { container, _ -> ChannelGroupsViewModel(container, kind) }
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    ChannelGroupsContent(
        groups = groups,
        onVisibility = viewModel::setVisible,
        onShowAll = viewModel::showAll,
        onBack = onBack,
        kind = kind,
    )
}

/** Liste des catégories d'un type de contenu, sans dépendance au ViewModel (captures d'écran). */
@Composable
fun ChannelGroupsContent(
    groups: List<GroupVisibilityRow>?,
    onVisibility: (String, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onBack: () -> Unit,
    kind: ContentKind = ContentKind.LIVE,
) {
    val window = rememberWindowInfo()
    val resources = LocalContext.current.resources
    val hiddenCount = groups.orEmpty().count { it.hidden }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(
                    when (kind) {
                        ContentKind.MOVIE -> R.string.settings_movie_groups
                        ContentKind.SERIES -> R.string.settings_series_groups
                        else -> R.string.settings_channel_groups
                    },
                ),
                subtitle = if (hiddenCount == 0) {
                    stringResource(R.string.settings_channel_groups_visible)
                } else {
                    resources.getQuantityString(R.plurals.channel_groups_hidden, hiddenCount, hiddenCount)
                },
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                actions = {
                    PillButton(stringResource(R.string.channel_groups_show_all), onShowAll, icon = NexTvIcons.Tv, enabled = hiddenCount > 0)
                },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = window.horizontalPadding, end = window.horizontalPadding, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            stringResource(
                                when {
                                    groups?.isEmpty() == true -> when (kind) {
                                        ContentKind.MOVIE -> R.string.movie_groups_empty
                                        ContentKind.SERIES -> R.string.series_groups_empty
                                        else -> R.string.channel_groups_empty
                                    }
                                    kind == ContentKind.MOVIE -> R.string.movie_groups_help
                                    kind == ContentKind.SERIES -> R.string.series_groups_help
                                    else -> R.string.channel_groups_help
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        )
                    }
                    items(groups.orEmpty(), key = { it.title ?: NO_GROUP_KEY }) { group ->
                        ToggleRow(
                            title = group.title ?: stringResource(R.string.channels_no_group),
                            description = resources.getQuantityString(
                                when (kind) {
                                    ContentKind.MOVIE -> R.plurals.movies_count
                                    ContentKind.SERIES -> R.plurals.series_count
                                    else -> R.plurals.channels_count
                                },
                                group.count,
                                group.count,
                            ),
                            checked = !group.hidden,
                            onChange = { visible -> onVisibility(group.title.orEmpty(), visible) },
                        )
                    }
                }
            }
        }
    }
}

private const val NO_GROUP_KEY = "\u0000"
