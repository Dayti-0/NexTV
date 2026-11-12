package tv.nextv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.AppContainer
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.BrandMark
import tv.nextv.app.ui.components.FadingBottom
import tv.nextv.app.ui.components.FadingRow
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.editor.ToggleRow
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.home.LayoutDemo
import tv.nextv.app.ui.theme.PaletteCategory
import tv.nextv.app.ui.theme.ThemePreview
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.stream.QualityTarget

/** Étapes de la configuration rapide. */
enum class QuickSetupStep { WELCOME, INTERFACE, COLORS, PLAYBACK, DONE }

/** Choix de la configuration rapide, enregistrés aussitôt (comme dans les réglages). */
class QuickSetupViewModel(container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val layout: StateFlow<HomeLayout> = settings.homeLayout.stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.DEFAULT)
    val theme: StateFlow<ThemeStyle> = settings.themeStyle.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeStyle.DEFAULT)
    val quality: StateFlow<QualityTarget> = settings.qualityTarget.stateIn(viewModelScope, SharingStarted.Eagerly, QualityTarget.BEST)
    val preview: StateFlow<Boolean> = settings.channelPreview.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val trailer: StateFlow<Boolean> = settings.trailerAutoplay.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastChannel: StateFlow<Boolean> = settings.startOnLastChannel.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Proposée une seule fois : dès qu'elle s'ouvre, même si elle est quittée en route.
        viewModelScope.launch { settings.setQuickSetupDone() }
    }

    fun setLayout(value: HomeLayout) = save { settings.setHomeLayout(value) }
    fun setTheme(value: ThemeStyle) = save { settings.setThemeStyle(value) }
    fun setQuality(value: QualityTarget) = save { settings.setQualityTarget(value) }
    fun setPreview(value: Boolean) = save { settings.setChannelPreview(value) }
    fun setTrailer(value: Boolean) = save { settings.setTrailerAutoplay(value) }
    fun setLastChannel(value: Boolean) = save { settings.setStartOnLastChannel(value) }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * Configuration rapide : à la première ouverture de l'application (une seule fois), puis depuis les
 * réglages. Interface de l'accueil, couleurs et lecture, une étape par écran.
 */
@Composable
fun QuickSetupScreen(onDone: () -> Unit) {
    val viewModel = appViewModel { container, _ -> QuickSetupViewModel(container) }
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val trailer by viewModel.trailer.collectAsStateWithLifecycle()
    val lastChannel by viewModel.lastChannel.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(QuickSetupStep.WELCOME) }
    QuickSetupContent(
        step = step,
        onStep = { step = it },
        onDone = onDone,
        layout = layout,
        onLayout = viewModel::setLayout,
        theme = theme,
        onTheme = viewModel::setTheme,
        quality = quality,
        onQuality = viewModel::setQuality,
        preview = preview,
        onPreview = viewModel::setPreview,
        trailer = trailer,
        onTrailer = viewModel::setTrailer,
        lastChannel = lastChannel,
        onLastChannel = viewModel::setLastChannel,
    )
}

@Composable
fun QuickSetupContent(
    step: QuickSetupStep,
    onStep: (QuickSetupStep) -> Unit,
    onDone: () -> Unit,
    layout: HomeLayout,
    onLayout: (HomeLayout) -> Unit,
    theme: ThemeStyle,
    onTheme: (ThemeStyle) -> Unit,
    quality: QualityTarget,
    onQuality: (QualityTarget) -> Unit,
    preview: Boolean,
    onPreview: (Boolean) -> Unit,
    trailer: Boolean,
    onTrailer: (Boolean) -> Unit,
    lastChannel: Boolean,
    onLastChannel: (Boolean) -> Unit,
) {
    val window = rememberWindowInfo()
    val steps = QuickSetupStep.entries
    val index = steps.indexOf(step)
    fun previous() = if (index == 0) onDone() else onStep(steps[index - 1])
    fun next() = if (index == steps.lastIndex) onDone() else onStep(steps[index + 1])
    // L'aperçu d'une palette ne survit pas à l'étape des couleurs.
    LaunchedEffect(step) { if (step != QuickSetupStep.COLORS) ThemePreview.style = null }
    DisposableEffect(Unit) { onDispose { ThemePreview.style = null } }
    BackHandler { previous() }
    // Chaque étape s'ouvre sur son choix principal (ou sur « Suivant »).
    val main = remember(step) { FocusRequester() }
    // Choix fait (OK sur une interface, une palette, une qualité) : la sélection passe à « Suivant ».
    val nextFocus = remember { FocusRequester() }
    val toNext = { runCatching { nextFocus.requestFocus() } }
    // Interface en démonstration (OK sur sa carte) : le vrai accueil, avec un contenu inventé, en plein écran.
    var demo by rememberSaveable { mutableStateOf<HomeLayout?>(null) }
    LaunchedEffect(step, demo) { if (demo == null) runCatching { main.requestFocus() } }
    demo?.let { tried ->
        LayoutDemo(
            layout = tried,
            onKeep = {
                onLayout(tried)
                demo = null
                onStep(steps[index + 1])
            },
            onClose = { demo = null },
        )
        return
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (window.isWide) {
            // Les étapes, à gauche : celle en cours en avant, les précédentes cochées.
            Column(
                Modifier.width(280.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = window.horizontalPadding - 8.dp, end = 20.dp, top = window.topPadding + 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrandMark()
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.quick_setup_title).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                steps.forEachIndexed { i, s -> StepRow(i + 1, stepLabel(s), done = i < index, current = i == index) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .padding(start = if (window.isWide) 40.dp else window.horizontalPadding, end = window.horizontalPadding, top = window.topPadding + 8.dp, bottom = 20.dp),
        ) {
            Text(
                stringResource(R.string.quick_setup_step_count, index + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            FadingBottom({ scroll.canScrollForward }, { scroll.canScrollBackward }, ends = scroll) { fade ->
            Column(
                // Marge tout autour : la carte choisie, agrandie et encadrée, n'est pas coupée aux bords.
                Modifier.fillMaxSize().then(fade).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    QuickSetupStep.WELCOME -> StepTitle(R.string.quick_setup_welcome_title, R.string.quick_setup_welcome_text)
                    QuickSetupStep.INTERFACE -> {
                        StepTitle(R.string.quick_setup_interface_title, R.string.quick_setup_interface_text)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                            HomeLayout.entries.forEach { option ->
                                LayoutCard(
                                    option,
                                    selected = option == layout,
                                    onClick = { demo = option },
                                    modifier = Modifier.weight(1f).then(if (option == layout) Modifier.focusRequester(main) else Modifier),
                                    lines = 6,
                                )
                            }
                        }
                    }
                    QuickSetupStep.COLORS -> {
                        StepTitle(R.string.quick_setup_colors_title, R.string.quick_setup_colors_text)
                        val palettes = remember { PaletteCategory.entries.flatMap { category -> ThemeStyle.entries.filter { it.category == category } } }
                        val row = rememberLazyListState(initialFirstVisibleItemIndex = (palettes.indexOf(theme) - 1).coerceAtLeast(0))
                        FadingRow(row) { fade ->
                        LazyRow(
                            modifier = fade,
                            state = row,
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(palettes, key = { _, style -> style.name }) { i, style ->
                                val first = i == 0 || palettes[i - 1].category != style.category
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        if (first) categoryLabel(style.category).uppercase() else "",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    Box(if (style == theme) Modifier.focusRequester(main) else Modifier) {
                                        PaletteCard(style, current = style == theme, onClick = { onTheme(style); toNext() })
                                    }
                                }
                            }
                        }
                        }
                    }
                    QuickSetupStep.PLAYBACK -> {
                        StepTitle(R.string.quick_setup_playback_title, R.string.quick_setup_playback_text)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QualityTarget.entries.forEach { target ->
                                QualityTile(
                                    target,
                                    selected = target == quality,
                                    onClick = { onQuality(target); toNext() },
                                    modifier = Modifier.weight(1f).then(if (target == quality) Modifier.focusRequester(main) else Modifier),
                                )
                            }
                        }
                        Column(Modifier.widthIn(max = 760.dp)) {
                            ToggleRow(stringResource(R.string.settings_channel_preview), stringResource(R.string.settings_channel_preview_help), preview, onPreview)
                            ToggleRow(stringResource(R.string.settings_trailer_autoplay), stringResource(R.string.settings_trailer_autoplay_help), trailer, onTrailer)
                            ToggleRow(stringResource(R.string.settings_start_last_channel), stringResource(R.string.settings_start_last_channel_help), lastChannel, onLastChannel)
                        }
                    }
                    QuickSetupStep.DONE -> {
                        StepTitle(R.string.quick_setup_done_title, R.string.quick_setup_done_text)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            SummaryLine(stringResource(R.string.quick_setup_summary_interface, homeLayoutLabel(layout)))
                            SummaryLine(stringResource(R.string.quick_setup_summary_colors, themeLabel(theme)))
                            SummaryLine(stringResource(R.string.quick_setup_summary_quality, qualityTargetLabel(quality)))
                        }
                    }
                }
            }
            }
            }
            // Boutons du bas : précédent (ou passer), suivant (ou terminer).
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    stringResource(if (index == 0) R.string.quick_setup_skip else R.string.quick_setup_previous),
                    { previous() },
                    icon = if (index == 0) null else Icons.AutoMirrored.Filled.ArrowBack,
                    style = PillStyle.Ghost,
                )
                Spacer(Modifier.weight(1f))
                val last = index == steps.lastIndex
                val mainButton = step == QuickSetupStep.WELCOME || last
                PillButton(
                    stringResource(
                        when {
                            index == 0 -> R.string.quick_setup_start
                            last -> R.string.quick_setup_finish
                            else -> R.string.quick_setup_next
                        },
                    ),
                    { next() },
                    icon = if (last) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(nextFocus).then(if (mainButton) Modifier.focusRequester(main) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun stepLabel(step: QuickSetupStep): String = stringResource(
    when (step) {
        QuickSetupStep.WELCOME -> R.string.quick_setup_step_welcome
        QuickSetupStep.INTERFACE -> R.string.quick_setup_step_interface
        QuickSetupStep.COLORS -> R.string.quick_setup_step_colors
        QuickSetupStep.PLAYBACK -> R.string.quick_setup_step_playback
        QuickSetupStep.DONE -> R.string.quick_setup_step_done
    },
)

@Composable
private fun StepRow(number: Int, label: String, done: Boolean, current: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(if (current) colors.surfaceContainerHigh else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (current || done) colors.primary else colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            } else {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = if (current) colors.onPrimary else colors.onSurfaceVariant)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepTitle(title: Int, text: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 720.dp))
    }
}

@Composable
private fun SummaryLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Qualité visée : nom et explication. */
@Composable
private fun QualityTile(target: QualityTarget, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        selected = selected,
        focusScale = 1.03f,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(qualityTargetLabel(target), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                qualityTargetHelp(target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 4,
                maxLines = 6,
            )
        }
    }
}
