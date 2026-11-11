package tv.nextv.core.audio

import kotlin.math.abs

/**
 * Suit le logo de la chaîne à l'image, pour l'option « baisser le son des pubs » : les chaînes le
 * retirent pendant les publicités et le remettent à la reprise du programme.
 *
 * Le logo n'est pas connu d'avance. Dans chaque coin de l'image, les contours qui restent au même
 * endroit pendant une minute alors que le reste change sont ceux d'un logo (ou d'une horloge, d'un
 * bandeau) : ils forment un masque. Ensuite, à chaque image, le logo est là si la plupart des points du
 * masque portent encore un contour, et nettement plus que les points voisins : le logo d'une marque ou
 * un texte de publicité au même endroit couvrent tout de contours, sans dessiner le logo. Une image
 * fixe ou un décor immobile ne permettent pas de distinguer le logo de ce qui l'entoure : rien n'est
 * retenu, et l'apprentissage recommence. Un masque n'est écouté qu'après être resté à l'image une minute de plus :
 * une mention légale commune à deux publicités de suite disparaît avant.
 *
 * Reçoit environ une image par seconde, réduite et en niveaux de gris ([frame]).
 *
 * @param width largeur des images reçues, en pixels (480 conseillé).
 */
class LogoWatcher(
    private val width: Int,
    private val height: Int,
    /** Images d'apprentissage (une par seconde : une minute). */
    private val learnFrames: Int = 60,
    /** Images pendant lesquelles un masque tout juste appris doit rester à l'image avant d'être écouté. */
    private val provingFrames: Int = learnFrames,
    /** Images de suite pour dire que le logo est parti ou revenu. */
    private val confirmFrames: Int = 3,
    /** Logo absent depuis ce nombre d'images (douze minutes) : il a changé, on le réapprend. */
    private val forgetFrames: Int = 720,
) {
    private val regionWidth = (width * REGION_WIDTH).toInt()
    private val regionHeight = (height * REGION_HEIGHT).toInt()

    private val corners = listOf(
        Corner(0, 0),
        Corner(width - regionWidth, 0),
        Corner(0, height - regionHeight),
        Corner(width - regionWidth, height - regionHeight),
    )

    var state: LogoState = LogoState.UNKNOWN
        private set

    /** Nouveau flux : tout est oublié. */
    fun reset() {
        corners.forEach(Corner::reset)
        state = LogoState.UNKNOWN
    }

    /** Image suivante ([gray] : luminance de chaque pixel, ligne par ligne). */
    fun frame(gray: ByteArray): LogoState {
        if (gray.size < width * height) return state
        corners.forEach { it.frame(gray) }
        val known = corners.filter { it.proven }
        state = when {
            known.isEmpty() -> LogoState.UNKNOWN
            known.any { it.present } -> LogoState.VISIBLE
            else -> LogoState.HIDDEN
        }
        return state
    }

    private inner class Corner(private val left: Int, private val top: Int) {
        private val size = regionWidth * regionHeight
        private val edge = BooleanArray(size)

        /** Image précédente du coin, et nombre d'images d'apprentissage où il avait changé. */
        private val previous = ByteArray(size)
        private var moving = 0
        private var moved = false

        /** Part des images où chaque point portait un contour. */
        private val persistence = FloatArray(size)
        private var learned = 0
        private var contrary = 0
        private var hidden = 0
        private var sinceRefresh = 0

        var mask: IntArray? = null
            private set

        /** Points voisins du logo, hors de son dessin. */
        private var around = IntArray(0)
        var present = false
            private set

        /** Masque resté assez longtemps à l'image pour être celui d'un logo. */
        var proven = false
            private set
        private var provenFor = 0

        fun reset() {
            persistence.fill(0f)
            learned = 0
            moving = 0
            contrary = 0
            hidden = 0
            sinceRefresh = 0
            mask = null
            around = IntArray(0)
            present = false
            proven = false
            provenFor = 0
        }

        fun frame(gray: ByteArray) {
            findEdges(gray)
            val current = mask
            if (current == null) {
                learn()
                return
            }
            val score = current.count { edge[it] }.toFloat() / current.size
            val busy = around.count { edge[it] }.toFloat() / around.size.coerceAtLeast(1)
            val drawn = score >= PRESENT_SCORE && score - busy >= LOGO_CONTRAST
            val contradicts = if (present) score <= ABSENT_SCORE else drawn
            contrary = if (contradicts) contrary + 1 else 0
            if (contrary >= confirmFrames) {
                // Parti avant d'avoir fait ses preuves : ce n'était pas un logo.
                if (!proven) return reset()
                present = !present
                contrary = 0
                hidden = 0
            }
            if (!proven && drawn && ++provenFor >= provingFrames) proven = true
            if (!present) {
                if (++hidden >= forgetFrames) reset()
                return
            }
            // Logo à l'image : le masque suit ses petits changements.
            if (drawn) {
                for (i in 0 until size) persistence[i] += ((if (edge[i]) 1f else 0f) - persistence[i]) / FOLLOW_FRAMES
                if (++sinceRefresh >= REFRESH_FRAMES) {
                    sinceRefresh = 0
                    buildMask()?.let(::keep)
                }
            }
        }

        private fun learn() {
            learned++
            if (moved) moving++
            for (i in 0 until size) persistence[i] += ((if (edge[i]) 1f else 0f) - persistence[i]) / learned
            if (learned < learnFrames) return
            // Coin resté immobile : ses contours sont ceux du décor autant que ceux d'un logo.
            val found = if (moving * 2 >= learnFrames) buildMask() else null
            if (found == null) {
                // Pas de logo, ou image trop immobile pour le distinguer : nouvel essai.
                reset()
                return
            }
            keep(found)
            present = true
        }

        private fun keep(points: IntArray) {
            mask = points
            // Autour du logo : son cadre, élargi de quelques points, sans les points du logo lui-même.
            val xs = points.map { it % regionWidth }
            val ys = points.map { it / regionWidth }
            val columns = (xs.min() - AROUND_MARGIN).coerceAtLeast(0)..(xs.max() + AROUND_MARGIN).coerceAtMost(regionWidth - 1)
            val rows = (ys.min() - AROUND_MARGIN).coerceAtLeast(0)..(ys.max() + AROUND_MARGIN).coerceAtMost(regionHeight - 1)
            val drawing = points.toHashSet()
            around = rows.flatMap { y -> columns.map { x -> y * regionWidth + x } }.filter { it !in drawing }.toIntArray()
        }

        private fun buildMask(): IntArray? {
            val points = (0 until size).filter { persistence[it] >= PERSISTENT }
            return points.takeIf { it.size >= MIN_MASK_POINTS && it.size <= size * MAX_MASK_SHARE }?.toIntArray()
        }

        private fun findEdges(gray: ByteArray) {
            var changed = 0
            for (y in 0 until regionHeight) {
                val row = (top + y) * width + left
                val frameY = top + y
                for (x in 0 until regionWidth) {
                    val frameX = left + x
                    // Bords de l'image : bandes noires et défauts d'encodage, ignorés.
                    val inside = frameX in BORDER until width - BORDER - 1 && frameY in BORDER until height - BORDER - 1
                    val index = y * regionWidth + x
                    val p = gray[row + x].toInt() and 0xFF
                    if (abs(p - (previous[index].toInt() and 0xFF)) > MOVED_CONTRAST) changed++
                    previous[index] = gray[row + x]
                    edge[index] = inside && run {
                        val right = gray[row + x + 1].toInt() and 0xFF
                        val below = gray[row + x + width].toInt() and 0xFF
                        abs(p - right) + abs(p - below) > EDGE_CONTRAST
                    }
                }
            }
            moved = changed >= size * MOVED_SHARE
        }
    }

    private companion object {
        /** Taille d'un coin : assez pour un logo et son décalage d'une chaîne à l'autre. */
        const val REGION_WIDTH = 0.275
        const val REGION_HEIGHT = 0.24
        const val BORDER = 2
        const val EDGE_CONTRAST = 20

        /** Coin qui a changé depuis l'image précédente : au moins cette part de ses points, de cet écart. */
        const val MOVED_CONTRAST = 12
        const val MOVED_SHARE = 0.1

        /** Contour présent sur au moins cette part des images : il ne bouge pas. */
        const val PERSISTENT = 0.8f
        const val MIN_MASK_POINTS = 30

        /** Au-delà de cette part du coin, ce n'est pas un logo mais une image immobile. */
        const val MAX_MASK_SHARE = 0.08
        const val PRESENT_SCORE = 0.65f
        const val ABSENT_SCORE = 0.35f

        /** Écart minimal entre les contours sur le logo et autour : en dessous, c'est une image chargée. */
        const val LOGO_CONTRAST = 0.35f
        const val AROUND_MARGIN = 4
        const val FOLLOW_FRAMES = 60f
        const val REFRESH_FRAMES = 30
    }
}
