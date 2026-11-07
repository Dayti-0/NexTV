package tv.nextv.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvIcons
import java.text.DateFormat
import java.util.Date

/** Position, durée et part déjà chargée de la vidéo, relues deux fois par seconde. */
private data class FilmProgress(val position: Long = 0, val duration: Long = 0, val buffered: Long = 0, val playing: Boolean = false)

/**
 * Commandes des films et épisodes, sur l'image : titre et épisode en haut, heure de fin à droite ;
 * en bas, la barre de progression (← → : reculer / avancer, OK : pause) et les boutons ronds
 * (−10 s, lecture, +30 s, épisode suivant, audio et sous-titres). Le reste du temps, l'image seule.
 */
@Composable
fun FilmControls(
    player: Player?,
    state: PlayerUiState,
    visible: Boolean,
    onInteraction: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNextEpisode: (() -> Unit)?,
    onOptions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sans lecteur (captures d'écran) : position et durée affichées. */
    sample: Pair<Long, Long>? = null,
) {
    val progress by produceState(FilmProgress(), player, visible) {
        while (true) {
            val current = player
            value = if (current == null) {
                sample?.let { (position, duration) -> FilmProgress(position, duration, (position + duration) / 2, playing = true) } ?: FilmProgress()
            } else {
                FilmProgress(current.currentPosition.coerceAtLeast(0), current.duration.coerceAtLeast(0), current.bufferedPosition.coerceAtLeast(0), current.playWhenReady)
            }
            delay(if (visible) 500 else 2_000)
        }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) runCatching { playFocus.requestFocus() } }
    val vod = state.vod
    val window = rememberWindowInfo()
    val side = if (window.isWide) 48.dp else window.horizontalPadding
    Box(modifier.fillMaxSize()) {
        // Haut : titre, épisode, année ; heure de fin à droite.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
                    .padding(start = side, end = side, top = if (window.isWide) 32.dp else 20.dp, bottom = 56.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            vod?.title ?: state.channelName,
                            color = Color.White,
                            fontSize = if (window.isWide) 34.sp else 24.sp,
                            lineHeight = if (window.isWide) 38.sp else 28.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            maxLines = if (window.isWide) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val details = listOfNotNull(vod?.subtitle, vod?.year?.toString()).joinToString("  ·  ")
                        if (details.isNotEmpty()) {
                            Text(details, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clock(System.currentTimeMillis()), color = Color.White, style = MaterialTheme.typography.titleLarge)
                        if (progress.duration > 0) {
                            val end = System.currentTimeMillis() + (progress.duration - progress.position)
                            Text(stringResource(R.string.film_ends_at, clock(end)), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        // Bas : progression et boutons.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = side, end = side, top = 72.dp, bottom = if (window.isWide) 32.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SeekBar(progress, onSeekTo = onSeekTo, onInteraction = onInteraction, onPlayPause = onPlayPause)
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatClock(progress.position), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("−" + formatClock((progress.duration - progress.position).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (window.isWide) 18.dp else 10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onClick = onBack, small = true)
                    SkipButton("−10", stringResource(R.string.film_back_10)) { onSeekBy(-10_000) }
                    RoundButton(
                        if (progress.playing) NexTvIcons.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (progress.playing) R.string.player_pause else R.string.player_play),
                        onClick = onPlayPause,
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    SkipButton("+30", stringResource(R.string.film_forward_30)) { onSeekBy(30_000) }
                    if (onNextEpisode != null) RoundButton(NexTvIcons.SkipNext, stringResource(R.string.film_next_episode), onClick = onNextEpisode, small = true)
                    RoundButton(Icons.Filled.Settings, stringResource(R.string.player_options), onClick = onOptions, small = true)
                }
                vod?.next?.takeIf { onNextEpisode != null }?.let { next ->
                    Text(
                        stringResource(R.string.film_next_label, listOfNotNull(next.label, next.title).joinToString("  ·  ")),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

/**
 * Barre de progression sélectionnable : ← → reculent / avancent (pas plus grands quand la touche
 * reste appuyée), OK met en pause. La position visée s'affiche au-dessus du curseur.
 */
@Composable
private fun SeekBar(progress: FilmProgress, onSeekTo: (Long) -> Unit, onInteraction: () -> Unit, onPlayPause: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Position visée pendant les appuis (appliquée quand ils s'arrêtent).
    var target by remember { mutableLongStateOf(-1L) }
    var presses by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, presses) {
        if (target < 0) return@LaunchedEffect
        delay(SEEK_COMMIT_MILLIS)
        onSeekTo(target)
        delay(600)
        target = -1
    }
    val shown = if (target >= 0) target else progress.position
    val fraction = if (progress.duration > 0) (shown.toFloat() / progress.duration).coerceIn(0f, 1f) else 0f
    val buffered = if (progress.duration > 0) (progress.buffered.toFloat() / progress.duration).coerceIn(0f, 1f) else 0f
    val height by animateDpAsState(if (focused) 8.dp else 5.dp, label = "bar")
    val thumb by animateFloatAsState(if (focused) 1f else 0f, label = "thumb")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val direction = if (event.key == Key.DirectionRight) 1 else -1
                        presses++
                        val step = when {
                            presses > 12 -> 120_000L
                            presses > 5 -> 30_000L
                            else -> 10_000L
                        }
                        val from = if (target >= 0) target else progress.position
                        target = (from + direction * step).coerceIn(0, progress.duration.coerceAtLeast(0))
                        onInteraction()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> true.also {
                        onPlayPause()
                        onInteraction()
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(Color.White.copy(alpha = 0.28f)))
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        // Curseur, plus visible sous le focus, avec l'heure visée.
        Box(
            Modifier
                .offset(x = width * fraction - 9.dp)
                .size(18.dp)
                .shadow(if (focused) 6.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.55f + 0.45f * thumb)),
        )
        if (target >= 0) {
            Text(
                formatClock(target),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .offset(x = (width * fraction - 30.dp).coerceIn(0.dp, width - 60.dp), y = (-26).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    LaunchedEffect(focused) { if (!focused) presses = 0 }
}

/** Bouton rond des commandes ; [primary] : lecture / pause, plus grand et plein. */
@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    small: Boolean = false,
) {
    val size = when {
        primary -> 64.dp
        small -> 44.dp
        else -> 52.dp
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (primary) Color.White else Color.White.copy(alpha = 0.14f),
        focusedColor = if (primary) Color.White else Color.White.copy(alpha = 0.32f),
        contentColor = if (primary) Color.Black else Color.White,
        focusScale = 1.12f,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(if (primary) 34.dp else 22.dp))
        }
    }
}

/** Saut de quelques secondes : nombre au centre d'un cercle. */
@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        focusedColor = Color.White.copy(alpha = 0.32f),
        contentColor = Color.White,
        focusScale = 1.12f,
    ) {
        Box(Modifier.border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
        }
    }
}

/**
 * Fin d'un épisode : le suivant, en grand, se lance seul après un décompte (« Lire maintenant »,
 * « Annuler »).
 */
@Composable
fun UpNextCard(next: NextEpisode, onPlay: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember(next) { mutableIntStateOf(UP_NEXT_SECONDS) }
    LaunchedEffect(next) {
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        onPlay()
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Row(
        modifier
            .widthIn(max = 620.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f))) {
            next.imageUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.film_up_next, seconds).uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(next.label, next.title).joinToString("  ·  "), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusSurface(
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocus),
                    shape = CircleShape,
                    color = Color.White,
                    focusedColor = Color.White,
                    contentColor = Color.Black,
                    focusScale = 1.06f,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.film_play_now), style = MaterialTheme.typography.labelLarge)
                    }
                }
                FocusSurface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    focusedColor = Color.White.copy(alpha = 0.3f),
                    contentColor = Color.White,
                    focusScale = 1.06f,
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

private fun clock(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private const val SEEK_COMMIT_MILLIS = 700L
private const val UP_NEXT_SECONDS = 10
