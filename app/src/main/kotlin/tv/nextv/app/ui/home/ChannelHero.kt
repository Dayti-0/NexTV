package tv.nextv.app.ui.home

import tv.nextv.app.ui.common.noProgrammeText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import tv.nextv.app.player.ContinuingPlayers
import tv.nextv.app.player.VideoLayers
import tv.nextv.app.player.VideoViews
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import tv.nextv.app.R
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.theme.NexTvColors
import java.text.DateFormat
import java.util.Date

/**
 * Panneau du haut de la section TV : aperçu vidéo (sans le son) de la chaîne sélectionnée,
 * programme en cours avec sa description, et programme suivant. La catégorie affichée et son
 * nombre de chaînes ([title], [count]) en forment la première ligne, avec l'éventuelle [action].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelHero(
    title: String,
    count: String,
    action: (@Composable () -> Unit)?,
    preview: ChannelPreview?,
    player: Player?,
    now: Long,
    window: WindowInfo,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (window.isTv) 196.dp else 220.dp)
            // Halo discret aux couleurs de l'application derrière le panneau.
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), Color.Transparent))),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 28.dp, end = window.horizontalPadding, top = 6.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val channel = preview?.channel
                if (channel != null) {
                    ChannelLogo(channel.logoUrl, channel.title, Modifier.fillMaxSize(0.45f), monogramSize = 30.dp)
                }
                if (player != null) PreviewVideo(player, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$title  ·  $count",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    action?.invoke()
                }
                AnimatedContent(
                    targetState = preview,
                    contentKey = { it?.channel?.id },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "hero",
                ) { shown ->
                    if (shown != null) HeroDetails(shown, now)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroDetails(preview: ChannelPreview, now: Long) {
    val channel = preview.channel
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            channel.channelNumber?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                channel.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).basicMarquee(),
            )
            qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
        }
        val current = preview.current
        if (current == null) {
            noProgrammeText(channel.name, channel.groupTitle).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            ProgrammeLine(current, now)
            current.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        preview.next?.let { next ->
            Text(
                stringResource(R.string.preview_next, timeOf(next.startMillis), next.title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Programme en cours : titre sur toute la ligne (il défile s'il est trop long), puis horaires et progression. */
@Composable
private fun ProgrammeLine(programme: GuideProgramme, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScrollingText(programme.title, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${timeOf(programme.startMillis)} – ${timeOf(programme.stopMillis)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.width(240.dp))
        }
    }
}

/**
 * Vidéo de l'aperçu : jamais sélectionnable à la télécommande (le focus reste dans la grille).
 * [zoom] : l'image remplit la zone (fond plein écran), quitte à rogner les bords.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
internal fun PreviewVideo(player: Player, modifier: Modifier = Modifier, zoom: Boolean = false) {
    // Son sans image : le flux a démarré pendant que l'écran de l'aperçu changeait (catégorie ouverte dans
    // Streaming), et le décodeur attend la prochaine image complète, parfois 20 s. Au bout de quelques secondes
    // de son sans image, l'aperçu repart au direct : l'image arrive aussitôt, comme en changeant de chaîne.
    LaunchedEffect(player) {
        // Couche vidéo (passage sans coupure) : le décodeur ne change pas de zone, l'image déjà là continue.
        var rendered = VideoLayers.of(player)?.hasImage == true
        // Temps passé sans image depuis l'arrivée de l'aperçu (ou de sa nouvelle chaîne, ou de la dernière reprise).
        var waited = 0L
        var restarts = 0
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                rendered = false
                restarts = 0
                waited = 0L
            }
        }
        player.addListener(listener)
        try {
            while (true) {
                delay(PREVIEW_FRAME_CHECK_MILLIS)
                if (rendered || restarts >= PREVIEW_MAX_RESTARTS) continue
                waited += PREVIEW_FRAME_CHECK_MILLIS
                // Le son joue depuis un moment, toujours pas d'image (une radio, sans vidéo, n'est pas concernée).
                val hasVideo = player.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                if (player.isPlaying && hasVideo && waited >= PREVIEW_NO_IMAGE_MILLIS) {
                    restarts++
                    waited = 0L
                    player.seekToDefaultPosition()
                }
            }
        } finally {
            player.removeListener(listener)
        }
    }
    // Radio (son sans image) : la vue vidéo reste cachée, le logo dessous reste visible ; une petite animation qui suit
    // le son montre que l'aperçu joue.
    var audioOnly by remember(player) { mutableStateOf(player.isAudioOnly()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioOnly = player.isAudioOnly()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(modifier) {
    AndroidView(
        factory = { context ->
            VideoViews.create(context, hideWithoutImage = true).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Sous-titres du plein écran (langue choisie) : pas dans le petit cadre de l'aperçu.
                subtitleView?.visibility = android.view.View.GONE
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view ->
            if (VideoViews.playerOf(view) !== player) {
                // L'aperçu jouait déjà sans image (retour d'un autre menu : le son continuait) : avec ce nouvel
                // écran, le décodeur attendrait la prochaine image complète du flux, parfois plusieurs secondes.
                // On repart au direct : l'image revient aussitôt, comme en ouvrant la chaîne. Pas pour le direct
                // que le plein écran vient de rendre : il a toujours eu une image, et repartir rouvrirait le flux.
                // Ni pour un lecteur qui a sa couche vidéo : son décodeur ne change pas de zone, l'image continue.
                val running = player.playbackState == Player.STATE_READY && player.playWhenReady
                val continuing = ContinuingPlayers.consume(player)
                VideoViews.bind(view, player)
                if (running && !continuing && !VideoLayers.has(player)) player.seekToDefaultPosition()
            }
        },
        onRelease = { view -> VideoViews.release(view) },
        modifier = Modifier.fillMaxSize(),
    )
    if (audioOnly) RadioPreviewBars(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).fillMaxHeight(0.22f).padding(bottom = 8.dp))
    }
}

/** Flux qui n'a que du son (radio, web radio). */
private fun Player.isAudioOnly(): Boolean =
    currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) && !currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)

/**
 * Barres de l'aperçu d'une radio : bandes du spectre du son (celui des animations des radios, [SpectrumAnalyzer]) ;
 * sans son analysé (son passé à l'ampli, aperçus des tests), une houle douce. 20 images/s sur une petite Fire TV.
 */
@Composable
private fun RadioPreviewBars(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val analyzer = remember { (context.applicationContext as? tv.nextv.app.NexTvApplication)?.container?.playerFactory?.spectrum }
    val lowPower = remember { tv.nextv.app.player.smallMemoryDevice(context) }
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    val bands = remember { FloatArray(analyzer?.bandCount ?: 32) }
    val heights = remember { FloatArray(PREVIEW_BARS) }
    var tick by remember { mutableIntStateOf(0) }
    if (analyzer != null) {
        DisposableEffect(analyzer) {
            val stop = analyzer.watch()
            onDispose { stop() }
        }
    }
    LaunchedEffect(analyzer) {
        if (inspection) {
            for (i in heights.indices) heights[i] = 0.35f + 0.5f * kotlin.math.abs(kotlin.math.sin(i * 0.9f))
            tick++
            return@LaunchedEffect
        }
        val start = System.nanoTime()
        var last = start
        while (true) {
            delay(if (lowPower) 50L else 33L)
            val now = System.nanoTime()
            val elapsed = ((now - last) / 1e9f).coerceIn(0.001f, 0.25f)
            last = now
            val seconds = (now - start) / 1e9f
            val sound = analyzer?.hasSound(now) == true
            if (sound) analyzer!!.read(bands, elapsed, now)
            val perBar = bands.size / PREVIEW_BARS
            for (i in 0 until PREVIEW_BARS) {
                val target = if (sound) {
                    var peak = 0f
                    for (b in i * perBar until (i + 1) * perBar) peak = maxOf(peak, bands[b])
                    peak.coerceIn(0f, 1f)
                } else {
                    0.25f + 0.2f * kotlin.math.sin(seconds * 3f + i * 0.8f)
                }
                heights[i] += (target - heights[i]) * (elapsed * 12f).coerceAtMost(1f)
            }
            tick++
        }
    }
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        tick // relu ici : seule l'image est redessinée.
        val gap = size.width / PREVIEW_BARS * 0.35f
        val barWidth = (size.width - gap * (PREVIEW_BARS - 1)) / PREVIEW_BARS
        for (i in 0 until PREVIEW_BARS) {
            val h = size.height * (0.12f + 0.88f * heights[i].coerceIn(0f, 1f))
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val PREVIEW_BARS = 8

/** Surveillance de l'image de l'aperçu : fréquence, son sans image toléré, reprises au direct au plus. */
private const val PREVIEW_FRAME_CHECK_MILLIS = 500L
private const val PREVIEW_NO_IMAGE_MILLIS = 2_000L
private const val PREVIEW_MAX_RESTARTS = 2

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
