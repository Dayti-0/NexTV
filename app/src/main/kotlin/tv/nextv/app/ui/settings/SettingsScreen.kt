package tv.nextv.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.repository.RefreshResult

@Composable
fun SettingsScreen(onBack: () -> Unit, onPlaylists: () -> Unit, onProxy: () -> Unit) {
    val viewModel = appViewModel { container, _ -> SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.refreshEvents.collect { event ->
            val message = when (val result = event.result) {
                is RefreshResult.Success -> if (event.epgOnly) {
                    resources.getString(R.string.settings_guide_updated, result.epg.programmeCount)
                } else {
                    resources.getString(R.string.settings_playlist_updated, result.channelCount)
                }
                is RefreshResult.Failure -> resources.describe(result.error)
                RefreshResult.NotFound -> return@collect
            }
            snackbar.showSnackbar(message)
        }
    }

    SettingsContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onPlaylists = onPlaylists,
        onRefreshPlaylist = viewModel::refreshPlaylist,
        onRefreshGuide = viewModel::refreshGuide,
        onAutoQuality = viewModel::setAutoQuality,
        onSaveTmdbKey = viewModel::saveTmdbKey,
        onClearTmdbKey = viewModel::clearTmdbKey,
        onProxy = onProxy,
    )
}

/** Réglages, sans dépendance au ViewModel (captures d'écran). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onPlaylists: () -> Unit,
    onRefreshPlaylist: () -> Unit,
    onRefreshGuide: () -> Unit,
    onAutoQuality: (Boolean) -> Unit,
    onSaveTmdbKey: (String) -> Unit,
    onClearTmdbKey: () -> Unit,
    onProxy: () -> Unit,
    now: Long = System.currentTimeMillis(),
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
            )
            Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Playlist active.
                    SectionCard(stringResource(R.string.settings_playlist), icon = NexTvIcons.Tv) {
                        val playlist = state.playlist
                        if (playlist == null) {
                            InfoText(stringResource(R.string.settings_no_playlist))
                        } else {
                            Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                            val status = playlist.status
                            InfoText(
                                listOfNotNull(
                                    resources.getQuantityString(R.plurals.channels_count, status.channelCount, status.channelCount),
                                    status.lastSuccessAtMillis?.let { stringResource(R.string.settings_updated, relativeTime(it, now).lowercaseFirst()) },
                                ).joinToString("  ·  "),
                            )
                            status.error?.let { ErrorText(resources.describe(it)) }
                            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NexTvColors.Live)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                stringResource(R.string.settings_manage_playlists, state.playlistCount),
                                onPlaylists,
                                icon = NexTvIcons.Grid,
                                style = PillStyle.Primary,
                            )
                            if (playlist != null) {
                                PillButton(
                                    stringResource(R.string.action_refresh),
                                    onRefreshPlaylist,
                                    icon = Icons.Filled.Refresh,
                                    enabled = !state.refreshing,
                                )
                            }
                        }
                    }

                    // Lecture.
                    SectionCard(stringResource(R.string.settings_playback), icon = Icons.Filled.PlayArrow) {
                        ToggleRow(
                            title = stringResource(R.string.settings_auto_quality),
                            description = stringResource(R.string.settings_auto_quality_help),
                            checked = state.autoQuality,
                            onChange = onAutoQuality,
                        )
                    }

                    // Guide TV commun.
                    SectionCard(
                        stringResource(R.string.settings_guide),
                        subtitle = stringResource(R.string.settings_guide_help, HttpEpgFeedProvider.XMLTV_FR_URL),
                        icon = NexTvIcons.Guide,
                    ) {
                        val epg = state.playlist?.status?.epg
                        InfoText(
                            state.guideDownloadedAt?.let { stringResource(R.string.settings_guide_downloaded, relativeTime(it, now).lowercaseFirst()) }
                                ?: stringResource(R.string.settings_guide_never),
                        )
                        if (epg != null && epg.programmeCount > 0) {
                            InfoText(stringResource(R.string.settings_guide_matched, epg.programmeCount, epg.matchedChannelCount))
                        }
                        epg?.error?.let { ErrorText(resources.describe(it)) }
                        if (state.playlist != null) {
                            PillButton(
                                stringResource(R.string.settings_guide_refresh),
                                onRefreshGuide,
                                icon = Icons.Filled.Refresh,
                                enabled = !state.refreshing,
                            )
                        }
                    }

                    // Fiches films et séries.
                    SectionCard(
                        stringResource(R.string.settings_tmdb),
                        subtitle = stringResource(R.string.settings_tmdb_help),
                        icon = Icons.Filled.Star,
                    ) {
                        var key by rememberSaveable { mutableStateOf("") }
                        InfoText(
                            stringResource(
                                when {
                                    state.tmdbKeySaved -> R.string.settings_tmdb_saved
                                    state.tmdbBuiltIn -> R.string.settings_tmdb_builtin
                                    else -> R.string.settings_tmdb_missing
                                },
                            ),
                        )
                        NexTvTextField(
                            value = key,
                            onValueChange = { key = it.trim() },
                            label = stringResource(R.string.settings_tmdb_key),
                            keyboardType = KeyboardType.Password,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                stringResource(R.string.settings_tmdb_save),
                                {
                                    onSaveTmdbKey(key)
                                    key = ""
                                },
                                icon = Icons.Filled.Check,
                                enabled = key.isNotBlank(),
                            )
                            if (state.tmdbKeySaved) {
                                PillButton(stringResource(R.string.settings_tmdb_remove), onClearTmdbKey, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
                            }
                        }
                    }

                    // Réseau.
                    SectionCard(stringResource(R.string.proxy_section), icon = NexTvIcons.Cloud) {
                        InfoText(
                            if (state.proxyEnabled) {
                                stringResource(R.string.settings_proxy_on, state.proxyAddress.orEmpty())
                            } else {
                                stringResource(R.string.settings_proxy_off)
                            },
                        )
                        PillButton(stringResource(R.string.settings_proxy_configure), onProxy, icon = Icons.Filled.Settings)
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoText(stringResource(R.string.settings_version, state.version))
                    }
                }
            }
        }
    }
}

/** « Il y a 5 minutes » → « il y a 5 minutes », en milieu de phrase. */
private fun String.lowercaseFirst(): String = replaceFirstChar { it.lowercaseChar() }

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
