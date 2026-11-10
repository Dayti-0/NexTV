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
 * Volet des catégories des interfaces à rangées (Streaming, Classique) : ← depuis le bord gauche
 * des rangées l'ouvre avec toutes les catégories et leur nombre ; OK ouvre une catégorie, → ou
 * « Retour » referme le volet et rend le focus là où il était.
 * Tant qu'il est ouvert, le focus ne peut pas en sortir (ni vers les affiches derrière, ni vers le menu) ;
 * et si le focus arrive malgré tout sur les rangées, le volet se referme : jamais ouvert sans le focus.
 * [rail] : la colonne des catégories ; [content] : les rangées.
 */
@Composable
internal fun CategoryDrawer(
    /** Colonne des catégories, et de quoi poser la sélection sur la catégorie choisie à l'ouverture. */
    rail: @Composable (Modifier, FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    /** Ouverture du volet, partagée avec un bouton « Catégories » (menu de gauche de Streaming). */
    openState: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    /** Focus de départ dans le contenu (premier affichage sur la télé), au lieu du menu. */
    initialFocus: Boolean = false,
    /** ← dans le volet : il se ferme et la sélection va au menu de gauche (`null` : retour aux rangées). */
    onExitLeft: (() -> Unit)? = null,
    /**
     * ← au bord gauche du contenu : le volet s'ouvre, même si un menu est à gauche (Streaming : ← ouvre
     * d'abord les catégories, un second ← le menu).
     */
    leftOpensDrawer: Boolean = false,
    /**
     * Catégorie choisie dans le volet (sa liste remplace l'ancienne) : rend `true` une fois, et le volet ne pose
     * pas la sélection sur le contenu à la fermeture (la nouvelle liste la prend elle-même une fois affichée).
     */
    consumePick: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var open by openState
    val focusManager = LocalFocusManager.current
    val rowJump = LocalRowJump.current
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    LaunchedEffect(open) {
        if (open) {
            // La colonne peut ne pas avoir encore placé ses catégories à la première image : on réessaie
            // quelques images, sinon le focus restait derrière (sur le menu ou les affiches).
            repeat(10) {
                if (railFocused || !open) return@LaunchedEffect
                // Sur la catégorie choisie, sinon en haut de la colonne.
                if (runCatching { selectedFocus.requestFocus() }.getOrDefault(false) != true) runCatching { railFocus.requestFocus() }
                withFrameNanos { }
            }
            // Le focus n'a pas pu y entrer : on ne laisse pas un volet ouvert sans focus.
            if (!railFocused) open = false
        } else if (railFocused && !consumePick()) {
            // Fermeture (→, Retour, catégorie choisie) : le focus retourne sur le contenu. Saut à une rangée en
            // cours : c'est lui qui pose la sélection. Nouvelle catégorie : sa liste peut arriver quelques images
            // plus tard (sinon la sélection se perdait, puis réapparaissait dans le menu).
            // La liste de la catégorie choisie peut remplacer l'ancienne juste après : on surveille quelques images.
            var settled = 0
            for (attempt in 1..90) {
                if (rowJump?.target != null || open) break
                if (contentFocused) {
                    if (++settled >= 15) break
                } else {
                    settled = 0
                    runCatching { contentFocus.requestFocus() }
                }
                withFrameNanos { }
            }
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
                    val moved = focusManager.moveFocus(FocusDirection.Left)
                    if (!moved) {
                        // Rien à gauche : le volet s'ouvre.
                        open = true
                    } else if (leftOpensDrawer && !contentFocused) {
                        // La sélection est passée au menu de gauche : elle revient aussitôt (avant que l'écran ne
                        // soit redessiné, le menu ne s'ouvre pas), et c'est le volet qui s'ouvre.
                        runCatching { contentFocus.requestFocus() }
                        open = true
                    }
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
                    // → : retour aux rangées ; ← : le menu de gauche (ou les rangées, sans menu). Le volet se ferme.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> open = false
                            Key.DirectionLeft -> {
                                open = false
                                onExitLeft?.invoke()
                            }
                            else -> return@onPreviewKeyEvent false
                        }
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
                rail(Modifier.fillMaxWidth().weight(1f), selectedFocus)
            }
        }
    }
}
