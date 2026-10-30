package tv.nextv.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.ui.components.Badge
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport

/** Contour signalant l'élément en cours de déplacement (réorganisation des favoris). */
private fun Modifier.movingOutline(moving: Boolean) =
    if (moving) border(3.dp, NexTvColors.Warning, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) else this

@Composable
fun FavoriteStar(modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.fillMaxSize(0.68f))
    }
}

/** Libellé de qualité (« FHD », « 4K »…). */
fun qualityLabel(quality: String?): String? = StreamQuality.entries.firstOrNull { it.name == quality }?.label

/** Taille d'une vignette de chaîne, selon sa largeur (nombre de chaînes par ligne). */
enum class CardSize {
    /** Logo, numéro, qualité, programme en cours et progression. */
    LARGE,

    /** Logo réduit, mêmes informations. */
    MEDIUM,

    /** Logo et nom seulement, avec la progression. */
    SMALL,
    ;

    companion object {
        fun of(width: Dp): CardSize = when {
            width >= 150.dp -> LARGE
            width >= 100.dp -> MEDIUM
            else -> SMALL
        }
    }
}

@Composable
fun ChannelCard(
    channel: ChannelListRow,
    now: Long,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    size: CardSize = CardSize.LARGE,
) {
    val small = size == CardSize.SMALL
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.movingOutline(moving),
        shape = MaterialTheme.shapes.medium,
        focusScale = if (small) 1.1f else 1.06f,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    // Moins haute en taille moyenne : plus de rangées visibles.
                    .aspectRatio(if (size == CardSize.MEDIUM) 2.4f else 16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                when (size) {
                    CardSize.LARGE -> {
                        ChannelLogo(channel.logoUrl, channel.title, Modifier.size(64.dp), monogramSize = 22.dp)
                        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            channel.channelNumber?.let { Badge(it) }
                            qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
                        }
                        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SupportBadge(channel.streamSupport)
                            if (isFavorite) FavoriteStar()
                        }
                    }
                    // Logo à gauche, favori et qualité (ou lecture limitée) à droite : rien ne se chevauche.
                    CardSize.MEDIUM -> Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelLogo(channel.logoUrl, channel.title, Modifier.size(34.dp), monogramSize = 13.dp)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isFavorite) FavoriteStar(Modifier.size(18.dp))
                            if (channel.streamSupport != StreamSupport.SUPPORTED.name) {
                                SupportBadge(channel.streamSupport)
                            } else {
                                qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
                            }
                        }
                    }
                    CardSize.SMALL -> {
                        ChannelLogo(channel.logoUrl, channel.title, Modifier.fillMaxHeight(0.6f).aspectRatio(1f), monogramSize = 10.dp)
                        if (isFavorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp))
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = if (size == CardSize.LARGE) 10.dp else 6.dp, vertical = if (small) 5.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Numéro devant le nom quand il n'a pas sa place sur le logo.
                    if (size == CardSize.MEDIUM) {
                        channel.channelNumber?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                    // Nom sur deux lignes (hauteur fixe : vignettes alignées) ; complet en haut à droite.
                    Text(
                        channel.title,
                        style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (!small) {
                    Text(
                        channel.nowTitle ?: stringResource(R.string.channels_no_epg),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (channel.nowTitle != null) MaterialTheme.colorScheme.onSurfaceVariant else NexTvColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val start = channel.nowStart
                val stop = channel.nowStop
                if (start != null && stop != null) {
                    Spacer(Modifier.height(if (small) 1.dp else 4.dp))
                    LiveProgress(start, stop, now)
                } else {
                    Spacer(Modifier.height(if (small) 4.dp else 7.dp))
                }
            }
        }
    }
}

@Composable
fun ChannelListItem(
    channel: ChannelListRow,
    now: Long,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().movingOutline(moving),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        focusScale = 1.01f,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChannelLogo(channel.logoUrl, channel.title, Modifier.size(width = 72.dp, height = 48.dp), monogramSize = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        listOfNotNull(channel.channelNumber, channel.title).joinToString("  "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    qualityLabel(channel.quality)?.let { Badge(it, color = NexTvColors.Primary) }
                    if (isFavorite) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp))
                }
                channel.nowTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val start = channel.nowStart
                val stop = channel.nowStop
                if (start != null && stop != null) LiveProgress(start, stop, now, Modifier.padding(top = 4.dp))
            }
            SupportBadge(channel.streamSupport)
        }
    }
}

@Composable
fun SupportBadge(streamSupport: String, modifier: Modifier = Modifier) {
    when (StreamSupport.entries.firstOrNull { it.name == streamSupport } ?: StreamSupport.SUPPORTED) {
        StreamSupport.SUPPORTED -> Unit
        StreamSupport.LIMITED -> Badge(stringResource(R.string.stream_limited), modifier, color = NexTvColors.Warning)
        StreamSupport.UNSUPPORTED -> Badge(stringResource(R.string.stream_unsupported_short), modifier, color = NexTvColors.Error)
    }
}

/** Affiche de film ou de série (2:3), avec un visuel de remplacement tant que l'image n'est pas chargée. */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    /** Part déjà regardée (reprise de lecture), barre en bas de l'affiche. */
    progress: Float? = null,
    /** Affiche étroite (nombreuses par ligne) : titre sur une ligne, sans sous-titre. */
    compact: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.movingOutline(moving),
        shape = MaterialTheme.shapes.medium,
        focusScale = 1.07f,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                PosterImage(title, imageUrl, Modifier.fillMaxSize(), compact)
                val inset = if (compact) 4.dp else 8.dp
                if (isFavorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(inset).then(if (compact) Modifier.size(16.dp) else Modifier))
                progress?.let { LiveProgress(0, 1_000, (it * 1_000).toLong(), Modifier.align(Alignment.BottomCenter).padding(inset)) }
            }
            if (compact) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                )
            } else {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Image d'affiche, ou dégradé avec le titre si aucune image n'est disponible. */
@Composable
fun PosterImage(title: String, imageUrl: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    val (start, end) = NexTvColors.swatchFor(title)
    Box(modifier.background(Brush.linearGradient(listOf(start.copy(alpha = 0.75f), end.copy(alpha = 0.55f), Color(0xFF111827)))), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Text(
                title,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(if (compact) 5.dp else 12.dp),
            )
        }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
