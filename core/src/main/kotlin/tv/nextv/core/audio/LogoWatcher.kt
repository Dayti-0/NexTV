package tv.nextv.core.audio

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Suit le logo de la chaîne à l'image, pour l'option « baisser le son des pubs » : les chaînes le
 * retirent pendant les publicités et le remettent à la reprise du programme.
 *
 * Le logo n'est pas connu d'avance. Dans chaque coin de l'image, on retient le **dessin** qui reste au même
 * endroit pendant que le reste change : en chaque point, le sens et la force du passage du clair au sombre
 * (le « gradient »), moyennés sur une quarantaine d'images. Ce qui bouge s'annule dans la moyenne, le logo
 * reste : c'est son gabarit. Ensuite, à chaque image, le logo est là si l'image dessine la même chose aux
 * mêmes points (ressemblance entre −1 et 1, proche de 1 avec le logo, de 0 sans lui).
 *
 * Comparer le dessin, et non la seule présence de contours, évite de prendre pour le logo ce qu'une
 * publicité affiche au même endroit (mentions légales, logo d'une marque) : mesuré sur de vraies coupures,
 * la ressemblance reste sous 0,2 pendant les publicités et au-dessus de 0,5 pendant le programme, même
 * avec un logo à moitié transparent sur un fond clair.
 *
 * Un gabarit n'est écouté qu'après avoir fait ses preuves : resté à l'image, sur un fond qui change, le
 * temps d'un second apprentissage qui doit redonner le même dessin. Une image fixe ou un décor immobile
 * ne donnent rien (tout y est « fixe »), et l'apprentissage recommence.
 *
 * Reçoit environ une image par seconde, réduite et en niveaux de gris ([frame]).
 *
 * @param width largeur des images reçues, en pixels (480 conseillé).
 */
class LogoWatcher(
    private val width: Int,
    private val height: Int,
    /** Images d'apprentissage (une par seconde). */
    private val learnFrames: Int = 40,
    /** Images pendant lesquelles un gabarit tout juste appris doit rester à l'image avant d'être écouté. */
    private val provingFrames: Int = learnFrames,
    /** Images de suite sans le logo pour dire qu'il est parti. */
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
        recheckLeft = 0
    }

    /** Images restantes pour revoir le logo sur une autre version du flux ([recheck]) ; 0 hors de cette attente. */
    private var recheckLeft = 0

    /**
     * Autre version du même flux (qualité, serveur de secours) : son image peut être cadrée autrement. Le logo connu
     * est dit absent jusqu'à ce qu'il soit revu ; s'il ne l'est pas en [frames] images, il n'est pas à la même place
     * sur cette version et il est réappris. Les apprentissages en cours (qui mêleraient les deux versions) repartent.
     */
    fun recheck(frames: Int = RECHECK_FRAMES) {
        corners.forEach(Corner::recheck)
        val known = corners.any { it.proven }
        state = if (known) LogoState.HIDDEN else LogoState.UNKNOWN
        recheckLeft = if (known) frames else 0
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
        if (recheckLeft > 0) {
            if (state == LogoState.VISIBLE) {
                recheckLeft = 0
            } else if (--recheckLeft == 0) {
                // Jamais revu sur cette version : ce n'est pas le même cadrage, on apprend son logo à elle.
                reset()
            }
        }
        return state
    }

    /** Mise au point : où en est chaque coin (apprentissage, mise à l'épreuve, logo connu) et sa dernière ressemblance. */
    internal fun describe(): String = corners.joinToString(" | ") { it.describe() }

    /**
     * Gabarits qui ont fait leurs preuves, pour les retrouver à la prochaine ouverture de la chaîne
     * ([restore]) ; `null` s'il n'y en a pas.
     */
    fun export(): ByteArray? {
        val known = corners.withIndex().filter { it.value.proven }
        if (known.isEmpty()) return null
        val size = HEADER_BYTES + known.sumOf { 3 + it.value.pointCount * POINT_BYTES }
        val out = ByteBuffer.allocate(size)
        out.put(FORMAT).putShort(width.toShort()).putShort(height.toShort()).put(known.size.toByte())
        for ((index, corner) in known) {
            out.put(index.toByte()).putShort(corner.pointCount.toShort())
            corner.write(out)
        }
        return out.array()
    }

    /**
     * Reprend des gabarits enregistrés par [export] : le logo est connu tout de suite, et dit absent tant qu'il
     * n'a pas été revu. Sans effet si les données ne sont pas celles d'images de cette taille.
     */
    fun restore(data: ByteArray) {
        val input = ByteBuffer.wrap(data)
        if (input.remaining() < HEADER_BYTES || input.get() != FORMAT) return
        if (input.short.toInt() != width || input.short.toInt() != height) return
        val restored = ArrayList<Pair<Corner, Template>>()
        repeat(input.get().toInt()) {
            if (input.remaining() < 3) return
            val corner = corners.getOrNull(input.get().toInt()) ?: return
            val count = input.short.toInt()
            if (count < MIN_POINTS || input.remaining() < count * POINT_BYTES) return
            val template = corner.read(input, count) ?: return
            restored += corner to template
        }
        reset()
        restored.forEach { (corner, template) -> corner.adopt(template) }
        if (restored.isNotEmpty()) state = LogoState.HIDDEN
    }

    /** Dessin d'un logo : ses points dans le coin, et le gradient moyen en chacun. */
    private class Template(val points: IntArray, val x: FloatArray, val y: FloatArray) {
        /** Part des images récentes où chaque point dessinait bien le logo. */
        val agreement = FloatArray(points.size) { 1f }
        var energy = 0f
            private set

        init {
            measure()
        }

        fun measure() {
            var sum = 0f
            for (i in points.indices) sum += x[i] * x[i] + y[i] * y[i]
            energy = sum
        }
    }

    private inner class Corner(private val left: Int, private val top: Int) {
        private val size = regionWidth * regionHeight

        /** Gradient de l'image en cours en chaque point du coin. */
        private val gradX = ShortArray(size)
        private val gradY = ShortArray(size)

        /** Image précédente du coin, et nombre d'images où il avait changé. */
        private val previous = ByteArray(size)
        private var moved = false
        private var moving = 0

        /** Somme des gradients (et de leur force) sur les images d'apprentissage, et nombre d'images où chaque point était net. */
        private val sumX = IntArray(size)
        private val sumY = IntArray(size)
        private val sumStrength = IntArray(size)
        private val marked = ShortArray(size)
        private var learned = 0

        private var template: Template? = null
        private var contrary = 0
        private var hidden = 0
        private var sinceRefresh = 0
        private var matched = 0
        private var provingFor = 0

        var present = false
            private set

        /** Gabarit resté assez longtemps à l'image pour être celui d'un logo. */
        var proven = false
            private set

        val pointCount: Int get() = template?.points?.size ?: 0

        private var lastLikeness = 0f

        fun describe(): String = when {
            template == null -> "apprend $learned (bouge $moving)"
            !proven -> "à l'épreuve $provingFor (reconnu $matched, bouge $moving), $pointCount points, ressemblance %.2f".format(lastLikeness)
            else -> "${if (present) "présent" else "absent"}, $pointCount points, ressemblance %.2f".format(lastLikeness)
        }

        fun reset() {
            clearLearning()
            template = null
            contrary = 0
            hidden = 0
            sinceRefresh = 0
            matched = 0
            provingFor = 0
            present = false
            proven = false
        }

        private fun clearLearning() {
            sumX.fill(0)
            sumY.fill(0)
            sumStrength.fill(0)
            marked.fill(0)
            learned = 0
            moving = 0
        }

        /** Autre version du flux : logo connu à revoir ; apprentissage ou mise à l'épreuve en cours repris de zéro. */
        fun recheck() {
            if (!proven) return reset()
            present = false
            contrary = 0
            hidden = 0
        }

        /** Gabarit repris d'une lecture précédente de la chaîne : connu, à revoir avant de le dire à l'image. */
        fun adopt(restored: Template) {
            reset()
            template = restored
            proven = true
        }

        fun write(out: ByteBuffer) {
            val current = template ?: return
            for (i in current.points.indices) {
                out.putShort(current.points[i].toShort())
                out.put(current.x[i].toInt().coerceIn(-127, 127).toByte())
                out.put(current.y[i].toInt().coerceIn(-127, 127).toByte())
            }
        }

        fun read(input: ByteBuffer, count: Int): Template? {
            val points = IntArray(count)
            val x = FloatArray(count)
            val y = FloatArray(count)
            for (i in 0 until count) {
                points[i] = input.short.toInt() and 0xFFFF
                if (points[i] >= size) return null
                x[i] = input.get().toFloat()
                y[i] = input.get().toFloat()
            }
            return Template(points, x, y).takeIf { it.energy > 0f }
        }

        fun frame(gray: ByteArray) {
            findGradients(gray)
            val current = template
            if (current == null) {
                learn()
                return
            }
            val likeness = likeness(current)
            lastLikeness = likeness
            // Fond tout blanc derrière le logo : il ne se voit pas, l'image ne dit rien.
            val readable = likeness >= PRESENT || brightness(gray, current) < WHITE_OUT
            if (!proven) {
                prove(current, likeness)
                return
            }
            val contradicts = if (present) readable && likeness <= ABSENT else likeness >= PRESENT
            contrary = if (contradicts) contrary + 1 else 0
            if (contrary >= (if (present) confirmFrames else RETURN_FRAMES)) {
                present = !present
                contrary = 0
                hidden = 0
            }
            if (!present) {
                if (++hidden >= forgetFrames) reset()
                return
            }
            if (likeness >= PRUNE_LIKENESS) follow(current, adapt = likeness >= FOLLOW_LIKENESS)
        }

        /** Apprentissage : moyenne des gradients sur [learnFrames] images. */
        private fun learn() {
            accumulate()
            if (learned < learnFrames) return
            // Coin resté immobile : son dessin est celui du décor autant que celui d'un logo.
            val found = if (moving >= learned * MOVING_FRAMES) buildTemplate() else null
            clearLearning()
            if (found == null) return
            template = found
            present = true
            matched = 0
            provingFor = 0
            contrary = 0
        }

        /**
         * Mise à l'épreuve : le gabarit doit rester à l'image pendant [provingFrames] images dont le fond change,
         * et un second apprentissage fait pendant ce temps doit redonner le même dessin (seuls ses points
         * communs sont gardés).
         */
        private fun prove(current: Template, likeness: Float) {
            contrary = if (likeness <= ABSENT) contrary + 1 else 0
            // Parti avant d'avoir fait ses preuves : ce n'était pas un logo.
            if (contrary >= confirmFrames) return reset()
            accumulate()
            provingFor++
            if (likeness >= PRESENT) matched++
            if (provingFor < provingFrames) return
            val again = if (moving >= learned * MOVING_FRAMES && matched >= provingFor * PROVEN_SHARE) buildTemplate() else null
            val confirmed = again?.let { common(current, it) }
            if (confirmed == null) {
                // Fond immobile ou dessin changé : pas de preuve. On repart de ce qui vient d'être vu.
                val restart = again
                reset()
                if (restart != null) {
                    template = restart
                    present = true
                }
                return
            }
            clearLearning()
            template = confirmed
            proven = true
            present = true
            contrary = 0
        }

        private fun accumulate() {
            learned++
            if (moved) moving++
            for (i in 0 until size) {
                val x = gradX[i].toInt()
                val y = gradY[i].toInt()
                val strength = abs(x) + abs(y)
                sumX[i] += x
                sumY[i] += y
                sumStrength[i] += strength
                if (strength >= MARKED_STRENGTH) marked[i]++
            }
        }

        /**
         * Points dont le gradient est fort, toujours dans le même sens, et visible sur presque toutes les images : ils
         * ne bougent pas. (Un contour du décor qui passe une fois ou deux par ce point ne compte pas.)
         */
        private fun buildTemplate(): Template? {
            val points = ArrayList<Int>()
            for (i in 0 until size) {
                val strength = abs(sumX[i]) + abs(sumY[i])
                if (strength >= MIN_STRENGTH * learned && strength >= COHERENT * sumStrength[i] && marked[i] >= PERSISTENT * learned) points += i
            }
            // Trop peu de points : rien de fixe. Trop : une image immobile, pas un logo.
            if (points.size < MIN_POINTS || points.size > size * MAX_SHARE) return null
            val indices = points.toIntArray()
            return Template(indices, FloatArray(indices.size) { sumX[indices[it]].toFloat() / learned }, FloatArray(indices.size) { sumY[indices[it]].toFloat() / learned })
        }

        /** Points présents dans les deux apprentissages avec le même dessin ; `null` s'ils sont trop peu nombreux. */
        private fun common(first: Template, second: Template): Template? {
            val later = HashMap<Int, Int>(second.points.size * 2)
            for (i in second.points.indices) later[second.points[i]] = i
            val points = ArrayList<Int>()
            val x = ArrayList<Float>()
            val y = ArrayList<Float>()
            for (i in first.points.indices) {
                val j = later[first.points[i]] ?: continue
                val dot = first.x[i] * second.x[j] + first.y[i] * second.y[j]
                val square = first.x[i] * first.x[i] + first.y[i] * first.y[i]
                if (dot < SAME_DRAWING * square) continue
                points += first.points[i]
                x += (first.x[i] + second.x[j]) / 2
                y += (first.y[i] + second.y[j]) / 2
            }
            if (points.size < MIN_POINTS || points.size < first.points.size * KEPT_SHARE) return null
            return Template(points.toIntArray(), x.toFloatArray(), y.toFloatArray())
        }

        /** Ressemblance entre l'image et le gabarit, aux points du gabarit : 1 = même dessin, 0 = rien de commun. */
        private fun likeness(current: Template): Float {
            var dot = 0f
            var energy = 0f
            for (i in current.points.indices) {
                val p = current.points[i]
                val x = gradX[p].toFloat()
                val y = gradY[p].toFloat()
                dot += x * current.x[i] + y * current.y[i]
                energy += x * x + y * y
            }
            val scale = sqrt(energy * current.energy)
            return if (scale < 1f) 0f else dot / scale
        }

        private fun brightness(gray: ByteArray, current: Template): Int {
            var sum = 0L
            for (p in current.points) sum += gray[(top + p / regionWidth) * width + left + p % regionWidth].toInt() and 0xFF
            return (sum / current.points.size).toInt()
        }

        /**
         * Logo à l'image : le gabarit perd les points qui ne le dessinent plus (un bout de décor appris avec lui, sur
         * un plan resté fixe), et suit ses petits changements quand il est bien net ([adapt]).
         */
        private fun follow(current: Template, adapt: Boolean) {
            for (i in current.points.indices) {
                val p = current.points[i]
                val x = gradX[p].toFloat()
                val y = gradY[p].toFloat()
                val agrees = x * current.x[i] + y * current.y[i] >= SAME_DRAWING * (current.x[i] * current.x[i] + current.y[i] * current.y[i]) * FAINT
                current.agreement[i] += ((if (agrees) 1f else 0f) - current.agreement[i]) / FOLLOW_FRAMES
                if (!adapt) continue
                current.x[i] += (x - current.x[i]) / FOLLOW_FRAMES
                current.y[i] += (y - current.y[i]) / FOLLOW_FRAMES
            }
            if (++sinceRefresh < REFRESH_FRAMES) return
            sinceRefresh = 0
            val kept = current.points.indices.filter { current.agreement[it] >= KEPT_AGREEMENT }
            if (kept.size == current.points.size || kept.size < MIN_POINTS) {
                current.measure()
                return
            }
            // Points du décor appris avec le logo : ils ne tiennent pas quand le plan change.
            val pruned = Template(IntArray(kept.size) { current.points[kept[it]] }, FloatArray(kept.size) { current.x[kept[it]] }, FloatArray(kept.size) { current.y[kept[it]] })
            for (i in kept.indices) pruned.agreement[i] = current.agreement[kept[i]]
            template = pruned
        }

        private fun findGradients(gray: ByteArray) {
            var changed = 0
            for (y in 0 until regionHeight) {
                val frameY = top + y
                val row = frameY * width + left
                for (x in 0 until regionWidth) {
                    val frameX = left + x
                    val index = y * regionWidth + x
                    val p = gray[row + x].toInt() and 0xFF
                    if (abs(p - (previous[index].toInt() and 0xFF)) > MOVED_CONTRAST) changed++
                    previous[index] = gray[row + x]
                    // Bords de l'image : bandes noires et défauts d'encodage, ignorés.
                    val inside = frameX in BORDER until width - BORDER - 1 && frameY in BORDER until height - BORDER - 1
                    if (inside) {
                        gradX[index] = ((gray[row + x + 1].toInt() and 0xFF) - p).toShort()
                        gradY[index] = ((gray[row + x + width].toInt() and 0xFF) - p).toShort()
                    } else {
                        gradX[index] = 0
                        gradY[index] = 0
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
        const val BORDER = 3

        /** Coin qui a changé depuis l'image précédente : au moins cette part de ses points, de cet écart. */
        const val MOVED_CONTRAST = 12
        const val MOVED_SHARE = 0.1

        /**
         * Part des images d'un apprentissage où le coin doit avoir changé : une série tournée en plans fixes ne change
         * de plan qu'une fois toutes les quatre ou cinq secondes (mesuré sur RMC Story : 10 à 27 images sur 40).
         */
        const val MOVING_FRAMES = 0.2f

        /** Point du gabarit : gradient moyen d'au moins cette force, et dans le même sens sur cette part des images. */
        const val MIN_STRENGTH = 8
        const val COHERENT = 0.7f

        /** … et visible (gradient d'au moins [MARKED_STRENGTH]) sur cette part des images. */
        const val MARKED_STRENGTH = 4
        const val PERSISTENT = 0.7f
        const val MIN_POINTS = 40

        /** Au-delà de cette part du coin, ce n'est pas un logo mais une image immobile. */
        const val MAX_SHARE = 0.15

        /** Ressemblance au-dessus de laquelle le logo est là, et en dessous de laquelle il n'y est pas. */
        const val PRESENT = 0.45f
        const val ABSENT = 0.2f
        const val RETURN_FRAMES = 2

        /** Luminance moyenne aux points du logo à partir de laquelle un logo blanc ne se voit plus. */
        const val WHITE_OUT = 235

        /** Mise à l'épreuve : part des images où le gabarit doit être reconnu, et des points à retrouver. */
        const val PROVEN_SHARE = 0.8f
        const val SAME_DRAWING = 0.5f
        const val KEPT_SHARE = 0.5f

        const val FOLLOW_LIKENESS = 0.6f

        /** Ressemblance à partir de laquelle le logo est sûrement là (0,16 au plus pendant des publicités) : le gabarit s'épure. */
        const val PRUNE_LIKENESS = 0.3f
        const val FOLLOW_FRAMES = 60f
        const val REFRESH_FRAMES = 30

        /** Logo pâli par un fond clair : le sens du dessin compte, pas sa force. */
        const val FAINT = 0.25f
        const val KEPT_AGREEMENT = 0.4f

        /** Autre version du flux : images (une par seconde) pour y revoir le logo avant de le réapprendre. */
        const val RECHECK_FRAMES = 120

        const val FORMAT: Byte = 1
        const val HEADER_BYTES = 6
        const val POINT_BYTES = 4
    }
}
