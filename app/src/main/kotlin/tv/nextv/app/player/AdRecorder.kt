package tv.nextv.app.player

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Enregistrement pour l'analyse des pubs : tout ce que la détection reçoit pendant une lecture, et ce qu'elle décide,
 * pour la rejouer ensuite dans le code (`AdReplayTool`). Pas la vidéo : le son ramené vers 8 000 Hz (µ-law, 8 Ko/s),
 * les petites images en gris que la détection reçoit, celle du logo (JPEG, une par seconde) et les décisions.
 *
 * Fichiers (dans [directory], réunis à la fin en un `.zip` à côté) :
 * - `info.txt` : chaîne, date, appareil, fréquences du son ;
 * - `levels.bin` : pour chaque tranche de son traitée, carré moyen (float), durée en ms (short), échantillons enregistrés
 *   pour cette tranche (short) ;
 * - `audio.ulaw` : le son (mono, µ-law) ;
 * - `frames.bin` : images reçues, chacune : type (« S » image réduite 96 × 54, « L » image du logo en JPEG), instant
 *   (horloge du son, long), taille (int), octets ;
 * - `events.tsv` : instant, quoi, valeur (son baissé, logo, repères du flux).
 *
 * Les écritures passent par un fil à part : le fil du son et celui des images ne font que copier.
 */
class AdRecorder(
    private val directory: File,
    info: Map<String, String>,
    /** Durée au bout de laquelle l'enregistrement s'arrête seul (horloge du son). */
    private val maxMillis: Long,
    private val onFinished: (File?) -> Unit,
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
    private var startedAt = -1L

    /** Durée enregistrée, en millisecondes (horloge du son). */
    @Volatile
    var recordedMillis: Long = 0
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

    /**
     * Tranche de son traitée par la détection : son carré moyen, sa durée, et le son lui-même ([mono], [count]
     * échantillons à [sampleRate] Hz). [now] : horloge du son après la tranche.
     */
    fun sound(meanSquare: Double, durationMillis: Long, mono: FloatArray, count: Int, sampleRate: Int, now: Long) {
        if (finished.get()) return
        if (startedAt < 0) startedAt = now - durationMillis
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
        recordedMillis = now - startedAt
        val square = meanSquare.toFloat()
        val samples = n
        post {
            levels.writeFloat(square)
            levels.writeShort(durationMillis.toInt())
            levels.writeShort(samples)
            audio.write(out, 0, samples)
        }
        if (recordedMillis >= maxMillis) finish()
    }

    /** Image réduite (96 × 54) reçue par la détection à l'instant [now]. */
    fun small(small: ByteArray, now: Long) {
        if (finished.get()) return
        val copy = small.copyOf()
        post { frame('S', now, copy) }
    }

    /** Image de l'écran (gris, [width] × [height]) lue pour le logo à l'instant [now] : gardée en JPEG. */
    fun logoFrame(gray: ByteArray, width: Int, height: Int, now: Long) {
        if (finished.get()) return
        val copy = gray.copyOf()
        post {
            val bitmap = bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            val pixels = IntArray(width * height) { i ->
                val v = copy[i].toInt() and 0xFF
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val jpeg = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            frame('L', now, jpeg.toByteArray())
        }
    }

    private var bitmap: Bitmap? = null

    /** Ce que la détection décide ou voit (son baissé, logo, repères du flux), à l'instant [now]. */
    fun event(now: Long, what: String, value: String) {
        if (finished.get()) return
        post { events.write("$now\t$what\t$value\n".toByteArray()) }
    }

    private fun frame(type: Char, now: Long, bytes: ByteArray) {
        frames.writeByte(type.code)
        frames.writeLong(now)
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
                    finish()
                }
            }
        } catch (e: RejectedExecutionException) {
            // Enregistrement déjà fini.
        }
    }

    /** Arrête l'enregistrement et réunit ses fichiers en un `.zip` (sur le fil d'écriture). */
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        try {
            writer.execute {
                val zip = try {
                    levels.close()
                    audio.close()
                    frames.close()
                    events.close()
                    if (broken) null else pack()
                } catch (e: Exception) {
                    // Fichiers jamais ouverts (disque plein dès le début) ou zip impossible.
                    null
                }
                directory.deleteRecursively()
                bitmap?.recycle()
                onFinished(zip)
            }
        } catch (e: RejectedExecutionException) {
            onFinished(null)
        }
        writer.shutdown()
    }

    private fun pack(): File? {
        if (recordedMillis < MIN_MILLIS) return null
        val zip = File(directory.parentFile, directory.name + ".zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            for (name in listOf("info.txt", "levels.bin", "audio.ulaw", "frames.bin", "events.tsv")) {
                val file = File(directory, name)
                if (!file.exists()) continue
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    companion object {
        const val TARGET_RATE = 8_000
        private const val JPEG_QUALITY = 70

        /** Moins d'une minute : rien d'utile, rien n'est gardé. */
        private const val MIN_MILLIS = 60_000L

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
