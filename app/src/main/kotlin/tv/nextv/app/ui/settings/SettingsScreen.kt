package tv.nextv.app.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Lock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.stream.QualityTarget
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.model.ContentKind
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.backup.BackupFormatException
import tv.nextv.core.repository.RefreshResult
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val BACKUP_FILE_NAME = "nextv-sauvegarde.json"

/** [onRestored] : sauvegarde restaurée, la playlist active est à recharger. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPlaylists: () -> Unit,
    onProxy: () -> Unit,
    onGroups: (ContentKind) -> Unit = {},
    onRestored: () -> Unit = {},
) {
    val viewModel = appViewModel { container, _ -> SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = context.resources
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Sauvegarde : fichier choisi avec le sélecteur du système, sinon dans le dossier de l'application.
    val backupFile = remember { File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_FILE_NAME) }
    fun saveBackup(where: String, write: (String) -> Unit) {
        scope.launch {
            val message = try {
                val text = viewModel.exportBackup()
                withContext(Dispatchers.IO) { write(text) }
                resources.getString(R.string.settings_backup_saved, where)
            } catch (e: IOException) {
                resources.getString(R.string.settings_backup_failed)
            } catch (e: SecurityException) {
                resources.getString(R.string.settings_backup_failed)
            }
            snackbar.showSnackbar(message)
        }
    }
    fun restoreBackup(read: () -> String) {
        scope.launch {
            val result = try {
                viewModel.restoreBackup(withContext(Dispatchers.IO) { read() })
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: BackupFormatException) {
                null
            }
            if (result == null) {
                snackbar.showSnackbar(resources.getString(R.string.settings_restore_failed))
            } else {
                snackbar.showSnackbar(resources.getQuantityString(R.plurals.settings_restore_done, result.total, result.total))
                onRestored()
            }
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            saveBackup(uri.lastPathSegment.orEmpty()) { text ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IOException("Fichier inaccessible")
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreBackup { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("Fichier inaccessible") }
        }
    }

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
        account = account,
        snackbarHostState = snackbar,
        onBack = onBack,
        onPlaylists = onPlaylists,
        onRefreshPlaylist = viewModel::refreshPlaylist,
        onRefreshGuide = viewModel::refreshGuide,
        onAutoQuality = viewModel::setAutoQuality,
        onSaveTmdbKey = viewModel::saveTmdbKey,
        onClearTmdbKey = viewModel::clearTmdbKey,
        onSaveFootballKey = viewModel::saveFootballKey,
        onClearFootballKey = viewModel::clearFootballKey,
        onProxy = onProxy,
        onGroups = onGroups,
        onBackup = {
            try {
                createDocument.launch(BACKUP_FILE_NAME)
            } catch (e: ActivityNotFoundException) {
                // Pas de sélecteur de fichiers (Fire TV) : dossier de l'application.
                saveBackup(backupFile.path) { backupFile.writeText(it) }
            }
        },
        onRestore = {
            try {
                openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (e: ActivityNotFoundException) {
                if (backupFile.exists()) {
                    restoreBackup { backupFile.readText() }
                } else {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.settings_restore_missing, backupFile.path)) }
                }
            }
        },
        onCycleDecoder = viewModel::cycleDecoder,
        onGridColumns = viewModel::setGridColumns,
        onChannelPreview = viewModel::setChannelPreview,
        onStartOnLastChannel = viewModel::setStartOnLastChannel,
        onLowerAdVolume = viewModel::setLowerAdVolume,
        onCycleTheme = viewModel::cycleTheme,
        onCycleHomeLayout = viewModel::cycleHomeLayout,
        onCycleQualityTarget = viewModel::cycleQualityTarget,
        onToggleChannelList = viewModel::toggleChannelList,
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
    onSaveFootballKey: (String) -> Unit = {},
    onClearFootballKey: () -> Unit = {},
    onGroups: (ContentKind) -> Unit = {},
    onCycleDecoder: (String) -> Unit = {},
    onGridColumns: (GridColumns) -> Unit = {},
    onChannelPreview: (Boolean) -> Unit = {},
    onStartOnLastChannel: (Boolean) -> Unit = {},
    onLowerAdVolume: (Boolean) -> Unit = {},
    onCycleTheme: () -> Unit = {},
    onCycleHomeLayout: () -> Unit = {},
    onCycleQualityTarget: () -> Unit = {},
    onToggleChannelList: () -> Unit = {},
    onCycleDns: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (AppRelease) -> Unit = {},
    onAllowInstall: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    account: AccountState? = null,
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
                            account?.let { AccountText(it, now) }
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
                        ValueRow(
                            title = stringResource(R.string.settings_quality_target),
                            value = qualityTargetLabel(state.qualityTarget),
                            onClick = onCycleQualityTarget,
                        )
                        InfoText(qualityTargetHelp(state.qualityTarget))
                        ToggleRow(
                            title = stringResource(R.string.settings_auto_quality),
                            description = stringResource(R.string.settings_auto_quality_help),
                            checked = state.autoQuality,
                            onChange = onAutoQuality,
                        )
                        ToggleRow(
                            title = stringResource(R.string.settings_start_last_channel),
                            description = stringResource(R.string.settings_start_last_channel_help),
                            checked = state.startOnLastChannel,
                            onChange = onStartOnLastChannel,
                        )
                        ToggleRow(
                            title = stringResource(R.string.settings_lower_ads),
                            description = stringResource(R.string.settings_lower_ads_help),
                            checked = state.lowerAdVolume,
                            onChange = onLowerAdVolume,
                        )
                        state.decoders.forEach { choice -> DecoderRow(choice) { onCycleDecoder(choice.mimeType) } }
                        if (state.decoders.isNotEmpty()) InfoText(stringResource(R.string.settings_decoder_help))
                    }

                    // Affichage de l'accueil.
                    SectionCard(stringResource(R.string.settings_display), icon = NexTvIcons.Grid) {
                        val grid = state.gridColumns
                        // Interface : disposition de l'accueil, thème de couleurs et disposition des chaînes.
                        ValueRow(
                            title = stringResource(R.string.settings_home_layout),
                            value = homeLayoutLabel(state.homeLayout),
                            onClick = onCycleHomeLayout,
                        )
                        InfoText(homeLayoutHelp(state.homeLayout))
                        ValueRow(
                            title = stringResource(R.string.settings_theme),
                            value = themeLabel(state.theme),
                            onClick = onCycleTheme,
                        )
                        // Mosaïque ou liste : interface classique seulement (les autres ont leur propre présentation).
                        if (state.homeLayout == HomeLayout.CLASSIC) {
                            ValueRow(
                                title = stringResource(R.string.settings_channel_layout),
                                value = stringResource(if (grid.channelList) R.string.settings_channel_layout_list else R.string.settings_channel_layout_grid),
                                onClick = onToggleChannelList,
                            )
                        }
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
                        ToggleRow(
                            title = stringResource(R.string.settings_channel_preview),
                            description = stringResource(R.string.settings_channel_preview_help),
                            checked = state.channelPreview,
                            onChange = onChannelPreview,
                        )
                        if (state.playlist != null) {
                            // Catégories masquables : chaînes, films, séries.
                            listOf(
                                ContentKind.LIVE to R.string.settings_channel_groups,
                                ContentKind.MOVIE to R.string.settings_movie_groups,
                                ContentKind.SERIES to R.string.settings_series_groups,
                            ).forEach { (kind, title) ->
                                val hidden = state.hiddenGroups[kind] ?: 0
                                ValueRow(
                                    title = stringResource(title),
                                    value = if (hidden == 0) {
                                        stringResource(R.string.settings_channel_groups_visible)
                                    } else {
                                        resources.getQuantityString(R.plurals.channel_groups_hidden, hidden, hidden)
                                    },
                                    onClick = { onGroups(kind) },
                                )
                            }
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

                    // Matchs de la section Foot.
                    SectionCard(
                        stringResource(R.string.settings_football),
                        subtitle = stringResource(R.string.settings_football_help),
                        icon = NexTvIcons.Football,
                    ) {
                        var key by rememberSaveable { mutableStateOf("") }
                        InfoText(
                            stringResource(
                                when {
                                    state.footballKeySaved -> R.string.settings_tmdb_saved
                                    state.footballBuiltIn -> R.string.settings_tmdb_builtin
                                    else -> R.string.settings_football_missing
                                },
                            ),
                        )
                        NexTvTextField(
                            value = key,
                            onValueChange = { key = it.trim() },
                            label = stringResource(R.string.settings_football_key),
                            keyboardType = KeyboardType.Password,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                stringResource(R.string.settings_tmdb_save),
                                {
                                    onSaveFootballKey(key)
                                    key = ""
                                },
                                icon = Icons.Filled.Check,
                                enabled = key.isNotBlank(),
                            )
                            if (state.footballKeySaved) {
                                PillButton(stringResource(R.string.settings_tmdb_remove), onClearFootballKey, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
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
                    // Sauvegarde et restauration.
                    SectionCard(stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_backup_help), icon = Icons.Filled.Lock) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(stringResource(R.string.settings_backup_save), onBackup, icon = Icons.Filled.Check)
                            PillButton(stringResource(R.string.settings_backup_restore), onRestore, icon = Icons.Filled.Refresh)
                        }
                    }

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
private fun qualityTargetLabel(target: QualityTarget): String = stringResource(
    when (target) {
        QualityTarget.BEST -> R.string.quality_target_best
        QualityTarget.HD -> R.string.quality_target_hd
        QualityTarget.ECO -> R.string.quality_target_eco
    },
)

@Composable
private fun qualityTargetHelp(target: QualityTarget): String = stringResource(
    when (target) {
        QualityTarget.BEST -> R.string.quality_target_best_help
        QualityTarget.HD -> R.string.quality_target_hd_help
        QualityTarget.ECO -> R.string.quality_target_eco_help
    },
)

@Composable
private fun homeLayoutLabel(layout: HomeLayout): String = stringResource(
    when (layout) {
        HomeLayout.CLASSIC -> R.string.home_layout_classic
        HomeLayout.STREAMING -> R.string.home_layout_streaming
        HomeLayout.BOX -> R.string.home_layout_box
    },
)

@Composable
private fun homeLayoutHelp(layout: HomeLayout): String = stringResource(
    when (layout) {
        HomeLayout.CLASSIC -> R.string.home_layout_classic_help
        HomeLayout.STREAMING -> R.string.home_layout_streaming_help
        HomeLayout.BOX -> R.string.home_layout_box_help
    },
)

@Composable
private fun themeLabel(style: ThemeStyle): String = stringResource(
    when (style) {
        ThemeStyle.NIGHT -> R.string.theme_night
        ThemeStyle.BLACK -> R.string.theme_black
        ThemeStyle.CINEMA -> R.string.theme_cinema
        ThemeStyle.VIOLET -> R.string.theme_violet
    },
)

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

/** Abonnement Xtream : état, date de fin et connexions simultanées. */
@Composable
private fun AccountText(account: AccountState, now: Long) {
    when (account) {
        AccountState.Loading -> InfoText(stringResource(R.string.settings_account_checking))
        AccountState.Unavailable -> InfoText(stringResource(R.string.settings_account_unavailable))
        is AccountState.Loaded -> {
            val info = account.info
            val resources = LocalContext.current.resources
            when {
                !info.authenticated -> ErrorText(stringResource(R.string.settings_account_refused))
                !info.active -> ErrorText(
                    stringResource(
                        R.string.settings_account_inactive,
                        when (info.status?.lowercase(Locale.ROOT)) {
                            "expired" -> stringResource(R.string.settings_account_expired)
                            "banned" -> stringResource(R.string.settings_account_banned)
                            "disabled" -> stringResource(R.string.settings_account_disabled)
                            else -> info.status.orEmpty()
                        },
                    ),
                )
                else -> {
                    val expires = info.expiresAtMillis
                    val text = listOfNotNull(
                        stringResource(if (info.trial) R.string.settings_account_trial else R.string.settings_account_active),
                        expires?.let { stringResource(R.string.settings_account_until, DateFormat.getDateInstance(DateFormat.LONG).format(Date(it))) }
                            ?: stringResource(R.string.settings_account_unlimited),
                        info.maxConnections?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.settings_account_connections, it, it) },
                    ).joinToString("  ·  ")
                    // Fin dans moins d'une semaine : en évidence.
                    val soon = expires != null && expires - now < ACCOUNT_WARNING_MILLIS
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (soon) NexTvColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val ACCOUNT_WARNING_MILLIS = 7 * 24 * 60 * 60_000L

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
