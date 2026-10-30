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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.player.VideoDecoders
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.core.update.AppRelease
import tv.nextv.app.data.update.UpdateState
import tv.nextv.app.data.update.UpdateFailure
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.repository.RefreshResult
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onPlaylists: () -> Unit, onProxy: () -> Unit, onChannelGroups: () -> Unit = {}) {
    val viewModel = appViewModel { container, _ -> SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = context.resources
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Recherche de mise à jour à l'ouverture des réglages.
    LaunchedEffect(viewModel) {
        if (update == UpdateState.Idle) viewModel.checkUpdate()
    }

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
        onChannelGroups = onChannelGroups,
        onCycleDecoder = viewModel::cycleDecoder,
        onGridColumns = viewModel::setGridColumns,
        onCycleDns = viewModel::cycleDns,
        update = update,
        onCheckUpdate = viewModel::checkUpdate,
        onInstallUpdate = viewModel::installUpdate,
        onAllowInstall = {
            val opened = viewModel.installPermissionIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }.isSuccess
            } ?: false
            if (!opened) scope.launch { snackbar.showSnackbar(resources.getString(R.string.settings_update_permission_manual)) }
        },
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
    onChannelGroups: () -> Unit = {},
    onCycleDecoder: (String) -> Unit = {},
    onGridColumns: (GridColumns) -> Unit = {},
    onCycleDns: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (AppRelease) -> Unit = {},
    onAllowInstall: () -> Unit = {},
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
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
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
                        state.decoders.forEach { choice -> DecoderRow(choice) { onCycleDecoder(choice.mimeType) } }
                        if (state.decoders.isNotEmpty()) InfoText(stringResource(R.string.settings_decoder_help))
                    }

                    // Affichage de l'accueil.
                    SectionCard(stringResource(R.string.settings_display), icon = NexTvIcons.Grid) {
                        val grid = state.gridColumns
                        NumberRow(
                            title = stringResource(R.string.settings_channel_columns),
                            value = grid.channels,
                            range = GridColumns.CHANNELS,
                            onChange = { onGridColumns(grid.copy(channels = it)) },
                        )
                        NumberRow(
                            title = stringResource(R.string.settings_poster_columns),
                            value = grid.posters,
                            range = GridColumns.POSTERS,
                            onChange = { onGridColumns(grid.copy(posters = it)) },
                        )
                        InfoText(stringResource(R.string.settings_columns_help))
                        if (state.playlist != null) {
                            ValueRow(
                                title = stringResource(R.string.settings_channel_groups),
                                value = if (state.hiddenChannelGroups == 0) {
                                    stringResource(R.string.settings_channel_groups_visible)
                                } else {
                                    resources.getQuantityString(R.plurals.channel_groups_hidden, state.hiddenChannelGroups, state.hiddenChannelGroups)
                                },
                                onClick = onChannelGroups,
                            )
                        }
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
                    SectionCard(stringResource(R.string.settings_network), icon = NexTvIcons.Cloud) {
                        ValueRow(
                            title = stringResource(R.string.settings_dns),
                            value = dnsLabel(state.dns),
                            onClick = onCycleDns,
                        )
                        InfoText(stringResource(R.string.settings_dns_help))
                        InfoText(
                            if (state.proxyEnabled) {
                                stringResource(R.string.settings_proxy_on, state.proxyAddress.orEmpty())
                            } else {
                                stringResource(R.string.settings_proxy_off)
                            },
                        )
                        PillButton(stringResource(R.string.settings_proxy_configure), onProxy, icon = Icons.Filled.Settings)
                    }

                    // Mise à jour de l'application.
                    SectionCard(stringResource(R.string.settings_update), icon = Icons.Filled.Refresh) {
                        UpdateSection(state.version, update, onCheckUpdate, onInstallUpdate, onAllowInstall)
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

/** Décodeur d'un codec : OK passe au choix suivant (automatique, puis chaque décodeur de l'appareil). */
@Composable
private fun DecoderRow(choice: DecoderChoice, onClick: () -> Unit) {
    val selected = choice.options.firstOrNull { it.name == choice.selected }
    val value = when {
        choice.options.isEmpty() -> stringResource(R.string.settings_decoder_none)
        selected == null -> stringResource(R.string.settings_decoder_auto)
        else -> stringResource(
            if (selected.hardware) R.string.settings_decoder_hardware else R.string.settings_decoder_software,
            selected.name,
        )
    }
    ValueRow(
        title = stringResource(if (choice.mimeType == VideoDecoders.HEVC) R.string.settings_decoder_hevc else R.string.settings_decoder_avc),
        value = value,
        onClick = onClick,
        enabled = choice.options.isNotEmpty(),
    )
}

/** État de la mise à jour et action possible (rechercher, installer, autoriser l'installation). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateSection(
    installed: String,
    update: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AppRelease) -> Unit,
    onAllowInstall: () -> Unit,
) {
    when (update) {
        UpdateState.Idle -> InfoText(stringResource(R.string.settings_update_installed, installed))
        UpdateState.Checking -> {
            InfoText(stringResource(R.string.settings_update_checking))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = NexTvColors.Live)
        }
        UpdateState.UpToDate -> InfoText(stringResource(R.string.settings_update_up_to_date, installed))
        is UpdateState.Available -> ReleaseInfo(update.release, installed)
        is UpdateState.Downloading -> {
            InfoText(stringResource(R.string.settings_update_downloading, update.release.version, megabytes(update.downloaded), megabytes(update.total)))
            LinearProgressIndicator(
                progress = { if (update.total > 0) (update.downloaded.toFloat() / update.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = NexTvColors.Live,
            )
        }
        is UpdateState.Installing -> InfoText(stringResource(R.string.settings_update_installing, update.release.version))
        is UpdateState.NeedsPermission -> InfoText(stringResource(R.string.settings_update_permission))
        is UpdateState.Failed -> ErrorText(
            stringResource(
                when (update.error) {
                    UpdateFailure.NOT_PUBLISHED -> R.string.settings_update_not_published
                    UpdateFailure.NETWORK -> R.string.settings_update_network
                    UpdateFailure.SERVER -> R.string.settings_update_server
                    UpdateFailure.CORRUPTED -> R.string.settings_update_corrupted
                    UpdateFailure.INSTALL -> R.string.settings_update_install_failed
                },
            ),
        )
    }
    val release = when (update) {
        is UpdateState.Available -> update.release
        is UpdateState.NeedsPermission -> update.release
        is UpdateState.Failed -> update.release
        else -> null
    }
    val busy = update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (update is UpdateState.NeedsPermission) {
            PillButton(stringResource(R.string.settings_update_allow), onAllowInstall, icon = Icons.Filled.Settings, style = PillStyle.Primary)
        }
        if (release != null) {
            PillButton(
                stringResource(R.string.settings_update_install, release.version),
                { onInstall(release) },
                icon = Icons.Filled.Check,
                style = if (update is UpdateState.NeedsPermission) PillStyle.Secondary else PillStyle.Primary,
                enabled = !busy,
            )
        }
        PillButton(stringResource(R.string.settings_update_check), onCheck, icon = Icons.Filled.Refresh, enabled = !busy, style = PillStyle.Ghost)
    }
}

@Composable
private fun ReleaseInfo(release: AppRelease, installed: String) {
    Text(stringResource(R.string.settings_update_available, release.version), style = MaterialTheme.typography.titleMedium)
    InfoText(stringResource(R.string.settings_update_installed, installed))
    // Notes de version : la liste des nouveautés, sans la partie installation.
    release.notes
        ?.substringBefore("**Installation**")
        ?.replace("**", "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12) }
}

private fun megabytes(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / 1_000_000.0)

@Composable
private fun dnsLabel(dns: DnsProvider): String = when (dns) {
    DnsProvider.SYSTEM -> stringResource(R.string.settings_dns_system)
    else -> stringResource(
        R.string.settings_dns_public,
        when (dns) {
            DnsProvider.QUAD9 -> "Quad9"
            DnsProvider.CLOUDFLARE -> "Cloudflare"
            else -> "Google"
        },
        dns.addresses.take(2).joinToString(" / "),
    )
}

/** Nombre réglable : gauche/droite pour diminuer/augmenter, OK passe à la valeur suivante (en boucle). */
@Composable
private fun NumberRow(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ValueRow(
        title = title,
        value = stringResource(R.string.settings_columns_value, value, range.first, range.last),
        onClick = { onChange(if (value >= range.last) range.first else value + 1) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            val step = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) onChange((value + step).coerceIn(range))
            true
        },
    )
}

/** Ligne titre + valeur ; OK change la valeur. */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        focusScale = 1f,
        enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
