package tv.nextv.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maquette « pendant les pubs » (pas encore dans l'appli) : la pub continue en petit en haut à droite, et un seul écran
 * réunit le programme, les matchs en direct, la radio qui remplace le son de la pub et la météo.
 * Capture dans `app/build/screenshots/tv_510_pub_ecran.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class AdBreakMockups {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @Test
    fun board() {
        captureRoboImage("build/screenshots/tv_510_pub_ecran.png") {
            NexTvTheme(ThemeStyle.NIGHT) { WithCover { AdBreakBoard(BoardMoment(elapsed = 80, time = 3f)) } }
        }
    }
}

/**
 * Vidéo de démonstration (13,5 s) : programme ; pub détectée, elle se réduit dans le coin et l'écran s'affiche ; but
 * pendant la pub ; fin de la pub, retour en plein écran. Une image par 1/20 s dans `build/ad_video/`
 * (`AD_VIDEO=1 ./gradlew :app:recordRoborazziDebug --tests '*AdBreakVideo*'`), à assembler avec ffmpeg.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class AdBreakVideo {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun frames() {
        assumeTrue(System.getenv("AD_VIDEO") != null)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        var time by mutableFloatStateOf(0f)
        compose.setContent { NexTvTheme(ThemeStyle.NIGHT) { WithCover { AdBreakScene(time) } } }
        for (frame in 0 until (VIDEO_SECONDS * FPS).toInt()) {
            time = frame / FPS
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/ad_video/frame_%04d.png".format(frame))
        }
    }

    private companion object {
        const val FPS = 20f
        const val VIDEO_SECONDS = 13.5f
    }
}

/** État de l'écran à un instant : [elapsed] secondes de pub, [time] pour l'animation de la radio, but en cours. */
private data class BoardMoment(val elapsed: Int, val time: Float, val goal: Float = 0f, val liverpoolScore: String = "3 – 0")

/** Page de pub prévue (secondes) : annoncée par la chaîne, ou durée habituelle de ses pubs. */
private const val PLANNED_SECONDS = 150

private val WindowWidth = 340.dp

/** L'écran pendant les pubs, la pub dans sa fenêtre en haut à droite. */
@Composable
private fun AdBreakBoard(moment: BoardMoment, window: Boolean = true) {
    Box(Modifier.fillMaxSize()) {
        // Image du programme en grand, assombrie (ici fabriquée : pas de réseau dans les tests).
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2F6B5E), Color(0xFF1F3B57), Color(0xFF0B0F14)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.55f)))))
        Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            Column(Modifier.fillMaxHeight().width(500.dp)) {
                ChannelHeader("M6")
                Spacer(Modifier.height(16.dp))
                ProgrammeBlock()
                Spacer(Modifier.height(16.dp))
                ScoresBlock(moment)
                Spacer(Modifier.weight(1f))
                RadioBar(moment.time)
            }
            Column(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(WindowWidth)) {
                if (window) AdWindow(Modifier.fillMaxWidth().height(WindowWidth * 9 / 16)) else Spacer(Modifier.height(WindowWidth * 9 / 16))
                Spacer(Modifier.height(10.dp))
                AdStatus(moment.elapsed)
                Spacer(Modifier.weight(1f))
                WeatherCard()
            }
        }
    }
}

@Composable
private fun ChannelHeader(channel: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelLogo(null, channel, Modifier.size(30.dp), monogramSize = 10.dp)
        Text(channel, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text("· reprend après la pub", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
private fun ProgrammeBlock() {
    Label("En ce moment")
    Text("Zone interdite", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
    Text("Vacances au camping : les nouveaux rois de l'été", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill("Magazine", NexTvColors.Accent)
        Text("21:10 – 23:00", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text("Reste 48 min", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
    Spacer(Modifier.height(6.dp))
    Bar(0.56f, NexTvColors.Live, Modifier.width(420.dp))
    Spacer(Modifier.height(8.dp))
    Text(
        "Chaque été, des milliers de familles posent leurs valises dans les campings du littoral : enquête sur un secteur en plein essor.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.8f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Picture(Color(0xFF7A3E2E), Color(0xFF2B1B3D), Modifier.size(80.dp, 45.dp))
        Column {
            Text("ENSUITE · 23:00", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NexTvColors.Accent, letterSpacing = 1.2.sp)
            Text("Enquête exclusive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Magazine · 1 h 10", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ScoresBlock(moment: BoardMoment) {
    Label("Foot en direct")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MatchRow("PSG", "Roma", "2 – 1", "67'")
        MatchRow("Liverpool", "Bruges", moment.liverpoolScore, "71'", goal = moment.goal)
        MatchRow("Bayern", "Lille", "1 – 1", "Mi-temps", resume = "reprise dans 9 min")
    }
}

/** Radio qui remplace le son de la pub : pochette, morceau, barres au rythme de la musique. */
@Composable
private fun RadioBar(time: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = "https://exemple.invalid/pochette.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text("FIP GROOVE · À LA PLACE DE LA PUB", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NexTvColors.Accent, letterSpacing = 1.2.sp)
            Text("Move On Up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text("Curtis Mayfield", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        }
        Canvas(Modifier.size(72.dp, 34.dp)) {
            val bars = 9
            val gap = size.width / bars
            repeat(bars) { i ->
                val level = 0.25f + 0.75f * abs(sin(time * 3.1f + i * 0.9f) * cos(time * 1.7f + i * 1.4f))
                val h = size.height * level
                drawRoundRect(
                    color = Color(0xFFFFB300),
                    topLeft = Offset(i * gap + gap * 0.2f, size.height - h),
                    size = Size(gap * 0.6f, h),
                    cornerRadius = CornerRadius(gap * 0.3f),
                )
            }
        }
    }
}

@Composable
private fun AdStatus(elapsed: Int) {
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    val since = if (minutes == 0) "depuis $seconds s" else "depuis $minutes min %02d".format(seconds)
    val left = PLANNED_SECONDS - elapsed
    val end = if (left < 60) "Fin prévue dans moins d'1 min" else "Fin prévue dans ${(left + 59) / 60} min"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("Pub", NexTvColors.Warning)
        Text(since, style = MaterialTheme.typography.labelLarge, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(end, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
    Spacer(Modifier.height(8.dp))
    Bar(elapsed / PLANNED_SECONDS.toFloat(), NexTvColors.Warning, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("OK  pub en grand      Retour  ce n'est pas une pub", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
}

private enum class Sky { SUN, MOON, CLOUD, RAIN, SUN_CLOUD }

/** Météo de la ville choisie dans les réglages (Open-Meteo, sans clé). */
@Composable
private fun WeatherCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WeatherIcon(Sky.RAIN, Modifier.size(44.dp))
            Column {
                Text("PARIS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NexTvColors.Accent, letterSpacing = 1.2.sp)
                Text("6°", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Averses", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("Min 3° · Max 9°", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(Triple("23 h", Sky.RAIN, "5°"), Triple("0 h", Sky.CLOUD, "5°"), Triple("1 h", Sky.MOON, "4°"), Triple("2 h", Sky.MOON, "4°"))
                .forEach { (hour, sky, temperature) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(hour, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        WeatherIcon(sky, Modifier.size(24.dp).padding(vertical = 2.dp))
                        Text(temperature, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherIcon(Sky.SUN_CLOUD, Modifier.size(20.dp))
            Text("Demain : éclaircies, 4° à 11°", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Petits pictogrammes dessinés (soleil, lune, nuage, pluie). */
@Composable
private fun WeatherIcon(sky: Sky, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        fun sun(center: Offset, radius: Float) {
            repeat(8) { i ->
                val angle = i * Math.PI.toFloat() / 4
                drawLine(
                    Color(0xFFFFC107),
                    center + Offset(cos(angle), sin(angle)) * radius * 1.35f,
                    center + Offset(cos(angle), sin(angle)) * radius * 1.75f,
                    strokeWidth = w * 0.06f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(Color(0xFFFFC107), radius, center)
        }
        fun cloud(color: Color, dx: Float = 0f, dy: Float = 0f) {
            drawCircle(color, w * 0.18f, Offset(w * 0.36f + dx, w * 0.52f + dy))
            drawCircle(color, w * 0.24f, Offset(w * 0.56f + dx, w * 0.44f + dy))
            drawRoundRect(color, Offset(w * 0.18f + dx, w * 0.5f + dy), Size(w * 0.64f, w * 0.2f), CornerRadius(w * 0.1f))
        }
        when (sky) {
            Sky.SUN -> sun(center, w * 0.22f)
            Sky.MOON -> {
                drawCircle(Color(0xFFFFF59D), w * 0.3f, center)
                drawCircle(Color(0xFF1B2A3A), w * 0.26f, center + Offset(w * 0.14f, -w * 0.1f))
            }
            Sky.CLOUD -> cloud(Color(0xFFCFD8DC))
            Sky.SUN_CLOUD -> {
                sun(Offset(w * 0.36f, w * 0.34f), w * 0.15f)
                cloud(Color(0xFFECEFF1), dx = w * 0.06f, dy = w * 0.08f)
            }
            Sky.RAIN -> {
                cloud(Color(0xFFB0BEC5), dy = -w * 0.12f)
                repeat(3) { i ->
                    val x = w * (0.32f + i * 0.18f)
                    drawLine(Color(0xFF64B5F6), Offset(x, w * 0.68f), Offset(x - w * 0.06f, w * 0.88f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun MatchRow(home: String, away: String, score: String, minute: String, resume: String? = null, goal: Float = 0f) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .background(NexTvColors.Live.copy(alpha = 0.3f * goal))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelLogo(null, home, Modifier.size(20.dp), monogramSize = 8.dp)
        Text(home, style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text(score, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NexTvColors.Live)
        Text(away, style = MaterialTheme.typography.labelLarge, color = Color.White)
        ChannelLogo(null, away, Modifier.size(20.dp), monogramSize = 8.dp)
        StatusPill(minute, NexTvColors.Live)
        resume?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f)) }
        if (goal > 0f) StatusPill("BUT !", NexTvColors.Warning, Modifier.alpha(goal))
    }
}

/** Image de la pub en cours (fausse pub), dans sa fenêtre. */
@Composable
private fun AdWindow(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF7043), Color(0xFFD81B60))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOLDES", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("jusqu'à -50 %", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
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

@Composable
private fun Picture(top: Color, bottom: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(top, bottom))))
}

/** Pochette fabriquée pour la radio (pas de réseau dans les tests). */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
@Composable
private fun WithCover(content: @Composable () -> Unit) {
    val art = remember {
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(0xFF00695C.toInt())
            val paint = android.graphics.Paint().apply { color = 0xFFFFB300.toInt() }
            canvas.drawCircle(150f, 150f, 95f, paint)
            paint.color = 0xFF263238.toInt()
            canvas.drawRect(0f, 220f, 300f, 300f, paint)
        }
    }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { art.asImage() },
    ) { Box(Modifier.fillMaxSize()) { content() } }
}

/** 0 avant [from], 1 après [to], en douceur entre les deux. */
private fun ramp(t: Float, from: Float, to: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Image de la vidéo à l'instant [t] (secondes). */
@Composable
private fun AdBreakScene(t: Float) {
    val adStart = 1.5f
    val adEnd = 11.2f
    // 0 : plein écran ; 1 : pub dans le coin, écran affiché.
    val shrink = ramp(t, 2.0f, 2.8f) - ramp(t, adEnd, adEnd + 0.8f)
    // Pub simulée : 15 s de pub par seconde de vidéo.
    val elapsed = ((t - adStart) * 15f).coerceIn(0f, (adEnd - adStart) * 15f).toInt()
    // But de Liverpool pendant la pub, ligne allumée quelques secondes.
    val goalAt = 6.5f
    val goal = ramp(t, goalAt, goalAt + 0.3f) - ramp(t, goalAt + 2.6f, goalAt + 3.2f)
    val moment = BoardMoment(elapsed, time = t, goal = goal, liverpoolScore = if (t >= goalAt) "3 – 0" else "2 – 0")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (shrink > 0f) Box(Modifier.fillMaxSize().alpha(shrink)) { AdBreakBoard(moment, window = false) }
        // La pub (puis le programme) : la même image, réduite dans le coin.
        val corner = WindowWidth.value
        val width = lerp(960f, corner, shrink)
        val scale = width / 960f
        val x = lerp(0f, 960f - 40f - corner, shrink)
        val y = lerp(0f, 28f, shrink)
        Box(
            Modifier
                .requiredSize(960.dp, 540.dp)
                .align(Alignment.TopStart)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = x.dp.toPx()
                    translationY = y.dp.toPx()
                    shape = RoundedCornerShape((12f * shrink / scale).dp)
                    clip = true
                },
        ) {
            if (t >= adStart && t < adEnd) AdScene(t) else ProgrammeScene(t)
        }
        Caption(t)
    }
}

/** Ce qui se passe, en légende en bas, au milieu. */
@Composable
private fun BoxScope.Caption(t: Float) {
    val captions = listOf(
        Triple(0.2f, 1.4f, "Programme en cours sur M6"),
        Triple(1.6f, 3.4f, "Pub détectée : tout s'affiche à côté"),
        Triple(6.4f, 8.6f, "But pendant la pub !"),
        Triple(11.0f, 12.8f, "Fin de la pub : retour au programme"),
    )
    val (from, to, text) = captions.firstOrNull { t >= it.first && t <= it.second } ?: return
    val alpha = minOf(ramp(t, from, from + 0.2f), 1f - ramp(t, to - 0.2f, to))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier
            .align(Alignment.Center)
            .alpha(alpha)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.94f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Fausse pub en plein écran (960 × 540), un peu animée. */
@Composable
private fun AdScene(t: Float) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF7043), Color(0xFFD81B60)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset(x = (((t * 140f) % 1200f) - 600f).dp, y = 150.dp)
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOLDES", fontSize = (96 + 6 * sin(t * 4f)).sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("jusqu'à -50 %", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Faux programme en plein écran (960 × 540), avec le logo de la chaîne. */
@Composable
private fun ProgrammeScene(t: Float) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4F86B8), Color(0xFFE8B07A), Color(0xFF3E5C3A))))) {
        Box(
            Modifier
                .offset(x = (520f + t * 6f).dp, y = 150.dp)
                .size(110.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFE082)),
        )
        Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.BottomStart).background(Color(0xFF2E4A2B)))
        Text(
            "M6",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(28.dp),
        )
    }
}
