package tv.nextv.core.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Couleurs de l'animation d'une radio tirées de la pochette du morceau en cours : un fond très sombre teinté de la
 * couleur dominante, puis trois teintes vives ([a], [b], [c], en ARGB). Pochette sans couleur franche (noir et blanc,
 * gris) : [extract] rend `null` et l'animation garde les couleurs de la radio.
 */
class CoverColors(val background: Int, val a: Int, val b: Int, val c: Int) {
    companion object {
        /** Nombre de familles de teintes (30° chacune). */
        private const val BINS = 12

        /** Pixel coloré : saturation et luminosité minimales. */
        private const val MIN_SATURATION = 0.28f
        private const val MIN_VALUE = 0.3f

        /** Part (pondérée) des pixels qui doit être colorée pour que la pochette donne ses couleurs. */
        private const val MIN_COLORED_SHARE = 0.03f

        /** Une famille de teintes retenue doit peser au moins cette part de la plus forte. */
        private const val MIN_BIN_SHARE = 0.08f

        /**
         * @param pixels pixels de la pochette réduite (ARGB, 48 × 48 suffit).
         * @return `null` si la pochette n'a pas assez de couleur.
         */
        fun extract(pixels: IntArray): CoverColors? {
            if (pixels.isEmpty()) return null
            val weight = FloatArray(BINS)
            val red = FloatArray(BINS)
            val green = FloatArray(BINS)
            val blue = FloatArray(BINS)
            val hsv = FloatArray(3)
            var counted = 0
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) continue
                counted++
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                toHsv(r, g, b, hsv)
                if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue
                val bin = ((hsv[0] / 360f) * BINS).toInt().coerceIn(0, BINS - 1)
                val w = hsv[1] * hsv[2]
                weight[bin] += w
                red[bin] += r * w
                green[bin] += g * w
                blue[bin] += b * w
            }
            if (counted == 0 || weight.sum() < counted * MIN_COLORED_SHARE) return null
            val order = (0 until BINS).sortedByDescending { weight[it] }
            val strongest = weight[order[0]]
            val kept = order.filter { weight[it] >= strongest * MIN_BIN_SHARE }
            // Teintes bien distinctes d'abord (familles non voisines), puis les autres.
            val picked = ArrayList<Int>()
            for (bin in kept) if (picked.none { hueDistance(it, bin) < 2 }) picked += bin
            for (bin in kept) if (picked.size < 3 && bin !in picked) picked += bin
            // La plus présente d'abord.
            val colors = picked.take(3).sortedByDescending { weight[it] }.map { bin ->
                vivid(red[bin] / weight[bin], green[bin] / weight[bin], blue[bin] / weight[bin])
            }.toMutableList()
            // Une ou deux couleurs seulement : les autres en sont des variantes plus claires.
            while (colors.size < 3) colors += lighter(colors[colors.size - 1])
            val dominant = picked[0]
            val background = dark(red[dominant] / weight[dominant], green[dominant] / weight[dominant], blue[dominant] / weight[dominant])
            return CoverColors(background, colors[0], colors[1], colors[2])
        }

        private fun hueDistance(a: Int, b: Int): Int = min(abs(a - b), BINS - abs(a - b))

        /** Couleur ravivée pour briller sur le fond sombre. */
        private fun vivid(r: Float, g: Float, b: Float): Int {
            val hsv = FloatArray(3)
            toHsv(r.toInt(), g.toInt(), b.toInt(), hsv)
            return fromHsv(hsv[0], max(hsv[1], 0.55f).coerceAtMost(0.95f), max(hsv[2], 0.9f))
        }

        private fun lighter(color: Int): Int {
            val hsv = FloatArray(3)
            toHsv((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)
            // Un peu décalée et plus pâle : reste dans la même famille.
            return fromHsv((hsv[0] + 20f) % 360f, hsv[1] * 0.55f, 1f)
        }

        private fun dark(r: Float, g: Float, b: Float): Int {
            val hsv = FloatArray(3)
            toHsv(r.toInt(), g.toInt(), b.toInt(), hsv)
            return fromHsv(hsv[0], min(hsv[1], 0.7f), 0.08f)
        }

        internal fun toHsv(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val high = max(rf, max(gf, bf))
            val low = min(rf, min(gf, bf))
            val delta = high - low
            val hue = when {
                delta == 0f -> 0f
                high == rf -> 60f * (((gf - bf) / delta) % 6f)
                high == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }
            out[0] = if (hue < 0) hue + 360f else hue
            out[1] = if (high == 0f) 0f else delta / high
            out[2] = high
        }

        internal fun fromHsv(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1 - abs((h / 60f) % 2 - 1))
            val m = v - c
            val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun channel(value: Float) = ((value + m) * 255f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
}
