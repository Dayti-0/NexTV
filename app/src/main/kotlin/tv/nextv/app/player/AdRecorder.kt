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
import java.util.concurrent.atomic.AtomicLong

/**
 * Enregistrement pour l'analyse des pubs : tout ce que la détection reçoit pendant une lecture, et ce qu'elle décide,
 * pour la rejouer ensuite dans le code (`AdDeviceReplayTool`). Ni la vidéo ni le son : les niveaux du son, ses
 * empreintes ([AudioFingerprinter][tv.nextv.core.audio.AudioFingerprinter]), les petites images en gris que la
 * détection reçoit, les coins de l'image où se trouve le logo et, toutes les 5 s, une vignette de l'image entière pour
 * repérer les pubs à l'œil. Environ 25 Mo par heure ; [bytes] dit la taille écrite (les fichiers sont coupés avant
 * 30 Mo, la limite d'un envoi). Il dure jusqu'à ce qu'on l'arrête (ou que la place manque), même d'une chaîne à l'autre.
 *
 * Fichiers, écrits au fil de l'eau dans [directory] (renommé sans « .part » à la fin, [AdRecordings]) :
 * - `info.txt` : chaîne, date, appareil, format ;
 * - `levels.bin` : pour chaque tranche de son traitée, carré moyen (float) et durée en ms (short) ;
 * - `prints.bin` : empreintes du son, chacune : instant (int), empreinte (int), son audible (byte) ;
 * - `frames.bin` : images, chacune : type, instant (long), taille (int), octets JPEG. Types : « J » image réduite
 *   96 × 54 reçue par la détection, « C » les quatre coins de l'image du logo (480 × 270 ; coins de 133 × 65, un point
 *   de plus que ceux que lit `LogoWatcher`) côte à côte, deux par deux,
 *   « T » vignette 160 × 90 de l'image entière ;
 * - `events.tsv` : instant, quoi, valeur : son baissé ou remis, logo, repères du flux, ce que la détection décide
 *   (« ouverture » / « fermeture » d'une page de pubs avec leur raison, « jonction », « empreinte » : pub reconnue),
 *   « flux » (nouvelle chaîne : la détection repart de zéro), « chaine » (son nom).
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
    private lateinit var prints: DataOutputStream
    private lateinit var frames: DataOutputStream
    private lateinit var events: BufferedOutputStream

    private val written = AtomicLong(0)

    /** Taille écrite jusqu'ici, en octets. */
    val bytes: Long get() = written.get()

    /** Horloge de l'enregistrement au début du flux en cours (la détection repart de zéro à chaque flux). */
    @Volatile
    private var base = 0L

    /** Dernier instant connu (horloge de l'enregistrement). */
    @Volatile
    private var last = 0L
    private var spaceCheckedAt = 0L
    private var thumbnailAt = Long.MIN_VALUE

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
            prints = DataOutputStream(BufferedOutputStream(FileOutputStream(File(directory, "prints.bin"))))
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
    }

    /** Tranche de son traitée par la détection : son carré moyen et sa durée. [now] : horloge de la détection après elle. */
    fun sound(meanSquare: Double, durationMillis: Long, now: Long) {
        if (finished.get()) return
        val time = at(now)
        recordedMillis = time
        val square = meanSquare.toFloat()
        val checkSpace = time - spaceCheckedAt >= SPACE_CHECK_MILLIS
        if (checkSpace) spaceCheckedAt = time
        post {
            levels.writeFloat(square)
            levels.writeShort(durationMillis.toInt())
            written.addAndGet(6)
            // Place sur l'appareil : l'enregistrement s'arrête avant de la remplir.
            if (checkSpace && directory.usableSpace < MIN_FREE_BYTES) {
                stopReason = "place"
                finish()
            }
        }
    }

    /** Empreinte du son calculée par la détection, à l'instant [now] de la détection. */
    fun print(print: Int, loud: Boolean, now: Long) {
        if (finished.get()) return
        val time = at(now)
        post {
            prints.writeInt(time.toInt())
            prints.writeInt(print)
            prints.writeByte(if (loud) 1 else 0)
            written.addAndGet(9)
        }
    }

    /** Image réduite (96 × 54) reçue par la détection à l'instant [now] (horloge de la détection). */
    fun small(small: ByteArray, now: Long) {
        if (finished.get()) return
        val copy = small.copyOf()
        val time = at(now)
        post { frame('J', time, jpeg(copy, SMALL_WIDTH, SMALL_HEIGHT, SMALL_QUALITY)) }
    }

    /**
     * Image de l'écran (gris, [width] × [height]) lue pour le logo à l'instant [now] : ses quatre coins (là où la
     * détection cherche le logo) et, toutes les 5 s, une vignette de l'image entière.
     */
    fun logoFrame(gray: ByteArray, width: Int, height: Int, now: Long) {
        if (finished.get()) return
        val copy = gray.copyOf()
        val time = at(now)
        val thumbnail = thumbnailAt == Long.MIN_VALUE || time - thumbnailAt >= THUMBNAIL_MILLIS
        if (thumbnail) thumbnailAt = time
        post {
            // Un point de plus vers l'intérieur : la détection du logo y lit le voisin de chaque point du coin.
            val cw = (width * CORNER_WIDTH).toInt() + 1
            val ch = (height * CORNER_HEIGHT).toInt() + 1
            val corners = ByteArray(cw * 2 * ch * 2)
            for (cy in 0..1) for (cx in 0..1) {
                val sx = if (cx == 0) 0 else width - cw
                val sy = if (cy == 0) 0 else height - ch
                for (y in 0 until ch) System.arraycopy(copy, (sy + y) * width + sx, corners, (cy * ch + y) * cw * 2 + cx * cw, cw)
            }
            frame('C', time, jpeg(corners, cw * 2, ch * 2, CORNER_QUALITY))
            if (thumbnail) {
                val small = ByteArray(THUMB_WIDTH * THUMB_HEIGHT)
                for (y in 0 until THUMB_HEIGHT) for (x in 0 until THUMB_WIDTH) {
                    small[y * THUMB_WIDTH + x] = copy[(y * height / THUMB_HEIGHT) * width + x * width / THUMB_WIDTH]
                }
                frame('T', time, jpeg(small, THUMB_WIDTH, THUMB_HEIGHT, THUMB_QUALITY))
            }
        }
    }

    private val bitmaps = HashMap<Long, Bitmap>()

    /** Image en gris compressée en JPEG (sur le fil d'écriture). */
    private fun jpeg(gray: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = bitmaps.getOrPut(width.toLong() shl 32 or height.toLong()) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        val pixels = IntArray(width * height) { i ->
            val v = gray[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Ce que la détection décide ou voit (son baissé, logo, repères du flux…), à l'instant [now] de la détection. */
    fun event(now: Long, what: String, value: String) {
        if (finished.get()) return
        val time = at(now)
        val line = "$time\t$what\t${value.replace('\t', ' ').replace('\n', ' ')}\n".toByteArray()
        post {
            events.write(line)
            written.addAndGet(line.size.toLong())
        }
    }

    private fun frame(type: Char, time: Long, bytes: ByteArray) {
        frames.writeByte(type.code)
        frames.writeLong(time)
        frames.writeInt(bytes.size)
        frames.write(bytes)
        written.addAndGet(13L + bytes.size)
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
                    prints.close()
                    frames.close()
                    events.close()
                } catch (e: Exception) {
                    // Fichiers jamais ouverts (disque plein dès le début).
                }
                bitmaps.values.forEach(Bitmap::recycle)
                onFinished(stopReason)
            }
        } catch (e: RejectedExecutionException) {
            onFinished(stopReason)
        }
        writer.shutdown()
    }

    companion object {
        /** Place laissée libre sur l'appareil : en dessous, l'enregistrement s'arrête. */
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val SPACE_CHECK_MILLIS = 30_000L

        /** Coins de l'image où la détection cherche le logo (comme `LogoWatcher`). */
        const val CORNER_WIDTH = 0.275
        const val CORNER_HEIGHT = 0.24
        private const val CORNER_QUALITY = 70

        private const val SMALL_WIDTH = 96
        private const val SMALL_HEIGHT = 54
        private const val SMALL_QUALITY = 90

        private const val THUMB_WIDTH = 160
        private const val THUMB_HEIGHT = 90
        private const val THUMB_QUALITY = 60
        private const val THUMBNAIL_MILLIS = 5_000L
    }
}
