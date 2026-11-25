package tv.nextv.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.formatDuration
import tv.nextv.app.ui.guide.rememberProgrammeImage
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.audio.SpectrumAnalyzer
import java.text.DateFormat
import java.util.Date

/**
 * Place de la pub sur l'écran des pubs, en haut à droite : 340 × 191 sur un écran de 960 × 540 (mêmes proportions sur
 * les autres). Le lecteur y place l'image du direct (même vue, seulement plus petite : jamais une autre surface).
 */
internal object AdBoardLayout {
    private const val WIDTH_SHARE = 340f / 960f
    private const val SIDE_SHARE = 40f / 960f
    private const val TOP_SHARE = 28f / 540f

    fun window(width: Dp, height: Dp): DpRect {
        val w = width * WIDTH_SHARE
        val left = width - width * SIDE_SHARE - w
        val top = height * TOP_SHARE
        return DpRect(left, top, left + w, top + w * 9 / 16)
    }

    fun side(width: Dp): Dp = width * SIDE_SHARE

    fun top(height: Dp): Dp = height * TOP_SHARE
}

/**
 * Écran pendant les pubs : autour de la pub (l'image du direct, réduite en haut à droite par le lecteur), le programme en
 * cours et le suivant, les matchs en direct, la radio qui remplace le son de la pub, la durée de la pub et la météo. Le
 * fond est découpé à la place de la pub : la vidéo, dessous, reste visible (jamais d'image par-dessus).
 */
@Composable
fun AdBoardScreen(
    board: AdBoard,
    channelName: String,
    logoUrl: String?,
    spectrum: SpectrumAnalyzer,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
    /** Captures d'écran : image fixe à cette heure, sans horloge ni barres qui bougent (sinon le test ne finit pas). */
    stillAt: Long? = null,
    /** Bandeau du lecteur affiché par-dessus : le contenu s'efface (la pub et son trou restent). */
    dimmed: Boolean = false,
    /** Flux en chargement : petit rond au milieu de la pub (pas au milieu de l'écran). */
    buffering: Boolean = false,
) {
    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, label = "ad-board-dim")
    val now by produceState(stillAt ?: System.currentTimeMillis()) {
        if (stillAt != null) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val window = AdBoardLayout.window(maxWidth, maxHeight)
        val side = AdBoardLayout.side(maxWidth)
        val top = AdBoardLayout.top(maxHeight)
        Backdrop(board.programme, window)
        if (buffering) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier
                    .offset(x = (window.left + window.right) / 2 - 18.dp, y = (window.top + window.bottom) / 2 - 18.dp)
                    .size(36.dp),
                color = NexTvColors.Primary,
                strokeWidth = 3.dp,
            )
        }
        // À gauche : chaîne, programme, foot en direct, radio.
        Column(
            Modifier
                .padding(start = side, top = top, bottom = top)
                .width(window.left - side * 2)
                .fillMaxHeight()
                .alpha(contentAlpha),
        ) {
            ChannelHeader(channelName, logoUrl)
            Spacer(Modifier.height(16.dp))
            board.programme?.let { ProgrammeBlock(it, board.next, now, compact = board.matches.isNotEmpty()) }
            if (board.matches.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MatchesBlock(board.matches, now, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            board.radio?.let {
                Spacer(Modifier.height(8.dp))
                MusicBar(it, spectrum, lowPower, animate = stillAt == null)
            }
        }
        // À droite : la pub (vidéo dessous), sa durée, la météo.
        Column(
            Modifier
                .offset(x = window.left, y = window.bottom + 10.dp)
                .width(window.right - window.left)
                .height(maxHeight - window.bottom - 10.dp - top)
                .alpha(contentAlpha),
        ) {
            AdStatus(board, now)
            Spacer(Modifier.weight(1f))
            when {
                board.weather != null -> WeatherCard(board.weather, Modifier.fillMaxWidth())
                board.weatherPlaceMissing -> Text(
                    stringResource(R.string.ad_board_weather_place),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** Fond : image du programme floutée et assombrie, partout sauf à la place de la pub (coins arrondis). */
@Composable
private fun Backdrop(programme: AdBoardProgramme?, window: DpRect) {
    val density = LocalDensity.current
    val image = rememberProgrammeImage(programme?.toGuide())
    val hole = remember(window, density) {
        with(density) {
            Path().apply {
                addRoundRect(
                    RoundRect(
                        window.left.toPx(), window.top.toPx(), window.right.toPx(), window.bottom.toPx(),
                        CornerRadius(12.dp.toPx()),
                    ),
                )
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent { clipPath(hole, ClipOp.Difference) { this@drawWithContent.drawContent() } },
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0F14)))
        image?.let { url ->
            // Image en vraie qualité (à la taille de l'écran), assombrie : nette derrière le texte, sans pixels visibles
            // (lue en 24 points et agrandie, elle était en gros carrés sur la box le 25/11/2026).
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.7f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Plus sombre à gauche (le texte) et en bas (matchs, radio, météo).
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.45f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
    }
}

@Composable
private fun ChannelHeader(channelName: String, logoUrl: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelLogo(logoUrl, channelName, Modifier.size(30.dp), monogramSize = 10.dp)
        Text(channelName, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgrammeBlock(programme: AdBoardProgramme, next: AdBoardProgramme?, now: Long, compact: Boolean) {
    Label(stringResource(R.string.ad_board_now))
    Text(programme.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    programme.subtitle?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        programme.category?.let { StatusPill(it.replaceFirstChar(Char::uppercase), NexTvColors.Accent) }
        Text("${timeOf(programme.startMillis)} – ${timeOf(programme.stopMillis)}", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text(
            stringResource(R.string.player_remaining, formatDuration((programme.stopMillis - now).coerceAtLeast(0))),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
    Spacer(Modifier.height(6.dp))
    Bar(
        ((now - programme.startMillis).toFloat() / (programme.stopMillis - programme.startMillis).coerceAtLeast(1)),
        NexTvColors.Live,
        Modifier.fillMaxWidth(0.85f),
    )
    programme.description?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = if (compact) 2 else 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
    next?.let { NextProgramme(it) }
}

@Composable
private fun NextProgramme(next: AdBoardProgramme) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        rememberProgrammeImage(next.toGuide())?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        Column {
            Text(
                stringResource(R.string.ad_board_next, timeOf(next.startMillis)).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = NexTvColors.Accent,
                letterSpacing = 1.2.sp,
            )
            Text(next.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(next.category?.replaceFirstChar(Char::uppercase), formatDuration(next.stopMillis - next.startMillis)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** Matchs en direct : autant de lignes que la place en laisse (celui de la chaîne et les équipes suivies d'abord). */
@Composable
private fun MatchesBlock(matches: List<AdBoardMatch>, now: Long, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        // Lignes entières seulement : plus hautes avec les buteurs.
        var used = LABEL_HEIGHT
        val shown = matches.takeWhile { match ->
            used += rowHeight(match) + MATCH_GAP
            used <= maxHeight + MATCH_GAP
        }
        Column {
            Label(stringResource(R.string.ad_board_football))
            Column(verticalArrangement = Arrangement.spacedBy(MATCH_GAP)) {
                shown.forEach { MatchRow(it, now) }
            }
        }
    }
}

@Composable
private fun MatchRow(match: AdBoardMatch, now: Long) {
    val goal = match.goalUntilMillis?.let { it > now } == true
    Column(
        Modifier
            .fillMaxWidth()
            .height(rowHeight(match))
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    goal -> NexTvColors.Live.copy(alpha = 0.28f)
                    match.onThisChannel -> Color.White.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogo(match.homeCrest, match.home, Modifier.size(20.dp), monogramSize = 8.dp)
            Text(match.home, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            Text(
                if (match.homeScore != null && match.awayScore != null) "${match.homeScore} – ${match.awayScore}" else "–",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NexTvColors.Live,
            )
            Text(match.away, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            ChannelLogo(match.awayCrest, match.away, Modifier.size(20.dp), monogramSize = 8.dp)
            val minute = if (match.halfTime) stringResource(R.string.football_half_time) else match.minute
            minute?.let { StatusPill(it, NexTvColors.Live) }
            match.resumeAtMillis?.takeIf { match.halfTime && it > now }?.let { resumeAt ->
                Text(
                    stringResource(R.string.player_half_time_resume, formatDuration(((resumeAt - now + 59_999) / 60_000) * 60_000)),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
            if (goal) StatusPill(stringResource(R.string.ad_board_goal), NexTvColors.Warning)
        }
        scorersOf(match)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** « ⚽ 13' A. Isak, 70' C. Gakpo · 66' H. Vanaken (pen.) » : buteurs de l'équipe à domicile, puis de l'autre. */
@Composable
private fun scorersOf(match: AdBoardMatch): String? {
    if (match.goals.isEmpty()) return null
    val penalty = stringResource(R.string.ad_board_penalty)
    val ownGoal = stringResource(R.string.ad_board_own_goal)
    fun side(home: Boolean) = match.goals.filter { it.home == home }.joinToString(", ") { goal ->
        listOfNotNull(goal.minute, goal.scorer, penalty.takeIf { goal.penalty }, ownGoal.takeIf { goal.ownGoal }).joinToString(" ")
    }
    return "⚽ " + listOf(side(true), side(false)).filter { it.isNotEmpty() }.joinToString("  ·  ")
}

/** Radio (ou musique) jouée à la place du son de la pub : pochette, morceau, barres au rythme de la musique. */
@Composable
private fun MusicBar(radio: AdBoardRadio, spectrum: SpectrumAnalyzer, lowPower: Boolean, animate: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        radio.cover?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(
                radio.name.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = NexTvColors.Accent,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            radio.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            radio.artist?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MusicBars(spectrum, lowPower, animate, Modifier.size(72.dp, 34.dp))
    }
}

/** Barres qui suivent le son de la radio des pubs (15 images par seconde sur une petite Fire TV). */
@Composable
private fun MusicBars(spectrum: SpectrumAnalyzer, lowPower: Boolean, animate: Boolean, modifier: Modifier) {
    val bands = remember(spectrum) { FloatArray(spectrum.bandCount) }
    var tick by remember { mutableIntStateOf(0) }
    val still = LocalInspectionMode.current || !animate
    DisposableEffect(spectrum) {
        val stop = spectrum.watch()
        onDispose { stop() }
    }
    LaunchedEffect(spectrum, lowPower, still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L && frame - last < (if (lowPower) LOW_POWER_FRAME_NANOS else 0L)) return@withFrameNanos
                val elapsed = if (last == 0L) 1f / 30 else (frame - last) / 1e9f
                last = frame
                spectrum.read(bands, elapsed, android.os.SystemClock.elapsedRealtimeNanos())
                tick++
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        tick
        val bars = BARS
        val gap = size.width / bars
        // Bandes graves à médium (les aiguës bougent peu).
        val used = (bands.size * 3 / 4).coerceAtLeast(bars)
        repeat(bars) { i ->
            val from = i * used / bars
            val to = ((i + 1) * used / bars).coerceAtLeast(from + 1).coerceAtMost(bands.size)
            var sum = 0f
            for (b in from until to) sum += bands[b]
            // Moyenne du groupe, contrastée : la musique forte ne met pas toutes les barres au maximum.
            val level = (sum / (to - from)).coerceIn(0f, 1f)
            val h = size.height * (0.1f + 0.9f * level * level)
            drawRoundRect(
                color = Color(0xFFFFB300),
                topLeft = Offset(i * gap + gap * 0.2f, size.height - h),
                size = Size(gap * 0.6f, h),
                cornerRadius = CornerRadius(gap * 0.3f),
            )
        }
    }
}

/** Sous la pub : depuis quand, fin prévue, et les deux touches. */
@Composable
private fun AdStatus(board: AdBoard, now: Long) {
    val elapsed = (now - board.sinceMillis).coerceAtLeast(0)
    val end = board.endMillis
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(stringResource(R.string.ad_board_ad), NexTvColors.Warning)
        Text(stringResource(R.string.ad_board_since, clockText(elapsed)), style = MaterialTheme.typography.labelLarge, color = Color.White)
        Spacer(Modifier.weight(1f))
        if (end != null) {
            val left = end - now
            Text(
                when {
                    left <= 0 -> stringResource(R.string.ad_board_end_soon)
                    board.announced -> stringResource(R.string.ad_board_end_announced, clockText(left))
                    else -> stringResource(R.string.ad_board_end_typical, formatDuration(((left + 59_999) / 60_000) * 60_000))
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
    if (end != null && end > board.sinceMillis) {
        Spacer(Modifier.height(8.dp))
        Bar(elapsed.toFloat() / (end - board.sinceMillis), NexTvColors.Warning, Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.ad_board_hints), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = NexTvColors.Accent, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
    }
}

private fun AdBoardProgramme.toGuide() = GuideProgramme(
    title = title,
    startMillis = startMillis,
    stopMillis = stopMillis,
    subtitle = subtitle,
    description = description,
    category = category,
    year = year,
    imageUrl = imageUrl,
)

/** « 45 s », « 1 min 20 ». */
private fun clockText(millis: Long): String {
    val seconds = (millis / 1_000).toInt()
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min %02d".format(seconds % 60)
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private const val DIMMED_ALPHA = 0.15f
private const val BARS = 9
private const val LOW_POWER_FRAME_NANOS = 66_000_000L
private val MATCH_HEIGHT = 40.dp
private val MATCH_WITH_SCORERS_HEIGHT = 52.dp

private fun rowHeight(match: AdBoardMatch): Dp = if (match.goals.isEmpty()) MATCH_HEIGHT else MATCH_WITH_SCORERS_HEIGHT
private val MATCH_GAP = 5.dp
private val LABEL_HEIGHT = 22.dp
