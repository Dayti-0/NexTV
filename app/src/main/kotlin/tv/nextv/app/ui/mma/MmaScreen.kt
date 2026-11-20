package tv.nextv.app.ui.mma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.ui.common.appViewModel
import tv.nextv.app.ui.common.frenchText
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.EmptyState
import tv.nextv.app.ui.components.FadingBottom
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.LiveProgress
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.components.ignoreHeldOk
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.home.HomeVisit
import tv.nextv.app.ui.home.RAIL_FOCUS_ROOM
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvIcons
import tv.nextv.core.mma.MmaFight
import tv.nextv.core.mma.MmaPromotion
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Section « MMA » de l'accueil. */
@Composable
fun MmaScreen(sourceId: Long, onPlay: (Long) -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = "mma-$sourceId") { container, _ -> MmaViewModel(container, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val promotion by viewModel.promotion.collectAsStateWithLifecycle()
    MmaContent(state, promotion, onPromotion = { viewModel.promotion.value = it }, onPlay = onPlay, modifier = modifier)
}

/**
 * Organisations à gauche (en haut sur écran étroit), galas par jour à droite. OK : regarder la chaîne qui diffuse le
 * gala (sinon sa fiche) ; appui long : fiche du gala (carte des combats, résultats, chaînes).
 */
@Composable
fun MmaContent(
    state: MmaUiState,
    promotion: MmaPromotion?,
    onPromotion: (MmaPromotion?) -> Unit,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberWindowInfo()
    var details by remember { mutableStateOf<MmaCard?>(null) }
    val shown = state.cards.filter { promotion == null || it.promotion == promotion }
    fun count(choice: MmaPromotion?) = state.cards.count { choice == null || it.promotion == choice }
    // Organisations sans gala à venir cachées (sauf celle choisie) : la liste reste courte.
    val choices = MmaPromotion.entries.filter { count(it) > 0 || it == promotion || it.espnLeague != null }

    val list: @Composable (Modifier) -> Unit = { listModifier ->
        val body = listModifier.fillMaxSize()
        when {
            state.loading -> Column(body, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.mma_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            shown.isEmpty() -> EmptyState(
                icon = NexTvIcons.Mma,
                title = stringResource(R.string.mma_empty_title),
                message = listOfNotNull(
                    stringResource(R.string.mma_empty_message),
                    stringResource(R.string.mma_schedule_unavailable).takeIf { state.scheduleUnavailable },
                ).joinToString("\n\n"),
                modifier = body,
            )
            else -> EventList(shown, state.now, state.scheduleUnavailable, window.horizontalPadding, !window.isWide, onPlay, { details = it }, body)
        }
    }

    if (window.isWide) {
        Row(modifier.fillMaxSize()) {
            val railState = rememberLazyListState()
            FadingBottom({ railState.canScrollForward }, { railState.canScrollBackward }) { fade ->
                LazyColumn(
                    state = railState,
                    modifier = Modifier
                        .width(290.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = (window.horizontalPadding - 16.dp - RAIL_FOCUS_ROOM).coerceAtLeast(0.dp), end = 12.dp - RAIL_FOCUS_ROOM)
                        .then(fade),
                    contentPadding = PaddingValues(start = RAIL_FOCUS_ROOM, end = RAIL_FOCUS_ROOM, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { PromotionItem(stringResource(R.string.mma_all), count(null), null, promotion == null) { onPromotion(null) } }
                    items(choices) { choice ->
                        PromotionItem(choice.label, count(choice), choice, choice == promotion) { onPromotion(choice) }
                    }
                }
            }
            list(Modifier.weight(1f))
        }
    } else {
        Column(modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = window.horizontalPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { PromotionChip(stringResource(R.string.mma_all), promotion == null) { onPromotion(null) } }
                items(choices) { choice -> PromotionChip(choice.label, choice == promotion, choice) { onPromotion(choice) } }
            }
            list(Modifier.weight(1f))
        }
    }

    details?.let { card ->
        EventSheet(
            card = card,
            now = state.now,
            onWatch = { channelId ->
                details = null
                onPlay(channelId)
            },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun EventList(
    cards: List<MmaCard>,
    now: Long,
    scheduleUnavailable: Boolean,
    horizontalPadding: Dp,
    compact: Boolean,
    onPlay: (Long) -> Unit,
    onDetails: (MmaCard) -> Unit,
    modifier: Modifier,
) {
    val days = remember(cards) { cards.groupBy { dayStart(it.startMillis) } }
    // Gala ouvert (lecture) : il reprend la sélection au retour sur l'accueil.
    var opened by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val visit = HomeVisit.current
    val backKey = opened?.takeIf { it.size == 2 && it[1] != visit.toString() }?.get(0)
    val backFocus = remember { FocusRequester() }
    if (backKey != null && cards.any { it.key == backKey }) {
        LaunchedEffect(backKey) {
            repeat(8) {
                if (runCatching { backFocus.requestFocus() }.isSuccess) {
                    opened = null
                    return@LaunchedEffect
                }
                delay(120)
            }
            opened = null
        }
    }
    val listState = rememberLazyListState()
    FadingBottom({ listState.canScrollForward }, { listState.canScrollBackward }) { fade ->
        LazyColumn(
            state = listState,
            modifier = modifier.then(fade),
            contentPadding = PaddingValues(start = if (compact) horizontalPadding else 28.dp, end = horizontalPadding, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (scheduleUnavailable) {
                item(key = "notice") {
                    Text(
                        stringResource(R.string.mma_schedule_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            days.forEach { (day, dayCards) ->
                item(key = "day-$day") {
                    Text(
                        dayLabel(day, now),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(dayCards, key = { it.key }) { card ->
                    val channel = card.channel
                    FocusSurface(
                        onClick = {
                            if (channel != null) {
                                opened = listOf(card.key, visit.toString())
                                onPlay(channel.id)
                            } else {
                                onDetails(card)
                            }
                        },
                        onLongClick = { onDetails(card) },
                        modifier = Modifier.fillMaxWidth().then(if (card.key == backKey) Modifier.focusRequester(backFocus) else Modifier),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        focusScale = 1.01f,
                    ) {
                        EventRow(card, now, compact)
                    }
                }
            }
        }
    }
}

/**
 * Un gala : heure de la carte principale à gauche (préliminaires dessous) ; à droite l'organisation, le nom du gala,
 * le combat principal avec les drapeaux, puis la chaîne.
 */
@Composable
private fun EventRow(card: MmaCard, now: Long, compact: Boolean) {
    val live = card.isLive(now)
    val main = card.mainCardMillis ?: card.startMillis
    Row(
        Modifier.padding(horizontal = if (compact) 14.dp else 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        Column(Modifier.width(if (compact) 72.dp else 104.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(timeOf(main), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
            if (card.mainCardMillis != null && card.mainCardMillis > card.startMillis) {
                Text(
                    stringResource(R.string.mma_prelims_at, timeOf(card.startMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                card.finished -> StatusPill(stringResource(R.string.mma_finished), MaterialTheme.colorScheme.onSurfaceVariant)
                live -> StatusPill(stringResource(R.string.mma_live), NexTvColors.Live)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromotionBadge(card.promotion)
                // Gala du guide seul : son nom est déjà écrit en grand dessous.
                if (card.event != null) {
                    Text(
                        card.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val mainEvent = card.event?.mainEvent
            if (mainEvent != null) {
                FightLine(mainEvent, large = !compact)
                mainEvent.weightClass?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Gala du guide seul : sous-titre du programme (souvent les combattants), sinon son nom en grand.
                Text(
                    card.programme?.subtitle?.takeIf { it.isNotBlank() && !it.equals(card.name, ignoreCase = true) } ?: card.name,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (live) LiveProgress(card.startMillis, card.endMillis, now, Modifier.widthIn(max = 480.dp))
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLine(card)
            }
        }
    }
}

/** « Procházka – Stirling » avec les drapeaux ; le vainqueur en gras une fois le combat terminé. */
@Composable
private fun FightLine(fight: MmaFight, large: Boolean) {
    val flag = if (large) 22.dp else 18.dp
    val style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fight.fighters.forEachIndexed { index, fighter ->
            if (index == 1) Text("–", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fighter.flagUrl?.let { ChannelLogo(it, fighter.country ?: fighter.name, Modifier.size(width = flag * 1.4f, height = flag), monogramSize = 8.dp) }
            val winner = fight.result?.winner
            Text(
                fighter.name,
                style = style,
                fontWeight = if (winner == index) FontWeight.Bold else null,
                color = if (winner != null && winner != index) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Chaîne de la playlist qui diffuse le gala (et combien d'autres), celle du guide, ou rien de connu. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ChannelLine(card: MmaCard) {
    val channel = card.channel
    if (channel == null) {
        Text(
            card.guideChannel?.let { stringResource(R.string.football_guide_only_channel, it) } ?: stringResource(R.string.mma_no_channel),
            style = MaterialTheme.typography.labelLarge,
            color = if (card.guideChannel != null) NexTvColors.Warning else NexTvColors.TextMuted,
            maxLines = 2,
        )
        return
    }
    ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 44.dp, height = 30.dp), monogramSize = 12.dp)
    Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    card.programme?.let {
        Text(
            "${timeOf(it.startMillis)} – ${timeOf(it.stopMillis)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    val others = card.channels.size - 1
    if (others > 0) {
        Text(
            LocalContext.current.resources.getQuantityString(R.plurals.football_other_channels, others, others),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Fiche d'un gala : date, lieu, chaînes (« Regarder »), carte des combats (carte principale puis préliminaires) avec
 * les résultats, ou la description du guide pour un gala qu'ESPN ne suit pas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventSheet(card: MmaCard, now: Long, onWatch: (Long) -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val window = rememberWindowInfo()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 720.dp).ignoreHeldOk(),
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PromotionBadge(card.promotion)
                    Text(
                        listOfNotNull(dayLabel(dayStart(card.startMillis), now), card.event?.venue).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        card.finished -> StatusPill(stringResource(R.string.mma_finished), MaterialTheme.colorScheme.onSurfaceVariant)
                        card.isLive(now) -> StatusPill(stringResource(R.string.mma_live), NexTvColors.Live)
                    }
                }
                Text(card.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val mainCard = card.mainCardMillis
                Text(
                    if (mainCard != null && mainCard > card.startMillis) {
                        stringResource(R.string.mma_card_times, timeOf(card.startMillis), timeOf(mainCard))
                    } else {
                        stringResource(R.string.mma_start_time, timeOf(card.startMillis))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fights = card.event?.fights.orEmpty()
                val scroll = rememberScrollState()
                Column(
                    Modifier.heightIn(max = if (window.isWide) 320.dp else 360.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (fights.isNotEmpty()) {
                        val (main, prelims) = fights.partition { it.mainCard }
                        if (main.isNotEmpty() && prelims.isNotEmpty()) SheetHeading(stringResource(R.string.mma_main_card))
                        main.forEach { FightRow(it) }
                        if (prelims.isNotEmpty()) {
                            SheetHeading(stringResource(R.string.mma_prelims))
                            prelims.forEach { FightRow(it) }
                        }
                    } else {
                        card.programme?.let { programme ->
                            programme.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            programme.description?.takeIf { it.isNotBlank() }?.let {
                                Text(frenchText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (card.channels.isNotEmpty()) {
                    Text(
                        stringResource(R.string.football_choose_channel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.heightIn(max = 176.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.channels.forEachIndexed { index, channel ->
                            ChannelChoice(channel, index == 0, { onWatch(channel.id) }, if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        }
                    }
                } else {
                    Text(
                        card.guideChannel?.let { stringResource(R.string.football_guide_only_channel, it) } ?: stringResource(R.string.mma_no_channel),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (card.guideChannel != null) NexTvColors.Warning else NexTvColors.TextMuted,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        stringResource(R.string.action_close),
                        onDismiss,
                        style = PillStyle.Ghost,
                        modifier = if (card.channels.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

/** Combat de la fiche : catégorie, combattants (palmarès), et le résultat (« R1 3:23 », « Décision »). */
@Composable
private fun FightRow(fight: MmaFight) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FightLine(fight, large = false)
            val records = fight.fighters.mapNotNull { it.record }.takeIf { it.size == 2 }?.joinToString(" / ")
            Text(
                listOfNotNull(fight.weightClass, records).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        val result = fight.result
        when {
            fight.live -> StatusPill(stringResource(R.string.mma_live), NexTvColors.Live)
            result != null -> Text(
                if (result.decision) stringResource(R.string.mma_decision) else stringResource(R.string.mma_finish_at, result.round, result.clock),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelChoice(channel: GuideChannel, preferred: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (preferred) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 48.dp, height = 32.dp), monogramSize = 11.dp)
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.guide_watch), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PromotionItem(name: String, count: Int, promotion: MmaPromotion?, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (promotion != null) PromotionBadge(promotion, short = true) else Icon(NexTvIcons.Mma, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PromotionChip(name: String, selected: Boolean, promotion: MmaPromotion? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        focusScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(promotion?.label ?: name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Pastille de l'organisation, à sa couleur (« UFC » rouge, « PFL » bleu…). [short] : sigle seul, largeur fixe. */
@Composable
private fun PromotionBadge(promotion: MmaPromotion, short: Boolean = false) {
    val color = promotionColor(promotion)
    Box(
        Modifier
            .then(if (short) Modifier.width(46.dp) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (short) promotion.short else promotion.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Sigle de la colonne des organisations. */
private val MmaPromotion.short: String
    get() = when (this) {
        MmaPromotion.CAGE_WARRIORS -> "CW"
        MmaPromotion.HEXAGONE -> "HEX"
        MmaPromotion.OTHER -> "MMA"
        else -> label
    }

private fun promotionColor(promotion: MmaPromotion): Color = when (promotion) {
    MmaPromotion.UFC -> Color(0xFFD20A0A)
    MmaPromotion.PFL -> Color(0xFF1F5FD6)
    MmaPromotion.CAGE_WARRIORS -> Color(0xFF8E24AA)
    MmaPromotion.KSW -> Color(0xFFB8860B)
    MmaPromotion.ARES -> Color(0xFF00897B)
    MmaPromotion.HEXAGONE -> Color(0xFF3949AB)
    MmaPromotion.ONE -> Color(0xFFE65100)
    MmaPromotion.OTHER -> Color(0xFF546E7A)
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** « Aujourd'hui », « Demain », puis « Samedi 21 novembre ». */
@Composable
private fun dayLabel(day: Long, now: Long): String {
    val today = dayStart(now)
    val tomorrow = dayStart(today + 36 * 60 * 60_000L)
    val yesterday = dayStart(today - 12 * 60 * 60_000L)
    return when (day) {
        today -> stringResource(R.string.football_today)
        tomorrow -> stringResource(R.string.football_tomorrow)
        yesterday -> stringResource(R.string.mma_yesterday)
        else -> SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(day)).replaceFirstChar { it.titlecase(Locale.FRANCE) }
    }
}
