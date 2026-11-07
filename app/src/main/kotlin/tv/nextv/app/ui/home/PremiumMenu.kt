package tv.nextv.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.ui.components.BrandMark
import tv.nextv.app.ui.components.CurrentTime
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.rememberWindowInfo

/**
 * Menu d'accueil de Cinéma Premium : une grande tuile par section (TV, Guide TV, Films, Séries, Foot),
 * puis les raccourcis (recherche de partout, favoris, reprise, réglages) et la fin de l'abonnement. Une tuile ouvre sa
 * section, « Retour » y ramène.
 */
@Composable
fun PremiumMenu(
    /** Section ouverte la dernière fois : sa tuile a le focus. */
    current: HomeSection,
    onSection: (HomeSection) -> Unit,
    onFavorites: () -> Unit,
    onContinue: () -> Unit,
    onSettings: () -> Unit,
    /** Recherche de partout : chaînes, films et séries. */
    onSearch: () -> Unit = {},
    /** « Abonnement jusqu'au 10 novembre 2026 » (compte Xtream), `null` si inconnu. */
    subscription: String? = null,
    modifier: Modifier = Modifier,
) {
    val window = rememberWindowInfo()
    val colors = MaterialTheme.colorScheme
    val focus = remember { HomeSection.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { focus.getValue(current).requestFocus() } }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            // Halo discret de la couleur du thème au centre, derrière les tuiles.
            .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.22f), Color.Transparent), radius = 1400f))
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.7f)))),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = window.horizontalPadding, vertical = window.topPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(showName = true)
                Spacer(Modifier.weight(1f))
                CurrentTime()
            }
            // Tuiles : en une rangée sur la télé, deux par ligne sur un écran étroit.
            val perRow = if (window.isWide) MENU_ORDER.size else 2
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MENU_ORDER.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(if (window.isWide) 20.dp else 12.dp)) {
                        row.forEach { section ->
                            MenuTile(section, onClick = { onSection(section) }, modifier = Modifier.weight(1f).focusRequester(focus.getValue(section)))
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.search_everywhere), onSearch, icon = Icons.Filled.Search)
                PillButton(stringResource(R.string.home_favorites), onFavorites, icon = Icons.Filled.Star)
                PillButton(stringResource(R.string.home_continue), onContinue, icon = Icons.Filled.PlayArrow)
                PillButton(stringResource(R.string.settings_title), onSettings, icon = Icons.Filled.Settings)
                Spacer(Modifier.weight(1f))
                subscription?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Ordre des tuiles : le direct et son guide, puis films, séries et foot. */
private val MENU_ORDER = listOf(HomeSection.TV, HomeSection.GUIDE, HomeSection.MOVIES, HomeSection.SERIES, HomeSection.FOOTBALL)

@Composable
private fun MenuTile(section: HomeSection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.aspectRatio(0.92f).widthIn(max = 260.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh.copy(alpha = 0.85f),
        focusedColor = colors.primary,
        contentColor = colors.onSurface,
        focusScale = 1.06f,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(sectionIcon(section), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(14.dp))
            Text(sectionLabel(section), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    when (section) {
                        HomeSection.TV -> R.string.menu_tv_hint
                        HomeSection.GUIDE -> R.string.menu_guide_hint
                        HomeSection.MOVIES -> R.string.menu_movies_hint
                        HomeSection.SERIES -> R.string.menu_series_hint
                        HomeSection.FOOTBALL -> R.string.menu_football_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
