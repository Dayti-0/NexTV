package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.nextv.app.R

/** Largeur du volet des catégories. */
private val DRAWER_WIDTH = 320.dp

/**
 * Volet des catégories des interfaces à rangées (Streaming, Cinéma Premium) : ← depuis le bord gauche
 * des rangées l'ouvre avec toutes les catégories et leur nombre ; OK ouvre une catégorie, → ou
 * « Retour » referme le volet et rend le focus là où il était.
 * Tant qu'il est ouvert, le focus ne peut pas en sortir (ni vers les affiches derrière, ni vers le menu) ;
 * et si le focus arrive malgré tout sur les rangées, le volet se referme : jamais ouvert sans le focus.
 * [rail] : la colonne des catégories ; [content] : les rangées.
 */
@Composable
internal fun CategoryDrawer(
    rail: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    /** Ouverture du volet, partagée avec un bouton « Catégories » (menu de gauche de Streaming). */
    openState: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    /** Focus de départ dans le contenu (premier affichage sur la télé), au lieu du menu. */
    initialFocus: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by openState
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    LaunchedEffect(open) {
        if (open) {
            // La colonne peut ne pas avoir encore placé ses catégories à la première image : on réessaie
            // quelques images, sinon le focus restait derrière (sur le menu ou les affiches).
            repeat(10) {
                if (railFocused || !open) return@LaunchedEffect
                runCatching { railFocus.requestFocus() }
                withFrameNanos { }
            }
            // Le focus n'a pas pu y entrer : on ne laisse pas un volet ouvert sans focus.
            if (!railFocused) open = false
        } else if (railFocused) {
            // Fermeture (→, Retour, catégorie choisie) : le focus retourne sur les rangées.
            runCatching { contentFocus.requestFocus() }
        }
    }
    // Volet quitté (autre catégorie, autre section) : il ne doit pas se rouvrir tout seul au retour.
    DisposableEffect(Unit) { onDispose { open = false } }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    if (initialFocus && !initialFocusDone) {
        LaunchedEffect(Unit) {
            // Les affiches arrivent parfois quelques images plus tard (catégorie qui se charge) : on réessaie.
            repeat(30) {
                if (open || contentFocused) return@repeat
                runCatching { contentFocus.requestFocus() }
                withFrameNanos { }
            }
            initialFocusDone = true
        }
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer()
                // Le focus est sur les rangées alors que le volet est ouvert : on le referme.
                .onFocusChanged {
                    contentFocused = it.hasFocus
                    if (it.hasFocus && open && !railFocused) open = false
                }
                // ← sans élément plus à gauche (bord des rangées) : le volet s'ouvre. Un champ de saisie
                // garde ses flèches (il consomme la touche avant).
                .onKeyEvent { event ->
                    if (event.key != Key.DirectionLeft || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (!focusManager.moveFocus(FocusDirection.Left)) open = true
                    true
                },
        ) { content() }
        // Voile sur les rangées, plus léger vers la droite : elles restent visibles derrière.
        AnimatedVisibility(open, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.6f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black.copy(alpha = 0.15f)),
                ),
            )
        }
        AnimatedVisibility(
            open,
            enter = slideInHorizontally(tween(220)) { -it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(180)) { -it / 3 } + fadeOut(tween(180)),
        ) {
            val colors = MaterialTheme.colorScheme
            Column(
                Modifier
                    .width(DRAWER_WIDTH)
                    .fillMaxHeight()
                    // Fond translucide (verre teinté) : on devine l'affiche et les rangées derrière.
                    .background(
                        Brush.horizontalGradient(
                            0f to colors.surfaceContainerLow.copy(alpha = 0.86f),
                            0.75f to colors.surfaceContainerLow.copy(alpha = 0.74f),
                            1f to colors.surfaceContainerLow.copy(alpha = 0.55f),
                        ),
                    )
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionRight || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        open = false
                        true
                    }
                    .onFocusChanged { railFocused = it.hasFocus }
                    // Ouvert, le focus reste dans le volet : ↑ en haut, ↓ en bas ou ← ne le font plus sortir.
                    .focusProperties { onExit = { if (open) cancelFocusChange() } }
                    .focusRequester(railFocus)
                    .focusGroup(),
            ) {
                Text(
                    stringResource(R.string.nav_categories).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier.align(Alignment.Start).padding(start = 22.dp, top = 18.dp, bottom = 2.dp),
                )
                rail(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
