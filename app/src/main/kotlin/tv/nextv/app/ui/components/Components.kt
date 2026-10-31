package tv.nextv.app.ui.components

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.focusable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.app.ui.theme.NexTvIcons

// --- Disposition adaptative -------------------------------------------------------------------

/** Contexte d'affichage : TV (télécommande, vue de loin) ou écran tactile, étroit ou large. */
@Immutable
data class WindowInfo(val isTv: Boolean, val widthDp: Int) {
    /** Deux volets (groupes + grille) : TV et tablettes. */
    val isWide: Boolean get() = isTv || widthDp >= 840

    /** Marges latérales : zone de sécurité « overscan » sur TV. */
    val horizontalPadding: Dp get() = if (isTv) 48.dp else if (widthDp >= 600) 24.dp else 16.dp

    /**
     * Marge du haut des écrans. Sur TV, zone de sécurité « overscan » (27 dp) : beaucoup de
     * téléviseurs rognent les bords de l'image, et une Fire TV ne le compense pas.
     */
    val topPadding: Dp get() = if (isTv) 27.dp else 12.dp
}

@Composable
fun rememberWindowInfo(): WindowInfo {
    val configuration = LocalConfiguration.current
    val isTv = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    return remember(isTv, configuration.screenWidthDp) { WindowInfo(isTv, configuration.screenWidthDp) }
}

// --- Champs de saisie à la télécommande -------------------------------------------------------------

/**
 * Navigation à la télécommande : TV, appareil sans écran tactile, ou dernière action faite avec des
 * touches (une box qui se déclare tactile, comme un Raspberry Pi, pilotée à la télécommande). Le
 * clavier ne s'ouvre alors qu'avec OK, jamais en passant simplement sur un champ.
 */
@Composable
fun rememberKeyboardOnOkOnly(): Boolean {
    val context = LocalContext.current
    val isTv = rememberWindowInfo().isTv
    val noTouch = remember(isTv) { isTv || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    return noTouch || LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

/**
 * Champ de saisie à la télécommande : en naviguant, le focus s'arrête sur le champ sans l'activer
 * (il s'affiche sélectionné, le clavier reste fermé) ; OK l'active et ouvre le clavier. Au toucher,
 * le champ se comporte normalement.
 *
 * [field] reçoit le modificateur et la source d'interactions à donner au champ.
 */
@Composable
fun RemoteTextField(
    modifier: Modifier = Modifier,
    field: @Composable (fieldModifier: Modifier, interactionSource: MutableInteractionSource) -> Unit,
) {
    val remote = rememberKeyboardOnOkOnly()
    val interactionSource = remember { MutableInteractionSource() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var frameFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }

    // Cadre sélectionné : le champ prend l'apparence sélectionnée, sans recevoir le focus.
    LaunchedEffect(frameFocused) {
        if (!frameFocused) return@LaunchedEffect
        val focus = FocusInteraction.Focus()
        interactionSource.emit(focus)
        try {
            awaitCancellation()
        } finally {
            interactionSource.tryEmit(FocusInteraction.Unfocus(focus))
        }
    }
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }
    Box(
        modifier
            .onFocusChanged { frameFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!frameFocused || !isOk) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) editing = true
                true
            }
            .focusable(enabled = remote),
    ) {
        field(
            Modifier
                .onFocusChanged { state ->
                    // Le champ quitté (navigation vers un autre élément) redevient inactif.
                    if (fieldFocused && !state.isFocused) editing = false
                    fieldFocused = state.isFocused
                }
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = !remote || editing },
            interactionSource,
        )
    }
}

/** Options de clavier d'un champ : à la télécommande, pas d'ouverture automatique quand il reçoit le focus. */
@Composable
fun fieldKeyboardOptions(keyboardType: KeyboardType = KeyboardType.Text): KeyboardOptions =
    KeyboardOptions(keyboardType = keyboardType, showKeyboardOnFocus = !rememberKeyboardOnOkOnly())

/** À la télécommande, OK (centre de la croix ou Entrée) ouvre le clavier du champ qui a le focus. */
@Composable
fun Modifier.openKeyboardOnOk(): Modifier {
    if (!rememberKeyboardOnOkOnly()) return this
    val keyboard = LocalSoftwareKeyboardController.current
    return onPreviewKeyEvent { event ->
        val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (isOk && event.type == KeyEventType.KeyUp) keyboard?.show()
        isOk
    }
}

// --- Surfaces sensibles au focus (télécommande) ---------------------------------------------------

/**
 * Surface cliquable dont le focus est bien visible : agrandissement, contour et fond plus clair.
 * Indispensable à la navigation à la télécommande ; sans effet gênant au toucher.
 *
 * [onLongClick] répond à l'appui long au toucher comme à l'appui long sur OK de la télécommande ;
 * avec la télécommande, il est appelé au relâchement de la touche. Sinon la fenêtre qu'il ouvre
 * (fiche, confirmation) recevrait ce relâchement, et la surface, qui ne le verrait pas, ignorerait
 * le clic suivant.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    focusedColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
    focusScale: Float = 1.03f,
    enabled: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    if (onFocusChange != null) {
        LaunchedEffect(focused) { onFocusChange(focused) }
    }
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    // Appui long : action dès qu'il est reconnu, OK encore tenu ; le menu qu'elle ouvre ignore la fin de l'appui.
    val longClick: (() -> Unit)? = onLongClick?.let {
        {
            if (HeldOk.held) HeldOk.armed = true
            currentOnLongClick?.invoke()
        }
    }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focusScale")
    val border = when {
        focused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        selected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        else -> null
    }
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 8.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.key in OK_KEYS) {
                    when (event.type) {
                        KeyEventType.KeyDown -> HeldOk.held = true
                        KeyEventType.KeyUp -> {
                            HeldOk.held = false
                            HeldOk.armed = false
                        }
                    }
                }
                false
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onLongClick = longClick,
                onClick = onClick,
            ),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * Touche OK de la télécommande tenue. Un appui long agit sans attendre le relâchement ([armed]) :
 * le menu qu'il ouvre ignore la suite de cet appui (répétitions, relâchement), sans quoi elle
 * activerait son premier bouton.
 */
private object HeldOk {
    var held = false
    var armed = false
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** À placer sur le contenu d'une boîte de dialogue : ignore la fin de l'appui long qui l'a ouverte. */
fun Modifier.ignoreHeldOk(): Modifier = onPreviewKeyEvent { event ->
    if (event.key !in OK_KEYS || !HeldOk.armed) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyUp) {
        HeldOk.armed = false
        HeldOk.held = false
    }
    true
}

/** Bouton icône rond, utilisable au toucher comme à la télécommande. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = Color.Transparent,
        enabled = enabled,
        focusScale = 1.1f,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

enum class PillStyle { Primary, Secondary, Ghost }

/** Bouton « pilule » avec icône et libellé. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Secondary,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (style) {
        PillStyle.Primary -> colors.primary to colors.onPrimary
        PillStyle.Secondary -> colors.surfaceContainerHigh to colors.onSurface
        PillStyle.Ghost -> Color.Transparent to colors.onSurface
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = background,
        focusedColor = if (style == PillStyle.Primary) colors.primary else colors.surfaceContainerHighest,
        contentColor = foreground,
        enabled = enabled,
        focusScale = 1.06f,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

// --- Éléments d'information ----------------------------------------------------------------------

/** Logo de chaîne, avec monogramme coloré tant que l'image n'est pas chargée (ou en l'absence de logo). */
@Composable
fun ChannelLogo(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    monogramSize: Dp = 18.dp,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    val (start, end) = NexTvColors.swatchFor(name)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (loaded) {
                    Brush.linearGradient(listOf(Color(0xFF2A3546), Color(0xFF1C2532)))
                } else {
                    Brush.linearGradient(listOf(start.copy(alpha = 0.85f), end.copy(alpha = 0.85f)))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            val letters = remember(name) { monogram(name) }
            Text(
                letters,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                // Sigles de 3 ou 4 caractères (« BFM », « TV78 ») un peu plus petits pour tenir dans la pastille.
                fontSize = (monogramSize.value * if (letters.length <= 2) 1f else if (letters.length == 3) 0.85f else 0.7f).sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
    }
}

/**
 * Pastille d'une chaîne sans logo, d'après son nom sans préfixe de pays ni mention de qualité
 * (« FR| BFM NICE FHD » → « BFM ») : sigle court gardé tel quel (« TF1 », « M6 », « TV78 »),
 * sinon initiales (« Télé Nantes » → « TN », « France 2 » → « F2 »).
 */
fun monogram(name: String): String {
    val words = ContentClassifier.cleanTitle(name).split(MONOGRAM_SEPARATORS).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words[0]
    val second = words.getOrNull(1)
    val letters = when {
        first.length <= 3 -> first + second?.takeIf { it.all(Char::isDigit) && first.length + it.length <= 4 }.orEmpty()
        second == null -> if (first.length <= 4) first else first.take(2)
        second.all(Char::isDigit) -> first.take(1) + second.take(2)
        else -> "${first.first()}${second.first()}"
    }
    return letters.uppercase()
}

private val MONOGRAM_SEPARATORS = Regex("[\\s\\-_.|:]+")

/** Pastille d'état : point coloré + libellé. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** Petit badge texte (numéro de chaîne, « Limité »…). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/** Progression du programme en cours. */
@Composable
fun LiveProgress(startMillis: Long, stopMillis: Long, now: Long, modifier: Modifier = Modifier) {
    val fraction = if (stopMillis > startMillis) ((now - startMillis).toFloat() / (stopMillis - startMillis)).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(NexTvColors.Live),
        )
    }
}

/** Marque NexTV : pictogramme dégradé + nom. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, compact: Boolean = false, showName: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(if (compact) 30.dp else 36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Brush.linearGradient(listOf(NexTvColors.Primary, NexTvColors.Accent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NexTvIcons.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
        }
        if (showName) {
            Text(
                "NexTV",
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/**
 * En-tête d'écran : navigation, titre (et sous-titre), actions alignées à droite.
 * Remplace la barre d'application pour un rendu identique sur téléphone et TV.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        navigation?.invoke()
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** État vide ou d'erreur, centré. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(NexTvColors.Primary.copy(alpha = 0.25f), NexTvColors.Accent.copy(alpha = 0.25f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** Marges de contenu standard d'un écran. */
fun WindowInfo.contentPadding(top: Dp = 8.dp, bottom: Dp = 24.dp) =
    PaddingValues(start = horizontalPadding, end = horizontalPadding, top = top, bottom = bottom)

/**
 * Le focus est sur les onglets du haut de l'accueil (on y passe d'une section à l'autre) : une
 * section qui s'ouvre ne doit pas leur prendre le focus.
 */
val LocalTabsFocused = compositionLocalOf<() -> Boolean> { { false } }
