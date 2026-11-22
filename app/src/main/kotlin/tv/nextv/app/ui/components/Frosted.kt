package tv.nextv.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Contenu légèrement flouté sous un menu ou un volet ouvert ([active]) : seulement la bande de gauche que le menu
 * recouvre (largeur [width], floutée en entier), puis un court fondu au-delà de son bord ; le reste de l'écran reste
 * net. Le flou de Compose
 * (`Modifier.blur`) n'existe qu'à partir d'Android 12, que la Fire TV n'a pas : à l'ouverture, l'image de
 * cette bande est prise une fois, réduite, floutée, puis posée par-dessus en fondu ; retirée d'un coup à la fermeture.
 *
 * [contentKey] : ce que montre le contenu (la rubrique). S'il change, l'image floutée de l'ancien contenu disparaît tout
 * de suite : rubrique choisie dans le menu, elle restait en fondu par-dessus la nouvelle page pendant que le menu se
 * refermait (titre et affiches de l'ancienne page en transparence, vu en vidéo sur l'appareil).
 */
@Composable
fun Frosted(active: Boolean, width: Dp, modifier: Modifier = Modifier, contentKey: Any? = null, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val background = MaterialTheme.colorScheme.background.toArgb()
    // Le fondu vers le net commence au bord du menu, pas avant : son bord droit, translucide, laissait voir le net.
    val stripPx = with(LocalDensity.current) { (width.toPx() / (1f - FROST_FADE)).roundToInt() }
    var frosted by remember(contentKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(active, contentKey) {
        if (!active) return@LaunchedEffect
        // Une image pour que le contenu soit dessiné tel qu'il est à l'ouverture.
        withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@LaunchedEffect
        frosted = withContext(Dispatchers.Default) { runCatching { blurred(shot.asAndroidBitmap(), stripPx, background) }.getOrNull() }
    }
    // Fondu à l'ouverture seulement. À la fermeture, l'image disparaît d'un coup : en fondu, l'ancien contenu flouté
    // restait en transparence sur le nouveau (catégorie choisie dans le volet : rangée et bandeau déjà changés).
    val alpha by animateFloatAsState(if (active && frosted != null) 1f else 0f, tween(if (active) 200 else 0), label = "frosted")
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            val image = frosted
            if (active && image != null && alpha > 0f) {
                drawImage(
                    image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(stripPx.coerceAtMost(size.width.roundToInt()), size.height.roundToInt()),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
        },
    ) { content() }
}

/**
 * Réduction de l’image : un pixel pour 6 de l’écran, le flou est ensuite étiré, sans coût. Assez fort pour que les
 * mots coupés par le bord du menu ne se lisent plus (avec 4 et 2, « …rame », « champi… » restaient lisibles à côté du
 * menu ouvert et semblaient des restes de l'écran).
 */
private const val FROST_SCALE = 6
private const val FROST_RADIUS = 3

/** Part de la bande, à droite (au-delà du menu), où le flou s'efface : pas de ligne nette au bord du menu. */
private const val FROST_FADE = 0.25f

private fun blurred(source: Bitmap, stripPx: Int, background: Int): ImageBitmap {
    val strip = stripPx.coerceIn(1, source.width)
    val width = (strip / FROST_SCALE).coerceAtLeast(1)
    val height = (source.height / FROST_SCALE).coerceAtLeast(1)
    // Image en mémoire graphique (Android 8 et après) : recopiée pour pouvoir la lire.
    val hardware = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) source.copy(Bitmap.Config.ARGB_8888, false) else source
    // Posée sur le fond de l'application : une image opaque. Floutés tels quels, les éléments translucides
    // (badges, halos) laissaient des traits de couleur sur leur bord, et le contenu net se voyait à travers.
    val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(small).apply {
        drawColor(background or (0xFF shl 24))
        drawBitmap(readable, Rect(0, 0, strip, source.height), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    boxBlur(pixels, width, height, FROST_RADIUS)
    fadeRight(pixels, width, height, FROST_FADE)
    small.setPixels(pixels, 0, width, 0, 0, width, height)
    return small.asImageBitmap()
}

/** Efface peu à peu l'image (opaque) vers son bord droit, sur la part [share] de sa largeur. */
internal fun fadeRight(pixels: IntArray, width: Int, height: Int, share: Float) {
    val columns = (width * share).roundToInt().coerceIn(0, width)
    for (i in 0 until columns) {
        val x = width - columns + i
        val alpha = 255 * (columns - 1 - i) / columns
        for (y in 0 until height) {
            val at = y * width + x
            pixels[at] = (alpha shl 24) or (pixels[at] and 0xFFFFFF)
        }
    }
}

/** Flou en boîte, deux passes horizontales et verticales (proche d'un flou gaussien). */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val buffer = IntArray(pixels.size)
    repeat(2) {
        blurPass(pixels, buffer, width, height, radius, horizontal = true)
        blurPass(buffer, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurPass(from: IntArray, to: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val clamped = i.coerceIn(0, length - 1)
            return if (horizontal) line * width + clamped else clamped * width + line
        }
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = from[at(i)]
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            to[at(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = from[at(i - radius)]
            val inn = from[at(i + radius + 1)]
            a += (inn ushr 24) - (out ushr 24)
            r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
