package tv.nextv.app.ui.home

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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.nextv.app.R
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.StatusPill
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
                    if (shown == null) {
                        Text(
                            stringResource(R.string.preview_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        HeroDetails(shown, now)
                    }
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
            Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.bodyLarge, color = NexTvColors.TextMuted)
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

@Composable
private fun ProgrammeLine(programme: GuideProgramme, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(stringResource(R.string.player_live), NexTvColors.Live)
            Text(programme.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                "${timeOf(programme.startMillis)} – ${timeOf(programme.stopMillis)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiveProgress(programme.startMillis, programme.stopMillis, now, Modifier.width(280.dp))
    }
}

/** Vidéo de l'aperçu : jamais sélectionnable à la télécommande (le focus reste dans la grille). */
@AndroidXOptIn(UnstableApi::class)
@Composable
private fun PreviewVideo(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
