package tv.nextv.app.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import android.text.format.Formatter
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.LoadProgress
import androidx.compose.material.icons.filled.Close
import tv.nextv.app.data.settings.AdSound
import tv.nextv.app.player.AdRadios
import tv.nextv.app.ui.player.detailRes
import tv.nextv.app.ui.player.labelRes
import tv.nextv.app.player.AdRecordings
import androidx.compose.runtime.DisposableEffect
import tv.nextv.app.ui.components.FadingBottom
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nextv.app.CrashLog
import tv.nextv.app.R
import tv.nextv.app.data.settings.PlaylistRefresh
import tv.nextv.app.data.settings.PreviewVolume
import tv.nextv.app.data.update.UpdateFailure
import tv.nextv.app.data.update.UpdateState
import tv.nextv.app.player.VideoDecoders
import tv.nextv.app.player.VideoLayers
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.common.relativeTime
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.QrCode
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.backup.BackupFormatException
import tv.nextv.core.catalog.FranceRegions
import tv.nextv.core.epg.HttpEpgFeedProvider
import tv.nextv.core.model.ContentKind
import tv.nextv.core.net.DnsProvider
import tv.nextv.core.repository.RefreshResult
import tv.nextv.core.stream.QualityTarget
import tv.nextv.core.update.AppRelease

private const val BACKUP_FILE_NAME = "nextv-sauvegarde.json"

/** [onRestored] : sauvegarde restaurée, la playlist active est à recharger. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPlaylists: () -> Unit,
    onProxy: () -> Unit,
    onGroups: (ContentKind) -> Unit = {},
    onRestored: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onQuickSetup: () -> Unit = {},
    onRemoteSend: () -> Unit = {},
    onRemoteReceive: () -> Unit = {},
) {
    val viewModel = appViewModel { container, _ -> SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val refreshProgress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val sportSections by viewModel.sportSections.collectAsStateWithLifecycle()
    val adRecordings by viewModel.adRecordings.collectAsStateWithLifecycle()
    val adPlace by viewModel.adPlace.collectAsStateWithLifecycle()
    val adSound by viewModel.adSound.collectAsStateWithLifecycle()
    val radioVisual by viewModel.radioVisual.collectAsStateWithLifecycle()
    val auddKeySaved by viewModel.auddKeySaved.collectAsStateWithLifecycle()
    val adUpload by viewModel.adUpload.collectAsStateWithLifecycle()
    val tvLink by viewModel.tvLink.state.collectAsStateWithLifecycle()
    // Playlist venue d'une télé reliée, choisie parce que celle-ci n'en avait pas : chargée comme après une restauration.
    LaunchedEffect(viewModel) { viewModel.tvLink.reload.collect { onRestored() } }
    // Clé USB branchée ou retirée, autorisation accordée : emplacements relus à chaque retour sur les réglages.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshAdPlaces()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
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
        refreshProgress = refreshProgress,
        sportSections = sportSections,
        onFootballSection = viewModel::setFootballSection,
        onMmaSection = viewModel::setMmaSection,
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
        auddKeySaved = auddKeySaved,
        onSaveAuddKey = viewModel::saveAuddKey,
        onClearAuddKey = viewModel::clearAuddKey,
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
        tvLink = tvLink,
        tvLinkActions = remember(viewModel) {
            TvLinkActions(
                onShowCode = viewModel.tvLink::showCode,
                onHideCode = viewModel.tvLink::hideCode,
                onJoin = viewModel.tvLink::join,
                onSyncNow = viewModel.tvLink::syncNow,
                onUnlink = viewModel.tvLink::unlink,
            )
        },
        onCycleDecoder = viewModel::cycleDecoder,
        onChannelPreview = viewModel::setChannelPreview,
        onPreviewHandoff = viewModel::setPreviewHandoff,
        onTrailerAutoplay = viewModel::setTrailerAutoplay,
        onTrailerSound = viewModel::setTrailerSound,
        onCycleTrailerDelay = viewModel::cycleTrailerDelay,
        onStartOnLastChannel = viewModel::setStartOnLastChannel,
        onLowerAdVolume = viewModel::setLowerAdVolume,
        adRecordings = adRecordings,
        adPlace = adPlace,
        onCycleAdPlace = viewModel::cycleAdPlace,
        adUpload = adUpload,
        onStopAdRecording = viewModel::stopAdRecording,
        onDeleteAdRecording = viewModel::deleteAdRecording,
        adSound = adSound,
        onCycleAdSound = viewModel::cycleAdSound,
        onAdRadio = viewModel::setAdRadio,
        radioVisual = radioVisual,
        onRadioVisual = viewModel::setRadioVisual,
        onDeleteAdMusic = viewModel::deleteAdMusic,
        onAllowUsb = {
            val opened = viewModel.allFilesAccess(context)?.let { intent -> runCatching { context.startActivity(intent) }.isSuccess } ?: false
            if (!opened) scope.launch { snackbar.showSnackbar(resources.getString(R.string.settings_ad_recordings_allow_manual)) }
        },
        onShareAdMusic = viewModel::shareAdMusic,
        onDeleteAdRecordings = viewModel::deleteAdRecordings,
        onAppearance = onAppearance,
        onCycleQualityTarget = viewModel::cycleQualityTarget,
        onCyclePreviewVolume = viewModel::cyclePreviewVolume,
        onCyclePlaylistRefresh = viewModel::cyclePlaylistRefresh,
        onClearCrash = viewModel::clearCrash,
        onToggleChannelList = viewModel::toggleChannelList,
        onCycleDns = viewModel::cycleDns,
        onHideUhd = viewModel::setHideUhd,
        onAudioPassthrough = viewModel::setAudioPassthrough,
        onFrance3Region = viewModel::setFrance3Region,
        onMergeChannels = viewModel::setMergeChannels,
        onFrenchOnly = viewModel::setFrenchOnly,
        update = update,
        onQuickSetup = onQuickSetup,
        onRemoteSend = onRemoteSend,
        onRemoteReceive = onRemoteReceive,
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
    auddKeySaved: Boolean = false,
    onSaveAuddKey: (String) -> Unit = {},
    onClearAuddKey: () -> Unit = {},
    onGroups: (ContentKind) -> Unit = {},
    onCycleDecoder: (String) -> Unit = {},
    onChannelPreview: (Boolean) -> Unit = {},
    onPreviewHandoff: (Boolean) -> Unit = {},
    onTrailerAutoplay: (Boolean) -> Unit = {},
    onTrailerSound: (Boolean) -> Unit = {},
    onCycleTrailerDelay: () -> Unit = {},
    onStartOnLastChannel: (Boolean) -> Unit = {},
    onLowerAdVolume: (Boolean) -> Unit = {},
    /** Enregistrements pour l'analyse des pubs gardés sur l'appareil. */
    adRecordings: List<AdRecordings.Recording> = emptyList(),
    adPlace: AdPlace = AdPlace(),
    onCycleAdPlace: () -> Unit = {},
    /** Envoi des enregistrements sur filebin. */
    adUpload: AdUploadUi = AdUploadUi(),
    onStopAdRecording: () -> Unit = {},
    onDeleteAdRecording: (String) -> Unit = {},
    /** Pendant les pubs : son baissé, radio ou musique de l'appareil. */
    adSound: AdSoundUi = AdSoundUi(),
    onCycleAdSound: () -> Unit = {},
    onAdRadio: (String) -> Unit = {},
    radioVisual: tv.nextv.app.data.settings.RadioVisual = tv.nextv.app.data.settings.RadioVisual.DEFAULT,
    onRadioVisual: (tv.nextv.app.data.settings.RadioVisual) -> Unit = {},
    onDeleteAdMusic: () -> Unit = {},
    onAllowUsb: () -> Unit = {},
    onShareAdMusic: () -> AdRecordings.Share? = { null },
    onDeleteAdRecordings: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onCycleQualityTarget: () -> Unit = {},
    onCyclePreviewVolume: () -> Unit = {},
    onCyclePlaylistRefresh: () -> Unit = {},
    onClearCrash: () -> Unit = {},
    onToggleChannelList: () -> Unit = {},
    onCycleDns: () -> Unit = {},
    onHideUhd: (Boolean) -> Unit = {},
    onAudioPassthrough: (Boolean) -> Unit = {},
    onFrance3Region: (String?) -> Unit = {},
    onMergeChannels: (Boolean) -> Unit = {},
    onFrenchOnly: (Boolean) -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (AppRelease) -> Unit = {},
    onAllowInstall: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    /** Télés reliées. */
    tvLink: TvLinkUi = TvLinkUi(),
    tvLinkActions: TvLinkActions = TvLinkActions(),
    account: AccountState? = null,
    /** Avancement de la mise à jour de la playlist en cours (`null` : pas encore d'étape signalée). */
    refreshProgress: LoadProgress? = null,
    /** Rubriques Foot et MMA de l'accueil (Affichage). */
    sportSections: SportSections = SportSections(),
    onFootballSection: (Boolean) -> Unit = {},
    onMmaSection: (Boolean) -> Unit = {},
    now: Long = System.currentTimeMillis(),
    onQuickSetup: () -> Unit = {},
    /** Configuration à distance : envoyer à une autre télé, afficher le code de celle-ci. */
    onRemoteSend: () -> Unit = {},
    onRemoteReceive: () -> Unit = {},
    /** Page ouverte en arrivant (écran large). */
    initialPage: SettingsPage = SettingsPage.PLAYLIST,
) {
    val window = rememberWindowInfo()
    val resources = LocalContext.current.resources
    val pages = remember(state.lastCrash != null) { SettingsPage.entries.filter { it != SettingsPage.INCIDENT || state.lastCrash != null } }

    // Contenu de chaque page : des cartes regroupant les réglages voisins.
    val body: @Composable (SettingsPage) -> Unit = { page ->
        when (page) {
            // Dernier plantage : en tête, pour pouvoir le signaler (photo de l'écran).
            SettingsPage.INCIDENT -> state.lastCrash?.let { crash ->
                SectionCard(stringResource(R.string.settings_crash), icon = Icons.Filled.Warning) {
                    val help = when (crash.kind) {
                        CrashLog.Kind.CRASH -> R.string.settings_crash_help
                        CrashLog.Kind.SYSTEM -> R.string.settings_crash_system_help
                        CrashLog.Kind.BACKGROUND -> R.string.settings_crash_background_help
                    }
                    InfoText(stringResource(help, relativeTime(crash.atMillis, now).lowercaseFirst(), crash.version))
                    Text(crash.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        crash.details,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = CRASH_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PillButton(stringResource(R.string.settings_crash_clear), onClearCrash, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
                }
            }

            SettingsPage.PLAYLIST -> {
                SectionCard(stringResource(R.string.settings_group_active_playlist)) {
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
                        if (state.refreshing) RefreshProgressBar(refreshProgress)
                        // Fréquence de mise à jour automatique (le guide TV, lui, se met à jour chaque jour).
                        ValueRow(
                            title = stringResource(R.string.settings_playlist_refresh),
                            value = playlistRefreshLabel(state.playlistRefresh),
                            onClick = onCyclePlaylistRefresh,
                        )
                        InfoText(stringResource(R.string.settings_playlist_refresh_help))
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
                                stringResource(R.string.settings_refresh_playlist_now),
                                onRefreshPlaylist,
                                icon = Icons.Filled.Refresh,
                                enabled = !state.refreshing,
                            )
                        }
                    }
                }
                // Guide TV commun à toutes les playlists.
                SectionCard(
                    stringResource(R.string.settings_group_guide_source),
                    subtitle = stringResource(R.string.settings_guide_help, HttpEpgFeedProvider.XMLTV_FR_URL),
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
            }

            // Ce que montrent les listes de chaînes : rangement, pays, région, catégories masquées.
            SettingsPage.CHANNELS -> {
                SectionCard(stringResource(R.string.settings_group_channels)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_merge_channels),
                        description = stringResource(R.string.settings_merge_channels_help),
                        checked = state.mergeChannels,
                        onChange = onMergeChannels,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_french_only),
                        description = stringResource(R.string.settings_french_only_help),
                        checked = state.frenchOnly,
                        onChange = onFrenchOnly,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_start_last_channel),
                        description = stringResource(R.string.settings_start_last_channel_help),
                        checked = state.startOnLastChannel,
                        onChange = onStartOnLastChannel,
                    )
                    // Une seule édition régionale de France 3 dans les listes (aucune sans choix, chaînes rassemblées).
                    var choosingRegion by remember { mutableStateOf(false) }
                    val noRegion = stringResource(if (state.mergeChannels) R.string.settings_france3_region_national else R.string.settings_france3_region_all)
                    ValueRow(
                        title = stringResource(R.string.settings_france3_region),
                        value = state.france3Regions.firstOrNull { it.key == state.france3Region }?.label
                            ?: state.france3Region?.let { stringResource(R.string.settings_france3_region_missing) }
                            ?: noRegion,
                        onClick = { choosingRegion = true },
                        enabled = state.france3Regions.isNotEmpty() || state.france3Region != null,
                    )
                    InfoText(
                        when {
                            state.france3Regions.isEmpty() -> stringResource(R.string.settings_france3_region_none)
                            state.mergeChannels -> stringResource(R.string.settings_france3_region_merged_help)
                            else -> stringResource(R.string.settings_france3_region_help)
                        },
                    )
                    if (choosingRegion) {
                        RegionDialog(
                            regions = state.france3Regions,
                            noRegion = noRegion,
                            selected = state.france3Region,
                            onSelect = {
                                choosingRegion = false
                                onFrance3Region(it)
                            },
                            onDismiss = { choosingRegion = false },
                        )
                    }
                }
                if (state.playlist != null) {
                    // Catégories masquables : chaînes, films, séries.
                    SectionCard(stringResource(R.string.settings_group_categories)) {
                        listOf(
                            // Sous « Catégories affichées » : juste Chaînes, Films, Séries.
                            ContentKind.LIVE to R.string.settings_groups_channels,
                            ContentKind.MOVIE to R.string.settings_groups_movies,
                            ContentKind.SERIES to R.string.settings_groups_series,
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
            }

            SettingsPage.PLAYBACK -> {
                SectionCard(stringResource(R.string.settings_group_quality)) {
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
                        title = stringResource(R.string.settings_hide_uhd),
                        description = stringResource(R.string.settings_hide_uhd_help),
                        checked = state.hideUhd,
                        onChange = onHideUhd,
                    )
                }
                if (state.decoders.isNotEmpty()) {
                    SectionCard(stringResource(R.string.settings_group_decoders), subtitle = stringResource(R.string.settings_decoder_help)) {
                        state.decoders.forEach { choice -> DecoderRow(choice) { onCycleDecoder(choice.mimeType) } }
                    }
                }
            }

            // Radios, pubs et sortie du son.
            SettingsPage.SOUND -> {
                SectionCard(stringResource(R.string.settings_group_radios)) {
                    // Radios et Web radios : animation à la place de l'écran noir, ou rien.
                    var choosingVisual by remember { mutableStateOf(false) }
                    ValueRow(
                        title = stringResource(R.string.settings_radio_visual),
                        value = stringResource(radioVisual.labelRes()),
                        onClick = { choosingVisual = true },
                    )
                    InfoText(stringResource(R.string.settings_radio_visual_help))
                    if (choosingVisual) {
                        ChoiceDialog(
                            title = stringResource(R.string.settings_radio_visual),
                            choices = tv.nextv.app.data.settings.RadioVisual.entries.map { visual ->
                                Choice(visual.name, stringResource(visual.labelRes()), visual.detailRes()?.let { stringResource(it) })
                            },
                            selected = radioVisual.name,
                            onSelect = { key ->
                                choosingVisual = false
                                tv.nextv.app.data.settings.RadioVisual.entries.firstOrNull { it.name == key }?.let(onRadioVisual)
                            },
                            onDismiss = { choosingVisual = false },
                        )
                    }
                }
                SectionCard(stringResource(R.string.settings_group_ads), subtitle = stringResource(R.string.settings_group_ads_help)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_lower_ads),
                        description = stringResource(R.string.settings_lower_ads_help),
                        checked = state.lowerAdVolume,
                        onChange = onLowerAdVolume,
                    )
                    // Pendant les pubs : son baissé, une radio sans pub, ou la musique envoyée depuis le téléphone.
                    if (state.lowerAdVolume) {
                        ValueRow(
                            title = stringResource(R.string.settings_ad_sound),
                            value = when (adSound.mode) {
                                AdSound.RADIO -> stringResource(R.string.settings_ad_sound_radio, adSound.radio)
                                AdSound.MUSIC -> stringResource(R.string.settings_ad_sound_music, adSound.tracks)
                                else -> stringResource(R.string.settings_ad_sound_lower)
                            },
                            onClick = onCycleAdSound,
                        )
                        when (adSound.mode) {
                            AdSound.RADIO -> {
                                var choosingRadio by remember { mutableStateOf(false) }
                                ValueRow(stringResource(R.string.settings_ad_radio), adSound.radio, onClick = { choosingRadio = true })
                                if (choosingRadio) {
                                    ChoiceDialog(
                                        title = stringResource(R.string.settings_ad_radio),
                                        choices = AdRadios.all.map { Choice(it.key, it.name, it.genre) },
                                        selected = adSound.radioKey,
                                        onSelect = { key ->
                                            choosingRadio = false
                                            key?.let(onAdRadio)
                                        },
                                        onDismiss = { choosingRadio = false },
                                    )
                                }
                            }
                            AdSound.MUSIC -> {
                                var addingMusic by remember { mutableStateOf(false) }
                                ValueRow(
                                    stringResource(R.string.settings_ad_music),
                                    stringResource(R.string.settings_ad_music_value, adSound.tracks),
                                    onClick = { addingMusic = true },
                                )
                                if (addingMusic) {
                                    AdRecordingsDialog(adRecordings, onShareAdMusic, onDeleteAdMusic, onDismiss = { addingMusic = false }, musicTracks = adSound.tracks)
                                }
                            }
                        }
                        InfoText(stringResource(R.string.settings_ad_sound_help))
                    }
                    // Enregistrements pour l'analyse des pubs : envoyés tout seuls sur filebin (adresse et QR code ici).
                    if (state.lowerAdVolume || adRecordings.isNotEmpty()) {
                        var sharing by remember { mutableStateOf(false) }
                        ValueRow(
                            title = stringResource(R.string.settings_ad_recordings),
                            value = when {
                                adPlace.recordingMinutes != null -> stringResource(R.string.settings_ad_recordings_running, adPlace.recordingMinutes)
                                adRecordings.isEmpty() -> stringResource(R.string.settings_ad_recordings_none)
                                else -> stringResource(R.string.settings_ad_recordings_count, adRecordings.size, megabytes(adRecordings.sumOf { it.bytes }))
                            },
                            onClick = { sharing = true },
                        )
                        if (sharing) {
                            AdRecordingsDialog(
                                adRecordings, { null }, onDeleteAdRecordings, onDismiss = { sharing = false },
                                recordingMinutes = adPlace.recordingMinutes, onStopRecording = onStopAdRecording,
                                onDeleteOne = onDeleteAdRecording, filebin = adUpload,
                            )
                        }
                        if (adUpload.bin != null) InfoText(filebinStatus(adUpload))
                        // Où vont les enregistrements : mémoire de l'appareil ou clé USB branchée (OK : la suivante).
                        val placeName = if (adPlace.usb) adPlace.label.orEmpty() else stringResource(R.string.settings_ad_recordings_internal)
                        ValueRow(
                            title = stringResource(R.string.settings_ad_recordings_place),
                            value = when {
                                adPlace.missing -> stringResource(R.string.settings_ad_recordings_missing)
                                adPlace.access == AdRecordings.Access.NEEDS_PERMISSION -> stringResource(R.string.settings_ad_recordings_needs_access, placeName)
                                adPlace.access == AdRecordings.Access.READ_ONLY -> stringResource(R.string.settings_ad_recordings_read_only, placeName)
                                adPlace.access == AdRecordings.Access.NOT_MOUNTED -> stringResource(R.string.settings_ad_recordings_not_mounted, placeName)
                                adPlace.freeBytes != null -> stringResource(R.string.settings_ad_recordings_free, placeName, gigabytes(adPlace.freeBytes))
                                else -> placeName
                            },
                            onClick = onCycleAdPlace,
                        )
                        // Clé vue mais fermée à l'appli : l'autorisation se donne dans les réglages du système.
                        if (adPlace.access == AdRecordings.Access.NEEDS_PERMISSION) {
                            ValueRow(
                                title = stringResource(R.string.settings_ad_recordings_allow),
                                value = stringResource(R.string.settings_ad_recordings_allow_help),
                                onClick = onAllowUsb,
                            )
                        }
                        InfoText(stringResource(R.string.settings_ad_recordings_place_help))
                        // Stockages annoncés par le système : la photo de cette ligne dit pourquoi une clé n'est pas proposée.
                        if (adPlace.storage.isNotEmpty()) InfoText(stringResource(R.string.settings_ad_recordings_storage, adPlace.storage))
                    }
                }
                SectionCard(stringResource(R.string.settings_group_audio_output)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_audio_passthrough),
                        description = stringResource(R.string.settings_audio_passthrough_help),
                        checked = state.audioPassthrough,
                        onChange = onAudioPassthrough,
                    )
                }
            }

            SettingsPage.DISPLAY -> {
                // Interface de l'accueil et palette de couleurs : écran Apparence.
                SectionCard(stringResource(R.string.settings_group_interface)) {
                    ValueRow(
                        title = stringResource(R.string.appearance_open),
                        value = "${homeLayoutLabel(state.homeLayout)}  ·  ${themeLabel(state.theme)}",
                        onClick = onAppearance,
                    )
                }
                // Rubriques de sport de l'accueil : masquées si on ne les regarde pas.
                SectionCard(stringResource(R.string.settings_group_sections)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_section_football),
                        description = stringResource(R.string.settings_section_football_help),
                        checked = sportSections.football,
                        onChange = onFootballSection,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_section_mma),
                        description = stringResource(R.string.settings_section_mma_help),
                        checked = sportSections.mma,
                        onChange = onMmaSection,
                    )
                }
                SectionCard(stringResource(R.string.settings_group_preview)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_channel_preview),
                        description = stringResource(R.string.settings_channel_preview_help),
                        checked = state.channelPreview,
                        onChange = onChannelPreview,
                    )
                    if (state.channelPreview) {
                        ValueRow(
                            title = stringResource(R.string.settings_preview_volume),
                            value = previewVolumeLabel(state.previewVolume),
                            onClick = onCyclePreviewVolume,
                        )
                        // Couche vidéo du lecteur : Android 10 et plus seulement.
                        if (VideoLayers.supported) {
                            ToggleRow(
                                title = stringResource(R.string.settings_preview_handoff),
                                description = stringResource(R.string.settings_preview_handoff_help),
                                checked = state.previewHandoff,
                                onChange = onPreviewHandoff,
                            )
                        }
                    }
                    ToggleRow(
                        title = stringResource(R.string.settings_trailer_autoplay),
                        description = stringResource(R.string.settings_trailer_autoplay_help),
                        checked = state.trailerAutoplay,
                        onChange = onTrailerAutoplay,
                    )
                    if (state.trailerAutoplay) {
                        ToggleRow(
                            title = stringResource(R.string.settings_trailer_sound),
                            description = stringResource(R.string.settings_trailer_sound_help),
                            checked = state.trailerSound,
                            onChange = onTrailerSound,
                        )
                        ValueRow(
                            title = stringResource(R.string.settings_trailer_delay),
                            value = stringResource(R.string.settings_trailer_delay_value, state.trailerDelaySeconds),
                            onClick = onCycleTrailerDelay,
                        )
                    }
                }
            }

            SettingsPage.NETWORK -> {
                SectionCard(stringResource(R.string.settings_group_dns)) {
                    ValueRow(
                        title = stringResource(R.string.settings_dns),
                        value = dnsLabel(state.dns),
                        onClick = onCycleDns,
                    )
                }
                SectionCard(stringResource(R.string.settings_group_proxy)) {
                    InfoText(
                        if (state.proxyEnabled) {
                            stringResource(R.string.settings_proxy_on, state.proxyAddress.orEmpty())
                        } else {
                            stringResource(R.string.settings_proxy_off)
                        },
                    )
                    PillButton(stringResource(R.string.settings_proxy_configure), onProxy, icon = Icons.Filled.Settings)
                }
            }

            // Mêmes favoris, « Déjà vu » et réglages sur plusieurs télés.
            SettingsPage.LINK -> TvLinkPage(tvLink, tvLinkActions, now)

            // Installer une autre télé à distance, sauvegarder ou restaurer celle-ci dans un fichier.
            SettingsPage.TRANSFER -> {
                // Configuration rapide : interface, couleurs et lecture, une étape par écran (comme au premier lancement).
                SectionCard(stringResource(R.string.quick_setup_title), subtitle = stringResource(R.string.quick_setup_entry_help), icon = Icons.Filled.Build) {
                    PillButton(stringResource(R.string.settings_quick_setup_open), onQuickSetup, icon = Icons.Filled.Build, style = PillStyle.Primary)
                }
                RemoteSetupPage(onRemoteSend, onRemoteReceive)
                SectionCard(stringResource(R.string.settings_group_backup_file), subtitle = stringResource(R.string.settings_backup_help)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.settings_backup_save), onBackup, icon = Icons.Filled.Check)
                        PillButton(stringResource(R.string.settings_backup_restore), onRestore, icon = Icons.Filled.Refresh)
                    }
                }
                // Clés des services : fiches des films et séries, matchs de la section Foot, titres des radios.
                KeyCard(
                    title = stringResource(R.string.settings_tmdb),
                    subtitle = stringResource(R.string.settings_tmdb_help),
                    status = stringResource(
                        when {
                            state.tmdbKeySaved -> R.string.settings_tmdb_saved
                            state.tmdbBuiltIn -> R.string.settings_tmdb_builtin
                            else -> R.string.settings_tmdb_missing
                        },
                    ),
                    label = stringResource(R.string.settings_tmdb_key),
                    saved = state.tmdbKeySaved,
                    onSave = onSaveTmdbKey,
                    onClear = onClearTmdbKey,
                )
                KeyCard(
                    title = stringResource(R.string.settings_football),
                    subtitle = stringResource(R.string.settings_football_help),
                    status = stringResource(
                        when {
                            state.footballKeySaved -> R.string.settings_tmdb_saved
                            state.footballBuiltIn -> R.string.settings_tmdb_builtin
                            else -> R.string.settings_football_missing
                        },
                    ),
                    label = stringResource(R.string.settings_football_key),
                    saved = state.footballKeySaved,
                    onSave = onSaveFootballKey,
                    onClear = onClearFootballKey,
                )
                // Morceau en cours des radios qui ne l'annoncent pas : reconnu à l'écoute (quelques essais par jour sans clé).
                KeyCard(
                    title = stringResource(R.string.settings_audd),
                    subtitle = stringResource(R.string.settings_audd_help),
                    status = stringResource(
                        when {
                            auddKeySaved -> R.string.settings_tmdb_saved
                            tv.nextv.app.BuildConfig.AUDD_API_KEY.isNotBlank() -> R.string.settings_tmdb_builtin
                            else -> R.string.settings_audd_missing
                        },
                    ),
                    label = stringResource(R.string.settings_audd_key),
                    saved = auddKeySaved,
                    onSave = onSaveAuddKey,
                    onClear = onClearAuddKey,
                )
            }

            // Mise à jour de l'application, puis version et mentions légales.
            SettingsPage.UPDATE -> {
                SectionCard(stringResource(R.string.settings_update)) {
                    UpdateSection(state.version, update, onCheckUpdate, onInstallUpdate, onAllowInstall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoText(stringResource(R.string.settings_version, state.version))
                    // Mentions légales (licence GPL v3, imposée par le décodeur FFmpeg).
                    InfoText(stringResource(R.string.settings_license))
                }
            }
        }
    }

    // Résumé de chaque page sous son nom, dans la liste.
    val summary: @Composable (SettingsPage) -> String? = { page ->
        when (page) {
            SettingsPage.INCIDENT -> state.lastCrash?.let { relativeTime(it.atMillis, now) }
            SettingsPage.PLAYLIST -> state.playlist?.name ?: stringResource(R.string.settings_page_no_playlist)
            SettingsPage.CHANNELS -> channelsSummary(state)
            SettingsPage.PLAYBACK -> qualityTargetLabel(state.qualityTarget)
            SettingsPage.SOUND -> stringResource(radioVisual.labelRes())
            SettingsPage.DISPLAY -> "${homeLayoutLabel(state.homeLayout)} · ${themeLabel(state.theme)}"
            SettingsPage.NETWORK -> dnsLabel(state.dns)
            SettingsPage.LINK -> tvLinkSummary(tvLink)
            SettingsPage.TRANSFER -> null
            SettingsPage.UPDATE -> when (update) {
                is UpdateState.Available -> stringResource(R.string.settings_page_update_available, update.release.version)
                else -> stringResource(R.string.settings_page_version, state.version)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (window.isWide) {
            SettingsPanes(
                pages = pages,
                window = window,
                onBack = onBack,
                summary = summary,
                body = body,
                modifier = Modifier.fillMaxSize().padding(padding),
                initialPage = initialPage,
            )
        } else {
            // Écran étroit (téléphone) : toutes les pages l'une sous l'autre.
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenHeader(
                    title = stringResource(R.string.settings_title),
                    modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
                    navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
                )
                val settingsScroll = rememberScrollState()
                FadingBottom({ settingsScroll.canScrollForward }, { settingsScroll.canScrollBackward }, ends = settingsScroll) { fade ->
                    Column(
                        Modifier.fillMaxSize().imePadding().then(fade).verticalScroll(settingsScroll)
                            .padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        pages.forEach { page ->
                            PageHeader(page, Modifier.padding(top = 12.dp))
                            body(page)
                        }
                    }
                }
            }
        }
    }
}

/** Résumé de la page « Chaînes » : catégories masquées, sinon les chaînes rassemblées ou non. */
@Composable
private fun channelsSummary(state: SettingsUiState): String {
    val hidden = state.hiddenGroups.values.sum()
    return when {
        hidden > 0 -> LocalContext.current.resources.getQuantityString(R.plurals.channel_groups_hidden, hidden, hidden)
        state.frenchOnly -> stringResource(R.string.settings_french_only)
        else -> stringResource(if (state.mergeChannels) R.string.settings_channels_summary_merged else R.string.settings_channels_summary_all)
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
    // Une partie par version : la télécommande s'arrête sur chacune, toujours entière à l'écran (le texte
    // seul ne prend pas le focus, la liste sautait d'un bouton à l'autre par-dessus).
    val sections = release.changes?.replace("**", "")
        ?.split(Regex("""\n\s*\n"""))
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    ReadableBlock {
        Text(stringResource(R.string.settings_update_available, release.version), style = MaterialTheme.typography.titleMedium)
        InfoText(stringResource(R.string.settings_update_installed, installed))
        sections.firstOrNull()?.let { first ->
            Text(stringResource(R.string.settings_update_changes), style = MaterialTheme.typography.labelLarge)
            ChangesText(first)
        }
    }
    sections.drop(1).forEach { section -> ReadableBlock { ChangesText(section) } }
}

@Composable
private fun ChangesText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Texte à lire où la télécommande s'arrête : la liste l'amène entier à l'écran, il s'éclaire au focus. */
@Composable
private fun ReadableBlock(content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

private fun megabytes(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / 1_000_000.0)

private fun gigabytes(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / 1_000_000_000.0)

@Composable
private fun previewVolumeLabel(volume: PreviewVolume): String = stringResource(
    when (volume) {
        PreviewVolume.OFF -> R.string.preview_volume_off
        PreviewVolume.LOW -> R.string.preview_volume_low
        PreviewVolume.MEDIUM -> R.string.preview_volume_medium
    },
)

/** « À chaque démarrage », « Toutes les 12 h », « Tous les 3 jours ». */
@Composable
private fun playlistRefreshLabel(refresh: PlaylistRefresh): String {
    val hours = (refresh.maxAgeMillis / HOUR_MILLIS).toInt()
    return when {
        refresh == PlaylistRefresh.EVERY_START -> stringResource(R.string.playlist_refresh_every_start)
        hours <= 24 -> stringResource(R.string.playlist_refresh_hours, hours)
        else -> stringResource(R.string.playlist_refresh_days, hours / 24)
    }
}

private const val HOUR_MILLIS = 60 * 60_000L

/**
 * Lignes de la trace d'un plantage montrées dans les réglages : toutes celles enregistrées (les noms
 * du code de NexTV sont raccourcis dans les versions publiées, l'endroit en cause est souvent bas).
 */
private const val CRASH_LINES = 40

@Composable
internal fun qualityTargetLabel(target: QualityTarget): String = stringResource(
    when (target) {
        QualityTarget.BEST -> R.string.quality_target_best
        QualityTarget.HD -> R.string.quality_target_hd
        QualityTarget.ECO -> R.string.quality_target_eco
    },
)

@Composable
internal fun qualityTargetHelp(target: QualityTarget): String = stringResource(
    when (target) {
        QualityTarget.BEST -> R.string.quality_target_best_help
        QualityTarget.HD -> R.string.quality_target_hd_help
        QualityTarget.ECO -> R.string.quality_target_eco_help
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

/** Un choix d'une liste ([ChoiceDialog]) : clé (`null` permis), libellé et ligne de précision facultative. */
internal data class Choice(val key: String?, val label: String, val detail: String? = null)

/** Choix de la région de France 3 : « Toutes les régions » puis chaque édition de la playlist. */
@Composable
private fun RegionDialog(
    regions: List<FranceRegions.Region>,
    /** Libellé du choix « aucune région » : toutes les éditions, ou France 3 seule (chaînes rassemblées). */
    noRegion: String,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) = ChoiceDialog(
    title = stringResource(R.string.settings_france3_region),
    choices = listOf(Choice(null, noRegion)) + regions.map { Choice(it.key, it.label) },
    selected = selected,
    onSelect = onSelect,
    onDismiss = onDismiss,
)

/** Fenêtre de choix dans une liste qui défile, la sélection posée sur le choix en cours. */
@Composable
internal fun ChoiceDialog(
    title: String,
    choices: List<Choice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).ignoreHeldOk(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val selectedIndex = choices.indexOfFirst { it.key == selected }.coerceAtLeast(0)
                LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(choices, key = { _, choice -> choice.key ?: "" }) { index, choice ->
                        FocusSurface(
                            onClick = { onSelect(choice.key) },
                            modifier = Modifier.fillMaxWidth().then(if (index == selectedIndex) Modifier.focusRequester(firstFocus) else Modifier),
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent,
                            selected = choice.key == selected,
                            focusScale = 1f,
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(choice.label, style = MaterialTheme.typography.titleSmall)
                                    choice.detail?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (choice.key == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Page filebin et état des envois, sur une ligne. */
@Composable
private fun filebinStatus(upload: AdUploadUi, withPage: Boolean = true): String {
    val status = upload.status
    return (if (withPage) stringResource(R.string.settings_ad_upload_page, "filebin.net/${upload.bin}") + "  ·  " else "") +
        when {
            status.sending != null -> stringResource(R.string.settings_ad_upload_sending, status.sent, status.pending)
            status.failed -> stringResource(R.string.settings_ad_upload_failed, status.sent, status.pending)
            else -> stringResource(R.string.settings_ad_upload_done, status.sent, status.pending)
        }
}

/**
 * Enregistrements pour l'analyse des pubs : la page filebin où ils partent tout seuls (adresse et QR code à scanner
 * avec le téléphone) et la liste de ceux encore sur l'appareil. Variante « Ma musique » ([musicTracks]) : l'adresse à
 * ouvrir sur un téléphone du même réseau pour envoyer des morceaux (petit serveur web de l'appareil, ouvert tant que la
 * fenêtre l'est).
 */
@Composable
internal fun AdRecordingsDialog(
    recordings: List<AdRecordings.Recording>,
    onShare: () -> AdRecordings.Share?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Variante « Ma musique » : envoyer des morceaux depuis le téléphone (même adresse). */
    musicTracks: Int? = null,
    /** Enregistrement en cours (minutes), qu'on peut arrêter d'ici ; `null` sans enregistrement. */
    recordingMinutes: Int? = null,
    onStopRecording: () -> Unit = {},
    /** Supprime un seul enregistrement (nom du `.zip`). */
    onDeleteOne: (String) -> Unit = {},
    /** Page filebin des enregistrements. */
    filebin: AdUploadUi = AdUploadUi(),
) {
    var address by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Petit serveur web seulement pour la musique : les enregistrements passent par filebin.
        val share = if (musicTracks != null) onShare() else null
        if (musicTracks == null) return@DisposableEffect onDispose {}
        address = share?.address
        noNetwork = share == null
        onDispose { share?.close() }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 620.dp).ignoreHeldOk(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (musicTracks != null) R.string.settings_ad_music else R.string.settings_ad_recordings),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (musicTracks == null) {
                    // Page filebin : QR code à scanner avec le téléphone, et l'adresse en clair.
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        filebin.url?.let { QrCode(it, 150.dp) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.settings_ad_recordings_help), style = MaterialTheme.typography.bodyMedium)
                            filebin.bin?.let {
                                Text("filebin.net/$it", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                Text(filebinStatus(filebin, withPage = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (noNetwork) {
                    Text(stringResource(R.string.settings_ad_recordings_no_network), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.settings_ad_music_help), style = MaterialTheme.typography.bodyMedium)
                    Text(address.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
                musicTracks?.let { Text(stringResource(R.string.settings_ad_music_count, it), style = MaterialTheme.typography.bodyMedium) }
                // Enregistrement qui continue hors du lecteur : arrêté ici, il rejoint la liste une seconde après.
                if (musicTracks == null && recordingMinutes != null) {
                    PillButton(stringResource(R.string.settings_ad_recordings_stop, recordingMinutes), onStopRecording, icon = Icons.Filled.Close)
                }
                // Chaque enregistrement, avec son bouton pour le supprimer seul (liste qui défile s'il y en a beaucoup).
                if (musicTracks == null && recordings.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recordings.forEach { file ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${file.name.removeSuffix(".zip")}  ·  ${megabytes(file.bytes)} Mo" + (file.location?.let { "  ·  $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                PillButton(stringResource(R.string.settings_ad_recordings_delete_one), { onDeleteOne(file.name) }, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(stringResource(R.string.settings_ad_recordings_close), onDismiss, modifier = Modifier.focusRequester(firstFocus), style = PillStyle.Primary)
                    PillButton(
                        stringResource(R.string.settings_ad_recordings_delete),
                        {
                            onDelete()
                            onDismiss()
                        },
                        icon = Icons.Filled.Delete,
                        style = PillStyle.Ghost,
                    )
                }
            }
        }
    }
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

/**
 * Barre de chargement de la mise à jour de la playlist : étape en cours (téléchargement, enregistrement des chaînes,
 * guide TV) avec sa part faite quand le total est connu, sinon les octets reçus et une barre qui défile.
 */
@Composable
private fun RefreshProgressBar(progress: LoadProgress?) {
    val context = LocalContext.current
    val step = when (progress?.step) {
        null -> R.string.startup_step_preparing
        LoadStep.PLAYLIST -> R.string.startup_step_playlist
        LoadStep.SAVING -> R.string.startup_step_saving
        LoadStep.EPG_DOWNLOAD -> R.string.startup_step_epg_download
        LoadStep.EPG_IMPORT -> R.string.startup_step_epg_import
    }
    val fraction = progress?.fraction
    val detail = when {
        fraction != null -> "${(fraction * 100).toInt()} %"
        progress != null && progress.done > 0 && (progress.step == LoadStep.PLAYLIST || progress.step == LoadStep.EPG_DOWNLOAD) ->
            stringResource(R.string.startup_bytes_received, Formatter.formatShortFileSize(context, progress.done))
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            listOfNotNull(stringResource(step), detail).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier, color = NexTvColors.Live, gapSize = 0.dp, drawStopIndicator = {})
        } else {
            LinearProgressIndicator(barModifier, color = NexTvColors.Live, gapSize = 0.dp)
        }
    }
}
