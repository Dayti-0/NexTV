package tv.nextv.app.ui.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import java.text.DateFormat
import java.util.Date
import androidx.annotation.OptIn as AndroidXOptIn

@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = appViewModel { container, handle -> PlayerViewModel(container, handle, context) }
    val player by viewModel.player.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var controllerVisible by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    // Le bandeau s'affiche à chaque changement de chaîne ou action, puis se masque.
    LaunchedEffect(state.channelVersion, interactions) {
        bannerVisible = true
        delay(BANNER_DURATION_MILLIS)
        bannerVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ChannelUp -> true.also { viewModel.zap(+1) }
                    Key.ChannelDown -> true.also { viewModel.zap(-1) }
                    // Haut/bas changent de chaîne tant que les commandes de lecture sont masquées.
                    Key.DirectionUp -> (!controllerVisible).also { if (it) viewModel.zap(+1) }
                    Key.DirectionDown -> (!controllerVisible).also { if (it) viewModel.zap(-1) }
                    Key.Info, Key.Guide -> true.also { interactions++ }
                    else -> false.also { interactions++ }
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = true
                    keepScreenOn = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controllerVisible = visibility == View.VISIBLE },
                    )
                    requestFocus()
                    playerView = this
                }
            },
            update = { view -> view.player = player },
            onRelease = { view ->
                view.player = null
                playerView = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        PlayerOverlay(
            state = state,
            now = now,
            visible = bannerVisible || controllerVisible || state.error != null,
            showTouchControls = !rememberWindowInfo().isTv,
            onBack = onBack,
            onZap = viewModel::zap,
            onRetry = viewModel::retry,
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
) {
    val window = rememberWindowInfo()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = window.horizontalPadding, end = window.horizontalPadding, top = 20.dp, bottom = 56.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (showTouchControls) {
                        IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack, tint = Color.White)
                    }
                    ChannelLogo(
                        state.logoUrl,
                        state.channelName.ifEmpty { "?" },
                        Modifier.size(width = 96.dp, height = 64.dp),
                        monogramSize = 22.dp,
                    )
                    ChannelInfo(state, now, Modifier.weight(1f))
                    if (showTouchControls) {
                        Column {
                            IconAction(Icons.Filled.KeyboardArrowUp, stringResource(R.string.player_next_channel), { onZap(+1) }, tint = Color.White)
                            IconAction(Icons.Filled.KeyboardArrowDown, stringResource(R.string.player_previous_channel), { onZap(-1) }, tint = Color.White)
                        }
                    }
                }
            }
        }

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = NexTvColors.Primary, strokeWidth = 4.dp)
        }

        state.error?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 520.dp),
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
                        PillButton(stringResource(R.string.player_next_channel), { onZap(+1) }, icon = Icons.Filled.KeyboardArrowUp)
                        PillButton(stringResource(R.string.action_back), onBack, style = PillStyle.Ghost)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelInfo(state: PlayerUiState, now: Long, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.channelNumber?.let { Badge(it, color = Color.White) }
            Text(
                state.channelName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.now != null) StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
        }
        if (state.sourceName.isNotEmpty()) {
            Text(state.sourceName, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
        state.now?.let { programme ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(timeRange(programme.startMillis, programme.stopMillis), style = MaterialTheme.typography.labelLarge, color = NexTvColors.Live)
                Text(programme.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.widthIn(max = 420.dp))
        }
        state.next?.let { programme ->
            Text(
                stringResource(R.string.player_next, timeOf(programme.startMillis), programme.title),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.notices.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = NexTvColors.Warning)
        }
    }
}

private const val BANNER_DURATION_MILLIS = 5_000L

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun timeRange(start: Long, stop: Long) = "${timeOf(start)} – ${timeOf(stop)}"
