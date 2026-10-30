package tv.nextv.app.ui.startup

import android.text.format.Formatter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons

@Composable
fun StartupScreen(onHome: () -> Unit, onWelcome: () -> Unit, onChoosePlaylist: () -> Unit) {
    val resources = LocalContext.current.resources
    val viewModel = appViewModel { container, handle ->
        StartupViewModel(container, resources, forceRefresh = handle.get<Boolean>(StartupViewModel.ARG_RELOAD) ?: false)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        when (destination) {
            StartupDestination.HOME -> onHome()
            StartupDestination.WELCOME -> onWelcome()
            null -> Unit
        }
    }

    StartupContent(
        state = state,
        onRetry = viewModel::retry,
        onContinue = viewModel::continueWithCache,
        onChoosePlaylist = onChoosePlaylist,
    )
}

/** Écran de démarrage plein écran : logo animé, progression réelle, étape en cours, erreur éventuelle. */
@Composable
fun StartupContent(
    state: StartupUiState,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onChoosePlaylist: () -> Unit,
    animate: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), NexTvColors.Background, Color(0xFF07090D)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 560.dp).padding(32.dp),
        ) {
            AnimatedLogo(animate = animate && state.error == null)
            Spacer(Modifier.height(20.dp))
            Text("NexTV", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            state.playlistName?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(40.dp))
            val error = state.error
            if (error == null) {
                LoadingProgress(state)
            } else {
                StartupErrorPanel(error, onRetry, onContinue, onChoosePlaylist)
            }
        }
    }
}

@Composable
internal fun AnimatedLogo(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "logo")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "ring",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val ringAngle = if (animate) rotation else 30f
    val glowScale = if (animate) pulse else 1f
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        // Halo lumineux.
        Box(
            Modifier
                .size(150.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(NexTvColors.Primary.copy(alpha = 0.45f), NexTvColors.Accent.copy(alpha = 0.18f), Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2 * glowScale,
                        ),
                        radius = size.minDimension / 2 * glowScale,
                    )
                },
        )
        // Anneau tournant.
        Canvas(Modifier.size(150.dp).rotate(ringAngle)) {
            val stroke = 5.dp.toPx()
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, NexTvColors.Primary, NexTvColors.Accent, Color.Transparent)),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(listOf(NexTvColors.Primary, NexTvColors.Accent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NexTvIcons.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
private fun LoadingProgress(state: StartupUiState) {
    val context = LocalContext.current
    val animatedPercent by animateFloatAsState(state.percent / 100f, animationSpec = tween(400), label = "progress")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                stepLabel(state.step),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${state.percent} %",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animatedPercent)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(NexTvColors.Primary, NexTvColors.Accent))),
            )
        }
        Spacer(Modifier.height(12.dp))
        val detail = when {
            state.summary != null -> stringResource(
                R.string.startup_summary,
                state.summary.channels,
                state.summary.movies,
                state.summary.series,
            )
            state.bytesReceived != null -> stringResource(
                R.string.startup_bytes_received,
                Formatter.formatShortFileSize(context, state.bytesReceived),
            )
            else -> ""
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        StepList(state.step)
    }
}

/** Les étapes, avec celle en cours mise en valeur. */
@Composable
private fun StepList(current: StartupStep) {
    val steps = listOf(StartupStep.PLAYLIST, StartupStep.SAVING, StartupStep.EPG_DOWNLOAD, StartupStep.EPG_IMPORT)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, step ->
            val done = current.ordinal > step.ordinal
            val active = current == step
            Box(
                Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> NexTvColors.Live
                            active -> NexTvColors.Primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            if (index < steps.lastIndex) {
                Box(Modifier.width(28.dp).height(2.dp).background(if (done) NexTvColors.Live else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun stepLabel(step: StartupStep): String = stringResource(
    when (step) {
        StartupStep.PREPARING -> R.string.startup_step_preparing
        StartupStep.PLAYLIST -> R.string.startup_step_playlist
        StartupStep.SAVING -> R.string.startup_step_saving
        StartupStep.EPG_DOWNLOAD -> R.string.startup_step_epg_download
        StartupStep.EPG_IMPORT -> R.string.startup_step_epg_import
        StartupStep.READY -> R.string.startup_step_ready
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupErrorPanel(
    error: StartupError,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onChoosePlaylist: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(error) { runCatching { focusRequester.requestFocus() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = NexTvColors.Warning)
            Text(stringResource(R.string.startup_error_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            error.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton(
                stringResource(R.string.action_retry),
                onRetry,
                icon = Icons.Filled.Refresh,
                style = PillStyle.Primary,
                modifier = Modifier.focusRequester(focusRequester),
            )
            if (error.canContinue) PillButton(stringResource(R.string.startup_continue_cached), onContinue)
            PillButton(stringResource(R.string.startup_choose_playlist), onChoosePlaylist, style = PillStyle.Ghost)
        }
    }
}
