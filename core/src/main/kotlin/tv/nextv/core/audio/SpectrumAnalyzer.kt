package tv.nextv.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectre du son en cours, pour les animations des radios : le son décodé (fil audio) y est versé échantillon par
 * échantillon ([write]) ; l'écran ([read], environ 30 fois par seconde) en tire des bandes de fréquences de 0 à 1, du
 * grave à l'aigu, lissées (montée rapide, descente lente), le niveau d'ensemble et le niveau des basses.
 *
 * Rien n'est calculé tant que [active] est faux (radio non affichée) : le fil audio ne fait qu'un test.
 */
class SpectrumAnalyzer(val bandCount: Int = DEFAULT_BANDS, private val size: Int = FFT_SIZE) {

    init {
        require(size >= 64 && size and (size - 1) == 0) { "Taille de FFT : puissance de deux" }
    }

    /** Une animation regarde le son : le fil audio verse les échantillons. */
    @Volatile
    var active: Boolean = false

    /** Fréquence d'échantillonnage du son versé. */
    @Volatile
    var sampleRate: Int = 44_100

    private val ring = FloatArray(size)

    @Volatile
    private var written = 0L

    /** Dernier versement (horloge de [read]) : sans son depuis un moment, les bandes retombent. */
    @Volatile
    private var lastWriteNanos = 0L

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val window = FloatArray(size) { i -> (0.5 - 0.5 * cos(2 * PI * i / (size - 1))).toFloat() }
    private val bitReverse = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val cosTable = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }

    private val smoothed = FloatArray(bandCount)
    private val target = FloatArray(bandCount)
    private var smoothedLevel = 0f
    private var smoothedBass = 0f

    /** Niveau d'ensemble lissé (0 à 1), relu après [read]. */
    var level: Float = 0f
        private set

    /** Niveau des basses lissé (0 à 1, sous 150 Hz), relu après [read] : battement des animations. */
    var bass: Float = 0f
        private set

    /** Il y a du son à analyser (versé depuis moins d'une seconde). */
    fun hasSound(nowNanos: Long): Boolean = written > 0 && nowNanos - lastWriteNanos < 1_000_000_000L

    /** Verse [count] échantillons mono de [samples] (de −1 à 1). Fil audio. */
    fun write(samples: FloatArray, count: Int, nowNanos: Long) {
        if (!active || count <= 0) return
        var position = written
        val mask = size - 1
        for (i in 0 until count) {
            ring[(position and mask.toLong()).toInt()] = samples[i]
            position++
        }
        written = position
        lastWriteNanos = nowNanos
    }

    /**
     * Bandes de fréquences actuelles dans [out] (taille [bandCount]), lissées depuis l'appel précédent ([elapsedSeconds]).
     * Sans son, tout retombe doucement vers zéro.
     */
    fun read(out: FloatArray, elapsedSeconds: Float, nowNanos: Long) {
        val step = elapsedSeconds.coerceIn(0.001f, 0.25f)
        target.fill(0f)
        var levelTarget = 0f
        var bassTarget = 0f
        if (hasSound(nowNanos)) {
            val end = written
            val mask = size - 1
            var energy = 0f
            for (i in 0 until size) {
                val sample = ring[((end - size + i) and mask.toLong()).toInt()]
                energy += sample * sample
                re[bitReverse[i]] = sample * window[i]
                im[bitReverse[i]] = 0f
            }
            fft()
            levelTarget = normalizeDb(10 * log10(max(energy / size, 1e-10f)), floor = -50f, ceiling = -8f)
            val binHz = sampleRate.toFloat() / size
            for (band in 0 until bandCount) {
                val low = bandEdge(band)
                val high = bandEdge(band + 1)
                val first = max(1, (low / binHz).toInt())
                val last = min(size / 2 - 1, max(first, (high / binHz).toInt()))
                var peak = 0f
                for (bin in first..last) peak = max(peak, re[bin] * re[bin] + im[bin] * im[bin])
                // Amplitude rapportée à une sinusoïde pleine échelle (fenêtre de Hann : gain 1/2).
                val amplitude = sqrt(peak) / (size / 4f)
                // Les aigus portent moins d'énergie : légère remontée pour que tout le spectre bouge.
                val tilt = 1f + 1.5f * band / bandCount
                target[band] = normalizeDb(20 * log10(max(amplitude * tilt, 1e-6f)), floor = -62f, ceiling = -12f)
                if (high <= BASS_HZ) bassTarget = max(bassTarget, target[band])
            }
        }
        // Montée rapide, descente lente (comme un vumètre).
        val up = 1f - exp(-step / 0.04f)
        val down = 1f - exp(-step / 0.35f)
        for (band in 0 until bandCount) {
            val current = smoothed[band]
            val goal = target[band]
            smoothed[band] = current + (goal - current) * (if (goal > current) up else down)
            if (band < out.size) out[band] = smoothed[band]
        }
        smoothedLevel += (levelTarget - smoothedLevel) * (if (levelTarget > smoothedLevel) up else down)
        smoothedBass += (bassTarget - smoothedBass) * (if (bassTarget > smoothedBass) up else down * 1.5f).coerceAtMost(1f)
        level = smoothedLevel
        bass = smoothedBass
    }

    /** Limite basse de la bande [band] (Hz), en échelle logarithmique de [LOW_HZ] à [HIGH_HZ]. */
    fun bandEdge(band: Int): Float = (LOW_HZ * (HIGH_HZ / LOW_HZ).pow(band.toFloat() / bandCount))

    private fun normalizeDb(db: Float, floor: Float, ceiling: Float): Float = ((db - floor) / (ceiling - floor)).coerceIn(0f, 1f)

    /** FFT complexe en place (base 2), entrées déjà rangées dans l'ordre inversé des bits. */
    private fun fft() {
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * stride]
                    val wi = -sinTable[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += length
            }
            length *= 2
        }
    }

    companion object {
        const val DEFAULT_BANDS = 32
        const val FFT_SIZE = 1024
        const val LOW_HZ = 40f
        const val HIGH_HZ = 16_000f
        const val BASS_HZ = 160f
    }
}
