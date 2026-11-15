package tv.nextv.app.player

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Enregistrement pour l'analyse des pubs : tout ce que la détection reçoit pendant une lecture, et ce qu'elle décide,
 * pour la rejouer ensuite dans le code (`AdDeviceReplayTool`). Pas la vidéo : le son ramené vers 8 000 Hz (µ-law,
 * 8 Ko/s), les petites images en gris que la détection reçoit, celle du logo (JPEG, une par seconde) et les décisions.
 * Environ 90 Mo par heure ; il dure jusqu'à ce qu'on l'arrête (ou que la place manque), même si l'on change de chaîne.
 *
 * Fichiers, écrits au fil de l'eau dans [directory] (renommé sans « .part » à la fin, [AdRecordings]) :
 * - `info.txt` : chaîne, date, appareil ;
 * - `levels.bin` : pour chaque tranche de son traitée, carré moyen (float), durée en ms (short), échantillons enregistrés
 *   pour cette tranche (short) ;
 * - `audio.ulaw` : le son (mono, µ-law) ;
 * - `frames.bin` : images reçues, chacune : type (« S » image réduite 96 × 54, « L » image du logo en JPEG), instant
 *   (long), taille (int), octets ;
 * - `events.tsv` : instant, quoi, valeur : son baissé ou remis, logo, repères du flux, ce que la détection décide
 *   (« ouverture » / « fermeture » d'une page de pubs avec leur raison, « jonction », « empreinte » : pub reconnue),
 *   « flux » (nouvelle chaîne : la détection repart de zéro), « chaine » (son nom), « frequence » (du son reçu).
 *
 * Instants : horloge de l'enregistrement en millisecondes, celle de la détection (durée de son traitée) mise bout à bout
 * d'une chaîne à l'autre (la détection repart de zéro à chaque flux, [newStream]).
 *
 * Les écritures passent par un fil à part : le fil du son et celui des images ne font que copier.
 */
class AdRecorder(
    private val directory: File,
    info: Map<String, String>,
    private val onFinished: (reason: String?) -> Unit,
) {
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ad-recorder").apply { isDaemon = true } }
    private val finished = AtomicBoolean(false)

    /** Écriture impossible (disque plein…) : plus rien n'est écrit, l'enregistrement s'arrête. */
    @Volatile
    private var broken = false

    private lateinit var levels: DataOutputStream
    private lateinit var audio: BufferedOutputStream
    private lateinit var frames: DataOutputStream
    private lateinit var events: BufferedOutputStream

    // Son ramené vers 8 000 Hz : moyenne de [factor] échantillons.
    private var rate = 0
    private var factor = 1
    private var sum = 0f
    private var summed = 0

    /** Horloge de l'enregistrement au début du flux en cours (la détection repart de zéro à chaque flux). */
    @Volatile
    private var base = 0L

    /** Dernier instant connu (horloge de l'enregistrement). */
    @Volatile
    private var last = 0L
    private var spaceCheckedAt = 0L

    /** Durée enregistrée, en millisecondes. */
    @Volatile
    var recordedMillis: Long = 0
        private set

    /** Pourquoi l'enregistrement s'est arrêté tout seul (place), `null` s'il a été arrêté à la main. */
    @Volatile
    var stopReason: String? = null
        private set

    val active: Boolean get() = !finished.get()

    init {
        post {
            directory.mkdirs()
            File(directory, "info.txt").writeText(info.entries.joinToString("") { (key, value) -> "$key=$value\n" })
            levels = DataOutputStream(BufferedOutputStream(FileOutputStream(File(directory, "levels.bin"))))
            audio = BufferedOutputStream(FileOutputStream(File(directory, "audio.ulaw")))
            frames = DataOutputStream(BufferedOutputStream(FileOutputStream(File(directory, "frames.bin"))))
            events = BufferedOutputStream(FileOutputStream(File(directory, "events.tsv")))
        }
    }

    private fun at(now: Long): Long = (base + now).also { last = maxOf(last, it) }

    /**
     * Nouveau flux (autre chaîne…) : la détection repart de zéro, l'enregistrement continue à la suite.
     * @param name nom de la chaîne, s'il est connu.
     */
    fun newStream(name: String? = null) {
        if (finished.get()) return
        base = last
        event(0, "flux", name.orEmpty())
        rate = 0
    }

    /**
     * Tranche de son traitée par la détection : son carré moyen, sa durée, et le son lui-même ([mono], [count]
     * échantillons à [sampleRate] Hz). [now] : horloge de la détection après la tranche.
     */
    fun sound(meanSquare: Double, durationMillis: Long, mono: FloatArray, count: Int, sampleRate: Int, now: Long) {
        if (finished.get()) return
        val time = at(now)
        if (sampleRate != rate) {
            // Fréquence du son reçu : celle du son enregistré s'en déduit (divisée par [factor]).
            event(now, "frequence", sampleRate.toString())
            rate = sampleRate
            factor = (sampleRate / TARGET_RATE.toDouble()).roundToInt().coerceAtLeast(1)
            sum = 0f
            summed = 0
        }
        val out = ByteArray(count / factor + 1)
        var n = 0
        for (i in 0 until count) {
            sum += mono[i]
            if (++summed == factor) {
                out[n++] = ulaw((sum / factor * 32_767).roundToInt())
                sum = 0f
                summed = 0
            }
        }
        recordedMillis = time
        val square = meanSquare.toFloat()
        val samples = n
        val checkSpace = time - spaceCheckedAt >= SPACE_CHECK_MILLIS
        if (checkSpace) spaceCheckedAt = time
        post {
            levels.writeFloat(square)
            levels.writeShort(durationMillis.toInt())
            levels.writeShort(samples)
            audio.write(out, 0, samples)
            // Place sur l'appareil : l'enregistrement s'arrête avant de la remplir.
            if (checkSpace && directory.usableSpace < MIN_FREE_BYTES) {
                stopReason = "place"
                finish()
            }
        }
    }

    /** Image réduite (96 × 54) reçue par la détection à l'instant [now] (horloge de la détection). */
    fun small(small: ByteArray, now: Long) {
        if (finished.get()) return
        val copy = small.copyOf()
        val time = at(now)
        post { frame('S', time, copy) }
    }

    /** Image de l'écran (gris, [width] × [height]) lue pour le logo à l'instant [now] : gardée en JPEG. */
    fun logoFrame(gray: ByteArray, width: Int, height: Int, now: Long) {
        if (finished.get()) return
        val copy = gray.copyOf()
        val time = at(now)
        post {
            val bitmap = bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            val pixels = IntArray(width * height) { i ->
                val v = copy[i].toInt() and 0xFF
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val jpeg = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            frame('L', time, jpeg.toByteArray())
        }
    }

    private var bitmap: Bitmap? = null

    /** Ce que la détection décide ou voit (son baissé, logo, repères du flux…), à l'instant [now] de la détection. */
    fun event(now: Long, what: String, value: String) {
        if (finished.get()) return
        val time = at(now)
        val line = "$time\t$what\t${value.replace('\t', ' ').replace('\n', ' ')}\n".toByteArray()
        post { events.write(line) }
    }

    private fun frame(type: Char, time: Long, bytes: ByteArray) {
        frames.writeByte(type.code)
        frames.writeLong(time)
        frames.writeInt(bytes.size)
        frames.write(bytes)
    }

    /** Travail confié au fil d'écriture ; une erreur arrête l'enregistrement, jamais l'application. */
    private fun post(work: () -> Unit) {
        try {
            writer.execute {
                if (broken) return@execute
                try {
                    work()
                } catch (e: Exception) {
                    broken = true
                    if (stopReason == null) stopReason = "erreur"
                    finish()
                }
            }
        } catch (e: RejectedExecutionException) {
            // Enregistrement déjà fini.
        }
    }

    /** Arrête l'enregistrement : les fichiers sont fermés sur le fil d'écriture, puis [onFinished]. */
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        try {
            writer.execute {
                try {
                    levels.close()
                    audio.close()
                    frames.close()
                    events.close()
                } catch (e: Exception) {
                    // Fichiers jamais ouverts (disque plein dès le début).
                }
                bitmap?.recycle()
                onFinished(stopReason)
            }
        } catch (e: RejectedExecutionException) {
            onFinished(stopReason)
        }
        writer.shutdown()
    }

    companion object {
        const val TARGET_RATE = 8_000
        private const val JPEG_QUALITY = 70

        /** Place laissée libre sur l'appareil : en dessous, l'enregistrement s'arrête. */
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val SPACE_CHECK_MILLIS = 30_000L

        /** Codage µ-law (G.711) d'un échantillon 16 bits. */
        fun ulaw(sample: Int): Byte {
            var value = sample.coerceIn(-32_635, 32_635)
            val sign = if (value < 0) 0x80 else 0
            if (value < 0) value = -value
            value += 0x84
            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && value and mask == 0) {
                exponent--
                mask = mask shr 1
            }
            val mantissa = (value shr (exponent + 3)) and 0x0F
            return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
        }
    }
}
