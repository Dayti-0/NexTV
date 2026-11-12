package tv.nextv.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Contenu légèrement flouté quand [active] (derrière un menu ou un volet ouvert). Le flou de Compose
 * (`Modifier.blur`) n'existe qu'à partir d'Android 12, que la Fire TV n'a pas : à l'ouverture, l'image du
 * contenu est prise une fois, réduite, floutée, puis posée par-dessus en fondu. Le contenu ne bouge pas tant
 * que le menu a la sélection ; une vidéo (aperçu en direct) reste nette, elle n'est pas dans l'image.
 */
@Composable
fun Frosted(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var frosted by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Une image pour que le contenu soit dessiné tel qu'il est à l'ouverture.
        withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@LaunchedEffect
        frosted = withContext(Dispatchers.Default) { runCatching { blurred(shot.asAndroidBitmap()) }.getOrNull() }
    }
    val alpha by animateFloatAsState(if (active && frosted != null) 1f else 0f, tween(if (active) 200 else 160), label = "frosted")
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            val image = frosted
            if (image != null && alpha > 0f) {
                drawImage(
                    image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
        },
    ) { content() }
}

/** Largeur de l’image réduite : un pixel pour ~4 de l’écran, le flou (léger) est ensuite étiré, sans coût. */
private const val FROST_WIDTH = 480
private const val FROST_RADIUS = 2

private fun blurred(source: Bitmap): ImageBitmap {
    val width = FROST_WIDTH.coerceAtMost(source.width).coerceAtLeast(1)
    val height = (source.height * width / source.width.toFloat()).roundToInt().coerceAtLeast(1)
    val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val small = Bitmap.createScaledBitmap(readable, width, height, true)
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    boxBlur(pixels, width, height, FROST_RADIUS)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out.asImageBitmap()
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
