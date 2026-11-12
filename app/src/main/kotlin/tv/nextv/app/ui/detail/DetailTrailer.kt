package tv.nextv.app.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.nextv.app.R
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.home.TRAILER_FILL_ZOOM
import tv.nextv.app.ui.home.TrailerBackdrop
import tv.nextv.app.ui.home.TrailerSettings

/**
 * Bande-annonce en bas de la fiche (réglage « Bande-annonce en fond »), dans toutes les interfaces : un cadre 16/9
 * qui la lit une fois, muette selon les réglages, quand il est à l'écran ([active]) et après le délai des réglages.
 * Descendu jusqu'à elle (tout en bas de la fiche), elle passe d'elle-même en plein écran, avec le son ([onOpen]) ;
 * « Retour » ramène sur la fiche, la sélection sur le cadre (OK la remet en plein écran). [opened] : plein écran
 * affiché. [preview] : captures d'écran, image posée à la place de la vidéo (la page YouTube ne s'y dessine pas).
 */
@Composable
internal fun DetailTrailerSection(
    trailerKey: String,
    language: String?,
    imageUrl: String?,
    trailers: TrailerSettings,
    active: Boolean,
    opened: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TRAILER_CARD_WIDTH,
    preview: (@Composable () -> Unit)? = null,
) {
    var done by remember(trailerKey) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Plein écran ouvert en arrivant sur le cadre ; pas de nouveau au retour sur la fiche (la sélection y est encore,
    // ou y revient) : il faut d'abord la quitter.
    var armed by remember { mutableStateOf(true) }
    val currentOpened by rememberUpdatedState(opened)
    val hint by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "trailerHint")
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.detail_trailer), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        FocusSurface(
            onClick = onOpen,
            modifier = Modifier.width(width).aspectRatio(16f / 9f),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
            focusedColor = Color.Black,
            focusScale = 1.02f,
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
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
                // Image du titre en attendant la vidéo (et après sa fin).
                imageUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (preview != null) {
                    preview()
                } else if (active && !done) {
                    TrailerBackdrop(
                        trailerKey,
                        language,
                        Modifier.fillMaxSize(),
                        delayMillis = trailers.delayMillis,
                        muted = !trailers.sound,
                        onFinished = { done = true },
                        zoom = TRAILER_FILL_ZOOM,
                    )
                }
                // Ce que fait OK, quand le cadre est choisi.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .graphicsLayer { alpha = hint }
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.detail_trailer_full_screen), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

/** Largeur du cadre de la bande-annonce en bas de la fiche. */
private val TRAILER_CARD_WIDTH = 560.dp
