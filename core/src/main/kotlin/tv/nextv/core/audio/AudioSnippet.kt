package tv.nextv.core.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Dernières secondes du son en cours, en mono à [RATE] Hz, pour reconnaître le morceau d'une radio qui ne l'annonce pas
 * ([wav] : fichier WAV de quelques secondes, envoyé au service de reconnaissance). Le son décodé y est versé par le fil
 * audio ([write], échantillons de −1 à 1 à leur fréquence d'origine), ramené à [RATE] Hz par moyenne.
 */
class AudioSnippet(private val seconds: Int = 10) {
    private val ring = ShortArray(RATE * seconds)

    @Volatile
    private var written = 0L
    private var sum = 0f
    private var count = 0
    private var phase = 0.0

    /** Durée de son gardée (ms), au plus [seconds] s. */
    val availableMillis: Long get() = minOf(written, ring.size.toLong()) * 1_000 / RATE

    /** Oublie le son gardé (autre radio). */
    @Synchronized
    fun clear() {
        written = 0
        sum = 0f
        count = 0
        phase = 0.0
    }

    /** Verse [frames] échantillons mono de [samples] à [sampleRate] Hz. Fil audio. */
    @Synchronized
    fun write(samples: FloatArray, frames: Int, sampleRate: Int) {
        if (sampleRate <= 0) return
        val step = RATE.toDouble() / sampleRate
        for (i in 0 until frames) {
            sum += samples[i]
            count++
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                val value = (sum / count).coerceIn(-1f, 1f)
                ring[(written % ring.size).toInt()] = (value * Short.MAX_VALUE).toInt().toShort()
                written++
                sum = 0f
                count = 0
            }
        }
    }

    /** Les [millis] dernières millisecondes en WAV (16 bits, mono, [RATE] Hz), `null` s'il n'y en a pas encore assez. */
    @Synchronized
    fun wav(millis: Long): ByteArray? {
        val wanted = (millis * RATE / 1_000).toInt()
        if (wanted <= 0 || wanted > ring.size || written < wanted) return null
        val out = ByteArrayOutputStream(44 + wanted * 2)
        DataOutputStream(out).use { data ->
            fun int(value: Int) = data.writeInt(Integer.reverseBytes(value))
            fun short(value: Int) = data.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            data.writeBytes("RIFF")
            int(36 + wanted * 2)
            data.writeBytes("WAVEfmt ")
            int(16)
            short(1)
            short(1)
            int(RATE)
            int(RATE * 2)
            short(2)
            short(16)
            data.writeBytes("data")
            int(wanted * 2)
            val start = written - wanted
            for (i in 0 until wanted) short(ring[((start + i) % ring.size).toInt()].toInt())
        }
        return out.toByteArray()
    }

    companion object {
        const val RATE = 11_025
    }
}
