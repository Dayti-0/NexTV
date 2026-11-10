package tv.nextv.app.ui.guide

import tv.nextv.app.ui.common.frenchText
import tv.nextv.app.ui.components.FadingBottom
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import tv.nextv.app.ui.home.HomeVisit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import tv.nextv.app.ui.components.bottomFade
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import kotlin.math.abs
import tv.nextv.app.R
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.LocalTabsFocused
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.source.Catchup
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Guide TV de la playlist active (section de l'accueil). [onReplay] : programme passé d'une chaîne
 * avec replay (chaîne, début, fin).
 */
@Composable
fun GuideScreen(
    sourceId: Long,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onReplay: (Long, Long, Long) -> Unit = { _, _, _ -> },
    /** Chaîne ouverte depuis la catégorie « Favoris » : le lecteur reste dans les favoris. */
    onPlayFavorite: (Long) -> Unit = onPlay,
) {
    val context = LocalContext.current
    val viewModel = appViewModel(key = "guide-$sourceId") { container, _ -> GuideViewModel(container, sourceId, context.resources) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val tonight by viewModel.tonight.collectAsStateWithLifecycle()
    GuideContent(
        state = state,
        groups = groups,
        selectedGroup = selectedGroup,
        onSelectGroup = viewModel::selectGroup,
        tonight = tonight,
        onTonightDay = { viewModel.tonightDay.value = it },
        onPlay = { id ->
            val favorites = selectedGroup == GuideViewModel.FAVORITES || selectedGroup == GuideViewModel.FAVORITES_TONIGHT
            if (favorites) onPlayFavorite(id) else onPlay(id)
        },
        onReplay = { channel, programme -> onReplay(channel.id, programme.startMillis, programme.stopMillis) },
        modifier = modifier,
        describe = viewModel::describe,
    )
}

/**
 * [pair] avec la description de son programme, lue à la demande par [describe] (la grille ne charge pas les
 * descriptions de ses milliers de programmes). Le programme s'affiche tout de suite, sa description le rejoint.
 */
@Composable
private fun described(
    pair: Pair<GuideChannel, GuideProgramme>?,
    describe: suspend (GuideProgramme) -> GuideProgramme,
): Pair<GuideChannel, GuideProgramme>? {
    val full by produceState(pair, pair) { value = pair?.let { it.first to describe(it.second) } }
    val read = full ?: return pair
    // Autre programme sélectionné entre-temps : pas la description du précédent.
    val current = pair ?: return null
    val same = read.first.id == current.first.id && read.second.id == current.second.id &&
        read.second.startMillis == current.second.startMillis && read.second.title == current.second.title
    return if (same) read else current
}

/**
 * Grille des programmes : chaînes à gauche, programmes sur l'axe du temps (défilement commun).
 * Haut/bas : chaînes ; gauche/droite : horaires (jusqu'à la veille) ; OK sur un programme : fiche
 * détaillée, avec « Revoir » pour les programmes passés des chaînes avec replay.
 * Seules les informations du guide sont affichées : une période sans programme reste vide.
 */
@Composable
fun GuideContent(
    state: GuideUiState,
    groups: List<GroupRow>,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onReplay: (GuideChannel, GuideProgramme) -> Unit = { _, _ -> },
    initialDetails: Pair<GuideChannel, GuideProgramme>? = null,
    /** Programme du soir (catégorie « Ce soir »). */
    tonight: TonightUiState = TonightUiState(),
    onTonightDay: (Int) -> Unit = {},
    /** Programme de la grille avec sa description (lue à la demande). */
    describe: suspend (GuideProgramme) -> GuideProgramme = { it },
) {
    val window = rememberWindowInfo()
    val minuteWidth = if (window.isWide) 4.6.dp else 3.2.dp
    val channelColumnWidth = if (window.isWide) 230.dp else 112.dp
    val rowHeight = if (window.isWide) 72.dp else 64.dp
    val scroll = rememberScrollState()
    var focused by remember { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
    var details by remember { mutableStateOf(initialDetails) }
    val highlighted = focused ?: state.channels.firstNotNullOfOrNull { channel ->
        channel.programmes.firstOrNull { it.isLive(state.now) }?.let { channel to it }
    }

    val favorites = selectedGroup == GuideViewModel.FAVORITES || selectedGroup == GuideViewModel.FAVORITES_TONIGHT
    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Favoris : en grille ou en « Ce soir » (choix juste en dessous), le mode choisi est gardé.
            item {
                GuideGroupChip(stringResource(R.string.home_favorites), favorites, star = true) {
                    if (!favorites) onSelectGroup(GuideViewModel.FAVORITES)
                }
            }
            // « Ce soir » : une ligne par chaîne, TNT d'abord (sans les doublons SD, HD, FHD).
            item { GuideGroupChip(stringResource(R.string.guide_tonight), selectedGroup == GuideViewModel.TONIGHT) { onSelectGroup(GuideViewModel.TONIGHT) } }
            item { GuideGroupChip(stringResource(R.string.channels_all_short), selectedGroup == null) { onSelectGroup(null) } }
            items(groups, key = { it.title ?: "\u0000" }) { group ->
                GuideGroupChip(group.title ?: stringResource(R.string.channels_no_group), selectedGroup == group.title.orEmpty()) {
                    onSelectGroup(group.title.orEmpty())
                }
            }
        }

        if (favorites) {
            // Favoris : grille du guide ou programme du soir.
            Row(
                Modifier.padding(start = window.horizontalPadding, end = window.horizontalPadding, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GuideModeChip(stringResource(R.string.guide_mode_grid), selectedGroup == GuideViewModel.FAVORITES) { onSelectGroup(GuideViewModel.FAVORITES) }
                GuideModeChip(stringResource(R.string.guide_tonight), selectedGroup == GuideViewModel.FAVORITES_TONIGHT) { onSelectGroup(GuideViewModel.FAVORITES_TONIGHT) }
            }
        }
        if (selectedGroup == GuideViewModel.TONIGHT || selectedGroup == GuideViewModel.FAVORITES_TONIGHT) {
            TonightView(tonight, window, onTonightDay, onOpen = { channel, programme -> details = channel to programme })
        } else if (!state.loading && state.channels.isEmpty()) {
            EmptyState(
                icon = NexTvIcons.Guide,
                title = stringResource(
                    when (selectedGroup) {
                        GuideViewModel.FAVORITES -> R.string.guide_no_favorites_title
                        else -> R.string.guide_empty_title
                    },
                ),
                message = stringResource(
                    when (selectedGroup) {
                        GuideViewModel.FAVORITES -> R.string.guide_no_favorites_message
                        else -> R.string.guide_empty_message
                    },
                ),
            )
        } else {
            GuideGrid(
                state = state,
                highlighted = described(highlighted, describe),
                scroll = scroll,
                minuteWidth = minuteWidth,
                channelColumnWidth = channelColumnWidth,
                rowHeight = rowHeight,
                compact = !window.isWide,
                horizontalPadding = window.horizontalPadding,
                requestInitialFocus = window.isTv,
                onPlay = onPlay,
                onFocus = { focused = it },
                onOpen = { details = it },
            )
        }
    }

    described(details, describe)?.let { (channel, programme) ->
        ProgrammeSheet(
            channel = channel,
            programme = programme,
            now = state.now,
            onWatch = {
                details = null
                onPlay(channel.id)
            },
            onDismiss = { details = null },
            onReplay = if (Catchup.isAvailable(channel.catchupDays, programme.startMillis, state.now)) {
                {
                    details = null
                    onReplay(channel, programme)
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun GuideGrid(
    state: GuideUiState,
    highlighted: Pair<GuideChannel, GuideProgramme>?,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    rowHeight: Dp,
    compact: Boolean,
    horizontalPadding: Dp,
    requestInitialFocus: Boolean,
    onPlay: (Long) -> Unit,
    onFocus: (Pair<GuideChannel, GuideProgramme>) -> Unit,
    onOpen: (Pair<GuideChannel, GuideProgramme>) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    // Ouverture sur la demi-heure écoulée ; la veille reste accessible vers la gauche.
    var initialScrollDone by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Programme ouvert (lecture, fiche) : ligne, heure, défilement horizontal et affichage de l'accueil,
    // pour revenir exactement au même endroit.
    var opened by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    val visit = HomeVisit.current
    val listState = rememberLazyListState()
    var pendingFocus by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val back = opened?.takeIf { it.size == 4 && it[3].toInt() != visit }
    if (back != null && !state.loading && state.channels.isNotEmpty()) {
        LaunchedEffect(back) {
            val row = back[0].toInt().coerceIn(0, state.channels.lastIndex)
            scroll.scrollTo(back[2].toInt())
            listState.scrollToItem(row)
            initialScrollDone = true
            initialFocusDone = true
            pendingFocus = row to back[1]
            opened = null
        }
    }
    val rememberOpened: (Int, Long) -> Unit = { row, at -> opened = listOf(row.toLong(), at, scroll.value.toLong(), visit.toLong()) }
    if (!initialScrollDone && !state.loading && back == null) {
        LaunchedEffect(Unit) {
            scroll.scrollTo(with(density) { (minutes(state.openAt - state.windowStart) * minuteWidth).roundToPx() })
            initialScrollDone = true
        }
    }
    val firstLive = state.channels.firstOrNull()?.let { channel -> channel.programmes.firstOrNull { it.isLive(state.now) } }
    // Guide ouvert en parcourant les onglets du haut : le focus y reste.
    val tabsFocused = LocalTabsFocused.current
    if (requestInitialFocus && initialScrollDone && !initialFocusDone && firstLive != null && back == null) {
        LaunchedEffect(firstLive) {
            if (!tabsFocused()) runCatching { initialFocus.requestFocus() }
            initialFocusDone = true
        }
    }

    // Heure visée : haut / bas restent sur cette heure, gauche / droite la déplacent.
    val scope = rememberCoroutineScope()
    var anchor by remember { mutableLongStateOf(state.now) }
    var focusedRow by remember { mutableIntStateOf(-1) }
    var verticalMove by remember { mutableStateOf(false) }
    // Haut / bas : la frise horaire reste en place (le programme visé couvre l'heure affichée) ;
    // gauche / droite la font défiler comme avant.
    var timelineLocked by remember { mutableStateOf(false) }
    val minutePx = with(density) { minuteWidth.toPx() }
    val rowStepPx = with(density) { (rowHeight + 6.dp).toPx() }
    val onProgrammeFocus: (Int, GuideProgramme) -> Unit = { index, programme ->
        focusedRow = index
        if (verticalMove) {
            verticalMove = false
        } else if (anchor !in programme.startMillis until programme.stopMillis) {
            // Programme atteint par gauche / droite : son début, ou le bord gauche visible s'il commence avant.
            val leftEdge = state.windowStart + (scroll.value / minutePx * 60_000).toLong()
            anchor = maxOf(programme.startMillis, leftEdge).coerceAtMost(programme.stopMillis - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProgrammeDetails(highlighted, state.now, Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp))
        TimeRuler(
            state = state,
            scroll = scroll,
            minuteWidth = minuteWidth,
            channelColumnWidth = channelColumnWidth,
            modifier = Modifier.padding(start = horizontalPadding),
        )
        FadingBottom({ listState.canScrollForward }, { listState.canScrollBackward }) { fade ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = horizontalPadding, bottom = 24.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().then(fade).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || focusedRow < 0) return@onPreviewKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionDown -> 1
                        Key.DirectionUp -> -1
                        else -> {
                            if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) timelineLocked = false
                            return@onPreviewKeyEvent false
                        }
                    }
                    val target = focusedRow + step
                    // Première ligne vers le haut (catégories), dernière vers le bas, ligne sans programme : comportement habituel.
                    if (target !in state.channels.indices || visibleProgrammes(state.channels[target], state).isEmpty()) return@onPreviewKeyEvent false
                    verticalMove = true
                    timelineLocked = true
                    pendingFocus = target to anchor
                    // Ligne hors de l'écran : elle n'existe pas encore, on fait défiler d'une ligne.
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                        scope.launch { listState.scrollBy(step * rowStepPx) }
                    }
                    true
                },
            ) {
                itemsIndexed(state.channels, key = { _, channel -> channel.id }) { index, channel ->
                    GuideRow(
                        channel = channel,
                        state = state,
                        scroll = scroll,
                        minuteWidth = minuteWidth,
                        channelColumnWidth = channelColumnWidth,
                        rowHeight = rowHeight,
                        compact = compact,
                        focusRequester = initialFocus.takeIf { channel == state.channels.first() },
                        focusAt = pendingFocus?.takeIf { it.first == index }?.second,
                        timelineLocked = { timelineLocked },
                        onFocusAtDone = { pendingFocus = null },
                        onChannelFocus = { focusedRow = -1 },
                        onPlay = {
                            rememberOpened(index, state.now)
                            onPlay(channel.id)
                        },
                        onFocus = { programme ->
                            onProgrammeFocus(index, programme)
                            onFocus(channel to programme)
                        },
                        onOpen = { programme ->
                            rememberOpened(index, maxOf(programme.startMillis, minOf(anchor, programme.stopMillis - 1)))
                            onOpen(channel to programme)
                        },
                    )
                }
            }
        }
    }
}

/** Choix du mode des Favoris (« Guide TV » ou « Ce soir »), plus discret que les catégories. */
@Composable
private fun GuideModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        focusedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun GuideGroupChip(name: String, selected: Boolean, star: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) Icon(Icons.Filled.Star, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ProgrammeDetails(highlighted: Pair<GuideChannel, GuideProgramme>?, now: Long, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        // Image du programme (guide, sinon TMDB pour un film ou une série).
        if (highlighted != null && rememberProgrammeImage(highlighted.second) != null) {
            ProgrammeArt(highlighted.second, Modifier.width(150.dp).aspectRatio(16f / 9f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (highlighted != null) ProgrammeDetailsText(highlighted.first, highlighted.second, now)
        }
    }
}

@Composable
private fun ProgrammeDetailsText(channel: GuideChannel, programme: GuideProgramme, now: Long) {
    Text(
        "${channel.name}  ·  ${dayPrefix(programme.startMillis, now)}${timeRange(programme.startMillis, programme.stopMillis)}  ·  ${duration(programme)}" +
            (programme.category?.let { "  ·  $it" } ?: "") +
            (programme.year?.let { "  ·  $it" } ?: ""),
        style = MaterialTheme.typography.labelLarge,
        color = if (programme.isLive(now)) NexTvColors.Live else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(programme.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    (programme.description ?: programme.subtitle)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TimeRuler(
    state: GuideUiState,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val totalWidth = minutes(state.windowEnd - state.windowStart) * minuteWidth
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(state.now)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(channelColumnWidth + 6.dp),
            maxLines = 1,
        )
        Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                var t = state.windowStart
                while (t < state.windowEnd) {
                    val midnight = isMidnight(t)
                    Text(
                        if (midnight) dayOf(t) else timeOf(t),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = minutes(t - state.windowStart) * minuteWidth + 6.dp).align(Alignment.CenterStart),
                    )
                    t += HALF_HOUR
                }
                Box(
                    Modifier
                        .offset(x = minutes(state.now - state.windowStart) * minuteWidth - 4.dp)
                        .align(Alignment.BottomStart)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NexTvColors.Live),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    channel: GuideChannel,
    state: GuideUiState,
    scroll: ScrollState,
    minuteWidth: Dp,
    channelColumnWidth: Dp,
    rowHeight: Dp,
    compact: Boolean,
    focusRequester: FocusRequester?,
    /** Heure dont le programme doit prendre le focus (haut / bas depuis une autre ligne). */
    focusAt: Long?,
    onFocusAtDone: () -> Unit,
    /** Frise horaire figée : le programme visé, visible, ne la fait pas défiler. */
    timelineLocked: () -> Boolean,
    onChannelFocus: () -> Unit,
    onPlay: () -> Unit,
    onFocus: (GuideProgramme) -> Unit,
    onOpen: (GuideProgramme) -> Unit,
) {
    val totalWidth = minutes(state.windowEnd - state.windowStart) * minuteWidth
    val shown = visibleProgrammes(channel, state)
    val requesters = remember(shown) { shown.associateWith { FocusRequester() } }
    // Programme qui doit prendre le focus (haut / bas) : celui de l'heure visée, sinon le plus proche.
    val focusTarget = remember(focusAt, shown) {
        focusAt?.let { at ->
            shown.firstOrNull { at >= it.startMillis && at < it.stopMillis }
                ?: shown.minByOrNull { minOf(abs(it.startMillis - at), abs(it.stopMillis - at)) }
        }
    }
    if (focusAt != null) {
        LaunchedEffect(focusAt) {
            focusTarget?.let { runCatching { requesters.getValue(it).requestFocus() } }
            onFocusAtDone()
        }
    }
    // Seules les cases proches de l'heure affichée sont dessinées : un écran de marge de chaque côté, plus la
    // voisine de chaque bord (← → trouvent toujours la suivante). La ligne entière couvre deux jours, soit des
    // dizaines de cases par chaîne pour une poignée à l'écran. Recalculé seulement quand une case entre dans cette
    // zone ou en sort, pas à chaque pixel de défilement.
    val minutePx = with(LocalDensity.current) { minuteWidth.toPx() }
    val drawnRange by remember(shown, state.windowStart, minutePx) {
        derivedStateOf {
            val viewportPx = scroll.viewportSize.takeIf { it > 0 } ?: DEFAULT_VIEWPORT_PX
            val visibleMillis = (viewportPx / minutePx * 60_000).toLong()
            val margin = visibleMillis.coerceAtLeast(MIN_DRAWN_MARGIN_MILLIS)
            val visibleStart = state.windowStart + (scroll.value / minutePx * 60_000).toLong()
            drawnRange(shown, visibleStart - margin, visibleStart + visibleMillis + margin)
        }
    }
    val drawn = remember(drawnRange, shown, focusTarget) {
        val near = if (drawnRange.isEmpty()) emptyList() else shown.subList(drawnRange.first, drawnRange.last + 1)
        // Le programme visé par haut / bas peut être loin (ligne sans programme à cette heure) : dessiné aussi.
        if (focusTarget != null && focusTarget !in near) near + focusTarget else near
    }
    Row(Modifier.fillMaxWidth().height(rowHeight)) {
        FocusSurface(
            onClick = onPlay,
            modifier = Modifier.width(channelColumnWidth).fillMaxHeight(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            focusScale = 1f,
            onFocusChange = { if (it) onChannelFocus() },
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(if (compact) 36.dp else 44.dp), monogramSize = 14.dp)
                if (!compact) {
                    Column(Modifier.weight(1f)) {
                        channel.number?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(channel.number ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        val parentSpec = LocalBringIntoViewSpec.current
        val timelineSpec = remember(parentSpec) { TimelineBringIntoViewSpec(parentSpec, timelineLocked) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides timelineSpec) {
            Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) {
                    if (channel.programmes.isEmpty()) {
                        Text(
                            stringResource(R.string.guide_no_programme),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexTvColors.TextMuted,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                        )
                    }
                    drawn.forEach { programme ->
                      // Chaque case garde son identité (et le focus) quand la zone dessinée glisse.
                      key(programme.id, programme.startMillis, programme.stopMillis, programme.title) {
                        val start = maxOf(programme.startMillis, state.windowStart)
                        val end = minOf(programme.stopMillis, state.windowEnd)
                        val live = programme.isLive(state.now)
                        ProgrammeBlock(
                            programme = programme,
                            live = live,
                            past = programme.stopMillis <= state.now,
                            replay = Catchup.isAvailable(channel.catchupDays, programme.startMillis, state.now),
                            blockStart = minutes(start - state.windowStart) * minuteWidth,
                            scroll = scroll,
                            modifier = Modifier
                                .offset(x = minutes(start - state.windowStart) * minuteWidth)
                                .width(minutes(end - start) * minuteWidth)
                                .fillMaxHeight()
                                .padding(end = 4.dp)
                                .focusRequester(requesters.getValue(programme))
                                .then(if (live && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                            onClick = { onOpen(programme) },
                            onFocus = { onFocus(programme) },
                        )
                      }
                    }
                    Box(
                        Modifier
                            .offset(x = minutes(state.now - state.windowStart) * minuteWidth - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(NexTvColors.Live),
                    )
                }
            }
        }
    }
}

/**
 * Défilement horizontal d'une ligne du guide quand un programme prend le focus : rien tant que la
 * frise est figée (haut / bas) et que le programme est à l'écran, sinon le comportement habituel.
 */
@OptIn(ExperimentalFoundationApi::class)
private class TimelineBringIntoViewSpec(
    private val parent: BringIntoViewSpec,
    private val locked: () -> Boolean,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (locked() && offset < containerSize && offset + size > 0f) return 0f
        return parent.calculateScrollDistance(offset, size, containerSize)
    }
}

@Composable
private fun ProgrammeBlock(
    programme: GuideProgramme,
    live: Boolean,
    past: Boolean,
    replay: Boolean,
    /** Début de la case dans la frise, et défilement de la frise : le texte suit le bord gauche visible. */
    blockStart: Dp,
    scroll: ScrollState,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = when {
            live -> NexTvColors.PrimaryContainer
            past -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        focusScale = 1f,
        onFocusChange = { if (it) onFocus() },
    ) {
        Column(
            Modifier
                // Programme commencé avant le bord gauche de l'écran : titre et horaires glissent jusqu'à ce
                // bord (ils restaient tout à gauche de la case, hors de l'écran), sans sortir de la case.
                .layout { measurable, constraints ->
                    val hidden = scroll.value - blockStart.roundToPx()
                    val maxShift = (constraints.maxWidth - STICKY_MIN_TEXT.roundToPx()).coerceAtLeast(0)
                    val shift = hidden.coerceIn(0, maxShift)
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - shift).coerceAtLeast(0)))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(shift, 0) }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(programme.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Programme passé disponible en replay.
                if (replay && past) Icon(NexTvIcons.Replay, contentDescription = null, tint = NexTvColors.Primary, modifier = Modifier.size(12.dp))
                Text(
                    timeRange(programme.startMillis, programme.stopMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) NexTvColors.Live else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Fiche détaillée d'un programme (`null` : aucun programme connu), avec l'action « Regarder » et,
 * si [onReplay] est donné, « Revoir » (programme passé) ou « Depuis le début » (programme en cours).
 */
@Composable
fun ProgrammeSheet(
    channel: GuideChannel,
    programme: GuideProgramme?,
    now: Long,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    onReplay: (() -> Unit)? = null,
    inSelection: Boolean? = null,
    onToggleSelection: () -> Unit = {},
) {
    // Largeur propre à la fiche (plus large que celle d'une boîte de dialogue) : la description tient entière.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.ignoreHeldOk().padding(24.dp)) {
            ProgrammeSheetContent(channel, programme, now, onWatch, onDismiss, onReplay, inSelection, onToggleSelection)
        }
    }
}

@Composable
fun ProgrammeSheetContent(
    channel: GuideChannel,
    programme: GuideProgramme?,
    now: Long,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    onReplay: (() -> Unit)? = null,
    /** Chaîne dans « L'essentiel » ; `null` : pas de bouton (lecteur). */
    inSelection: Boolean? = null,
    onToggleSelection: () -> Unit = {},
) {
    val watchFocus = remember { FocusRequester() }
    // Programme terminé : « Revoir » passe en premier.
    val replayFirst = onReplay != null && programme != null && programme.stopMillis <= now
    LaunchedEffect(Unit) { runCatching { watchFocus.requestFocus() } }
    val wide = rememberWindowInfo().isWide
    // Description trop longue pour l'écran : « haut » depuis les boutons y entre, haut / bas la font défiler.
    val detailsScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var detailsFocused by remember { mutableStateOf(false) }
    val step = with(LocalDensity.current) { SHEET_SCROLL_STEP.toPx() }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = if (wide) SHEET_WIDTH_WIDE else SHEET_WIDTH),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 64.dp, height = 44.dp), monogramSize = 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (programme != null) {
                        Text(
                            "${dayPrefix(programme.startMillis, now)}${timeRange(programme.startMillis, programme.stopMillis)}  ·  ${duration(programme)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (programme == null) {
                Text(stringResource(R.string.channels_no_epg), style = MaterialTheme.typography.headlineSmall)
            } else {
                // Fiche plus haute que l'écran : ce bloc défile (description entière), les boutons restent visibles en bas.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .then(if (detailsFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                        .onFocusChanged { detailsFocused = it.isFocused }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                // En bas du texte, « bas » revient aux boutons.
                                Key.DirectionDown -> detailsScroll.canScrollForward.also { if (it) scope.launch { detailsScroll.animateScrollBy(step) } }
                                Key.DirectionUp -> {
                                    if (detailsScroll.canScrollBackward) scope.launch { detailsScroll.animateScrollBy(-step) }
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable(enabled = detailsScroll.maxValue > 0)
                        .bottomFade({ detailsScroll.canScrollForward })
                        .verticalScroll(detailsScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val details: @Composable () -> Unit = {
                        Text(programme.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        programme.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        // Genre et année de production, quand le guide les indique.
                        listOfNotNull(programme.category, programme.year?.toString()).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (programme.isLive(now)) LiveProgress(programme.startMillis, programme.stopMillis, now)
                    }
                    val image = rememberProgrammeImage(programme) != null
                    if (wide && image) {
                        // TV : image à côté du titre, la fiche tient dans la hauteur de l'écran.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ProgrammeArt(programme, Modifier.width(SHEET_ART_WIDTH).aspectRatio(16f / 9f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { details() }
                        }
                    } else {
                        if (image) ProgrammeArt(programme, Modifier.fillMaxWidth().aspectRatio(16f / 7f))
                        details()
                    }
                    programme.description?.let {
                        Text(
                            // Description dans une autre langue (chaîne étrangère) : en français.
                            frenchText(it),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (replayFirst && onReplay != null) {
                    PillButton(
                        stringResource(R.string.guide_replay),
                        onReplay,
                        icon = NexTvIcons.Replay,
                        style = PillStyle.Primary,
                        modifier = Modifier.focusRequester(watchFocus),
                    )
                }
                PillButton(
                    stringResource(if (replayFirst) R.string.guide_watch_live else R.string.guide_watch),
                    onWatch,
                    icon = Icons.Filled.PlayArrow,
                    style = if (replayFirst) PillStyle.Secondary else PillStyle.Primary,
                    modifier = if (replayFirst) Modifier else Modifier.focusRequester(watchFocus),
                )
                if (!replayFirst && onReplay != null) {
                    PillButton(stringResource(R.string.guide_restart), onReplay, icon = NexTvIcons.Replay)
                }
                if (inSelection != null) {
                    PillButton(
                        stringResource(if (inSelection) R.string.guide_selection_remove else R.string.guide_selection_add),
                        onToggleSelection,
                        icon = if (inSelection) Icons.Filled.Check else Icons.Filled.Add,
                    )
                }
                PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
            }
        }
    }
}

private fun GuideProgramme.isLive(now: Long) = startMillis <= now && stopMillis > now

/** Fiche d'un programme : largeurs (TV, autres écrans), image (TV) et pas de défilement de la description. */
private val SHEET_WIDTH = 640.dp
private val SHEET_WIDTH_WIDE = 860.dp
private val SHEET_ART_WIDTH = 220.dp
private val SHEET_SCROLL_STEP = 120.dp

/**
 * Cases à dessiner d'une ligne du guide : celles qui touchent la période [from, to[ (ce qui est à l'écran et sa
 * marge), plus la voisine de chaque bord, pour que le focus trouve toujours la case suivante avec ← →.
 *
 * @param programmes programmes de la ligne, dans l'ordre des heures.
 * @return indices du premier et du dernier programme à dessiner ; vide pour une ligne sans programme.
 */
internal fun drawnRange(programmes: List<GuideProgramme>, from: Long, to: Long): IntRange {
    if (programmes.isEmpty()) return IntRange.EMPTY
    // Rien dans la période : les cases les plus proches de chaque côté restent atteignables.
    var first = programmes.indexOfFirst { it.stopMillis > from }
    if (first < 0) first = programmes.lastIndex
    var last = programmes.indexOfLast { it.startMillis < to }
    if (last < 0) last = 0
    if (last < first) {
        val swap = first
        first = last
        last = swap
    }
    return (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(programmes.lastIndex)
}

/** Largeur prise pour l'écran tant qu'elle n'est pas mesurée (premier affichage). */
private const val DEFAULT_VIEWPORT_PX = 1920

/** Marge minimale dessinée de chaque côté de ce qui est à l'écran. */
private const val MIN_DRAWN_MARGIN_MILLIS = 2 * 60 * 60_000L

/** Programmes affichés d'une ligne : au moins une minute dans la fenêtre de la grille. */
private fun visibleProgrammes(channel: GuideChannel, state: GuideUiState): List<GuideProgramme> =
    channel.programmes.filter { minOf(it.stopMillis, state.windowEnd) - maxOf(it.startMillis, state.windowStart) >= 60_000 }

private const val HALF_HOUR = 30 * 60_000L

private fun minutes(millis: Long): Float = millis / 60_000f

private operator fun Float.times(dp: Dp): Dp = dp * this

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun isMidnight(millis: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = millis }.let { it.get(Calendar.HOUR_OF_DAY) == 0 && it.get(Calendar.MINUTE) == 0 }

/** « ven. 30 ». */
private fun dayOf(millis: Long): String = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(millis))

/** Jour du programme s'il n'est pas aujourd'hui (« Hier · », « Demain · », « sam. 1 · »), sinon rien. */
@Composable
private fun dayPrefix(millis: Long, now: Long): String {
    return when (dayIndex(millis) - dayIndex(now)) {
        0L -> ""
        -1L -> stringResource(R.string.guide_yesterday) + "  ·  "
        1L -> stringResource(R.string.guide_tomorrow) + "  ·  "
        else -> dayOf(millis) + "  ·  "
    }
}

/** Numéro du jour local (jours depuis l'époque, dans le fuseau de l'appareil). */
private fun dayIndex(millis: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(millis)
    return Math.floorDiv(millis + offset, 86_400_000L)
}

private fun timeRange(start: Long, stop: Long) = "${timeOf(start)} – ${timeOf(stop)}"

/** « 45 min », « 1 h 30 ». */
fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h ${minutes.toString().padStart(2, '0')}"
    }
}

private fun duration(programme: GuideProgramme) = formatDuration(programme.stopMillis - programme.startMillis)

/** Place minimale gardée au texte d'une case du guide quand il suit le bord gauche de l'écran. */
private val STICKY_MIN_TEXT = 110.dp
