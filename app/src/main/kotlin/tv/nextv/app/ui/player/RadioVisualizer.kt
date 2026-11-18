package tv.nextv.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.nextv.app.data.settings.RadioVisual
import tv.nextv.app.ui.components.ChannelLogo
import tv.nextv.core.audio.SpectrumAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Modes réellement dessinés ([RadioVisual.MIX] passe de l'un à l'autre). */
private val DRAWN = listOf(RadioVisual.SPECTRUM, RadioVisual.WAVES, RadioVisual.AURORA, RadioVisual.PARTICLES, RadioVisual.VINYL)

/** Vinyle : place verticale du disque (−1 haut, 1 bas) et taille de son étiquette (logo). */
private const val VINYL_BIAS = -0.22f
private val VINYL_LABEL = 96.dp

/** Durée de chaque mode en mode « Mélange ». */
private const val MIX_NANOS = 120_000_000_000L

/** Couleurs d'une animation : fond, puis trois teintes vives. Choisies d'après le nom de la radio. */
internal class Palette(val background: Color, val a: Color, val b: Color, val c: Color)

private val PALETTES = listOf(
    Palette(Color(0xFF07060F), Color(0xFFFF3D7F), Color(0xFF7C4DFF), Color(0xFF00E5FF)),
    Palette(Color(0xFF04100C), Color(0xFF00E676), Color(0xFFFFEA00), Color(0xFFFF6D00)),
    Palette(Color(0xFF0A0614), Color(0xFFB388FF), Color(0xFFFF80AB), Color(0xFF82B1FF)),
    Palette(Color(0xFF0F0704), Color(0xFFFF9100), Color(0xFFFF1744), Color(0xFFFFD740)),
    Palette(Color(0xFF020A12), Color(0xFF18FFFF), Color(0xFF2979FF), Color(0xFF64FFDA)),
    Palette(Color(0xFF0C0A02), Color(0xFFC6FF00), Color(0xFF1DE9B6), Color(0xFFFFFF8D)),
)

/**
 * Couleurs qui représentent la radio : celles de son genre (vert, jaune, rouge pour le reggae ; noir, blanc, rouge pour
 * le ska…) ou de la radio elle-même (rose de FIP, vert de Mouv'), sinon une palette tirée de son nom.
 */
internal fun paletteFor(name: String, genre: String?): Palette {
    val text = "$name ${genre.orEmpty()}".lowercase()
    fun has(vararg words: String) = words.any { it in text }
    return when {
        has("reggae", "dub", "rocksteady") && !has("ska") -> Palette(Color(0xFF041207), Color(0xFF00C853), Color(0xFFFFD600), Color(0xFFE53935))
        has("ska") -> Palette(Color(0xFF0A0A0A), Color(0xFFF5F5F5), Color(0xFFE53935), Color(0xFF9E9E9E))
        has("mouv") -> Palette(Color(0xFF02110A), Color(0xFF00FB8E), Color(0xFF00BFA5), Color(0xFFFFFFFF))
        has("rap", "hip-hop", "hip hop", "g-funk", "r&b") -> Palette(Color(0xFF0D0812), Color(0xFFFFC107), Color(0xFF9C27B0), Color(0xFFFF5722))
        has("groove", "funk", "soul", "disco", "beats") -> Palette(Color(0xFF120612), Color(0xFFFF9100), Color(0xFFFF4081), Color(0xFF7C4DFF))
        has("jazz", "blues") -> Palette(Color(0xFF050A18), Color(0xFFFFB300), Color(0xFF1E88E5), Color(0xFF26A69A))
        has("électro", "electro", "house", "dance") -> Palette(Color(0xFF060414), Color(0xFF00E5FF), Color(0xFFD500F9), Color(0xFF651FFF))
        has("monde", "latin", "afro") -> Palette(Color(0xFF140A02), Color(0xFFFF6D00), Color(0xFF00BFA5), Color(0xFFFFD600))
        has("classique", "musique") -> Palette(Color(0xFF0F0A05), Color(0xFFD4AF37), Color(0xFFF5E6C8), Color(0xFF8E2430))
        has("info", "culture", "débat", "economie", "économie", "humour") -> Palette(Color(0xFF050914), Color(0xFF2962FF), Color(0xFFFFFFFF), Color(0xFFE53935))
        has("fip") -> Palette(Color(0xFF14030C), Color(0xFFE2007A), Color(0xFFFF80AB), Color(0xFF7C4DFF))
        has("nrj") -> Palette(Color(0xFF120404), Color(0xFFE53935), Color(0xFFFFFFFF), Color(0xFFFF8A80))
        has("skyrock") -> Palette(Color(0xFF0A0414), Color(0xFF7C4DFF), Color(0xFFE040FB), Color(0xFFFFFFFF))
        else -> PALETTES[Math.floorMod(name.hashCode(), PALETTES.size)]
    }
}

/** État d'une animation, modifié à chaque image (hors Compose : seul [tick] fait redessiner). */
private class VisualFrame(bandCount: Int, particleCount: Int) {
    val bands = FloatArray(bandCount)
    val peaks = FloatArray(bandCount)
    var level = 0f
    var bass = 0f
    var seconds = 0f
    var startNanos = 0L
    var lastNanos = 0L

    // Ondes : anneaux partis du centre (rayon relatif, opacité), battement des basses.
    val rings = ArrayList<FloatArray>()
    var bassAverage = 0f
    var lastBeat = 0f
    var nextRing = 0f

    // Particules : angle, distance (0 à 1+), vitesse, taille, teinte (0 à 2).
    val particles = Array(particleCount) { FloatArray(5) }
    var particlesReady = false

    // Vinyle : angle du disque.
    var spin = 0f
}

/**
 * Animation plein écran d'une radio ou Web radio ([RadioVisual]), au rythme du son ([analyzer]) quand le lecteur de
 * l'appli le décode, sinon un mouvement doux de remplacement (moteur VLC). En dessous : logo, nom, genre et morceau en
 * cours. [lowPower] (petite Fire TV) : 30 images par seconde et moins de particules.
 */
@Composable
fun RadioVisualizer(
    visual: RadioVisual,
    analyzer: SpectrumAnalyzer,
    name: String,
    logoUrl: String?,
    genre: String?,
    title: String?,
    lowPower: Boolean,
    /** Pochette du morceau en cours, à la place du logo dans la carte. */
    cover: String? = null,
    modifier: Modifier = Modifier,
    /** Captures d'écran : quelques secondes d'animation déjà jouées, puis image fixe ([animate] faux). */
    warmUpSeconds: Int = 0,
    animate: Boolean = true,
) {
    val palette = remember(name, genre) { paletteFor(name, genre) }
    val frame = remember(lowPower) {
        VisualFrame(analyzer.bandCount, if (lowPower) 70 else 150).also { warm ->
            repeat(warmUpSeconds * 30) { step ->
                warm.seconds = step / 30f
                update(warm, analyzer, 1f / 30, nowNanos = step * 33_333_333L)
            }
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    var drawn by remember(visual, name) { mutableStateOf(firstMode(visual, name)) }

    DisposableEffect(analyzer) {
        analyzer.active = true
        onDispose { analyzer.active = false }
    }
    LaunchedEffect(analyzer, lowPower, visual, name, animate) {
        if (!animate) return@LaunchedEffect
        var skip = false
        while (true) {
            withFrameNanos { now ->
                // Petite Fire TV : une image sur deux.
                skip = lowPower && !skip
                if (skip) return@withFrameNanos
                if (frame.startNanos == 0L) {
                    frame.startNanos = now
                    frame.lastNanos = now
                }
                val elapsed = ((now - frame.lastNanos) / 1e9f).coerceIn(0f, 0.25f)
                frame.lastNanos = now
                frame.seconds = (now - frame.startNanos) / 1e9f
                update(frame, analyzer, elapsed, System.nanoTime())
                if (visual == RadioVisual.MIX) drawn = mixMode(name, now - frame.startNanos)
                tick++
            }
        }
    }

    Box(modifier.fillMaxSize().background(palette.background)) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            tick // relu ici : seule l'image est redessinée, sans recomposition.
            when (drawn) {
                RadioVisual.WAVES -> drawWaves(frame, palette)
                RadioVisual.AURORA -> drawAurora(frame, palette)
                RadioVisual.PARTICLES -> drawParticles(frame, palette)
                RadioVisual.VINYL -> drawVinyl(frame, palette)
                else -> drawSpectrum(frame, palette)
            }
        }
        // Le texte glisse très lentement (quelques dizaines de pixels en plusieurs minutes) : pas de marque sur l'écran.
        val drift = remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(animate) {
            while (animate) {
                withFrameNanos { now -> drift.value = Offset((sin(now / 9e10) * 24).toFloat(), (cos(now / 1.3e11) * 14).toFloat()) }
                kotlinx.coroutines.delay(1_000)
            }
        }
        Row(
            Modifier
                .align(if (drawn == RadioVisual.VINYL) Alignment.BottomCenter else Alignment.BottomStart)
                .offset(drift.value.x.dp, drift.value.y.dp)
                .padding(horizontal = 56.dp, vertical = 44.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (cover != null) {
                coil3.compose.AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
                )
            } else {
                ChannelLogo(url = logoUrl, name = name, modifier = Modifier.size(64.dp), shape = RoundedCornerShape(14.dp), monogramSize = 22.dp)
            }
            Column(Modifier.widthIn(max = 560.dp)) {
                Text(name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                genre?.let { Text(it, color = palette.c.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1) }
                title?.let {
                    Text("♪  $it", color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (drawn == RadioVisual.VINYL) {
            // Étiquette du disque : le logo au centre.
            ChannelLogo(
                url = logoUrl,
                name = name,
                modifier = Modifier.align(androidx.compose.ui.BiasAlignment(0f, VINYL_BIAS)).size(VINYL_LABEL),
                shape = CircleShape,
                monogramSize = 30.dp,
            )
        }
    }
}

private fun firstMode(visual: RadioVisual, name: String): RadioVisual =
    if (visual == RadioVisual.MIX) mixMode(name, 0) else visual

/** Mode « Mélange » : un mode par radio au départ, puis le suivant toutes les deux minutes. */
internal fun mixMode(name: String, elapsedNanos: Long): RadioVisual =
    DRAWN[Math.floorMod(name.hashCode().toLong() + elapsedNanos / MIX_NANOS, DRAWN.size.toLong()).toInt()]

/** Bandes du son (ou mouvement de remplacement sans son analysé), niveaux, crêtes et battements. */
private fun update(frame: VisualFrame, analyzer: SpectrumAnalyzer, elapsed: Float, nowNanos: Long) {
    if (analyzer.hasSound(android.os.SystemClock.elapsedRealtimeNanos())) {
        analyzer.read(frame.bands, elapsed, android.os.SystemClock.elapsedRealtimeNanos())
        frame.level = analyzer.level
        frame.bass = analyzer.bass
    } else {
        // Pas de son analysé (moteur VLC, son passé tel quel à l'ampli) : une houle douce, toujours vivante.
        val t = nowNanos / 1e9f
        val n = frame.bands.size
        for (i in 0 until n) {
            val x = i.toFloat() / n
            val goal = (0.35f + 0.25f * sin(t * 1.3f + x * 7f) + 0.15f * sin(t * 2.9f - x * 13f)) * (1f - 0.45f * x)
            frame.bands[i] += (goal.coerceIn(0f, 1f) - frame.bands[i]) * min(1f, elapsed * 4f)
        }
        frame.level = 0.45f + 0.1f * sin(t * 0.8f)
        frame.bass = (0.4f + 0.35f * max(0f, sin(t * PI.toFloat() * 1.0f))).coerceIn(0f, 1f)
    }
    for (i in frame.bands.indices) {
        frame.peaks[i] = max(frame.bands[i], frame.peaks[i] - elapsed * 0.35f)
    }
    // Battement : les basses dépassent nettement leur moyenne récente.
    frame.bassAverage += (frame.bass - frame.bassAverage) * min(1f, elapsed * 2f)
    val beat = frame.bass > 0.3f && frame.bass > frame.bassAverage * 1.18f && frame.seconds - frame.lastBeat > 0.28f
    if (beat || frame.seconds >= frame.nextRing) {
        frame.lastBeat = frame.seconds
        frame.nextRing = frame.seconds + 1.4f
        if (frame.rings.size < 14) frame.rings += floatArrayOf(0.08f, 0.55f + 0.45f * frame.bass, (frame.rings.size % 3).toFloat())
    }
    val ringIterator = frame.rings.iterator()
    while (ringIterator.hasNext()) {
        val ring = ringIterator.next()
        ring[0] += elapsed * (0.28f + 0.25f * frame.level)
        ring[1] -= elapsed * 0.32f
        if (ring[1] <= 0f || ring[0] > 1.6f) ringIterator.remove()
    }
    if (!frame.particlesReady) {
        frame.particles.forEach { respawn(it, initial = true) }
        frame.particlesReady = true
    }
    val boost = 0.15f + 1.6f * frame.level * frame.level + 0.8f * frame.bass
    for (p in frame.particles) {
        p[1] += elapsed * p[2] * boost * (0.15f + p[1])
        if (p[1] > 1.25f) respawn(p, initial = false)
    }
    frame.spin += elapsed * (3.49f * (0.85f + 0.3f * frame.level))
}

private fun respawn(p: FloatArray, initial: Boolean) {
    p[0] = Random.nextFloat() * 2f * PI.toFloat()
    p[1] = if (initial) Random.nextFloat() * 1.2f else Random.nextFloat() * 0.05f
    p[2] = 0.25f + Random.nextFloat() * 0.75f
    p[3] = 1f + Random.nextFloat() * 2.2f
    p[4] = Random.nextInt(3).toFloat()
}

private fun Palette.tint(index: Int): Color = when (index % 3) {
    0 -> a
    1 -> b
    else -> c
}

/** Lueur de fond qui respire avec la musique. */
private fun DrawScope.glow(frame: VisualFrame, palette: Palette, color: Color = palette.b, center: Offset = this.center) {
    val radius = size.minDimension * (0.55f + 0.25f * frame.level)
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.18f + 0.22f * frame.bass), Color.Transparent), center, radius),
        radius,
        center,
    )
}

/** Spectre : barres en miroir au milieu de l'écran, dégradé, crêtes qui retombent, reflet. */
private fun DrawScope.drawSpectrum(frame: VisualFrame, palette: Palette) {
    glow(frame, palette)
    val n = frame.bands.size
    val total = size.width * 0.84f
    val slot = total / (n * 2)
    val barWidth = slot * 0.64f
    val left = (size.width - total) / 2
    val baseline = size.height * 0.58f
    val maxHeight = size.height * 0.36f
    val brush = Brush.verticalGradient(listOf(palette.a, palette.b, palette.c), startY = baseline - maxHeight, endY = baseline)
    val reflection = Brush.verticalGradient(listOf(palette.c.copy(alpha = 0.28f), Color.Transparent), startY = baseline, endY = baseline + maxHeight * 0.45f)
    for (i in 0 until n * 2) {
        // Grave au centre, aigu vers les bords.
        val band = if (i < n) n - 1 - i else i - n
        val height = max(4f, frame.bands[band] * maxHeight)
        val x = left + i * slot + (slot - barWidth) / 2
        drawRoundRect(brush, Offset(x, baseline - height), Size(barWidth, height), androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
        drawRect(reflection, Offset(x, baseline + 3f), Size(barWidth, height * 0.45f))
        val peak = baseline - max(6f, frame.peaks[band] * maxHeight) - 8f
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x, peak), Size(barWidth, 4f), androidx.compose.ui.geometry.CornerRadius(2f))
    }
}

/** Ondes : anneaux partis du centre à chaque battement, cœur qui pulse. */
private fun DrawScope.drawWaves(frame: VisualFrame, palette: Palette) {
    glow(frame, palette, palette.a)
    val maxRadius = size.maxDimension * 0.6f
    for (ring in frame.rings) {
        val color = palette.tint(ring[2].toInt())
        drawCircle(color.copy(alpha = ring[1].coerceIn(0f, 1f) * 0.8f), ring[0] * maxRadius, center, style = Stroke(width = 3f + 10f * ring[1]))
    }
    // Couronne du spectre autour du cœur.
    val n = frame.bands.size
    val inner = size.minDimension * 0.16f * (1f + 0.18f * frame.bass)
    for (i in 0 until n * 2) {
        val band = if (i < n) i else 2 * n - 1 - i
        val angle = (i.toFloat() / (n * 2)) * 2f * PI.toFloat() - PI.toFloat() / 2
        val length = 8f + frame.bands[band] * size.minDimension * 0.14f
        val start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner)
        val end = Offset(center.x + cos(angle) * (inner + length), center.y + sin(angle) * (inner + length))
        drawLine(palette.tint(i / 6).copy(alpha = 0.9f), start, end, strokeWidth = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
    drawCircle(
        Brush.radialGradient(listOf(palette.c.copy(alpha = 0.9f), palette.b.copy(alpha = 0.5f), Color.Transparent), center, inner),
        inner,
        center,
    )
}

/** Aurore : grands voiles colorés qui dérivent lentement et s'éclairent avec les graves, les médiums et les aigus. */
private fun DrawScope.drawAurora(frame: VisualFrame, palette: Palette) {
    val t = frame.seconds
    val n = frame.bands.size
    fun range(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to) sum += frame.bands[i]
        return sum / (to - from)
    }
    val energies = floatArrayOf(range(0, n / 4), range(n / 4, n / 2), range(n / 2, 3 * n / 4), range(3 * n / 4, n), frame.level)
    for (i in 0 until 5) {
        val phase = i * 1.7f
        val x = size.width * (0.5f + 0.38f * sin(t * (0.07f + 0.013f * i) + phase))
        val y = size.height * (0.5f + 0.32f * cos(t * (0.05f + 0.017f * i) + phase * 0.7f))
        val radius = size.minDimension * (0.42f + 0.22f * energies[i])
        val color = palette.tint(i)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f + 0.4f * energies[i]), Color.Transparent), Offset(x, y), radius), radius, Offset(x, y))
    }
    // Rideaux : rubans ondulants en haut de l'écran.
    for (k in 0 until 3) {
        val color = palette.tint(k + 1).copy(alpha = 0.10f + 0.18f * energies[k])
        val path = androidx.compose.ui.graphics.Path()
        val top = size.height * (0.18f + 0.1f * k)
        path.moveTo(0f, top)
        var x = 0f
        while (x <= size.width) {
            val y = top + sin(x / size.width * 6f + t * (0.5f + 0.2f * k)) * size.height * (0.05f + 0.06f * energies[k])
            path.lineTo(x, y)
            x += size.width / 48
        }
        path.lineTo(size.width, top + size.height * 0.35f)
        path.lineTo(0f, top + size.height * 0.35f)
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(color, Color.Transparent), startY = top - size.height * 0.08f, endY = top + size.height * 0.35f))
    }
}

/** Particules : traînées qui jaillissent du centre, de plus en plus vite et lumineuses vers les bords. */
private fun DrawScope.drawParticles(frame: VisualFrame, palette: Palette) {
    glow(frame, palette, palette.c)
    val reach = size.maxDimension * 0.62f
    for (p in frame.particles) {
        val distance = p[1] * reach
        val dx = cos(p[0])
        val dy = sin(p[0])
        val position = Offset(center.x + dx * distance, center.y + dy * distance)
        val alpha = (p[1] * 1.4f).coerceIn(0f, 1f)
        val color = palette.tint(p[4].toInt())
        val trail = 6f + distance * 0.06f * (0.5f + frame.level)
        drawLine(color.copy(alpha = alpha * 0.55f), Offset(position.x - dx * trail, position.y - dy * trail), position, strokeWidth = p[3])
        drawCircle(color.copy(alpha = alpha), p[3] * (1f + 0.6f * frame.bass) * (0.6f + p[1]), position)
    }
}

/** Vinyle : disque qui tourne, sillons éclairés par le spectre, reflet tournant, halo des basses. */
private fun DrawScope.drawVinyl(frame: VisualFrame, palette: Palette) {
    val radius = size.minDimension * 0.29f
    // Même centre que le logo posé dessus (VINYL_BIAS), au-dessus de la carte du nom.
    val label = VINYL_LABEL.toPx()
    val center = Offset(size.width / 2, (1f + VINYL_BIAS) / 2 * (size.height - label) + label / 2)
    glow(frame, palette, palette.a, center)
    drawCircle(palette.b.copy(alpha = 0.25f + 0.4f * frame.bass), radius * (1.04f + 0.06f * frame.bass), center, style = Stroke(width = 6f))
    drawCircle(Color(0xFF0B0B0E), radius, center)
    val n = frame.bands.size
    val labelRadius = radius * 0.34f
    for (i in 0 until n) {
        val r = labelRadius + (radius - labelRadius) * (i + 0.5f) / n
        val energy = frame.bands[n - 1 - i]
        drawCircle(palette.tint(i / 8).copy(alpha = 0.08f + 0.6f * energy), r, center, style = Stroke(width = 1.5f + 2.5f * energy))
    }
    rotate(frame.spin * 180f / PI.toFloat(), center) {
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                center,
            ),
            radius,
            center,
        )
    }
    drawCircle(Color(0xFF1A1A1F), labelRadius * 1.02f, center)
}

/** Précision sous le nom du mode dans le choix des réglages. */
internal fun RadioVisual.detailRes(): Int? = when (this) {
    RadioVisual.SPECTRUM -> tv.nextv.app.R.string.radio_visual_spectrum_detail
    RadioVisual.WAVES -> tv.nextv.app.R.string.radio_visual_waves_detail
    RadioVisual.AURORA -> tv.nextv.app.R.string.radio_visual_aurora_detail
    RadioVisual.PARTICLES -> tv.nextv.app.R.string.radio_visual_particles_detail
    RadioVisual.VINYL -> tv.nextv.app.R.string.radio_visual_vinyl_detail
    RadioVisual.OFF, RadioVisual.MIX -> null
}

/** Nom du mode dans les réglages. */
internal fun RadioVisual.labelRes(): Int = when (this) {
    RadioVisual.OFF -> tv.nextv.app.R.string.radio_visual_off
    RadioVisual.SPECTRUM -> tv.nextv.app.R.string.radio_visual_spectrum
    RadioVisual.WAVES -> tv.nextv.app.R.string.radio_visual_waves
    RadioVisual.AURORA -> tv.nextv.app.R.string.radio_visual_aurora
    RadioVisual.PARTICLES -> tv.nextv.app.R.string.radio_visual_particles
    RadioVisual.VINYL -> tv.nextv.app.R.string.radio_visual_vinyl
    RadioVisual.MIX -> tv.nextv.app.R.string.radio_visual_mix
}
