package tv.nextv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.ui.components.FadingBottom
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.editor.NexTvTextField
import tv.nextv.app.ui.editor.SectionCard
import tv.nextv.app.ui.theme.NexTvIcons

/** Pages des réglages, dans l'ordre de la liste. */
enum class SettingsPage {
    /** Dernier plantage, seulement s'il y en a un. */
    INCIDENT,
    PLAYLIST,
    PLAYBACK,
    DISPLAY,
    GUIDE,
    SERVICES,
    NETWORK,
    BACKUP,
    UPDATE,
}

private fun SettingsPage.title(): Int = when (this) {
    SettingsPage.INCIDENT -> R.string.settings_crash
    SettingsPage.PLAYLIST -> R.string.settings_playlist
    SettingsPage.PLAYBACK -> R.string.settings_playback
    SettingsPage.DISPLAY -> R.string.settings_page_display
    SettingsPage.GUIDE -> R.string.settings_guide
    SettingsPage.SERVICES -> R.string.settings_page_services
    SettingsPage.NETWORK -> R.string.settings_network
    SettingsPage.BACKUP -> R.string.settings_backup
    SettingsPage.UPDATE -> R.string.settings_update
}

private fun SettingsPage.description(): Int? = when (this) {
    SettingsPage.INCIDENT -> null
    SettingsPage.PLAYLIST -> R.string.settings_page_playlist_help
    SettingsPage.PLAYBACK -> R.string.settings_page_playback_help
    SettingsPage.DISPLAY -> R.string.settings_page_display_help
    SettingsPage.GUIDE -> R.string.settings_page_guide_help
    SettingsPage.SERVICES -> R.string.settings_page_services_help
    SettingsPage.NETWORK -> R.string.settings_page_network_help
    SettingsPage.BACKUP -> R.string.settings_page_backup_help
    SettingsPage.UPDATE -> R.string.settings_page_update_help
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.INCIDENT -> Icons.Filled.Warning
    SettingsPage.PLAYLIST -> NexTvIcons.Tv
    SettingsPage.PLAYBACK -> Icons.Filled.PlayArrow
    SettingsPage.DISPLAY -> NexTvIcons.Grid
    SettingsPage.GUIDE -> NexTvIcons.Guide
    SettingsPage.SERVICES -> Icons.Filled.Star
    SettingsPage.NETWORK -> NexTvIcons.Cloud
    SettingsPage.BACKUP -> Icons.Filled.Lock
    SettingsPage.UPDATE -> Icons.Filled.Refresh
}

/** Largeur de la liste des pages (écran large). */
private val PAGE_LIST_WIDTH = 320.dp

/**
 * Écran large (télé, tablette) : la liste des pages à gauche, la page choisie à droite. Passer sur
 * une page l'affiche ; OK ou → entre dedans, ← ou Retour revient à la liste.
 */
@Composable
internal fun SettingsPanes(
    pages: List<SettingsPage>,
    window: WindowInfo,
    onBack: () -> Unit,
    onQuickSetup: () -> Unit,
    summary: @Composable (SettingsPage) -> String?,
    body: @Composable (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.PLAYLIST,
) {
    var page by rememberSaveable { mutableStateOf(initialPage.takeIf { it in pages } ?: pages.first()) }
    val requesters = remember(pages) { pages.associateWith { FocusRequester() } }
    val content = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    fun focusPage() = runCatching { requesters.getValue(page).requestFocus() }
    fun enterPage() = runCatching { content.requestFocus() }
    // Retour depuis la page : à la liste, sur la page affichée.
    BackHandler(enabled = contentFocused) { focusPage() }
    LaunchedEffect(Unit) { if (window.isTv) focusPage() }

    Row(modifier) {
        Column(
            Modifier
                .width(PAGE_LIST_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // En revenant dans la liste (←) : sur la page affichée, pas sur la plus proche ni sur « Retour ».
                .focusProperties { onEnter = { focusPage() } }
                .focusGroup(),
        ) {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = 16.dp, top = window.topPadding, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
            val listScroll = rememberScrollState()
            FadingBottom({ listScroll.canScrollForward }, { listScroll.canScrollBackward }, ends = listScroll) { fade ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(fade)
                        .verticalScroll(listScroll)
                        .padding(start = window.horizontalPadding - 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QuickSetupItem(onQuickSetup, Modifier.focusProperties { right = content })
                    Spacer(Modifier.height(8.dp))
                    pages.forEach { target ->
                        PageItem(
                            page = target,
                            summary = summary(target),
                            selected = target == page,
                            onFocused = { page = target },
                            onClick = { enterPage() },
                            modifier = Modifier
                                .focusRequester(requesters.getValue(target))
                                .focusProperties { right = content },
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { contentFocused = it.hasFocus }
                .focusRequester(content)
                .focusGroup(),
        ) {
            // Une page nouvelle s'ouvre en haut.
            key(page) {
                val scroll = rememberScrollState()
                FadingBottom({ scroll.canScrollForward }, { scroll.canScrollBackward }, ends = scroll) { fade ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .imePadding()
                            .then(fade)
                            .verticalScroll(scroll)
                            .padding(start = 32.dp, end = window.horizontalPadding, top = window.topPadding + 4.dp, bottom = 8.dp)
                            .widthIn(max = 820.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PageHeader(page)
                        body(page)
                    }
                }
            }
        }
    }
}

/** Titre de la page et ce qu'on y règle. */
@Composable
internal fun PageHeader(page: SettingsPage, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PageIcon(page.icon(), highlighted = true, size = 48)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(page.title()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            page.description()?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PageIcon(icon: ImageVector, highlighted: Boolean, size: Int = 40) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(if (highlighted) colors.primaryContainer else colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
    }
}

/** Page de la liste : icône, nom et résumé (playlist active, qualité, interface…). */
@Composable
private fun PageItem(page: SettingsPage, summary: String?, selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.surfaceContainerHigh else Color.Transparent,
        focusScale = 1f,
        onFocusChange = { if (it) onFocused() },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageIcon(page.icon(), highlighted = selected)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(page.title()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (page == SettingsPage.INCIDENT) colors.error else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Entrée « Configuration rapide », en tête de la liste. */
@Composable
private fun QuickSetupItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.primaryContainer.copy(alpha = 0.55f),
        focusedColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.quick_setup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(stringResource(R.string.quick_setup_entry_help), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Écran étroit : la configuration rapide en carte, au-dessus des pages. */
@Composable
internal fun QuickSetupCard(onClick: () -> Unit) {
    QuickSetupItem(onClick)
}

/** Clé d'un service (TMDB, football-data.org) : jamais affichée une fois enregistrée. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyCard(
    title: String,
    subtitle: String,
    status: String,
    label: String,
    saved: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(title, subtitle = subtitle) {
        var key by rememberSaveable(title) { mutableStateOf("") }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NexTvTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = label,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                stringResource(R.string.settings_tmdb_save),
                {
                    onSave(key)
                    key = ""
                },
                icon = Icons.Filled.Check,
                enabled = key.isNotBlank(),
            )
            if (saved) {
                PillButton(stringResource(R.string.settings_tmdb_remove), onClear, icon = Icons.Filled.Delete, style = PillStyle.Ghost)
            }
        }
    }
}
