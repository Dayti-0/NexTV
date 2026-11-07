package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * Volet des catégories des interfaces à rangées (Streaming, Cinéma Premium) : ← depuis le bord gauche
 * des rangées l'ouvre avec toutes les catégories et leur nombre ; OK ouvre une catégorie, → ou
 * « Retour » referme le volet et rend le focus là où il était.
 * [rail] : la colonne des catégories ; [content] : les rangées.
 */
@Composable
internal fun CategoryDrawer(
    rail: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val close = {
        open = false
        runCatching { contentFocus.requestFocus() }
    }
    BackHandler(enabled = open) { close() }
    LaunchedEffect(open) {
        if (open) runCatching { railFocus.requestFocus() }
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer()
                // ← sans élément plus à gauche (bord des rangées) : le volet s'ouvre. Un champ de saisie
                // garde ses flèches (il consomme la touche avant).
                .onKeyEvent { event ->
                    if (event.key != Key.DirectionLeft || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (!focusManager.moveFocus(FocusDirection.Left)) open = true
                    true
                },
        ) { content() }
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }
        AnimatedVisibility(open, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
            Box(
                Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionRight || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        close()
                        true
                    }
                    .focusRequester(railFocus)
                    .focusGroup(),
            ) {
                rail(Modifier.fillMaxSize())
            }
        }
    }
}
