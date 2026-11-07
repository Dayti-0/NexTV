package tv.nextv.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.ui.components.BrandMark
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.WindowInfo

/** Largeur du menu de gauche de Streaming replié (icônes seules) : place laissée au contenu. */
internal val STREAMING_NAV_WIDTH: Dp = 76.dp
private val STREAMING_NAV_OPEN_WIDTH: Dp = 240.dp

/**
 * Menu de gauche de l'interface Streaming, comme celui de Netflix : replié en icônes, il s'ouvre avec
 * leurs noms quand la télécommande y entre (← depuis le bord du contenu) et se pose par-dessus le
 * contenu. Rechercher, les sections, les catégories de la section et les réglages ; OK choisit.
 */
@Composable
internal fun StreamingNav(
    section: HomeSection,
    window: WindowInfo,
    onSection: (HomeSection) -> Unit,
    onSearch: (() -> Unit)?,
    /** Volet de toutes les catégories (TV, Films, Séries), `null` dans le guide et le foot. */
    onCategories: (() -> Unit)?,
    onSettings: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) STREAMING_NAV_OPEN_WIDTH else STREAMING_NAV_WIDTH, tween(200), label = "nav")
    val current = remember { FocusRequester() }
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(
                if (open) {
                    Brush.horizontalGradient(0f to background, 0.75f to background.copy(alpha = 0.97f), 1f to background.copy(alpha = 0.85f))
                } else {
                    Brush.horizontalGradient(listOf(background, background.copy(alpha = 0.92f)))
                },
            )
            .onFocusChanged {
                open = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            // En entrant dans le menu : sur la section affichée, pas sur l'élément le plus proche.
            .focusProperties { onEnter = { runCatching { current.requestFocus() } } }
            .focusGroup()
            .padding(vertical = window.topPadding, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.height(48.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(compact = true, showName = open)
        }
        Spacer(Modifier.height(18.dp))
        onSearch?.let { NavItem(Icons.Filled.Search, stringResource(R.string.search_everywhere), open, selected = false, onClick = it) }
        Spacer(Modifier.height(10.dp))
        HomeSection.entries.forEach { target ->
            NavItem(
                icon = sectionIcon(target),
                label = sectionLabel(target),
                open = open,
                selected = target == section,
                onClick = { onSection(target) },
                modifier = if (target == section) Modifier.focusRequester(current) else Modifier,
            )
        }
        onCategories?.let {
            Spacer(Modifier.height(10.dp))
            NavItem(Icons.AutoMirrored.Filled.List, stringResource(R.string.nav_categories), open, selected = false, onClick = it)
        }
        Spacer(Modifier.weight(1f))
        NavItem(Icons.Filled.Settings, stringResource(R.string.settings_title), open, selected = false, onClick = onSettings)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, open: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = Color.Transparent,
        focusedColor = colors.surfaceContainerHighest,
        contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
        focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
            AnimatedVisibility(open, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
