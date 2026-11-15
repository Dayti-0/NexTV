package tv.nextv.core.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Outil de mise au point (pas un test) : rejoue un enregistrement fait sur l'appareil (options du lecteur ›
 * « Enregistrer pour l'analyse des pubs », fichier `.zip` ou dossier décompressé) dans la détection, exactement comme
 * elle l'a reçu : tranches de son (durées réelles), images réduites et images du logo à leurs instants, son pour les
 * empreintes.
 *
 * Écrit à côté de chaque enregistrement `<nom>_timeline_<tag>.tsv` (seconde, son baissé ici, son baissé sur l'appareil,
 * logo, pub reconnue), `<nom>_appareil.txt` (ce que l'appareil a noté : ouvertures et fermetures des pages de pubs avec
 * leur raison, jonctions, pubs reconnues, logo, repères du flux) et, si un fichier `<nom>.truth.tsv` donne les pages de pub (début et fin en secondes, une par
 * ligne, relevées en regardant les images), la note.
 *
 * Réglages : `AD_DEVICE` (enregistrements, séparés par « : »), `AD_TAG`, `AD_PRINTS` (mémoire des pubs commune à tous,
 * dans l'ordre donné), `AD_IMAGES` (secondes entre deux images du logo écrites en JPEG dans `<nom>_images/`, pour
 * relever les pages de pub à l'œil).
 */
class AdDeviceReplayTool {

    @Test
    fun replay() {
        val paths = System.getenv("AD_DEVICE")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(paths.isNotEmpty())
        val tag = System.getenv("AD_TAG") ?: "current"
        val usePrints = System.getenv("AD_PRINTS") != null
        val imagesEvery = System.getenv("AD_IMAGES")?.toIntOrNull()
        val library = AdPrintLibrary()
        for (path in paths) {
            val recording = DeviceRecording.load(File(path))
            val base = File(path).let { File(it.parentFile, it.name.removeSuffix(".zip")) }
            val events = StringBuilder()
            // Chaque flux (changement de chaîne) : détection repartie de zéro, comme sur l'appareil.
            var guard = AdVolumeGuard()
            var watcher = LogoWatcher(WIDTH, HEIGHT)
            var watch: AdPrintWatch? = null
            var offset = 0L
            fun fresh(at: Long) {
                offset = at
                guard = AdVolumeGuard()
                watcher = LogoWatcher(WIDTH, HEIGHT)
                val current = guard
                current.trace = { events.append("[${(offset + current.now) / 1_000} s] ").append(it).append('\n') }
                watch = if (usePrints) {
                    AdPrintWatch(library, current, learner = { prints, loud, confirmed ->
                        val added = library.learn(prints, loud, confirmed, now = (offset + current.now) / 60_000)
                        events.append("appris ${prints.size} empreintes (${if (confirmed) "sûres" else "à l'essai"}) : $added nouvelles\n")
                    }, forget = { library.forget(it) })
                } else {
                    null
                }
            }
            fresh(0)
            val streams = recording.events.filter { it.second == "flux" }.map { it.first }.toMutableList()
            imagesEvery?.let { File("${base.path}_images").mkdirs() }
            val out = StringBuilder("second\tattenuating\tdevice\tlogo\tprint\n")
            var frame = 0
            var audioAt = 0
            var second = 0L
            var attenuatedSlices = 0
            var slices = 0
            var recognized = false
            var nextImage = 0L
            val samples = FloatArray(4_096)
            for (slice in recording.slices) {
                if (streams.isNotEmpty() && streams.first() <= offset + guard.now) fresh(streams.removeAt(0))
                val now = offset + guard.now
                // Images reçues avant cette tranche de son (datées à l'horloge de l'enregistrement).
                while (frame < recording.frames.size && recording.frames[frame].time <= now) {
                    val f = recording.frames[frame++]
                    if (f.type == 'S') {
                        guard.frame(f.bytes)
                    } else {
                        val gray = gray(f.bytes) ?: continue
                        guard.logo = watcher.frame(gray)
                        if (imagesEvery != null && f.time >= nextImage) {
                            nextImage = f.time + imagesEvery * 1_000L
                            File("${base.path}_images", "%05d.jpg".format(f.time / 1_000)).writeBytes(f.bytes)
                        }
                    }
                }
                guard.process(slice.meanSquare.toDouble(), slice.durationMillis.toLong())
                val w = watch
                if (w != null && slice.samples > 0) {
                    val n = minOf(slice.samples, recording.audio.size - audioAt, samples.size).coerceAtLeast(0)
                    for (k in 0 until n) samples[k] = recording.audio[audioAt + k]
                    w.feed(samples, n, recording.audioRate(offset + guard.now), guard.now)
                    if (w.recognized) recognized = true
                }
                audioAt += slice.samples
                slices++
                if (guard.attenuating) attenuatedSlices++
                while (offset + guard.now >= (second + 1) * 1_000) {
                    val device = recording.deviceLowered(second * 1_000 + 500)
                    out.append(second).append('\t').append(if (attenuatedSlices * 2 >= slices) 1 else 0).append('\t')
                        .append(if (device) 1 else 0).append('\t').append(guard.logo.name).append('\t').append(if (recognized) 1 else 0).append('\n')
                    second++
                    attenuatedSlices = 0
                    slices = 0
                    recognized = false
                }
            }
            File("${base.path}_timeline_$tag.tsv").writeText(out.toString())
            // Ce que l'appareil a vu et décidé, en minutes:secondes (ce qui a ouvert ou fermé chaque page de pubs…).
            File("${base.path}_appareil.txt").writeText(
                recording.events.joinToString("") { (time, what, detail) -> "%d:%02d\t%s\t%s\n".format(time / 60_000, time / 1_000 % 60, what, detail) },
            )
            File("${base.path}_events_$tag.txt").writeText(events.toString())
            val lowered = out.lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t')[1] == "1" }
            val truth = File("${base.path}.truth.tsv").takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) null else (parts[0].toDoubleOrNull() ?: return@mapNotNull null) to (parts[1].toDoubleOrNull() ?: return@mapNotNull null)
            }
            val summary = if (truth == null) {
                "${lowered.size} s rejouées, ${lowered.count { it }} s baissées (${recording.info["chaine"]})"
            } else {
                val inBreak = lowered.indices.map { s -> truth.any { (from, to) -> s + 0.5 in from..to } }
                val ads = inBreak.count { it }
                val hit = lowered.indices.count { inBreak[it] && lowered[it] }
                val wrong = lowered.indices.count { !inBreak[it] && lowered[it] }
                "pubs baissées $hit/$ads s, programme baissé à tort $wrong/${lowered.size - ads} s (${recording.info["chaine"]})"
            }
            println("$path : $summary")
        }
        if (usePrints) println("mémoire des pubs : ${library.size} morceaux, ${library.seconds} s")
    }

    private fun gray(jpeg: ByteArray): ByteArray? {
        val image = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return null
        if (image.width != WIDTH || image.height != HEIGHT) return null
        val out = ByteArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) out[y * WIDTH + x] = (image.getRGB(x, y) and 0xFF).toByte()
        return out
    }

    private class Slice(val meanSquare: Float, val durationMillis: Int, val samples: Int)

    private class Frame(val type: Char, val time: Long, val bytes: ByteArray)

    /** Enregistrement de l'appareil relu ([AdRecorder] de l'application). */
    private class DeviceRecording(
        val info: Map<String, String>,
        val slices: List<Slice>,
        val audio: FloatArray,
        /** Fréquences du son reçu au fil de l'enregistrement (instant, fréquence). */
        val soundRates: List<Pair<Long, Int>>,
        val frames: List<Frame>,
        /** Instants où l'appareil a baissé (1) ou remis (0) le son. */
        val lowering: List<Pair<Long, Boolean>>,
        /** Tout ce que l'appareil a noté : instant, quoi, détail. */
        val events: List<Triple<Long, String, String>>,
    ) {
        /** Fréquence du son enregistré à l'instant [time] : celle du son reçu divisée par arrondi(fréquence / 8 000). */
        fun audioRate(time: Long): Int {
            val sound = soundRates.lastOrNull { it.first <= time }?.second ?: soundRates.firstOrNull()?.second ?: 48_000
            return sound / (sound / 8_000.0).roundToInt().coerceAtLeast(1)
        }

        fun deviceLowered(time: Long): Boolean = lowering.lastOrNull { it.first <= time }?.second == true

        companion object {
            fun load(path: File): DeviceRecording {
                val entries: Map<String, ByteArray> = if (path.isDirectory) {
                    path.listFiles().orEmpty().associate { it.name to it.readBytes() }
                } else {
                    ZipFile(path).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes() } }
                }
                val read: (String) -> ByteArray? = { name -> entries[name] }
                val info = read("info.txt")?.decodeToString()?.lines()?.filter { '=' in it }?.associate { it.substringBefore('=') to it.substringAfter('=') }.orEmpty()
                val slices = ArrayList<Slice>()
                read("levels.bin")?.let { bytes ->
                    val input = DataInputStream(ByteArrayInputStream(bytes))
                    try {
                        while (true) slices += Slice(input.readFloat(), input.readShort().toInt(), input.readShort().toInt() and 0xFFFF)
                    } catch (e: EOFException) {
                        // Fin.
                    }
                }
                val frames = ArrayList<Frame>()
                read("frames.bin")?.let { bytes ->
                    val input = DataInputStream(ByteArrayInputStream(bytes))
                    try {
                        while (true) {
                            val type = input.readByte().toInt().toChar()
                            val time = input.readLong()
                            val data = ByteArray(input.readInt()).also(input::readFully)
                            frames += Frame(type, time, data)
                        }
                    } catch (e: EOFException) {
                        // Fin (la dernière image peut être coupée).
                    }
                }
                val events = read("events.tsv")?.decodeToString()?.lines()?.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) null else Triple(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1], parts[2])
                }.orEmpty()
                val soundRates = events.filter { it.second == "frequence" }.mapNotNull { (time, _, value) -> value.toIntOrNull()?.let { time to it } }
                val audio = read("audio.ulaw")?.let { bytes -> FloatArray(bytes.size) { ulaw(bytes[it]) / 32_768f } } ?: FloatArray(0)
                val lowering = events.filter { it.second == "baisse" }.map { it.first to (it.third == "1") }
                return DeviceRecording(info, slices, audio, soundRates, frames.sortedBy { it.time }, lowering, events)
            }

            /** Décodage µ-law (G.711). */
            fun ulaw(byte: Byte): Int {
                val value = byte.toInt().inv() and 0xFF
                val sign = value and 0x80
                val exponent = (value shr 4) and 0x07
                val mantissa = value and 0x0F
                val magnitude = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
                return if (sign != 0) -magnitude else magnitude
            }
        }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
    }
}
