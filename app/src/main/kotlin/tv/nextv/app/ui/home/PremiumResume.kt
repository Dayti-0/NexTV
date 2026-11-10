package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.ui.components.CurrentTime
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo

/**
 * « Reprendre la lecture » de Classique, ouvert depuis le menu d'accueil : onglets Films et Séries
 * (ouvert sur celui du dernier visionnage), grandes affiches avec la progression et le temps restant ou
 * l'épisode. OK reprend la lecture, appui long retire de la liste, « Retour » ramène au menu.
 */
@Composable
fun PremiumResume(
    movies: List<ProgressEntity>,
    series: List<ProgressEntity>,
    onResume: (Long) -> Unit,
    onClear: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val window = rememberWindowInfo()
    val colors = MaterialTheme.colorScheme
    // Onglet de départ : celui du dernier film ou épisode regardé.
    var showSeries by rememberSaveable {
        mutableStateOf((series.maxOfOrNull { it.updatedAt } ?: 0L) > (movies.maxOfOrNull { it.updatedAt } ?: 0L))
    }
    val items = (if (showSeries) series else movies).sortedByDescending { it.updatedAt }
    val firstCard = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    if (!initialFocusDone) {
        LaunchedEffect(Unit) {
            for (attempt in 1..10) {
                val target = if (items.isNotEmpty()) firstCard else tabFocus
                if (runCatching { target.requestFocus() }.getOrDefault(false)) break
                withFrameNanos { }
            }
            initialFocusDone = true
        }
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
                Text(stringResource(R.string.home_continue), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CurrentTime()
            }
            Spacer(Modifier.height(18.dp))
            // Onglets : la liste suit l'onglet sélectionné, sans OK.
            Row(Modifier.padding(horizontal = window.horizontalPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResumeTab(sectionLabel(HomeSection.MOVIES), movies.size, selected = !showSeries, modifier = if (!showSeries) Modifier.focusRequester(tabFocus) else Modifier) { showSeries = false }
                ResumeTab(sectionLabel(HomeSection.SERIES), series.size, selected = showSeries, modifier = if (showSeries) Modifier.focusRequester(tabFocus) else Modifier) { showSeries = true }
            }
            Spacer(Modifier.height(18.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (showSeries) R.string.resume_empty_series else R.string.resume_empty_movies),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.itemKey }) { index, item ->
                        ResumeCard(
                            item,
                            onClick = { onResume(item.channelId) },
                            onLongClick = { onClear(item.itemKey) },
                            // ↑ : l'onglet en cours (et non le plus proche, qui changerait la liste).
                            modifier = (if (index == 0) Modifier.focusRequester(firstCard) else Modifier).focusProperties { up = tabFocus },
                        )
                    }
                }
                Text(
                    stringResource(R.string.resume_clear_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = window.horizontalPadding),
                )
            }
        }
    }
}

@Composable
private fun ResumeTab(label: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
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

/** Grande affiche à reprendre : progression en bas de l'image, titre et épisode ou temps restant dessous. */
@Composable
private fun ResumeCard(item: ProgressEntity, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val progress = if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else null
    Column(Modifier.width(RESUME_CARD_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.width(RESUME_CARD_WIDTH).aspectRatio(POSTER_RATIO),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceContainer,
            focusScale = 1.06f,
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(item.title, item.logoUrl, Modifier.fillMaxSize())
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.45f))) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.primary))
                    }
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        continueDetail(item)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val RESUME_CARD_WIDTH = 168.dp
