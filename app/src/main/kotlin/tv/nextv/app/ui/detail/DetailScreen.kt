package tv.nextv.app.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.home.PosterImage
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.model.ContentKind
import java.text.NumberFormat

@Composable
fun DetailScreen(channelId: Long, kind: ContentKind, onPlay: (Long) -> Unit, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "detail-$kind-$channelId") { container, _ -> DetailViewModel(container, channelId, kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noTrailerApp = stringResource(R.string.detail_no_trailer_app)
    DetailContent(
        state = state,
        kind = kind,
        snackbarHostState = snackbar,
        onPlay = onPlay,
        onTrailer = { key -> if (!openTrailer(context, key)) scope.launch { snackbar.showSnackbar(noTrailerApp) } },
        onToggleFavorite = viewModel::toggleFavorite,
        onSelectSeason = viewModel::selectSeason,
        onBack = onBack,
    )
}

/** Ouvre la bande-annonce dans l'application YouTube (ou le navigateur). */
private fun openTrailer(context: Context, key: String): Boolean {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            // Essai suivant.
        }
    }
    return false
}

/** Fiche d'un film ou d'une série. Sans dépendance au ViewModel (captures d'écran). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailContent(
    state: DetailUiState,
    kind: ContentKind,
    snackbarHostState: SnackbarHostState,
    onPlay: (Long) -> Unit,
    onTrailer: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val window = rememberWindowInfo()
    val item = state.item
    val details = (state.metadata as? MetadataState.Available)?.details
    val title = details?.title?.takeIf { it.isNotBlank() } ?: item?.title.orEmpty()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item != null) { if (item != null && window.isTv) runCatching { playFocus.requestFocus() } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Backdrop(details?.backdropUrl ?: details?.posterUrl ?: item?.logoUrl)
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(start = window.horizontalPadding - 12.dp, top = 12.dp)) {
                        IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack, tint = Color.White)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (window.isWide) 40.dp else 16.dp),
                    ) {
                        PosterImage(
                            title = title,
                            imageUrl = details?.posterUrl ?: item?.logoUrl,
                            modifier = Modifier
                                .width(if (window.isWide) 240.dp else 120.dp)
                                .aspectRatio(2f / 3f)
                                .clip(MaterialTheme.shapes.large),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                title,
                                style = if (window.isWide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MetaLine(details, item, kind)
                            details?.stars?.let { Rating(it, details) }
                            details?.tagline?.let {
                                Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.8f))
                            }
                            when (val metadata = state.metadata) {
                                MetadataState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.detail_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                is MetadataState.Available -> metadata.details.overview?.let {
                                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f), maxLines = 8, overflow = TextOverflow.Ellipsis)
                                }
                                MetadataState.NotFound -> MetadataNotice(stringResource(R.string.detail_tmdb_not_found))
                                is MetadataState.Unavailable -> MetadataNotice(
                                    stringResource(
                                        when (metadata.error) {
                                            TmdbError.MISSING_KEY -> R.string.detail_tmdb_missing_key
                                            TmdbError.INVALID_KEY -> R.string.detail_tmdb_invalid_key
                                            else -> R.string.detail_tmdb_unavailable
                                        },
                                    ),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Retour à la ligne des boutons sur un écran étroit.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val playTarget = if (kind == ContentKind.SERIES) {
                                    state.seasons.getOrNull(state.selectedSeason)?.episodes?.firstOrNull()?.id
                                } else {
                                    item?.firstId
                                }
                                PillButton(
                                    stringResource(if (kind == ContentKind.SERIES) R.string.detail_play_episode else R.string.detail_play),
                                    { playTarget?.let(onPlay) },
                                    icon = Icons.Filled.PlayArrow,
                                    style = PillStyle.Primary,
                                    enabled = playTarget != null,
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                                details?.trailerYoutubeKey?.let { key ->
                                    PillButton(stringResource(R.string.detail_trailer), { onTrailer(key) }, icon = NexTvIcons.Movie)
                                }
                                PillButton(
                                    stringResource(if (state.isFavorite) R.string.detail_unfavorite else R.string.detail_favorite),
                                    onToggleFavorite,
                                    icon = Icons.Filled.Star,
                                )
                            }
                        }
                    }
                }
                if (kind == ContentKind.SERIES && state.seasons.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = window.horizontalPadding, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(state.seasons) { index, season ->
                                SeasonChip(season, index == state.selectedSeason) { onSelectSeason(index) }
                            }
                        }
                    }
                    val episodes = state.seasons.getOrNull(state.selectedSeason)?.episodes.orEmpty()
                    items(episodes.size, key = { episodes[it].id }) { index ->
                        EpisodeItem(
                            episode = episodes[index],
                            modifier = Modifier.padding(horizontal = window.horizontalPadding, vertical = 3.dp),
                            onClick = { onPlay(episodes[index].id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun Backdrop(imageUrl: String?) {
    Box(Modifier.fillMaxSize()) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(NexTvColors.Background, NexTvColors.Background.copy(alpha = 0.85f), NexTvColors.Background.copy(alpha = 0.35f)),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, NexTvColors.Background), startY = 400f)),
        )
    }
}

@Composable
private fun MetaLine(details: MediaDetails?, item: PlaylistItem?, kind: ContentKind) {
    val resources = LocalContext.current.resources
    val parts = buildList {
        (details?.year ?: item?.year)?.let { add(it.toString()) }
        details?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.take(3).joinToString(", ")) }
        details?.runtimeMinutes?.let { add(formatDuration(it * 60_000L)) }
        if (kind == ContentKind.SERIES) {
            details?.seasonCount?.let { add(resources.getQuantityString(R.plurals.seasons_count, it, it)) }
        }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Note en étoiles (sur 5, demi-étoiles comprises) et note TMDB sur 10. */
@Composable
private fun Rating(stars: Double, details: MediaDetails) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 5) {
            val fill = (stars - i).coerceIn(0.0, 1.0).toFloat()
            Box {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = NexTvColors.Warning,
                    modifier = Modifier
                        .size(24.dp)
                        .drawWithContent { clipRect(right = size.width * fill) { this@drawWithContent.drawContent() } },
                )
            }
        }
        val rating = details.rating ?: 0.0
        Text(
            stringResource(R.string.detail_rating, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(rating), details.voteCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MetadataNotice(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeasonChip(season: Season, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Text(
            season.number?.let { stringResource(R.string.detail_season, it) } ?: stringResource(R.string.detail_episodes),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun EpisodeItem(episode: EpisodeRow, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, focusScale = 1.01f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    episode.episode?.let { stringResource(R.string.detail_episode, it) } ?: episode.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(episode.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
