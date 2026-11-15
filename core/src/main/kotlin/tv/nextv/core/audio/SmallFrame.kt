package tv.nextv.core.audio

import kotlin.math.abs

/**
 * Image très réduite (96 × 54, niveaux de gris) pour comparer deux instants de la vidéo : même plan ou
 * autre plan, image unie ou non. Tirée de l'image 480 × 270 du lecteur par moyenne de pavés de 5 × 5 points.
 */
object SmallFrame {
    const val WIDTH = 96
    const val HEIGHT = 54
    const val SIZE = WIDTH * HEIGHT

    /** Réduit [gray] ([width] × [height], multiples de [WIDTH] × [HEIGHT]) ; `null` si la taille ne convient pas. */
    fun reduce(gray: ByteArray, width: Int, height: Int): ByteArray? = ByteArray(SIZE).takeIf { reduce(gray, width, height, it) }

    /** Réduit [gray] dans [into] (au moins [SIZE] octets) ; faux, sans rien écrire, si la taille ne convient pas. */
    fun reduce(gray: ByteArray, width: Int, height: Int, into: ByteArray): Boolean {
        if (width % WIDTH != 0 || height % HEIGHT != 0 || width / WIDTH != height / HEIGHT || gray.size < width * height || into.size < SIZE) return false
        val factor = width / WIDTH
        val area = factor * factor
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * width + x * factor
                    for (dx in 0 until factor) sum += gray[row + dx].toInt() and 0xFF
                }
                into[y * WIDTH + x] = ((sum + area / 2) / area).toByte()
            }
        }
        return true
    }

    /** Part des points qui ont nettement changé entre deux images : proche de 0 dans un même plan, de 1 d'un plan à un autre. */
    fun changedShare(a: ByteArray, b: ByteArray): Float {
        var changed = 0
        for (i in 0 until SIZE) {
            if (abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > CHANGED_CONTRAST) changed++
        }
        return changed.toFloat() / SIZE
    }

    /** Part des points de la teinte dominante : proche de 1 pour un carton uni ou une image noire. */
    fun flatShare(frame: ByteArray): Float {
        val histogram = IntArray(256)
        for (i in 0 until SIZE) histogram[frame[i].toInt() and 0xFF]++
        var seen = 0
        var median = 0
        while (median < 255 && seen + histogram[median] < SIZE / 2) seen += histogram[median++]
        var near = 0
        for (level in (median - FLAT_CONTRAST).coerceAtLeast(0)..(median + FLAT_CONTRAST).coerceAtMost(255)) near += histogram[level]
        return near.toFloat() / SIZE
    }

    /** Résumé d'une image (12 × 6 pavés) pour reconnaître un même carton d'une fois sur l'autre. */
    fun signature(frame: ByteArray): ByteArray {
        val out = ByteArray(SIGNATURE_COLUMNS * SIGNATURE_ROWS)
        val blockWidth = WIDTH / SIGNATURE_COLUMNS
        val blockHeight = HEIGHT / SIGNATURE_ROWS
        for (row in 0 until SIGNATURE_ROWS) {
            for (column in 0 until SIGNATURE_COLUMNS) {
                var sum = 0
                for (y in row * blockHeight until (row + 1) * blockHeight) {
                    for (x in column * blockWidth until (column + 1) * blockWidth) sum += frame[y * WIDTH + x].toInt() and 0xFF
                }
                out[row * SIGNATURE_COLUMNS + column] = (sum / (blockWidth * blockHeight)).toByte()
            }
        }
        return out
    }

    /** Deux résumés du même carton. */
    fun sameSignature(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var sum = 0
        for (i in a.indices) {
            val gap = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            if (gap > SIGNATURE_MAX_GAP) return false
            sum += gap
        }
        return sum <= SIGNATURE_MEAN_GAP * a.size
    }

    /** Luminance moyenne d'un résumé. */
    fun brightness(signature: ByteArray): Int = signature.sumOf { it.toInt() and 0xFF } / signature.size.coerceAtLeast(1)

    private const val CHANGED_CONTRAST = 24
    private const val FLAT_CONTRAST = 12
    private const val SIGNATURE_COLUMNS = 12
    private const val SIGNATURE_ROWS = 6
    private const val SIGNATURE_MAX_GAP = 48
    private const val SIGNATURE_MEAN_GAP = 10
}
