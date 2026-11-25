package tv.nextv.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assume.assumeTrue
import org.junit.Rule
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.nextv.app.data.settings.RadioVisual
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.app.ui.components.StatusPill
import tv.nextv.app.ui.player.RadioVisualizer
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.audio.SpectrumAnalyzer
import java.util.TimeZone

/**
 * Maquettes « pendant les pubs » (pas encore dans l'appli) : la pub continue en petit en haut à droite, le reste de
 * l'écran montre autre chose (programme, scores, radio). Captures dans `app/build/screenshots/tv_50*`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class AdBreakMockups {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/screenshots/tv_$name.png") {
            NexTvTheme(ThemeStyle.NIGHT) { content() }
        }
    }

    @Test
    fun programme() = capture("501_pub_programme") {
        ProgrammeBackground()
        Frame(channel = "M6", adStatus = "depuis 1 min 20", adEnd = "Fin prévue dans 2 min", adProgress = 0.4f) { ProgrammePanel() }
    }

    @Test
    fun scores() = capture("502_pub_scores") {
        ScoresBackground()
        Frame(channel = "Canal+ Foot", adStatus = "depuis 40 s", adEnd = "Durée habituelle : 3 min", adProgress = 0.22f) { ScoresPanel(onThisChannel = true) }
    }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    @Test
    fun radio() = capture("503_pub_radio") {
        WithCover {
            RadioBackground()
            Frame(channel = null, adStatus = "depuis 1 min 20", adEnd = "Pub sur M6 · FIP Groove à la place", adProgress = 0.4f, windowWidth = RadioWindowWidth) {}
        }
    }
}

/** Cadre commun : à gauche le contenu, en haut à droite la pub en petit avec sa durée, en bas les touches. */
@Composable
private fun Frame(
    channel: String?,
    adStatus: String,
    adEnd: String?,
    adProgress: Float,
    windowWidth: Dp = WindowWidth,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxHeight().width(500.dp)) {
            if (channel != null) {
                ChannelHeader(channel)
                Spacer(Modifier.height(22.dp))
            }
            content()
        }
        Column(Modifier.align(Alignment.TopEnd).width(windowWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdWindow(Modifier.fillMaxWidth().height(windowWidth * 9 / 16))
            AdStatus(adStatus, adEnd, adProgress)
        }
        Hints(Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun ChannelHeader(channel: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelLogo(null, channel, Modifier.size(34.dp), monogramSize = 11.dp)
        Text(channel, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text("· le programme reprend après la pub", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
private fun AdStatus(adStatus: String, adEnd: String?, adProgress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Pub", NexTvColors.Warning)
            Text(adStatus, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        Bar(adProgress, NexTvColors.Warning, Modifier.fillMaxWidth())
        adEnd?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f)) }
    }
}

@Composable
private fun Hints(modifier: Modifier) {
    Text(
        "OK  plein écran      ◀ ▶  autre écran      Retour  ce n'est pas une pub",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.5f),
        modifier = modifier,
    )
}

@Composable
private fun ProgrammeBackground() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2F6B5E), Color(0xFF1F3B57), Color(0xFF0B0F14)))))
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.35f)))))
}

@Composable
private fun ScoresBackground() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0E1A2B), Color(0xFF0B0F14)))))
}

@Composable
private fun RadioBackground() {
    RadioVisualizer(
        visual = RadioVisual.VINYL,
        analyzer = remember { SpectrumAnalyzer() },
        name = "FIP Groove",
        logoUrl = null,
        genre = "Groove, funk, soul",
        title = "Move On Up",
        artist = "Curtis Mayfield",
        cover = "https://exemple.invalid/pochette.jpg",
        lowPower = false,
        warmUpSeconds = 4,
        animate = false,
    )
}

/** Pochette fabriquée pour le vinyle (pas de réseau dans les tests). */
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

@Composable
private fun ProgrammePanel() {
    Column {
        Label("En ce moment")
        Text("Zone interdite", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Vacances au camping : les nouveaux rois de l'été", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill("Magazine", NexTvColors.Accent)
            Text("21:10 – 23:00", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text("Reste 48 min", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(8.dp))
        Bar(0.56f, NexTvColors.Live, Modifier.width(420.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Chaque été, des milliers de familles posent leurs valises dans les campings du littoral. Entre animations, " +
                "emplacements les plus prisés et nouveaux mobil-homes haut de gamme, enquête sur un secteur en plein essor.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(22.dp))
        Label("Ensuite")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Picture(Color(0xFF7A3E2E), Color(0xFF2B1B3D), Modifier.size(150.dp, 84.dp))
            Column(Modifier.weight(1f)) {
                Text("23:00 · Enquête exclusive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Magazine · 1 h 10", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                Text(
                    "Plongée dans les coulisses des grands palaces de la Côte d'Azur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Puis 00:10 · 66 minutes : grand format", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun ScoresPanel(onThisChannel: Boolean) {
    Column {
        Label("Ligue des champions · en direct")
        Spacer(Modifier.height(6.dp))
        MatchLine(
            "PSG", "Roma", "2 – 1", "67'", scorers = "12' L. Martin, 54' K. Diallo · 38' M. Rossi",
            note = "Sur cette chaîne".takeIf { onThisChannel }, highlight = onThisChannel,
        )
        MatchLine("Liverpool", "Bruges", "3 – 0", "71'", scorers = "But à la 70' · 9' A. Smith, 33' A. Smith, 70' J. Clark", goal = true)
        MatchLine("Bayern", "Lille", "1 – 1", "Mi-temps", resume = "Reprise dans 9 min", scorers = "22' T. Weber · 41' N. Dupont")
        MatchLine("Inter", "Stuttgart", "0 – 0", "58'")
        Spacer(Modifier.height(14.dp))
        Label("À suivre")
        Text("Demain 18:45 · Marseille – Ajax · Canal+ Foot", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        Text("Samedi 21:05 · Lyon – Lens · DAZN 1", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
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
private fun MatchLine(
    home: String,
    away: String,
    score: String,
    minute: String,
    scorers: String? = null,
    resume: String? = null,
    note: String? = null,
    highlight: Boolean = false,
    goal: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    goal -> NexTvColors.Live.copy(alpha = 0.22f)
                    highlight -> Color.White.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(null, home, Modifier.size(24.dp), monogramSize = 9.dp)
            Text(home, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(score, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NexTvColors.Live)
            Text(away, style = MaterialTheme.typography.titleSmall, color = Color.White)
            ChannelLogo(null, away, Modifier.size(24.dp), monogramSize = 9.dp)
            StatusPill(minute, NexTvColors.Live)
            resume?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f)) }
            if (goal) StatusPill("BUT !", NexTvColors.Warning)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = NexTvColors.Accent) }
        }
        scorers?.let {
            Text("⚽ $it", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(color))
    }
}

@Composable
private fun Picture(top: Color, bottom: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(top, bottom))))
}

/** Fenêtre de la pub dans un coin (plus petite sur l'écran radio : la pochette est à côté). */
private val WindowWidth = 340.dp
private val RadioWindowWidth = 260.dp

/**
 * Vidéo de démonstration (14 s) : programme ; pub détectée, elle se réduit dans le coin et le panneau Programme
 * apparaît ; ▶ Scores ; ▶ Radio ; fin de la pub, retour en plein écran. Une image par 1/20 s dans `build/ad_video/`
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
        const val VIDEO_SECONDS = 14.5f
    }
}

/** 0 avant [from], 1 après [to], en douceur entre les deux. */
private fun ramp(t: Float, from: Float, to: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Image de la scène à l'instant [t] (secondes) de la vidéo. */
@Composable
private fun AdBreakScene(t: Float) {
    val adStart = 1.5f
    val adEnd = 12f
    // 0 : pub en plein écran ; 1 : pub dans le coin, panneau affiché.
    val shrink = ramp(t, 2.0f, 2.8f) - ramp(t, adEnd, adEnd + 0.8f)
    val toScores = ramp(t, 6.0f, 6.6f)
    val toRadio = ramp(t, 9.0f, 9.6f)
    // Pub simulée : 12 s de pub par seconde de vidéo, page prévue de 2 min 30.
    val elapsed = ((t - adStart) * 12f).coerceIn(0f, (adEnd - adStart) * 12f).toInt()
    val planned = 150
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (shrink > 0f) {
            Box(Modifier.fillMaxSize().alpha(shrink)) {
                // Chaque écran a sa place : -1 à gauche, 0 affiché, 1 à droite.
                PanelLayer(position = -toScores, background = { ProgrammeBackground() }) { PanelColumn { ProgrammePanel() } }
                PanelLayer(position = 1 - toScores - toRadio, background = { ScoresBackground() }) { PanelColumn { ScoresPanel(onThisChannel = false) } }
                PanelLayer(position = 1 - toRadio, background = { RadioBackground() }) {}
                // Nom de la chaîne, fixe (l'écran radio a le sien).
                Box(Modifier.padding(horizontal = 40.dp, vertical = 28.dp).alpha(1f - toRadio)) { ChannelHeader("M6") }
            }
        }
        // La pub (puis le programme) : la même image, réduite dans le coin.
        val cornerWidth = lerp(WindowWidth.value, RadioWindowWidth.value, toRadio)
        val cornerX = 960f - 40f - cornerWidth
        val width = lerp(960f, cornerWidth, shrink)
        val scale = width / 960f
        val x = lerp(0f, cornerX, shrink)
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
        // Durée de la pub : seulement une fois la fenêtre à sa place (sinon par-dessus la pub qui se réduit).
        val settled = ((shrink - 0.85f) / 0.15f).coerceIn(0f, 1f)
        if (shrink > 0f) {
            Column(
                Modifier
                    .offset(x = cornerX.dp, y = (28f + cornerWidth * 9 / 16 + 8f).dp)
                    .width(cornerWidth.dp)
                    .alpha(settled),
            ) {
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                val since = if (minutes == 0) "depuis $seconds s" else "depuis $minutes min %02d".format(seconds)
                val left = planned - elapsed
                val end = when {
                    toRadio > 0.5f -> "Pub sur M6 · FIP Groove à la place"
                    left < 60 -> "Fin prévue dans moins d'1 min"
                    else -> "Fin prévue dans ${(left + 59) / 60} min"
                }
                AdStatus(since, end, elapsed / planned.toFloat())
            }
            Hints(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 28.dp).alpha(shrink))
        }
        Caption(t)
    }
}

@Composable
private fun PanelLayer(position: Float, background: @Composable () -> Unit, content: @Composable () -> Unit) {
    // Fond en fondu enchaîné ; texte : l'écran qui part s'efface avant que le suivant n'arrive.
    val shown = (1f - kotlin.math.abs(position)).coerceIn(0f, 1f)
    if (shown <= 0f) return
    val text = (1f - 2 * kotlin.math.abs(position)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().alpha(shown)) {
        background()
        Box(Modifier.fillMaxSize().offset(x = (position * 80f).dp).alpha(text)) { content() }
    }
}

@Composable
private fun PanelColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp).width(500.dp)) {
        Spacer(Modifier.height(56.dp))
        content()
    }
}

/** Ce qui se passe, en légende en bas à gauche. */
@Composable
private fun BoxScope.Caption(t: Float) {
    val captions = listOf(
        Triple(0.2f, 1.4f, "Programme en cours sur M6"),
        Triple(1.6f, 3.0f, "Pub détectée"),
        Triple(5.5f, 6.8f, "▶  écran suivant : les scores"),
        Triple(8.5f, 9.8f, "▶  écran suivant : la radio"),
        Triple(11.8f, 13.6f, "Fin de la pub : retour au programme"),
    )
    val (from, to, text) = captions.firstOrNull { t >= it.first && t <= it.second } ?: return
    val alpha = minOf(ramp(t, from, from + 0.2f), 1f - ramp(t, to - 0.2f, to))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 40.dp, bottom = 24.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 18.dp, vertical = 8.dp),
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
            Text("SOLDES", fontSize = (96 + 6 * kotlin.math.sin(t * 4f)).sp, fontWeight = FontWeight.Black, color = Color.White)
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
