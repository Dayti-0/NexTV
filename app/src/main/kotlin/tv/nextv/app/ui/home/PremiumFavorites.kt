package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.components.CurrentTime
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.core.model.ContentKind

/** Onglet de la fenêtre des favoris. */
private enum class FavoritesTab { MOVIES, SERIES, CHANNELS }

/**
 * « Favoris » de Classique, ouvert depuis le menu d'accueil, comme « Reprendre la lecture » : onglets Films, Séries
 * et Chaînes TV (la liste suit l'onglet sélectionné, sans OK), ouvert sur le premier qui a des favoris. OK sur un
 * film ou une série ouvre sa fiche ; sur une chaîne, la lance en mode favoris (haut/bas passe à la chaîne favorite
 * suivante). Appui long : retire des favoris. « Retour » ramène au menu.
 */
@Composable
fun PremiumFavorites(
    movies: List<MovieRow>,
    series: List<SeriesRow>,
    channels: List<ChannelListRow>,
    now: Long,
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val window = rememberWindowInfo()
    val colors = MaterialTheme.colorScheme
    // Onglet de départ : le premier qui a des favoris (gardé au retour d'une fiche ou du lecteur).
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                movies.isNotEmpty() -> FavoritesTab.MOVIES
                series.isNotEmpty() -> FavoritesTab.SERIES
                channels.isNotEmpty() -> FavoritesTab.CHANNELS
                else -> FavoritesTab.MOVIES
            },
        )
    }
    val count = when (tab) {
        FavoritesTab.MOVIES -> movies.size
        FavoritesTab.SERIES -> series.size
        FavoritesTab.CHANNELS -> channels.size
    }
    val firstCard = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    if (!initialFocusDone) {
        LaunchedEffect(Unit) {
            for (attempt in 1..10) {
                val target = if (count > 0) firstCard else tabFocus
                if (runCatching { target.requestFocus() }.getOrDefault(false)) break
                withFrameNanos { }
            }
            initialFocusDone = true
        }
    }
    // Dernier favori de l'onglet retiré (appui long) : la sélection remonte sur l'onglet.
    LaunchedEffect(count == 0) {
        if (count == 0 && initialFocusDone) runCatching { tabFocus.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.18f), Color.Transparent), radius = 1400f)),
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = window.topPadding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = window.horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PillButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Filled.ArrowBack, style = PillStyle.Ghost)
                Text(stringResource(R.string.home_favorites), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CurrentTime()
            }
            Spacer(Modifier.height(18.dp))
            // Onglets : la liste suit l'onglet sélectionné, sans OK.
            Row(Modifier.padding(horizontal = window.horizontalPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(FavoritesTab.MOVIES, sectionLabel(HomeSection.MOVIES), movies.size),
                    Triple(FavoritesTab.SERIES, sectionLabel(HomeSection.SERIES), series.size),
                    Triple(FavoritesTab.CHANNELS, stringResource(R.string.favorites_tab_channels), channels.size),
                ).forEach { (target, label, size) ->
                    FavoritesTabChip(
                        label = label,
                        count = size,
                        selected = tab == target,
                        modifier = if (tab == target) Modifier.focusRequester(tabFocus) else Modifier,
                    ) { tab = target }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (count == 0) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            when (tab) {
                                FavoritesTab.MOVIES -> R.string.favorites_empty_movies
                                FavoritesTab.SERIES -> R.string.favorites_empty_series
                                FavoritesTab.CHANNELS -> R.string.favorites_empty_channels
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = window.horizontalPadding),
                    )
                }
            } else {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    // ↑ depuis une affiche : l'onglet en cours (et non le plus proche, qui changerait la liste).
                    val first = { index: Int ->
                        (if (index == 0) Modifier.focusRequester(firstCard) else Modifier).focusProperties { up = tabFocus }
                    }
                    when (tab) {
                        FavoritesTab.MOVIES -> itemsIndexed(movies, key = { _, it -> it.itemKey }) { index, movie ->
                            PremiumPoster(
                                movie.title,
                                movie.logoUrl,
                                isFavorite = false,
                                onClick = { actions.onOpenMovie(movie.id) },
                                onLongClick = { actions.onToggleFavorite(ContentKind.MOVIE, movie.itemKey, movie.title, movie.logoUrl) },
                                modifier = first(index),
                                quality = movie.quality,
                            )
                        }
                        FavoritesTab.SERIES -> itemsIndexed(series, key = { _, it -> it.itemKey }) { index, show ->
                            PremiumPoster(
                                show.title,
                                show.logoUrl,
                                isFavorite = false,
                                onClick = { actions.onOpenSeries(show.id) },
                                onLongClick = { actions.onToggleFavorite(ContentKind.SERIES, show.itemKey, show.title, show.logoUrl) },
                                modifier = first(index),
                                quality = show.quality,
                            )
                        }
                        FavoritesTab.CHANNELS -> itemsIndexed(channels, key = { _, it -> it.itemKey }) { index, channel ->
                            PremiumChannelTile(
                                channel,
                                now,
                                isFavorite = false,
                                onClick = { actions.onPlayFavoriteChannel(channel.id) },
                                onLongClick = { actions.onToggleFavorite(ContentKind.LIVE, channel.itemKey, channel.title, channel.logoUrl) },
                                modifier = first(index),
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (tab == FavoritesTab.CHANNELS) R.string.favorites_channels_hint else R.string.favorites_remove_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = window.horizontalPadding),
                )
            }
        }
    }
}

@Composable
private fun FavoritesTabChip(label: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onSelect,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceContainerHigh,
        focusedColor = if (selected) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        focusScale = 1.05f,
        onFocusChange = { if (it) onSelect() },
    ) {
        Text("$label  ·  $count", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}
