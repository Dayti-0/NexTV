package tv.nextv.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import tv.nextv.core.meta.CastMember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.data.progress.WatchedRepository
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.WindowInfo
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.home.PosterImage
import tv.nextv.app.ui.home.QualityBadge
import tv.nextv.app.ui.home.PosterItem
import tv.nextv.app.ui.home.StreamingPoster
import tv.nextv.app.ui.home.TRAILER_FILL_ZOOM
import tv.nextv.app.ui.home.TrailerBackdrop
import tv.nextv.app.ui.home.TrailerFullScreen
import tv.nextv.app.ui.home.TrailerSettings
import tv.nextv.app.ui.home.WatchedBadge
import tv.nextv.app.ui.player.formatClock
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.ContentKind
import java.text.NumberFormat

/**
 * Fiche d'un film ou d'une série de l'interface Streaming, sur grand écran : grand visuel TMDB en haut à
 * droite (sa bande-annonce y démarre selon les réglages, muette ou non, bouton « Son »), fondu dans le
 * fond ; à gauche l'affiche, le titre, la note, l'année, les genres, la durée, l'accroche et le résumé,
 * puis « Reprendre » / « Regarder », « Depuis le début », « Bande-annonce » et « Favoris ». Série : les
 * saisons et leurs épisodes en vignettes (déjà vus, en cours) ; puis les titres similaires.
 */
@Composable
internal fun StreamingDetailContent(
    state: DetailUiState,
    kind: ContentKind,
    window: WindowInfo,
    trailers: TrailerSettings,
    onPlay: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRestart: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onOpenItem: (PosterItem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val item = state.item
    val details = (state.metadata as? MetadataState.Available)?.details
    val title = details?.title?.takeIf { it.isNotBlank() } ?: item?.title.orEmpty()
    val padding = if (window.isWide) 48.dp else window.horizontalPadding
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val headerInView = remember { BringIntoViewRequester() }
    var focusDone by remember { mutableStateOf(false) }
    // La sélection sur « Reprendre » (connu juste après la fiche) ou « Regarder ».
    LaunchedEffect(item != null, state.progress != null) {
        if (item == null || focusDone) return@LaunchedEffect
        for (attempt in 1..20) {
            withFrameNanos { }
            if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) break
        }
        focusDone = true
    }
    // Bande-annonce : une seule lecture, muette selon les réglages ; plein écran avec « Bande-annonce ».
    var muted by remember(trailers.sound) { mutableStateOf(!trailers.sound) }
    var trailerShown by remember { mutableStateOf(false) }
    var trailerDone by remember { mutableStateOf(false) }
    var fullTrailer by remember { mutableStateOf(false) }
    // Descendu dans les épisodes ou les titres similaires : le visuel s'efface un peu (lisibilité).
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val dim by animateFloatAsState(if (scrolled) 0.55f else 0f, tween(300), label = "dim")
    val frameHeight = 360.dp

    Box(Modifier.fillMaxSize().background(background)) {
        Box(Modifier.align(Alignment.TopEnd).height(frameHeight).aspectRatio(16f / 9f)) {
            val image = details?.backdropUrl ?: details?.posterUrl ?: item?.logoUrl
            Crossfade(image, animationSpec = tween(600), label = "detailImage", modifier = Modifier.fillMaxSize()) { url ->
                if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
            val key = details?.trailerYoutubeKey
            if (trailers.autoplay && key != null && !trailerDone && !fullTrailer) {
                TrailerBackdrop(
                    key,
                    details.trailerLanguage,
                    Modifier.fillMaxSize(),
                    delayMillis = trailers.delayMillis,
                    muted = muted,
                    onShown = { trailerShown = it },
                    onFinished = { trailerDone = true },
                    zoom = TRAILER_FILL_ZOOM,
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.35f to background.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to background.copy(alpha = 0.3f), 0.2f to Color.Transparent, 0.65f to Color.Transparent, 1f to background)))
            Box(Modifier.fillMaxSize().background(background.copy(alpha = dim)))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = frameHeight - 20.dp)
                        // Boutons choisis (à l'ouverture, ou en remontant) : tout le haut de la fiche est montré. La
                        // télé place sinon l'élément choisi au tiers de l'écran, et le titre restait coupé.
                        .bringIntoViewRequester(headerInView)
                        .onFocusChanged { if (it.hasFocus) scope.launch { headerInView.bringIntoView() } }
                        .padding(start = padding, end = padding, top = window.topPadding),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterImage(
                        title = title,
                        imageUrl = details?.posterUrl ?: item?.logoUrl,
                        modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (kind == ContentKind.MOVIE && item != null && item.itemKey in state.watched) WatchedBadge()
                        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            details?.rating?.takeIf { details.voteCount > 0 }?.let { rating ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(18.dp))
                                    Text(NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(rating), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            QualityBadge(item?.quality, large = true)
                            DetailMeta(state, kind)
                        }
                        details?.tagline?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic, color = colors.onSurface.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        (details?.overview)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH))
                        }
                        // Les têtes d'affiche, sous le résumé (la distribution complète est plus bas).
                        CastLine(state.cast)
                        DetailButtons(
                            state, kind, playFocus,
                            trailerKey = state.trailer?.first,
                            trailerShown = trailerShown,
                            muted = muted,
                            onPlay = onPlay,
                            onResume = onResume,
                            onRestart = onRestart,
                            onToggleFavorite = onToggleFavorite,
                            onTrailer = { fullTrailer = true },
                            onToggleSound = { muted = !muted },
                        )
                    }
                }
            }
            if (kind == ContentKind.SERIES && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    // En arrivant sur les saisons : sur celle affichée, pas sur la plus proche.
                    val selectedFocus = remember { FocusRequester() }
                    LazyRow(
                        modifier = Modifier.focusRestorer(selectedFocus),
                        contentPadding = PaddingValues(horizontal = padding, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.seasons) { index, season ->
                            val selected = index == state.selectedSeason
                            SeasonTab(season, selected, if (selected) Modifier.focusRequester(selectedFocus) else Modifier) { onSelectSeason(index) }
                        }
                    }
                }
                item(key = "episodes") {
                    val episodes = state.seasons.getOrNull(state.selectedSeason)?.episodes.orEmpty()
                    val seriesKey = item?.itemKey.orEmpty()
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = padding, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(episodes, key = { _, it -> it.id }) { _, episode ->
                            val current = state.progress?.channelId == episode.id
                            EpisodeCard(
                                episode = episode,
                                fallbackImage = details?.backdropUrl,
                                watched = WatchedRepository.episodeKey(seriesKey, episode.season, episode.episode, episode.name) in state.watched,
                                progress = state.progress?.takeIf { current && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                                current = current,
                                onClick = { if (current) onResume(episode.id) else onPlay(episode.id) },
                            )
                        }
                    }
                }
            }
            if (state.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.detail_cast), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = padding))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = padding, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(state.cast, key = { index, person -> "$index:${person.name}" }) { _, person -> CastCard(person) }
                        }
                    }
                }
            }
            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.detail_similar), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = padding))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = padding, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(state.similar, key = { _, it -> it.itemKey }) { _, similar ->
                                StreamingPoster(
                                    similar.title,
                                    similar.imageUrl,
                                    isFavorite = false,
                                    onClick = { onOpenItem(similar) },
                                    onLongClick = null,
                                    modifier = Modifier,
                                    width = 120.dp,
                                    watched = similar.kind == ContentKind.MOVIE && similar.itemKey in state.watched,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (fullTrailer) {
        // Série : bande-annonce de la saison choisie si TMDB en a une.
        state.trailer?.let { (key, language) -> TrailerFullScreen(key, language) { fullTrailer = false } }
    }
}

/** Année, genres, durée (film) ou nombre de saisons (série). */
@Composable
private fun DetailMeta(state: DetailUiState, kind: ContentKind) {
    val details = (state.metadata as? MetadataState.Available)?.details
    val resources = LocalContext.current.resources
    val parts = buildList {
        (details?.year ?: state.item?.year)?.let { add(it.toString()) }
        details?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.take(3).joinToString(", ")) }
        if (kind == ContentKind.SERIES) {
            // Saisons proposées par la playlist d'abord : TMDB en compte parfois moins (Lupin : 3 au lieu de 4).
            val seasons = state.seasons.count { it.number != null }.takeIf { it > 0 } ?: details?.seasonCount
            seasons?.let { add(resources.getQuantityString(R.plurals.seasons_count, it, it)) }
        } else {
            details?.runtimeMinutes?.let { add(formatDuration(it * 60_000L)) }
        }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailButtons(
    state: DetailUiState,
    kind: ContentKind,
    playFocus: FocusRequester,
    trailerKey: String?,
    trailerShown: Boolean,
    muted: Boolean,
    onPlay: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRestart: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onTrailer: () -> Unit,
    onToggleSound: () -> Unit,
) {
    val progress = state.progress
    val firstEpisode = state.seasons.getOrNull(state.selectedSeason)?.episodes?.firstOrNull()
    val playTarget = if (kind == ContentKind.SERIES) firstEpisode?.id else state.item?.firstId
    // Sur une ligne : libellés courts (« Lire S4 E1 »), favoris en étoile dont le texte n'apparaît que sélectionnée.
    // Retour à la ligne seulement s'ils ne tiennent pas (« Reprendre S02E03 à 12:30 » sur un petit écran).
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (progress != null) {
            PillButton(
                when {
                    progress.episodeLabel == null -> stringResource(R.string.player_resume_at, formatClock(progress.positionMs))
                    progress.positionMs > 0 -> stringResource(R.string.player_resume_episode_at, progress.episodeLabel, formatClock(progress.positionMs))
                    else -> stringResource(R.string.player_resume_next_episode, progress.episodeLabel)
                },
                { onResume(progress.channelId) },
                icon = Icons.Filled.PlayArrow,
                style = PillStyle.Primary,
                modifier = Modifier.focusRequester(playFocus),
            )
        }
        // Film commencé : « Depuis le début ». Série : premier épisode de la saison choisie.
        val restartMovie = progress != null && kind == ContentKind.MOVIE
        val episodeNumbers = firstEpisode?.let { episode -> episode.season?.let { season -> episode.episode?.let { season to it } } }
        PillButton(
            when {
                restartMovie -> stringResource(R.string.player_restart)
                kind == ContentKind.SERIES && episodeNumbers != null -> stringResource(R.string.detail_play_season_episode, episodeNumbers.first, episodeNumbers.second)
                kind == ContentKind.SERIES -> stringResource(R.string.detail_play_episode)
                else -> stringResource(R.string.hero_watch)
            },
            { playTarget?.let(if (restartMovie) onRestart else onPlay) },
            icon = if (restartMovie) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
            style = if (progress == null) PillStyle.Primary else PillStyle.Secondary,
            enabled = playTarget != null,
            modifier = if (progress == null) Modifier.focusRequester(playFocus) else Modifier,
        )
        trailerKey?.let { PillButton(stringResource(R.string.hero_trailer), onTrailer, icon = NexTvIcons.Movie) }
        FavoriteButton(state.isFavorite, onToggleFavorite)
        AnimatedVisibility(trailerShown, enter = fadeIn(), exit = fadeOut()) {
            FocusSurface(
                onClick = onToggleSound,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusScale = 1.08f,
            ) {
                Icon(
                    if (muted) NexTvIcons.VolumeOff else NexTvIcons.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.hero_sound_on else R.string.hero_sound_off),
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
        }
    }
}

/**
 * Favoris : une étoile (pleine et dorée si le titre est en favori, vide sinon) ; sélectionnée, elle dit ce
 * que fait OK (« Ajouter aux favoris », « Retirer des favoris »).
 */
@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(if (isFavorite) R.string.detail_unfavorite else R.string.detail_favorite)
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        focusScale = 1.06f,
        onFocusChange = { focused = it },
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 10.dp).animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isFavorite) Icons.Filled.Star else NexTvIcons.StarBorder,
                contentDescription = label,
                tint = if (isFavorite) NexTvColors.Warning else LocalContentColor.current,
                modifier = Modifier.size(20.dp),
            )
            if (focused) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
            }
        }
    }
}

@Composable
private fun SeasonTab(season: Season, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) colors.primary.copy(alpha = 0.22f) else Color.Transparent,
        focusedColor = colors.primary.copy(alpha = 0.4f),
        contentColor = if (selected) colors.primary else colors.onSurface,
        focusScale = 1.05f,
        // Saison sélectionnée sans OK : ses épisodes s'affichent dès qu'on passe dessus.
        onFocusChange = { if (it && !selected) onClick() },
    ) {
        Text(
            season.number?.let { stringResource(R.string.detail_season, it) } ?: stringResource(R.string.detail_episodes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Vignette d'un épisode : image (ou visuel de la série), numéro et titre, déjà vu, progression s'il est en cours. */
@Composable
private fun EpisodeCard(
    episode: EpisodeRow,
    fallbackImage: String?,
    watched: Boolean,
    progress: Float?,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(EPISODE_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier.width(EPISODE_WIDTH).aspectRatio(16f / 9f),
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceContainer,
            focusScale = 1.06f,
        ) {
            Box(Modifier.fillMaxSize()) {
                val image = episode.logoUrl ?: fallbackImage
                if (image != null) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
                if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(colors.primary))
                    }
                }
            }
        }
        Text(
            listOfNotNull(episode.episode?.let { stringResource(R.string.detail_episode, it) }, if (current) stringResource(R.string.detail_episode_current) else null)
                .joinToString("  ·  ").ifEmpty { episode.name },
            style = MaterialTheme.typography.labelLarge,
            color = if (current) colors.primary else colors.onSurface,
            maxLines = 1,
        )
        Text(episode.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
    }
}

/** « Avec Sam Worthington, Zoe Saldana, Sigourney Weaver ». */
@Composable
internal fun CastLine(cast: List<CastMember>, modifier: Modifier = Modifier) {
    if (cast.isEmpty()) return
    Text(
        stringResource(R.string.detail_starring, cast.take(STARRING_COUNT).joinToString(", ") { it.name }),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = TEXT_MAX_WIDTH),
    )
}

/**
 * Acteur ou actrice : photo ronde (ses initiales sans photo), nom et rôle. Sélectionnable sans action : la
 * télécommande parcourt la distribution, et la page défile jusqu'à elle.
 */
@Composable
internal fun CastCard(person: CastMember) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(CAST_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = {},
            modifier = Modifier.size(CAST_PHOTO),
            shape = CircleShape,
            color = colors.surfaceContainerHigh,
            focusScale = 1.08f,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    person.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant,
                )
                person.photoUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        person.character?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

private val CAST_WIDTH = 110.dp
private val CAST_PHOTO = 88.dp
private const val STARRING_COUNT = 3

private val EPISODE_WIDTH = 240.dp

/** Largeur du titre et du résumé : lisibles, sans passer sous le visuel. */
private val TEXT_MAX_WIDTH = 620.dp
