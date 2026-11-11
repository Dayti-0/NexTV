package tv.nextv.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import tv.nextv.app.ui.home.TrailerFullScreen
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.player.formatClock
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.home.PosterImage
import tv.nextv.app.ui.home.QualityBadge
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.model.ContentKind
import java.text.NumberFormat

@Composable
fun DetailScreen(
    channelId: Long,
    kind: ContentKind,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    onResume: (Long) -> Unit = onPlay,
    onRestart: (Long) -> Unit = onPlay,
    /** Titre similaire choisi (fiche Streaming). */
    onOpenItem: (Long, ContentKind) -> Unit = { _, _ -> },
) {
    val viewModel = appViewModel(key = "detail-$kind-$channelId") { container, _ -> DetailViewModel(container, channelId, kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val trailers by viewModel.trailers.collectAsStateWithLifecycle()
    val window = rememberWindowInfo()
    // Interface Streaming sur grand écran : fiche immersive (visuel, bande-annonce, épisodes en vignettes).
    if (layout == HomeLayout.STREAMING && window.isWide && !state.missing) {
        StreamingDetailContent(
            state = state,
            kind = kind,
            window = window,
            trailers = trailers,
            onPlay = onPlay,
            onResume = onResume,
            onRestart = onRestart,
            onToggleFavorite = viewModel::toggleFavorite,
            onSelectSeason = viewModel::selectSeason,
            onOpenItem = { onOpenItem(it.id, it.kind) },
            trailerAlternatives = viewModel::trailerAlternatives,
            onNextVersion = viewModel::nextVersion,
        )
        return
    }
    val snackbar = remember { SnackbarHostState() }
    // Bande-annonce lue dans l'appli, en plein écran (une autre est essayée si elle ne se lit pas).
    var fullTrailer by remember { mutableStateOf<String?>(null) }
    DetailContent(
        state = state,
        kind = kind,
        snackbarHostState = snackbar,
        onPlay = onPlay,
        onResume = onResume,
        onRestart = onRestart,
        onTrailer = { key -> fullTrailer = key },
        onToggleFavorite = viewModel::toggleFavorite,
        onSelectSeason = viewModel::selectSeason,
        onBack = onBack,
        onNextVersion = viewModel::nextVersion,
    )
    fullTrailer?.let { key ->
        val language = (state.metadata as? MetadataState.Available)?.details?.trailerLanguage
        TrailerFullScreen(key, language, onClose = { fullTrailer = null }, alternatives = viewModel::trailerAlternatives)
    }
}

/** Fiche d'un film ou d'une série. Sans dépendance au ViewModel (captures d'écran). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    /** Reprendre le film ou l'épisode en cours, sans question. */
    onResume: (Long) -> Unit = onPlay,
    /** Film recommencé depuis le début, sans question. */
    onRestart: (Long) -> Unit = onPlay,
    /** Film en plusieurs versions : passe à la suivante. */
    onNextVersion: () -> Unit = {},
) {
    val window = rememberWindowInfo()
    val item = state.item
    val details = (state.metadata as? MetadataState.Available)?.details
    val title = details?.title?.takeIf { it.isNotBlank() } ?: item?.title.orEmpty()
    val playFocus = remember { FocusRequester() }
    // Focus sur « Reprendre » s'il existe (la reprise est connue juste après la fiche), sinon sur « Lecture ».
    // Quelques essais : le bouton n'accepte la sélection qu'une fois la fiche à l'écran.
    LaunchedEffect(item != null, state.progress != null) {
        if (item == null || !window.isTv) return@LaunchedEffect
        for (attempt in 1..20) {
            if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) break
            withFrameNanos { }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Backdrop(details?.backdropUrl ?: details?.posterUrl ?: item?.logoUrl)
            // Défilement seulement si l'élément choisi sort de l'écran : la télé le place sinon au tiers de l'écran,
            // et passer d'un bouton à l'autre faisait descendre toute la fiche.
            val margin = with(LocalDensity.current) { BRING_INTO_VIEW_MARGIN.toPx() }
            val minimalScroll = remember(margin) { MinimalBringIntoView(margin) }
            CompositionLocalProvider(LocalBringIntoViewSpec provides minimalScroll) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(start = window.horizontalPadding - 12.dp, top = window.topPadding)) {
                        IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onBackground)
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
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MetaLine(details, item, kind, state.seasons.count { it.number != null })
                            details?.stars?.let { Rating(it, details) }
                            details?.tagline?.let {
                                Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                            }
                            when (val metadata = state.metadata) {
                                MetadataState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.detail_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                is MetadataState.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    metadata.details.overview?.let {
                                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), maxLines = 8, overflow = TextOverflow.Ellipsis)
                                    }
                                    // Têtes d'affiche (TMDB).
                                    CastLine(state.cast)
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
                                val progress = state.progress
                                if (progress != null) {
                                    PillButton(
                                        when {
                                            progress.episodeLabel == null -> stringResource(R.string.player_resume_at, formatClock(progress.positionMs))
                                            progress.positionMs > 0 ->
                                                stringResource(R.string.player_resume_episode_at, progress.episodeLabel, formatClock(progress.positionMs))
                                            else -> stringResource(R.string.player_resume_next_episode, progress.episodeLabel)
                                        },
                                        // Film en plusieurs versions : la reprise se fait dans celle qui est choisie.
                                        { onResume(playTarget?.takeIf { state.versions.isNotEmpty() } ?: progress.channelId) },
                                        icon = Icons.Filled.PlayArrow,
                                        style = PillStyle.Primary,
                                        modifier = Modifier.focusRequester(playFocus),
                                    )
                                }
                                // Film déjà commencé : « Depuis le début » (sans question). Série : premier épisode,
                                // le lecteur propose alors de reprendre l'épisode en cours.
                                val restartMovie = progress != null && kind == ContentKind.MOVIE
                                PillButton(
                                    stringResource(
                                        when {
                                            restartMovie -> R.string.player_restart
                                            kind == ContentKind.SERIES -> R.string.detail_play_episode
                                            else -> R.string.detail_play
                                        },
                                    ),
                                    { playTarget?.let(if (restartMovie) onRestart else onPlay) },
                                    icon = if (restartMovie) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                                    style = if (progress == null) PillStyle.Primary else PillStyle.Secondary,
                                    enabled = playTarget != null,
                                    modifier = if (progress == null) Modifier.focusRequester(playFocus) else Modifier,
                                )
                                VersionButton(state, onNextVersion)
                                details?.trailerYoutubeKey?.let { key ->
                                    PillButton(stringResource(R.string.detail_trailer), { onTrailer(key) }, icon = NexTvIcons.Movie)
                                }
                                // Grand écran : une étoile ronde (comme la fiche Streaming), pour que les boutons
                                // tiennent sur une ligne ; « Ajouter aux favoris » écrit en entier ailleurs.
                                if (window.isWide) {
                                    FavoriteButton(state.isFavorite, onToggleFavorite, labelOnFocus = false)
                                } else {
                                    PillButton(
                                        stringResource(if (state.isFavorite) R.string.detail_unfavorite else R.string.detail_favorite),
                                        onToggleFavorite,
                                        icon = Icons.Filled.Star,
                                    )
                                }
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
                // Distribution (TMDB) : photo, nom et rôle.
                if (state.cast.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.detail_cast),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = window.horizontalPadding),
                            )
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                itemsIndexed(state.cast) { _, person -> CastCard(person) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
            }
        }
    }
}

/**
 * Film que la playlist propose en plusieurs versions (« Truefrench », « FR », « VOSTFR », 4K…) : la version choisie,
 * et son rang. OK passe à la suivante ; « Lecture » et « Reprendre » lisent celle qui est affichée.
 */
@Composable
internal fun VersionButton(state: DetailUiState, onNext: () -> Unit) {
    val version = state.versions.getOrNull(state.selectedVersion) ?: return
    val rank = state.selectedVersion + 1
    PillButton(
        if (version.label.isEmpty()) {
            stringResource(R.string.detail_version_number, rank, state.versions.size)
        } else {
            stringResource(R.string.detail_version, version.label, rank, state.versions.size)
        },
        onNext,
    )
}

/**
 * Défilement minimal : juste ce qu'il faut pour montrer l'élément choisi, rien s'il est déjà visible ; avec une marge
 * de [margin] en haut et en bas, pour son agrandissement et son contour (le bouton Retour était coupé en haut).
 */
@OptIn(ExperimentalFoundationApi::class)
private class MinimalBringIntoView(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            // Plus grand que l'écran (marges comprises) : son début en haut.
            size + 2 * margin > containerSize -> offset - margin
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

/** Marge gardée autour de l'élément choisi en faisant défiler la fiche. */
private val BRING_INTO_VIEW_MARGIN = 24.dp

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
private fun MetaLine(details: MediaDetails?, item: PlaylistItem?, kind: ContentKind, playlistSeasons: Int) {
    val resources = LocalContext.current.resources
    val parts = buildList {
        (details?.year ?: item?.year)?.let { add(it.toString()) }
        details?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.take(3).joinToString(", ")) }
        details?.runtimeMinutes?.let { add(formatDuration(it * 60_000L)) }
        if (kind == ContentKind.SERIES) {
            // Saisons de la playlist d'abord : TMDB en compte parfois moins que ce qui est proposé.
            (playlistSeasons.takeIf { it > 0 } ?: details?.seasonCount)?.let { add(resources.getQuantityString(R.plurals.seasons_count, it, it)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QualityBadge(item?.quality, large = true)
        if (parts.isNotEmpty()) {
            Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
