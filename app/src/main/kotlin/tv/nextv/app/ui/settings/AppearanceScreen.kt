package tv.nextv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.FadingRow
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.IconAction
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ScreenHeader
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.theme.Palette
import tv.nextv.app.ui.theme.PaletteCategory
import tv.nextv.app.ui.theme.ThemePreview
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.app.ui.theme.palette

/** Palette, palette précédente et interface enregistrées. */
class AppearanceViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val theme: StateFlow<ThemeStyle> = settings.themeStyle.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeStyle.DEFAULT)
    val previousTheme: StateFlow<ThemeStyle?> = settings.previousThemeStyle.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val layout: StateFlow<HomeLayout> = settings.homeLayout.stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.DEFAULT)
    val channelMosaic: StateFlow<Boolean> = settings.gridColumns.map { it.liveCenterMosaic }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val perRow: StateFlow<Int> = settings.gridColumns.map { it.perRow }.stateIn(viewModelScope, SharingStarted.Eagerly, GridColumns.DEFAULT_PER_ROW)

    fun setTheme(style: ThemeStyle) {
        viewModelScope.launch { settings.setThemeStyle(style) }
    }

    fun setLayout(layout: HomeLayout) {
        viewModelScope.launch { settings.setHomeLayout(layout) }
    }

    /** Live Center : chaînes en mosaïque ou en liste. */
    fun setChannelMosaic(mosaic: Boolean) {
        viewModelScope.launch { settings.setGridColumns(settings.gridColumns.first().copy(liveCenterMosaic = mosaic)) }
    }

    /** Live Center : vignettes (chaînes, films, séries) par ligne. */
    fun setPerRow(perRow: Int) {
        viewModelScope.launch { settings.setGridColumns(settings.gridColumns.first().copy(perRow = perRow.coerceIn(GridColumns.PER_ROW))) }
    }
}

/**
 * Réglages › Apparence : interface de l'accueil et palette de couleurs. Sélectionner une palette
 * l'applique aussitôt à tout l'écran (aperçu) ; OK la garde (enregistrée tout de suite).
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { container, _ -> AppearanceViewModel(container) }
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val previous by viewModel.previousTheme.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val channelMosaic by viewModel.channelMosaic.collectAsStateWithLifecycle()
    val perRow by viewModel.perRow.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    // En quittant l'écran, l'aperçu cède la place à la palette enregistrée.
    DisposableEffect(Unit) { onDispose { ThemePreview.style = null } }
    BackHandler {
        ThemePreview.style = null
        onBack()
    }
    val labels = ThemeStyle.entries.associateWith { themeLabel(it) }
    AppearanceContent(
        theme = theme,
        previous = previous,
        layout = layout,
        onBack = {
            ThemePreview.style = null
            onBack()
        },
        onTheme = { style ->
            viewModel.setTheme(style)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(resources.getString(R.string.appearance_saved, labels.getValue(style)))
            }
        },
        onLayout = viewModel::setLayout,
        channelMosaic = channelMosaic,
        onChannelMosaic = viewModel::setChannelMosaic,
        perRow = perRow,
        onPerRow = viewModel::setPerRow,
        snackbar = snackbar,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceContent(
    theme: ThemeStyle,
    previous: ThemeStyle?,
    layout: HomeLayout,
    onBack: () -> Unit,
    onTheme: (ThemeStyle) -> Unit,
    onLayout: (HomeLayout) -> Unit,
    channelMosaic: Boolean = false,
    onChannelMosaic: (Boolean) -> Unit = {},
    perRow: Int = GridColumns.DEFAULT_PER_ROW,
    onPerRow: (Int) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val window = rememberWindowInfo()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.appearance_title),
                modifier = Modifier.padding(start = window.horizontalPadding - 12.dp, end = window.horizontalPadding, top = window.topPadding, bottom = 8.dp),
                navigation = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = window.horizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Interfaces : trois cartes illustrées.
                Heading(stringResource(R.string.appearance_interface))
                if (window.isWide) {
                    // Écran large : les trois interfaces côte à côte.
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeLayout.entries.forEach { option ->
                            LayoutCard(option, selected = option == layout, onClick = { onLayout(option) }, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeLayout.entries.forEach { option ->
                            LayoutCard(option, selected = option == layout, onClick = { onLayout(option) }, modifier = Modifier.width(300.dp))
                        }
                    }
                }
                if (layout == HomeLayout.LIVE_CENTER) {
                    // Live Center : chaînes en liste (logo, nom, programme) ou en mosaïque de vignettes.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.appearance_channels), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
                        listOf(false to R.string.appearance_channels_list, true to R.string.appearance_channels_mosaic).forEach { (mosaic, label) ->
                            val selected = mosaic == channelMosaic
                            PillButton(
                                stringResource(label),
                                { onChannelMosaic(mosaic) },
                                icon = if (selected) Icons.Filled.Check else null,
                                style = if (selected) PillStyle.Primary else PillStyle.Secondary,
                                modifier = Modifier.resetPreviewOnFocus(),
                            )
                        }
                    }
                    // Vignettes par ligne (mosaïque des chaînes, films et séries) : exactement ce nombre à l'écran.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.appearance_per_row), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
                        GridColumns.PER_ROW.forEach { count ->
                            val selected = count == perRow
                            PillButton(
                                count.toString(),
                                { onPerRow(count) },
                                icon = if (selected) Icons.Filled.Check else null,
                                style = if (selected) PillStyle.Primary else PillStyle.Secondary,
                                modifier = Modifier.resetPreviewOnFocus(),
                            )
                        }
                    }
                }

                Heading(stringResource(R.string.appearance_palette))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        stringResource(R.string.appearance_default),
                        { onTheme(ThemeStyle.DEFAULT) },
                        icon = Icons.Filled.Refresh,
                        style = if (theme == ThemeStyle.DEFAULT) PillStyle.Secondary else PillStyle.Primary,
                        modifier = Modifier.resetPreviewOnFocus(),
                    )
                    if (previous != null && previous != theme) {
                        PillButton(
                            stringResource(R.string.appearance_previous, themeLabel(previous)),
                            { onTheme(previous) },
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            modifier = Modifier.resetPreviewOnFocus(),
                        )
                    }
                }
                // Toutes les palettes sur une seule ligne, qui défile de gauche à droite jusqu'aux bords de
                // l'écran ; le nom de chaque catégorie au-dessus de sa première palette. Ouverte sur la palette choisie.
                val palettes = remember { PaletteCategory.entries.flatMap { category -> ThemeStyle.entries.filter { it.category == category } } }
                // Fondu sur les bords où il reste des palettes : la ligne se devine au-delà de l'écran.
                val paletteRow = rememberLazyListState(initialFirstVisibleItemIndex = (palettes.indexOf(theme) - 1).coerceAtLeast(0))
                FadingRow(paletteRow) { fade ->
                    LazyRow(
                        // Place en haut et en bas pour l'ombre et le zoom de la palette choisie (le fondu ne les coupe pas).
                        Modifier.bleed(window.horizontalPadding, vertical = SHADOW_ROOM).then(fade),
                        state = paletteRow,
                        contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 8.dp + SHADOW_ROOM),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(palettes, key = { _, style -> style.name }) { index, style ->
                            val first = index == 0 || palettes[index - 1].category != style.category
                            Column(
                                Modifier.padding(start = if (first && index > 0) CATEGORY_GAP else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    if (first) categoryLabel(style.category).uppercase() else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    maxLines = 1,
                                )
                                PaletteCard(style, current = style == theme, onClick = { onTheme(style) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

/**
 * Déborde de [edge] de chaque côté : une rangée qui défile va jusqu'aux bords de l'écran, malgré la
 * marge de la page ; et de [vertical] en haut et en bas, sans changer la place qu'elle occupe.
 */
private fun Modifier.bleed(edge: Dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
    val side = edge.roundToPx()
    val extra = vertical.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * extra else constraints.maxHeight
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side, maxHeight = maxHeight),
    )
    layout(constraints.maxWidth, (placeable.height - 2 * extra).coerceAtLeast(0)) { placeable.place(-side, -extra) }
}

/** Place laissée autour de la rangée des palettes pour l'ombre de la palette choisie. */
private val SHADOW_ROOM = 12.dp

/** Écart supplémentaire entre deux catégories de palettes sur la ligne. */
private val CATEGORY_GAP = 18.dp

/** Sur ce bouton, l'aperçu revient à la palette enregistrée. */
private fun Modifier.resetPreviewOnFocus(): Modifier = onFocusChanged { if (it.isFocused) ThemePreview.style = null }

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

/**
 * Carte d'une palette, dessinée avec ses propres couleurs (elle reste identique pendant l'aperçu
 * des autres) : fond, panneau, carte sélectionnée, bouton principal, bouton secondaire et textes.
 */
@Composable
internal fun PaletteCard(style: ThemeStyle, current: Boolean, onClick: () -> Unit) {
    val palette = remember(style) { style.palette() }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .width(196.dp)
            .onFocusChanged { if (it.isFocused) ThemePreview.style = style },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        selected = current,
        focusScale = 1.05f,
    ) {
        Column {
            PaletteSample(palette, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Swatch(palette.primary)
                Swatch(palette.secondary)
                Swatch(palette.accent)
                Text(
                    themeLabel(style),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (current) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.appearance_current), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(color).border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape))
}

/** Interface miniature aux couleurs de la palette. */
@Composable
private fun PaletteSample(palette: Palette, modifier: Modifier) {
    Box(modifier.background(palette.background).padding(10.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Colonne des catégories, l'une choisie.
            Column(
                Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(palette.surfaceContainerLow).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Line(palette.textMuted, 0.8f)
                Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(3.dp)).background(palette.primaryContainer))
                Line(palette.textMuted, 0.6f)
                Line(palette.textMuted, 0.7f)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.appearance_sample_title), color = palette.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Carte sélectionnée (contour de l'accent) et carte ordinaire.
                    Box(
                        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(5.dp)).background(palette.surfaceContainerHighest)
                            .border(1.5.dp, palette.primary, RoundedCornerShape(5.dp)),
                    )
                    Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(5.dp)).background(palette.surfaceContainer))
                }
                Line(palette.textSecondary, 0.9f)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(palette.primary, palette.onPrimary, stringResource(R.string.appearance_sample_button))
                    Box(Modifier.size(width = 22.dp, height = 12.dp).clip(CircleShape).background(palette.secondaryContainer)) {
                        Box(Modifier.align(Alignment.Center).size(5.dp).clip(CircleShape).background(palette.secondary))
                    }
                    Box(Modifier.size(5.dp).clip(CircleShape).background(palette.live))
                }
            }
        }
    }
}

@Composable
private fun Line(color: Color, fraction: Float) {
    Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(color.copy(alpha = 0.7f)))
}

@Composable
private fun Pill(background: Color, content: Color, text: String) {
    Box(Modifier.clip(CircleShape).background(background).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, color = content, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Interface de l'accueil : schéma, nom et description. */
@Composable
internal fun LayoutCard(layout: HomeLayout, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, lines: Int = 4) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.resetPreviewOnFocus(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        selected = selected,
        focusScale = 1.04f,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LayoutSketch(layout, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.background))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(homeLayoutLabel(layout), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.appearance_current), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                homeLayoutHelp(layout),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = lines,
                maxLines = lines,
            )
        }
    }
}

/** Schéma de chaque interface, aux couleurs du thème affiché. */
@Composable
private fun LayoutSketch(layout: HomeLayout, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.padding(8.dp)) {
        when (layout) {
            HomeLayout.LIVE_CENTER -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(0.22f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(colors.surfaceContainerLow).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(6) { index -> Block(if (index == 1) colors.primaryContainer else colors.surfaceContainerHigh, height = 7) }
                }
                Column(Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { index -> Block(if (index == 2) colors.surfaceContainerHighest else colors.surfaceContainer, height = 9, outline = if (index == 2) colors.primary else null) }
                }
                Column(Modifier.weight(0.38f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color.Black)) {
                        Box(Modifier.align(Alignment.Center).size(10.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.8f)))
                    }
                    Block(colors.onSurface.copy(alpha = 0.5f), height = 4, fraction = 0.8f)
                    Block(colors.secondary, height = 3, fraction = 0.6f)
                    Block(colors.onSurfaceVariant.copy(alpha = 0.5f), height = 3, fraction = 0.9f)
                }
            }
            HomeLayout.STREAMING -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    Modifier.fillMaxWidth().weight(0.42f).clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(listOf(colors.surfaceContainerHigh, colors.primary.copy(alpha = 0.55f)))),
                ) {
                    Column(Modifier.align(Alignment.CenterStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Block(colors.onSurface.copy(alpha = 0.8f), height = 5, width = 50)
                        Block(colors.primary, height = 5, width = 22)
                    }
                }
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth().weight(0.29f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { index ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                                    .background(if (row == 0 && index == 1) colors.surfaceContainerHighest else colors.surfaceContainer)
                                    .then(if (row == 0 && index == 1) Modifier.border(1.dp, colors.primary, RoundedCornerShape(3.dp)) else Modifier),
                            )
                        }
                    }
                }
            }
            HomeLayout.PREMIUM -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.35f), colors.background, colors.secondary.copy(alpha = 0.25f)))),
            ) {
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Block(colors.onSurface.copy(alpha = 0.9f), height = 8, width = 70)
                    Block(colors.onSurfaceVariant.copy(alpha = 0.7f), height = 3, width = 90)
                    Block(colors.onSurfaceVariant.copy(alpha = 0.7f), height = 3, width = 60)
                }
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { index ->
                        Box(
                            Modifier.size(width = 38.dp, height = 24.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (index == 0) colors.surfaceContainerHighest else colors.surfaceContainer.copy(alpha = 0.8f))
                                .then(if (index == 0) Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.8f), RoundedCornerShape(4.dp)) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(color: Color, height: Int, fraction: Float = 1f, width: Int? = null, outline: Color? = null) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        (if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth(fraction))
            .height(height.dp)
            .clip(shape)
            .background(color)
            .then(if (outline != null) Modifier.border(1.dp, outline, shape) else Modifier),
    )
}

@Composable
fun themeLabel(style: ThemeStyle): String = stringResource(
    when (style) {
        ThemeStyle.NIGHT -> R.string.theme_night
        ThemeStyle.BLACK -> R.string.theme_black
        ThemeStyle.CINEMA -> R.string.theme_cinema
        ThemeStyle.VIOLET -> R.string.theme_violet
        ThemeStyle.IVORY -> R.string.theme_ivory
        ThemeStyle.GLACIER -> R.string.theme_glacier
        ThemeStyle.POWDER -> R.string.theme_powder
        ThemeStyle.LAVENDER -> R.string.theme_lavender
        ThemeStyle.MINT -> R.string.theme_mint
        ThemeStyle.SAGE -> R.string.theme_sage
        ThemeStyle.PEACH -> R.string.theme_peach
        ThemeStyle.VANILLA -> R.string.theme_vanilla
        ThemeStyle.MIDNIGHT -> R.string.theme_midnight
        ThemeStyle.BORDEAUX -> R.string.theme_bordeaux
        ThemeStyle.SLATE -> R.string.theme_slate
        ThemeStyle.EMERALD -> R.string.theme_emerald
        ThemeStyle.TURQUOISE -> R.string.theme_turquoise
        ThemeStyle.CORAL -> R.string.theme_coral
        ThemeStyle.AMBER -> R.string.theme_amber
    },
)

@Composable
internal fun categoryLabel(category: PaletteCategory): String = stringResource(
    when (category) {
        PaletteCategory.CLASSIC -> R.string.palette_classic
        PaletteCategory.PASTEL -> R.string.palette_pastel
        PaletteCategory.DARK -> R.string.palette_dark
        PaletteCategory.COLORFUL -> R.string.palette_colorful
    },
)

@Composable
fun homeLayoutLabel(layout: HomeLayout): String = stringResource(
    when (layout) {
        HomeLayout.LIVE_CENTER -> R.string.home_layout_live
        HomeLayout.STREAMING -> R.string.home_layout_streaming
        HomeLayout.PREMIUM -> R.string.home_layout_premium
    },
)

@Composable
fun homeLayoutHelp(layout: HomeLayout): String = stringResource(
    when (layout) {
        HomeLayout.LIVE_CENTER -> R.string.home_layout_live_help
        HomeLayout.STREAMING -> R.string.home_layout_streaming_help
        HomeLayout.PREMIUM -> R.string.home_layout_premium_help
    },
)
