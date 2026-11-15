package tv.nextv.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Empreinte du son, à la manière de Shazam (méthode de Haitsma et Kalker) : toutes les [STEP_MILLIS] millisecondes, un
 * nombre de 32 bits qui résume la forme du spectre (le volume, la qualité du son ou un léger décalage n'y changent
 * presque rien). Une pub repasse des dizaines de fois par jour, sur plusieurs chaînes : ses empreintes, gardées une
 * première fois ([AdPrintLibrary]), la font reconnaître les fois suivantes en deux à trois secondes.
 *
 * Calcul : son ramené à 8 000 Hz, spectre sur 256 ms toutes les 16 ms, énergie de 33 bandes de 300 à 3 000 Hz ; chaque
 * bit dit si l'écart d'énergie entre deux bandes voisines a grandi ou diminué depuis l'empreinte précédente.
 *
 * @param inputRate fréquence d'échantillonnage du son reçu (au moins 8 000 Hz).
 */
class AudioFingerprinter(private val inputRate: Int) {

    // Filtre passe-bas avant de ramener le son à 8 000 Hz (sinon les aigus se replient dans les bandes mesurées).
    private val taps: FloatArray = lowPass(inputRate)
    private val history = FloatArray(taps.size)
    private var historyAt = 0
    private var consumed = 0L
    private var produced = 0L
    private var nextInput = 0L

    private val frame = FloatArray(FRAME)
    private var frameAt = 0
    private var sinceLast = 0
    private var filled = 0

    private val re = DoubleArray(FRAME)
    private val im = DoubleArray(FRAME)
    private val bands = DoubleArray(BANDS + 1)
    private val previous = DoubleArray(BANDS + 1)
    private var hasPrevious = false

    /** Le son reçu peut-il être analysé (fréquence assez haute) ? */
    val usable: Boolean = inputRate >= RATE

    /**
     * Son mono, valeurs entre −1 et 1. [sink] reçoit chaque nouvelle empreinte, et si le son était audible à ce moment
     * (un silence donne une empreinte quelconque, qui ne doit ni être gardée ni servir à reconnaître).
     */
    fun feed(samples: FloatArray, count: Int, sink: (print: Int, loud: Boolean) -> Unit) {
        if (!usable) return
        for (i in 0 until count) {
            history[historyAt] = samples[i]
            historyAt = (historyAt + 1) % history.size
            if (consumed++ != nextInput) continue
            // Échantillon à 8 000 Hz : le filtre appliqué aux derniers échantillons reçus.
            var value = 0.0f
            var index = historyAt
            for (tap in taps) {
                index = if (index == 0) history.size - 1 else index - 1
                value += tap * history[index]
            }
            produced++
            nextInput = produced * inputRate / RATE
            frame[frameAt] = value
            frameAt = (frameAt + 1) % FRAME
            if (filled < FRAME) filled++
            if (++sinceLast >= HOP && filled == FRAME) {
                sinceLast = 0
                analyse(sink)
            }
        }
    }

    private fun analyse(sink: (Int, Boolean) -> Unit) {
        var power = 0.0
        for (i in 0 until FRAME) {
            val sample = frame[(frameAt + i) % FRAME].toDouble()
            power += sample * sample
            re[i] = sample * WINDOW[i]
            im[i] = 0.0
        }
        fft(re, im)
        for (b in 0..BANDS) {
            var energy = 0.0
            for (k in EDGES[b] until EDGES[b + 1]) energy += re[k] * re[k] + im[k] * im[k]
            bands[b] = energy
        }
        var print = 0
        if (hasPrevious) {
            for (b in 0 until BANDS) {
                val now = bands[b] - bands[b + 1]
                val before = previous[b] - previous[b + 1]
                if (now - before > 0) print = print or (1 shl b)
            }
        }
        val loud = hasPrevious && power / FRAME > LOUD_MEAN_SQUARE
        bands.copyInto(previous)
        hasPrevious = true
        sink(print, loud)
    }

    companion object {
        /** Fréquence du son analysé. */
        const val RATE = 8_000

        /** 256 ms de son par spectre, un nouveau toutes les 16 ms. */
        private const val FRAME = 2_048
        private const val HOP = 128
        const val STEP_MILLIS = HOP * 1_000.0 / RATE
        const val PER_SECOND = RATE / HOP.toDouble()

        private const val BANDS = 32
        private const val LOW_HZ = 300.0
        private const val HIGH_HZ = 3_000.0

        /** Son audible : au-dessus de −50 dB environ (un silence entre deux spots est bien plus bas). */
        private const val LOUD_MEAN_SQUARE = 1e-5

        private val WINDOW = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }

        /** Bornes des bandes (indices du spectre), réparties régulièrement à l'oreille (échelle logarithmique). */
        private val EDGES = IntArray(BANDS + 2) { b ->
            (LOW_HZ * (HIGH_HZ / LOW_HZ).pow(b / (BANDS + 1.0)) * FRAME / RATE).roundToInt()
        }

        private val COS = DoubleArray(FRAME / 2) { cos(-2 * PI * it / FRAME) }
        private val SIN = DoubleArray(FRAME / 2) { sin(-2 * PI * it / FRAME) }

        /** Filtre passe-bas (sinus cardinal fenêtré) coupant vers 3,4 kHz à la fréquence [rate]. */
        private fun lowPass(rate: Int): FloatArray {
            val ratio = (rate / RATE.toDouble()).coerceAtLeast(1.0)
            val count = ((ratio * 12).roundToInt() or 1).coerceAtLeast(1)
            val cutoff = 3_400.0 / rate
            val middle = (count - 1) / 2.0
            val taps = DoubleArray(count) { i ->
                val x = i - middle
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = if (count == 1) 1.0 else 0.54 - 0.46 * cos(2 * PI * i / (count - 1))
                sinc * window
            }
            val sum = taps.sum()
            return FloatArray(count) { (taps[it] / sum).toFloat() }
        }

        /** Transformée de Fourier rapide, en place (taille [FRAME]). */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val half = length / 2
                val step = n / length
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                    }
                    start += length
                }
                length *= 2
            }
        }
    }
}
