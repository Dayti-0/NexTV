package tv.nextv.app.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import tv.nextv.app.R
import tv.nextv.app.ui.components.FocusSurface

/**
 * Bande-annonce en bas de la fiche, dans toutes les interfaces : une grande image de la vidéo, sur toute la largeur de
 * l'écran, avec un bouton de lecture. Elle dépasse en bas de la fiche et donne envie de descendre. Rien ne se lit à cet
 * endroit : y arriver ouvre la bande-annonce en plein écran, avec le son ([onOpen]) ; « Retour » ramène sur la fiche,
 * la sélection sur l'image (OK la rouvre, ↑ puis ↓ aussi). [opened] : plein écran affiché.
 *
 * L'image est celle de la vidéo sur YouTube, ou à défaut [imageUrl] (image du titre). [preview] : captures d'écran,
 * image posée à la place (pas de réseau).
 */
@Composable
internal fun DetailTrailerSection(
    trailerKey: String,
    imageUrl: String?,
    opened: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Marge du titre de la section (l'image, elle, va d'un bord à l'autre de l'écran). */
    horizontalPadding: Dp = 0.dp,
    preview: (@Composable () -> Unit)? = null,
) {
    // Plein écran ouvert en arrivant sur l'image ; pas de nouveau au retour sur la fiche (la sélection y est encore,
    // ou y revient) : il faut d'abord la quitter.
    var armed by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    val currentOpened by rememberUpdatedState(opened)
    val colors = MaterialTheme.colorScheme
    // Images essayées l'une après l'autre : grande image de la vidéo, image moyenne, puis celle du titre.
    val images = remember(trailerKey, imageUrl) { trailerImages(trailerKey, imageUrl) }
    var imageIndex by remember(images) { mutableIntStateOf(0) }
    // Milieu de la partie visible de l'image (depuis son haut) : le bouton de lecture s'y place, toujours à l'écran.
    var visibleMiddle by remember { mutableFloatStateOf(-1f) }
    var imageHeight by remember { mutableFloatStateOf(0f) }
    val buttonSize = 84.dp
    val buttonPx = with(LocalDensity.current) { buttonSize.toPx() }
    val buttonColor by animateColorAsState(if (focused) colors.primary else Color.Black.copy(alpha = 0.55f), label = "trailerButton")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.detail_trailer),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        FocusSurface(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInWindow().y
                    val visible = coordinates.boundsInWindow()
                    imageHeight = coordinates.size.height.toFloat()
                    visibleMiddle = if (visible.height > 0f) visible.top - top + visible.height / 2f else -1f
                },
            shape = RoundedCornerShape(0.dp),
            color = Color.Black,
            focusedColor = Color.Black,
            focusScale = 1f,
            onFocusChange = { has ->
                focused = has
                when {
                    has && armed && !currentOpened -> {
                        armed = false
                        onOpen()
                    }
                    !has && !currentOpened -> armed = true
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (preview != null) {
                    preview()
                } else {
                    images.getOrNull(imageIndex)?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { imageIndex++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Bord du haut adouci (fondu court : le haut de l'image doit se voir quand elle dépasse en bas de l'écran).
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to colors.background.copy(alpha = 0.6f), 0.1f to Color.Transparent),
                    ),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            // Au milieu de ce qui est à l'écran ; au centre de l'image tant que ce n'est pas mesuré.
                            val middle = if (visibleMiddle >= 0f) visibleMiddle else imageHeight / 2f
                            IntOffset(0, (middle - buttonPx / 2f).roundToInt().coerceAtLeast(0))
                        }
                        .size(buttonSize)
                        .clip(CircleShape)
                        .background(buttonColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.detail_trailer), tint = Color.White, modifier = Modifier.size(52.dp))
                }
            }
        }
    }
}

/**
 * Rangée juste au-dessus de la bande-annonce ([itemKey] dans [listState]) : choisie, la liste descend assez pour que
 * le haut de la grande image de la bande-annonce se voie dessous ([peek]) et donne envie d'y descendre. Le haut de la
 * rangée reste à l'écran.
 */
internal fun Modifier.revealBelow(listState: LazyListState, itemKey: Any, enabled: Boolean, peek: Dp = TRAILER_PEEK): Modifier = composed {
    val scope = rememberCoroutineScope()
    val peekPx = with(LocalDensity.current) { peek.toPx() }
    val currentEnabled by rememberUpdatedState(enabled)
    onFocusChanged { focus ->
        if (!focus.hasFocus || !currentEnabled) return@onFocusChanged
        scope.launch {
            // Après le défilement qui montre l'élément choisi.
            delay(REVEAL_AFTER_MILLIS)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return@launch
            val missing = item.offset + item.size - (info.viewportEndOffset - peekPx)
            val room = (item.offset - info.viewportStartOffset).toFloat()
            val distance = minOf(missing, room)
            if (distance > 1f) listState.animateScrollBy(distance)
        }
    }
}

/**
 * Bande-annonce en plein écran refermée ([opened] repasse à faux) : la fiche remonte tout en haut et la sélection va sur
 * le bouton de lecture ([playFocus]). Sinon elle restait sur la grande image du bas, qui remplit l'écran.
 */
@Composable
internal fun BackToSheetTop(opened: Boolean, listState: LazyListState, playFocus: () -> androidx.compose.ui.focus.FocusRequester) {
    var wasOpened by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(opened) {
        if (opened) {
            wasOpened = true
            return@LaunchedEffect
        }
        if (!wasOpened) return@LaunchedEffect
        wasOpened = false
        listState.scrollToItem(0)
        // Le bouton n'accepte la sélection qu'une fois la fenêtre de la bande-annonce fermée et le haut affiché.
        for (attempt in 1..20) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { playFocus().requestFocus() }.getOrDefault(false)) break
        }
    }
}

/** Haut de la bande-annonce montré sous la rangée du dessus. */
private val TRAILER_PEEK = 150.dp
private const val REVEAL_AFTER_MILLIS = 120L

/** Images de la bande-annonce YouTube [key] (grande, puis moyenne : toutes les vidéos n'ont pas la grande), puis [fallback]. */
internal fun trailerImages(key: String, fallback: String?): List<String> = listOfNotNull(
    "https://i.ytimg.com/vi/$key/maxresdefault.jpg",
    "https://i.ytimg.com/vi/$key/sddefault.jpg",
    fallback,
)
