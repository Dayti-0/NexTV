package tv.nextv.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.model.ContentKind

/**
 * Bandeau de l'accueil Streaming (films, séries), façon plateforme de streaming : grand visuel TMDB à
 * droite, fondu dans le fond ; à gauche la rubrique, le titre, l'année, les genres, la note, la durée et
 * le résumé.
 *
 * - [featured] (la sélection est sur le bandeau, ou aucune rangée encore parcourue) : bandeau haut, avec
 *   les boutons « Regarder » / « Reprendre », « Bande-annonce », « Plus d'infos » et le son de la
 *   bande-annonce ; les contenus mis en avant défilent d'eux-mêmes.
 * - Sinon : le contenu de l'affiche choisie dans les rangées, sans boutons.
 *
 * La bande-annonce ([trailers]) remplace peu à peu le visuel après le délai réglé, muette ou non
 * ([muted]) ; à sa fin, ou si elle ne peut pas être lue, le visuel revient ([onTrailerFinished]).
 * [frameHeight] : hauteur visée du bandeau (la bande-annonce y est dessinée, sans suivre l'animation).
 */
@Composable
internal fun StreamingHero(
    section: HomeSection,
    spotlight: Spotlight?,
    featured: Boolean,
    frameHeight: Dp,
    padding: Dp,
    progress: ProgressEntity?,
    trailers: TrailerSettings,
    trailerFinished: Boolean,
    muted: Boolean,
    trailerShown: Boolean,
    actions: HomeActions,
    heroFocus: FocusRequester,
    onToggleSound: () -> Unit,
    onTrailerShown: (Boolean) -> Unit,
    onTrailerFinished: () -> Unit,
    onOpenTrailer: (String, String?) -> Unit,
    onFocusInside: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val glow = colors.primary.copy(alpha = 0.07f)
    Box(modifier.fillMaxSize().clipToBounds()) {
        // Visuel (et bande-annonce) dans un cadre 16/9 à droite, sur la hauteur visée du bandeau.
        Box(Modifier.align(Alignment.TopEnd).height(frameHeight).aspectRatio(16f / 9f)) {
            val image = spotlightImage(spotlight)
            Crossfade(image, animationSpec = tween(600), label = "heroImage", modifier = Modifier.fillMaxSize()) { url ->
                if (url != null) {
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Transparent, colors.primary.copy(alpha = 0.18f)))))
                }
            }
            val trailerKey = spotlight?.details?.trailerYoutubeKey
            if (trailers.autoplay && trailerKey != null && !trailerFinished) {
                TrailerBackdrop(
                    trailerKey,
                    spotlight.details.trailerLanguage,
                    Modifier.fillMaxSize(),
                    delayMillis = trailers.delayMillis,
                    muted = muted,
                    onShown = onTrailerShown,
                    onFinished = onTrailerFinished,
                    zoom = TRAILER_FILL_ZOOM,
                )
            }
            HeroFade(background)
        }
        // Lueur discrète de la palette derrière le texte, par-dessus tout le bandeau (pas de cassure au bord du visuel).
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.3f, size.height * 0.5f), radius = size.width * 0.5f))
                // Bas du bandeau fondu vers le fond sur toute sa largeur : aucun bord avec les rangées.
                drawRect(Brush.verticalGradient(0.84f to Color.Transparent, 1f to background, startY = 0f, endY = size.height))
            },
        )
        // Texte, à gauche, par-dessus le fondu.
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.52f)
                .padding(start = padding, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                sectionLabel(section).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            )
            // Changement de contenu : le texte se fond, sans saut.
            // Le fondu ne joue qu'au changement de titre ; chaque texte garde le sien pendant qu'il s'efface.
            AnimatedContent(
                spotlight,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                contentKey = { it?.item?.itemKey },
                label = "heroText",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        shown?.item?.title ?: allLabel(section),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shown?.let { MetaLine(it) }
                    shown?.overview?.let { overview ->
                        Text(
                            overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = if (featured) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val item = spotlight?.item
            AnimatedVisibility(featured && item != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(150))) {
                if (item == null) return@AnimatedVisibility
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .onFocusChanged { onFocusInside(it.hasFocus) }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val series = item.kind == ContentKind.SERIES
                    // « Reprendre » quand le film ou la série est en cours, sinon « Regarder » (série : sa fiche).
                    val resume = progress != null
                    PillButton(
                        stringResource(if (resume) R.string.hero_resume else R.string.hero_watch),
                        {
                            when {
                                progress != null -> actions.onResume(progress.channelId)
                                series -> actions.onOpenSeries(item.id)
                                else -> actions.onPlayChannel(item.id)
                            }
                        },
                        icon = Icons.Filled.PlayArrow,
                        style = PillStyle.Primary,
                        modifier = Modifier.focusRequester(heroFocus),
                    )
                    spotlight.details?.trailerYoutubeKey?.let { key ->
                        PillButton(stringResource(R.string.hero_trailer), { onOpenTrailer(key, spotlight.details.trailerLanguage) }, icon = NexTvIcons.Movie)
                    }
                    PillButton(
                        stringResource(R.string.hero_more_info),
                        { if (series) actions.onOpenSeries(item.id) else actions.onOpenMovie(item.id) },
                        icon = Icons.Filled.Info,
                    )
                    // Son de la bande-annonce, tant qu'elle est à l'écran.
                    if (trailerShown) {
                        FocusSurface(
                            onClick = onToggleSound,
                            shape = CircleShape,
                            color = colors.surfaceContainerHigh,
                            focusedColor = colors.primary,
                            contentColor = colors.onSurface,
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
        }
    }
}

/**
 * Fondu du visuel vers le fond, étroit pour garder l'aperçu net : un peu à gauche (raccord avec le texte),
 * en bas (rangées), à peine en haut.
 */
@Composable
private fun HeroFade(background: Color) {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.15f to background.copy(alpha = 0.75f), 0.38f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to background.copy(alpha = 0.2f), 0.08f to Color.Transparent, 0.84f to Color.Transparent, 1f to background)))
}

/**
 * Affiche des rangées de Streaming : l'image seule, aux coins arrondis (le titre et les détails sont dans
 * le bandeau) ; au focus, elle s'agrandit un peu avec un contour de la couleur de la palette. Barre de
 * progression pour un contenu commencé, étoile pour un favori, [detail] sous l'affiche (reprise).
 */
@Composable
internal fun StreamingPoster(
    title: String,
    imageUrl: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    width: Dp,
    progress: Float? = null,
    detail: String? = null,
    watched: Boolean = false,
) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.width(width).aspectRatio(POSTER_RATIO),
            shape = STREAMING_POSTER_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            focusScale = 1.08f,
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(title, imageUrl, Modifier.fillMaxSize(), compact = width < COMPACT_POSTER_WIDTH)
                if (isFavorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                // Détail (épisode, temps restant) en bas de l'image : toutes les rangées gardent la même hauteur.
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = if (progress != null) 8.dp else 6.dp),
                    )
                }
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        // Titre sous l'affiche (sinon il faut deviner le film d'après l'image), sur une ligne : la rangée garde
        // sa hauteur, le titre de la rangée suivante reste visible dessous.
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val STREAMING_POSTER_SHAPE = RoundedCornerShape(10.dp)
