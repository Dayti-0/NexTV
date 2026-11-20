package tv.nextv.app.ui.mma

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.border
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.height
import tv.nextv.core.mma.MmaFighter
import androidx.compose.material.icons.filled.CheckCircle
import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
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
    /** Fiche ouverte d'emblée (captures). */
    initialDetails: MmaCard? = null,
) {
    val window = rememberWindowInfo()
    var details by remember { mutableStateOf(initialDetails) }
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
 * Un gala : heure de la carte principale à gauche (préliminaires dessous) ; à droite le logo de l'organisation et le nom
 * du gala, puis le combat principal, un combattant par ligne (drapeau, nom en entier, palmarès), et la chaîne.
 */
@Composable
private fun EventRow(card: MmaCard, now: Long, compact: Boolean) {
    val live = card.isLive(now)
    val main = card.mainCardMillis ?: card.startMillis
    Row(
        Modifier.padding(horizontal = if (compact) 14.dp else 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
    ) {
        Column(Modifier.width(if (compact) 76.dp else 110.dp).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(timeOf(main), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PromotionLogo(card.promotion, height = if (compact) 26.dp else 30.dp)
                headerName(card)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val mainEvent = card.event?.mainEvent
            if (mainEvent != null) {
                FighterLines(mainEvent, large = !compact)
                mainEvent.weightClass?.let {
                    Text(stringResource(R.string.mma_main_event, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Gala du guide seul : sous-titre du programme (souvent les combattants), sinon son nom en grand.
                Text(
                    guideHeadline(card),
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (live) LiveProgress(card.startMillis, card.endMillis, now, Modifier.widthIn(max = 480.dp))
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLine(card)
            }
        }
    }
}

/**
 * Les deux combattants l'un sous l'autre : drapeau, nom en entier, palmarès à droite ; une fois le combat terminé, une
 * coche devant le vainqueur et le perdant en grisé.
 */
@Composable
private fun FighterLines(fight: MmaFight, large: Boolean) {
    val style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
    val winner = fight.result?.winner
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        fight.fighters.forEachIndexed { index, fighter ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Flag(fighter, if (large) 26.dp else 20.dp)
                Text(
                    fighter.name,
                    style = style,
                    fontWeight = if (winner == index) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (winner != null && winner != index) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (winner == index) WinnerMark()
                fighter.record?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Drapeau du pays du combattant (ESPN), aux proportions d'un drapeau ; rien s'il n'est pas connu. */
@Composable
private fun Flag(fighter: MmaFighter, height: Dp) {
    val url = fighter.flagUrl ?: return
    AsyncImage(
        model = url,
        contentDescription = fighter.country,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = height * 1.5f, height = height).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun WinnerMark() {
    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.mma_winner), tint = NexTvColors.Live, modifier = Modifier.size(20.dp))
}

/** Chaîne de la playlist qui diffuse le gala (et combien d'autres), sa chaîne habituelle, celle du guide, ou rien. */
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
    ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 48.dp, height = 32.dp), monogramSize = 12.dp)
    Text(channelLabel(channel.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    if (card.usualChannel) {
        Text(stringResource(R.string.mma_usual_channel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    } else {
        card.programme?.let {
            Text(
                "${timeOf(it.startMillis)} – ${timeOf(it.stopMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Chaîne qui diffuse bien après l'heure de la carte (RMC Sport) : dit clairement, l'heure de gauche est celle du gala.
        if (delayedLabel(card) != null) {
            Text(stringResource(R.string.mma_delayed_short), style = MaterialTheme.typography.labelMedium, color = NexTvColors.Warning, maxLines = 1)
        }
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
 * Fiche d'un gala, presque plein écran : en haut le logo, le nom, la date, le lieu, les horaires et les chaînes
 * (« Regarder ») ; dessous toute la carte des combats, carte principale et préliminaires côte à côte, un combat par ligne
 * (portraits, noms et palmarès en entier, résultat). Un gala qu'ESPN ne suit pas montre seulement le sous-titre du guide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventSheet(card: MmaCard, now: Long, onWatch: (Long) -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val window = rememberWindowInfo()
    // Gala du guide seul (pas de carte des combats) : fiche à la hauteur de son contenu.
    val hasFights = card.event?.fights.orEmpty().isNotEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth(if (window.isWide) 0.94f else 0.96f)
                .widthIn(max = 1400.dp)
                .then(if (hasFights) Modifier.fillMaxHeight(0.94f) else Modifier)
                .ignoreHeldOk(),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetHeader(card, now, onWatch, onDismiss, firstFocus, wide = window.isWide)
                val fights = card.event?.fights.orEmpty()
                if (fights.isEmpty()) {
                    // Gala du guide seul : les combattants quand le guide les donne, puis chaque diffusion (pas de description).
                    card.programme?.subtitle?.takeIf { it.isNotBlank() && !it.equals(card.name, ignoreCase = true) }?.let {
                        Text(it, style = MaterialTheme.typography.titleLarge)
                    }
                    if (card.airings.isNotEmpty()) {
                        Text(stringResource(R.string.mma_airings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        card.airings.take(MAX_AIRINGS).forEach { AiringLine(it) }
                    }
                    return@Column
                }
                val (main, prelims) = fights.partition { it.mainCard }
                // Deux colonnes : carte principale | préliminaires (sans préliminaires, la carte coupée en deux).
                val columns = when {
                    !window.isWide -> listOf(stringResource(R.string.mma_main_card) to main, stringResource(R.string.mma_prelims) to prelims)
                    prelims.isNotEmpty() -> listOf(stringResource(R.string.mma_main_card) to main, stringResource(R.string.mma_prelims) to prelims)
                    else -> main.chunked((main.size + 1) / 2).mapIndexed { index, part -> (if (index == 0) stringResource(R.string.mma_main_card) else "") to part }
                }.filter { it.second.isNotEmpty() }
                if (window.isWide) {
                    Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        columns.forEach { (title, list) -> FightColumn(title, list, mainEvent = fights.first(), Modifier.weight(1f)) }
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        columns.forEach { (title, list) -> FightColumn(title, list, mainEvent = fights.first(), Modifier.fillMaxWidth(), scroll = false) }
                    }
                }
            }
        }
    }
}

/** En-tête de la fiche : logo, nom du gala, date · lieu · horaires, puis les chaînes et « Fermer ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetHeader(card: MmaCard, now: Long, onWatch: (Long) -> Unit, onDismiss: () -> Unit, firstFocus: FocusRequester, wide: Boolean) {
    val mainCard = card.mainCardMillis
    val times = if (mainCard != null && mainCard > card.startMillis) {
        stringResource(R.string.mma_card_times, timeOf(card.startMillis), timeOf(mainCard))
    } else {
        stringResource(R.string.mma_start_time, timeOf(card.startMillis))
    }
    val buttons: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            card.channels.take(MAX_SHEET_CHANNELS).forEachIndexed { index, channel ->
                PillButton(
                    channelLabel(channel.name),
                    { onWatch(channel.id) },
                    icon = Icons.Filled.PlayArrow,
                    style = if (index == 0) PillStyle.Primary else PillStyle.Secondary,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            if (card.usualChannel) {
                Text(stringResource(R.string.mma_usual_channel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (card.channels.isEmpty()) {
                Text(
                    card.guideChannel?.let { stringResource(R.string.football_guide_only_channel, it) } ?: stringResource(R.string.mma_no_channel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (card.guideChannel != null) NexTvColors.Warning else NexTvColors.TextMuted,
                )
            }
            PillButton(
                stringResource(R.string.action_close),
                onDismiss,
                style = PillStyle.Ghost,
                modifier = if (card.channels.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    // Ligne 1 : logo, nom du gala (toute la largeur), état. Ligne 2 : date · lieu · horaires, et les boutons à droite.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PromotionLogo(card.promotion, height = 30.dp)
        Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
        when {
            card.finished -> StatusPill(stringResource(R.string.mma_finished), MaterialTheme.colorScheme.onSurfaceVariant)
            card.isLive(now) -> StatusPill(stringResource(R.string.mma_live), NexTvColors.Live)
        }
    }
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // Espaces insécables dans chaque partie : la ligne ne se coupe qu'entre deux parties (« carte principale à 19:00 » entier).
                (listOfNotNull(dayLabel(dayStart(card.startMillis), now), card.event?.venue) + times.split(" · "))
                    .joinToString("  ·  ") { it.replace(' ', '\u00A0') },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            delayedLabel(card)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = NexTvColors.Warning) }
        }
    }
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            info(Modifier.weight(1f))
            Box(Modifier.widthIn(max = 600.dp)) { buttons() }
        }
    } else {
        info(Modifier.fillMaxWidth())
        buttons()
    }
}

/** Colonne de combats (titre, puis un combat par ligne) ; elle défile seule si elle ne tient pas. */
@Composable
private fun FightColumn(title: String, fights: List<MmaFight>, mainEvent: MmaFight, modifier: Modifier, scroll: Boolean = true) {
    Column(modifier.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Même hauteur de titre dans les deux colonnes, même vide : les lignes restent alignées.
        Text(title.ifEmpty { " " }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        fights.forEach { FightRow(it, highlighted = it === mainEvent) }
    }
}

/**
 * Un combat : les deux combattants l'un sous l'autre (portrait, drapeau, nom en entier, palmarès, coche du vainqueur), la
 * catégorie et « contre » ou le résultat à droite. Le combat principal est entouré. Chaque ligne se sélectionne : la
 * télécommande fait défiler une colonne qui ne tiendrait pas (petit écran).
 */
@Composable
private fun FightRow(fight: MmaFight, highlighted: Boolean) {
    val winner = fight.result?.winner
    FocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().then(
            if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), MaterialTheme.shapes.small) else Modifier,
        ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        focusScale = 1.01f,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                fight.fighters.forEachIndexed { index, fighter -> FighterLine(fighter, won = winner == index, lost = winner != null && winner != index) }
            }
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                fight.weightClass?.let {
                    Text(
                        it.removePrefix("Poids ").replaceFirstChar { c -> c.titlecase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
                val result = fight.result
                when {
                    fight.live -> StatusPill(stringResource(R.string.mma_live), NexTvColors.Live)
                    result != null -> Text(
                        if (result.decision) stringResource(R.string.mma_decision) else stringResource(R.string.mma_finish_at, result.round, result.clock),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Un combattant sur une ligne : portrait, drapeau, nom (en entier, une ligne), palmarès, coche s'il a gagné. */
@Composable
private fun FighterLine(fighter: MmaFighter, won: Boolean, lost: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FighterPhoto(fighter, 20.dp)
        Flag(fighter, 12.dp)
        Text(
            fighter.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (won) FontWeight.Bold else FontWeight.Medium,
            color = if (lost) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        fighter.record?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        if (won) WinnerMark()
    }
}

/**
 * Portrait du combattant (ESPN, fond transparent), carré arrondi cadré sur le visage ; sans portrait (ESPN n'en a pas pour
 * tous), une silhouette : la ligne garde sa forme.
 */
@Composable
private fun FighterPhoto(fighter: MmaFighter, size: Dp) {
    val url = fighter.photoUrl(if (size > 56.dp) 240 else 160)
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.18f)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize(0.7f))
        } else {
            AsyncImage(
                model = url,
                contentDescription = fighter.name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Nom du gala au-dessus de la carte : rien pour un gala du guide seul dont le nom n'est que celui de l'organisation (le
 * logo le dit) ou qu'on retrouve dans le texte en grand dessous.
 */
private fun headerName(card: MmaCard): String? {
    if (card.event != null) return card.name
    val headline = guideHeadline(card)
    return card.name.takeUnless {
        it.equals(card.promotion.label, ignoreCase = true) || it.equals(headline, ignoreCase = true) ||
            headline.contains(it, ignoreCase = true) || it.contains(headline, ignoreCase = true)
    }
}

/** Texte en grand d'un gala du guide seul : sous-titre du programme (souvent les combattants), sinon son nom. */
private fun guideHeadline(card: MmaCard): String =
    card.programme?.subtitle?.takeIf { it.isNotBlank() && !it.equals(card.name, ignoreCase = true) } ?: card.name

/** Une diffusion du guide : horaires, chaîne, titre du programme (« 1re partie »). */
@Composable
private fun AiringLine(airing: MmaAiring) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("${timeOf(airing.startMillis)} – ${timeOf(airing.stopMillis)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(120.dp))
        Text(channelLabel(airing.channelName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(airing.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Diffusions montrées dans la fiche d'un gala du guide seul. */
private const val MAX_AIRINGS = 8

/** Chaînes proposées en boutons dans la fiche d'un gala. */
private const val MAX_SHEET_CHANNELS = 2

/** Nom de chaîne sans le pays du fournisseur en tête (« BE| VOO WORLD 2 » → « VOO WORLD 2 »). */
private fun channelLabel(name: String): String = name.replace(Regex("""^\s*\|?\s*[A-Z]{2,3}\s*[|:]\s*"""), "").ifBlank { name }

/**
 * « Diffusé en différé à 20:30 » quand la chaîne commence le gala plus de 30 min après la carte principale (RMC Sport
 * diffuse souvent plus tard) ; `null` sinon.
 */
@Composable
private fun delayedLabel(card: MmaCard): String? {
    if (card.usualChannel || card.event == null) return null
    val programme = card.programme ?: return null
    val main = card.mainCardMillis ?: card.startMillis
    return if (programme.startMillis - main >= DELAY_MILLIS) stringResource(R.string.mma_delayed, timeOf(programme.startMillis)) else null
}

private const val DELAY_MILLIS = 30 * 60_000L

@Composable
private fun PromotionItem(name: String, count: Int, promotion: MmaPromotion?, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (promotion != null) {
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) { PromotionLogo(promotion, height = 30.dp) }
            } else {
                Icon(NexTvIcons.Mma, contentDescription = null, modifier = Modifier.size(24.dp))
            }
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
        Row(
            Modifier.padding(start = if (promotion?.logo != null) 6.dp else 14.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (promotion?.logo != null) PromotionLogo(promotion, height = 24.dp)
            Text(promotion?.label ?: name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Logo officiel de l'organisation sur une pastille claire (les logos sont faits pour un fond blanc), comme les logos des
 * compétitions de la section Foot ; sans logo connu (Hexagone, autres galas), une pastille à sa couleur avec son nom.
 */
@Composable
private fun PromotionLogo(promotion: MmaPromotion, height: Dp) {
    val logo = promotion.logo
    if (logo == null) {
        Box(
            Modifier.height(height).clip(RoundedCornerShape(height * 0.22f)).background(promotionColor(promotion)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(promotion.short, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
        return
    }
    Box(
        Modifier.height(height).clip(RoundedCornerShape(height * 0.22f)).background(LOGO_TILE).padding(horizontal = height * 0.22f, vertical = height * 0.14f),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(logo), contentDescription = promotion.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxHeight())
    }
}

/** Pastille des logos (même teinte que celle des compétitions de foot). */
private val LOGO_TILE = Color(0xFFF2F4F8)

/** Logo officiel embarqué dans l'appli (sites des organisations, ESPN), `null` : pas de logo. */
private val MmaPromotion.logo: Int?
    get() = when (this) {
        MmaPromotion.UFC -> R.drawable.mma_ufc
        MmaPromotion.PFL -> R.drawable.mma_pfl
        MmaPromotion.CAGE_WARRIORS -> R.drawable.mma_cage_warriors
        MmaPromotion.KSW -> R.drawable.mma_ksw
        MmaPromotion.ARES -> R.drawable.mma_ares
        MmaPromotion.ONE -> R.drawable.mma_one
        MmaPromotion.HEXAGONE, MmaPromotion.OTHER -> null
    }

/** Nom court de la pastille sans logo. */
private val MmaPromotion.short: String
    get() = when (this) {
        MmaPromotion.HEXAGONE -> "HEXAGONE"
        MmaPromotion.OTHER -> "MMA"
        else -> label
    }

private fun promotionColor(promotion: MmaPromotion): Color = when (promotion) {
    MmaPromotion.HEXAGONE -> Color(0xFF3949AB)
    else -> Color(0xFF546E7A)
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
