package tv.nextv.app.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.home.qualityLabel
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import androidx.annotation.OptIn as AndroidXOptIn

@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = appViewModel { container, handle -> PlayerViewModel(container, handle, context) }
    val player by viewModel.player.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val menuGroups by viewModel.menuGroups.collectAsStateWithLifecycle()
    val menuFavorites by viewModel.menuFavorites.collectAsStateWithLifecycle()
    val menuChannels = viewModel.menuChannels.collectAsLazyPagingItems()
    val window = rememberWindowInfo()
    var bannerTrigger by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var infoVisible by rememberSaveable { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    // Pendant une pause du direct, chaque seconde (retard sur le direct affiché) ; sinon toutes les 5 s.
    val now by produceState(System.currentTimeMillis(), state.paused) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (state.paused) 1_000 else 5_000)
        }
    }

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    // Bandeau : 5 s après un changement de chaîne ou un appui sur OK.
    LaunchedEffect(state.channelVersion, bannerTrigger) {
        bannerVisible = true
        delay(BANNER_DURATION_MILLIS)
        bannerVisible = false
    }
    // Le lecteur garde le focus quand le menu est fermé : les touches de la télécommande lui parviennent.
    LaunchedEffect(menu.open, state.isLive) {
        if (!menu.open && state.isLive) runCatching { rootFocus.requestFocus() }
    }

    // Premier « Retour » : menu des chaînes (sans interrompre la lecture) ; second : fermeture du menu.
    BackHandler(enabled = menu.open) { viewModel.closeMenu() }
    BackHandler(enabled = !menu.open && state.isLive) { viewModel.openMenu() }
    // Déclaré en dernier : prioritaire, « Retour » ferme d'abord le panneau d'infos.
    BackHandler(enabled = infoVisible && !menu.open) { infoVisible = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || menu.open || state.resumePrompt != null) return@onPreviewKeyEvent false
                val delayed = state.paused || state.liveDelayMs > 0
                when (event.key) {
                    Key.ChannelUp -> true.also { viewModel.zap(+1) }
                    Key.ChannelDown -> true.also { viewModel.zap(-1) }
                    Key.DirectionUp -> state.isLive.also { if (it) viewModel.zap(+1) }
                    Key.DirectionDown -> state.isLive.also { if (it) viewModel.zap(-1) }
                    // En erreur, OK relance la lecture ; bandeau affiché : pause / lecture ; sinon il affiche le bandeau.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                        !state.isLive -> false
                        state.error != null -> true.also { viewModel.retry() }
                        bannerVisible || state.paused -> true.also {
                            viewModel.togglePause()
                            bannerTrigger++
                        }
                        else -> true.also { bannerTrigger++ }
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> state.isLive.also {
                        if (it) {
                            viewModel.togglePause()
                            bannerTrigger++
                        }
                    }
                    // Différé : retour au direct.
                    Key.DirectionRight, Key.MediaFastForward -> (state.isLive && delayed).also { if (it) viewModel.backToLive() }
                    Key.Info, Key.Menu -> true.also { infoVisible = !infoVisible }
                    Key.Guide -> true.also { bannerTrigger++ }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(state.isLive) { detectTapGestures(onTap = { bannerTrigger++ }) },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    keepScreenOn = true
                }
            },
            update = { view ->
                view.player = player
                // Direct : pas de barre de lecture, OK affiche le bandeau. Film / épisode : commandes Media3.
                view.useController = !state.isLive
                // Film / épisode : focus sur le lecteur (commandes Media3), sans le reprendre à ses commandes.
                if (!state.isLive && state.resumePrompt == null && !view.hasFocus()) view.requestFocus()
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        PlayerOverlay(
            state = state,
            now = now,
            visible = (bannerVisible || state.error != null || state.paused) && !menu.open && state.resumePrompt == null,
            showTouchControls = !window.isTv,
            onBack = onBack,
            onZap = viewModel::zap,
            onRetry = viewModel::retry,
            onOpenMenu = viewModel::openMenu,
            onInfo = { infoVisible = !infoVisible },
        )

        PlaybackInfoPanel(
            state = state,
            visible = infoVisible && !menu.open,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        state.resumePrompt?.let { prompt ->
            ResumePromptCard(prompt, onAnswer = viewModel::answerResume, modifier = Modifier.align(Alignment.Center))
        }

        ChannelMenu(
            menu = menu,
            groups = menuGroups,
            channels = menuChannels,
            favorites = menuFavorites,
            now = now,
            onCategory = viewModel::selectMenuCategory,
            onPlay = viewModel::playFromMenu,
            onHome = onBack,
            onInfo = {
                viewModel.closeMenu()
                infoVisible = true
            },
        )
    }
}

/** Bandeau d'information, indicateur de chargement et carte d'erreur, sans dépendance au lecteur. */
@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    now: Long,
    visible: Boolean,
    showTouchControls: Boolean,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenMenu: () -> Unit = {},
    onInfo: () -> Unit = {},
) {
    val window = rememberWindowInfo()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.92f))))
                    .padding(start = window.horizontalPadding, end = window.horizontalPadding, top = 72.dp, bottom = if (window.isTv) 36.dp else 24.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    ChannelLogo(
                        state.logoUrl,
                        state.channelName.ifEmpty { "?" },
                        Modifier.size(width = if (window.isWide) 128.dp else 88.dp, height = if (window.isWide) 84.dp else 58.dp),
                        monogramSize = 24.dp,
                    )
                    ChannelInfo(state, now, Modifier.weight(1f))
                    // Heure actuelle.
                    Text(
                        timeOf(now),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Top),
                    )
                    if (showTouchControls && state.isLive) {
                        Column {
                            IconAction(Icons.Filled.KeyboardArrowUp, stringResource(R.string.player_next_channel), { onZap(+1) }, tint = Color.White)
                            IconAction(Icons.AutoMirrored.Filled.List, stringResource(R.string.player_channel_list), onOpenMenu, tint = Color.White)
                            IconAction(Icons.Filled.KeyboardArrowDown, stringResource(R.string.player_previous_channel), { onZap(-1) }, tint = Color.White)
                        }
                    }
                }
            }
        }

        if (showTouchControls && visible) {
            Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack, tint = Color.White)
            }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                IconAction(Icons.Filled.Info, stringResource(R.string.player_info_title), onInfo, tint = Color.White)
            }
        }

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = NexTvColors.Primary, strokeWidth = 4.dp)
        }

        state.error?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 560.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            ) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = NexTvColors.Warning)
                        Text(stringResource(R.string.player_error_title), style = MaterialTheme.typography.titleLarge)
                    }
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.action_retry), onRetry, icon = Icons.Filled.Refresh, style = PillStyle.Primary)
                        if (state.isLive) PillButton(stringResource(R.string.player_next_channel), { onZap(+1) }, icon = Icons.Filled.KeyboardArrowUp)
                        PillButton(stringResource(R.string.action_back), onBack, style = PillStyle.Ghost)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelInfo(state: PlayerUiState, now: Long, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Nom puis pastilles ; sur un écran étroit, les pastilles passent à la ligne.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                state.channelNumber?.let { Badge(it, color = Color.White) }
                Text(
                    state.channelName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.isLive) {
                val delay = liveDelay(state, now)
                if (state.paused) StatusPill(stringResource(R.string.player_paused), NexTvColors.Warning, Modifier.align(Alignment.CenterVertically))
                if (delay >= 1_000) {
                    StatusPill(stringResource(R.string.player_delay, formatClock(delay)), NexTvColors.Warning, Modifier.align(Alignment.CenterVertically))
                } else if (!state.paused) {
                    StatusPill(stringResource(R.string.player_live), NexTvColors.Live, Modifier.align(Alignment.CenterVertically))
                }
            }
            state.quality?.let { Badge(it, Modifier.align(Alignment.CenterVertically), color = NexTvColors.Primary) }
            state.bandwidthMbps?.let { SpeedLabel(it, Modifier.align(Alignment.CenterVertically)) }
        }
        if (state.sourceName.isNotEmpty()) {
            Text(state.sourceName, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
        state.now?.let { programme ->
            Text(programme.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 640.dp)) {
                Text(timeOf(programme.startMillis), style = MaterialTheme.typography.labelLarge, color = Color.White)
                LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.weight(1f))
                Text(timeOf(programme.stopMillis), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(
                    stringResource(R.string.player_remaining, formatDuration((programme.stopMillis - now).coerceAtLeast(0))),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        state.next?.let { programme ->
            Text(
                stringResource(R.string.player_next, timeOf(programme.startMillis), programme.title),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.qualityNotice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = NexTvColors.Warning)
        }
        if (state.isLive && (state.paused || state.liveDelayMs > 0)) {
            Text(stringResource(R.string.player_timeshift_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }
        state.notices.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = NexTvColors.Warning)
        }
    }
}

/** Débit mesuré, en Mbit/s. */
@Composable
private fun SpeedLabel(mbps: Double, modifier: Modifier = Modifier) {
    val format = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 } }
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(NexTvIcons.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.player_speed, format.format(mbps)), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * Menu latéral des chaînes : catégories en haut (gauche/droite), chaînes en dessous (haut/bas),
 * OK lance la chaîne. La lecture continue derrière.
 */
@Composable
fun ChannelMenu(
    menu: ChannelMenuState,
    groups: List<GroupRow>,
    channels: LazyPagingItems<ChannelListRow>,
    favorites: List<ChannelListRow>,
    now: Long,
    onCategory: (Category) -> Unit,
    onPlay: (Long) -> Unit,
    onHome: () -> Unit,
    onInfo: () -> Unit = {},
) {
    val window = rememberWindowInfo()
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val favoritesMode = menu.category == Category.Favorites

    // À l'ouverture : chaîne en cours visible et sélectionnée.
    LaunchedEffect(menu.open) {
        if (!menu.open) return@LaunchedEffect
        if (!favoritesMode) snapshotFlow { channels.itemCount }.first { it > 0 }
        runCatching { listState.scrollToItem(menu.initialIndex) }
        delay(50)
        runCatching { currentFocus.requestFocus() }
    }

    AnimatedVisibility(
        visible = menu.open,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .then(if (window.isWide) Modifier.width(480.dp) else Modifier.fillMaxWidth(0.88f))
                    .fillMaxHeight()
                    .background(Color(0xF20A0E14))
                    .statusBarsPadding()
                    .padding(top = 20.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.player_channel_list), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconAction(Icons.Filled.Info, stringResource(R.string.player_info_title), onInfo)
                    PillButton(stringResource(R.string.player_home), onHome, icon = Icons.Filled.Home, style = PillStyle.Ghost)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { MenuChip(stringResource(R.string.home_favorites), favoritesMode, star = true) { onCategory(Category.Favorites) } }
                    item { MenuChip(stringResource(R.string.channels_all_short), menu.category == Category.All) { onCategory(Category.All) } }
                    items(groups, key = { it.title ?: "\u0000" }) { group ->
                        val title = group.title.orEmpty()
                        MenuChip(group.title ?: stringResource(R.string.channels_no_group), menu.category == Category.Group(title)) {
                            onCategory(Category.Group(title))
                        }
                    }
                }
                val rowFor: @Composable (ChannelListRow) -> Unit = { channel ->
                    val current = channel.id == menu.currentChannelId
                    MenuChannelRow(
                        channel = channel,
                        current = current,
                        now = now,
                        onClick = { onPlay(channel.id) },
                        modifier = if (current) Modifier.focusRequester(currentFocus) else Modifier,
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (favoritesMode) {
                        if (favorites.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.favorites_empty_channels),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(favorites, key = { it.id }) { rowFor(it) }
                    } else {
                        items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                            channels[index]?.let { rowFor(it) }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
            )
        }
    }
}

@Composable
private fun MenuChip(name: String, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun MenuChannelRow(channel: ChannelListRow, current: Boolean, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (current) NexTvColors.PrimaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                channel.channelNumber ?: "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            ChannelLogo(channel.logoUrl, channel.title, Modifier.size(width = 56.dp, height = 38.dp), monogramSize = 13.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        channel.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
                    if (current) Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = NexTvColors.Live, modifier = Modifier.size(18.dp))
                }
                val start = channel.nowStart
                val stop = channel.nowStop
                if (channel.nowTitle != null && start != null && stop != null && stop > now) {
                    Text(channel.nowTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LiveProgress(start, stop, now, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private const val BANNER_DURATION_MILLIS = 5_000L

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Panneau « Infos de lecture » : ce que le lecteur décode réellement (formats, décodeurs, images perdues). */
@Composable
fun PlaybackInfoPanel(state: PlayerUiState, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val info = state.info
        val unknown = stringResource(R.string.player_info_unknown)
        val video = listOfNotNull(
            codecLabel(info.videoMimeType),
            if (info.width != null && info.height != null) "${info.width}×${info.height}" else null,
            info.frameRate?.let { stringResource(R.string.player_info_fps, formatDecimal(it.toDouble())) },
        ).joinToString(" · ").ifEmpty { unknown }
        val decoder = info.videoDecoder?.let { name ->
            when (info.hardwareDecoder) {
                true -> stringResource(R.string.settings_decoder_hardware, name)
                false -> stringResource(R.string.settings_decoder_software, name)
                null -> name
            }
        } ?: unknown
        val audio = listOfNotNull(codecLabel(info.audioMimeType), info.audioChannels?.let { channelsLabel(it) })
            .joinToString(" · ").ifEmpty { unknown }
        Surface(
            color = Color.Black.copy(alpha = 0.78f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.statusBarsPadding().padding(24.dp).widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.player_info_title), style = MaterialTheme.typography.titleMedium)
                InfoLine(stringResource(R.string.player_info_video), video)
                InfoLine(stringResource(R.string.player_info_decoder), decoder)
                InfoLine(stringResource(R.string.player_info_audio), audio)
                info.audioDecoder?.let { InfoLine(stringResource(R.string.player_info_audio_decoder), it) }
                InfoLine(
                    stringResource(R.string.player_info_bitrate),
                    state.bandwidthMbps?.let { stringResource(R.string.player_speed, formatDecimal(it)) } ?: unknown,
                )
                InfoLine(stringResource(R.string.player_info_dropped), info.droppedFrames.toString(), warning = info.droppedFrames > 0)
                InfoLine(stringResource(R.string.player_info_version), state.quality ?: unknown)
                Text(
                    stringResource(R.string.player_info_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, warning: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (warning) NexTvColors.Warning else Color.White)
    }
}

private fun formatDecimal(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

/** Nom lisible d'un format (types MIME de Media3). */
private fun codecLabel(mimeType: String?): String? = when (mimeType) {
    null -> null
    "video/hevc" -> "HEVC (H.265)"
    "video/avc" -> "H.264 (AVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/mpeg2" -> "MPEG-2"
    "audio/ac3" -> "Dolby Digital (AC-3)"
    "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
    "audio/ac4" -> "AC-4"
    "audio/mp4a-latm" -> "AAC"
    "audio/mpeg" -> "MP3"
    "audio/mpeg-L2" -> "MPEG audio"
    "audio/opus" -> "Opus"
    "audio/vnd.dts" -> "DTS"
    else -> mimeType.substringAfter('/')
}

@Composable
private fun channelsLabel(count: Int): String = when (count) {
    1 -> stringResource(R.string.player_info_mono)
    2 -> stringResource(R.string.player_info_stereo)
    6 -> "5.1"
    8 -> "7.1"
    else -> stringResource(R.string.player_info_channels, count)
}

/** Retard sur le direct, pause en cours comprise. */
private fun liveDelay(state: PlayerUiState, now: Long): Long =
    state.liveDelayMs + (state.pausedAtMillis?.let { (now - it).coerceAtLeast(0) } ?: 0)

/** « 4:05 », « 1:02:03 ». */
fun formatClock(millis: Long): String {
    val total = millis / 1_000
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Film ou épisode déjà commencé : reprendre (épisode en cours pour une série) ou recommencer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResumePromptCard(prompt: ResumePrompt, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(prompt) { runCatching { resumeFocus.requestFocus() } }
    val resumeText = when {
        prompt.resumeLabel == null -> stringResource(R.string.player_resume_at, formatClock(prompt.positionMs))
        prompt.positionMs > 0 -> stringResource(R.string.player_resume_episode_at, prompt.resumeLabel, formatClock(prompt.positionMs))
        else -> stringResource(R.string.player_resume_next_episode, prompt.resumeLabel)
    }
    val restartText = prompt.startLabel?.let { stringResource(R.string.player_restart_episode, it) }
        ?: stringResource(R.string.player_restart)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(24.dp).widthIn(max = 620.dp),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.player_resume_title), style = MaterialTheme.typography.titleLarge)
            Text(prompt.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    resumeText,
                    { onAnswer(true) },
                    icon = Icons.Filled.PlayArrow,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(resumeFocus),
                )
                PillButton(restartText, { onAnswer(false) }, icon = Icons.Filled.Refresh)
            }
        }
    }
}
