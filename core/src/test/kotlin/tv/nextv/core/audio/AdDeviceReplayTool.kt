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
        // `AD_SEED_OUT` : fabrique seulement une mémoire des pubs avec le son des pages de pub relevées (`<nom>.truth.tsv`)
        // de ces enregistrements, écrite dans ce fichier (bibliothèque fournie avec l'appli). `AD_SEED` : mémoire de départ.
        System.getenv("AD_SEED_OUT")?.let { out ->
            buildSeed(paths, File(out))
            return
        }
        val seed = System.getenv("AD_SEED")?.let(::File)
        val usePrints = System.getenv("AD_PRINTS") != null || seed != null
        val imagesEvery = System.getenv("AD_IMAGES")?.toIntOrNull()
        // Totaux (ici, sur l'appareil) : pubs baissées, secondes de pub, programme baissé à tort, secondes de programme, délais, pages.
        val totals = Array(2) { IntArray(6) }
        // `AD_KNOWN` : un premier passage apprend le logo et le carton de chaque chaîne, connus d'avance au second (comme sur
        // l'appareil, qui les garde d'un jour à l'autre). La mémoire des pubs, elle, repart de zéro au second passage.
        val passes = if (System.getenv("AD_KNOWN") != null) 2 else 1
        val marks = HashMap<String, AdMarks>()
        fun startLibrary() = AdPrintLibrary().also { library -> seed?.inputStream()?.use { library.restore(it) } }
        var library = startLibrary()
        for (pass in 1..passes) {
        val last = pass == passes
        library = startLibrary()
        for (path in paths) {
            val recording = DeviceRecording.load(File(path))
            val base = File(path).let { File(it.parentFile, it.name.removeSuffix(".zip")) }
            val events = StringBuilder()
            // Chaque flux (changement de chaîne) : détection repartie de zéro, comme sur l'appareil.
            var guard = AdVolumeGuard()
            var watcher = LogoWatcher(WIDTH, HEIGHT)
            var watch: AdPrintWatch? = null
            var offset = 0L
            var channel: String? = null
            fun keepMarks() {
                val name = channel ?: return
                val old = marks[name]
                marks[name] = AdMarks(watcher.export() ?: old?.logo, guard.separator ?: old?.separator, guard.logoFollowsBreaks || old?.logoFollowsBreaks == true)
            }
            fun fresh(at: Long) {
                keepMarks()
                offset = at
                // Chaîne de ce flux : notée par l'appareil juste après (sinon la même qu'avant : flux rouvert).
                // Celle notée à partir de cet instant d'abord : un flux changé moins d'une seconde après le précédent ne doit
                // pas prendre le nom de celui-ci (Gulli appris comme TF1).
                val name = (
                    recording.events.firstOrNull { it.second == "chaine" && it.first in at..at + 20_000 }
                        ?: recording.events.firstOrNull { it.second == "chaine" && it.first in at - 1_000..at }
                    )?.third ?: channel?.substringBefore(QUALITY_SEPARATOR)
                // Qualité lue (0.2.237) : l'appareil garde ce qu'il apprend par flux, donc par qualité ; le logo appris en
                // FHD ne se retrouve pas en SD (France 2 du 26/11/2026 : logo dit absent, programme baissé à tort).
                val quality = recording.events.lastOrNull { it.second == "qualite" && it.first <= at + 20_000 }?.third
                channel = name?.let { if (quality != null) "$it$QUALITY_SEPARATOR$quality" else it }
                guard = AdVolumeGuard()
                watcher = LogoWatcher(WIDTH, HEIGHT)
                channel?.let(marks::get)?.let { known ->
                    known.logo?.let(watcher::restore)
                    guard.separator = known.separator
                    guard.logoFollowsBreaks = known.logoFollowsBreaks
                    guard.logo = watcher.state
                }
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
            // Fichier commencé en cours de lecture (coupé au bout d'une heure…) : son horloge part de là.
            val start = recording.events.firstOrNull()?.first ?: 0L
            fresh(start)
            val streams = recording.events.filter { it.second == "flux" }.map { it.first }.toMutableList()
            imagesEvery?.let { File("${base.path}_images").mkdirs() }
            val out = StringBuilder("second\tattenuating\tdevice\tlogo\tprint\n")
            var frame = 0
            var print = 0
            var audioAt = 0
            var second = start / 1_000
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
                    when (f.type) {
                        // Image réduite reçue par la détection : brute (format 2) ou en JPEG (format 3).
                        'S' -> guard.frame(f.bytes)
                        'J' -> decode(f.bytes, SmallFrame.WIDTH, SmallFrame.HEIGHT)?.let(guard::frame)
                        // Image du logo : entière (format 2), ou ses quatre coins (format 3).
                        'L' -> gray(f.bytes)?.let { guard.logo = watcher.frame(it) }
                        'C' -> corners(f.bytes)?.let { guard.logo = watcher.frame(it) }
                        else -> Unit
                    }
                    // Images à regarder pour relever les pages de pub : vignettes (format 3) ou images du logo (format 2).
                    if (imagesEvery != null && (f.type == 'T' || f.type == 'L') && f.time >= nextImage) {
                        nextImage = f.time + imagesEvery * 1_000L
                        File("${base.path}_images", "%05d.jpg".format(f.time / 1_000)).writeBytes(f.bytes)
                    }
                }
                // Empreintes du son calculées par l'appareil (format 3), à leur instant.
                while (watch != null && print < recording.prints.size && recording.prints[print].time <= offset + guard.now) {
                    val p = recording.prints[print++]
                    watch?.print(p.print, p.loud, p.time - offset)
                    if (watch?.recognized == true) recognized = true
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
            keepMarks()
            if (!last) continue
            File("${base.path}_timeline_$tag.tsv").writeText(out.toString())
            // Ce que l'appareil a vu et décidé, en minutes:secondes (ce qui a ouvert ou fermé chaque page de pubs…).
            File("${base.path}_appareil.txt").writeText(
                recording.events.joinToString("") { (time, what, detail) -> "%d:%02d\t%s\t%s\n".format(time / 60_000, time / 1_000 % 60, what, detail) },
            )
            File("${base.path}_events_$tag.txt").writeText(events.toString())
            // Seconde (horloge de l'enregistrement) → son baissé ici, et sur l'appareil.
            val rows = out.lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
            val lowered = rows.map { it[1] == "1" }
            val secondsOf = rows.map { it[0].toInt() }
            val device = rows.map { it[2] == "1" }
            val truth = File("${base.path}.truth.tsv").takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) null else (parts[0].toDoubleOrNull() ?: return@mapNotNull null) to (parts[1].toDoubleOrNull() ?: return@mapNotNull null)
            }
            val summary = if (truth == null) {
                "${lowered.size} s rejouées, ${lowered.count { it }} s baissées (${recording.info["chaine"]})"
            } else {
                val inBreak = secondsOf.map { s -> truth.any { (from, to) -> s + 0.5 in from..to } }
                fun score(low: List<Boolean>): IntArray {
                    val hit = low.indices.count { inBreak[it] && low[it] }
                    val wrong = low.indices.count { !inBreak[it] && low[it] }
                    // Délai de chaque page de pubs : première seconde baissée après son début.
                    val delays = truth.filter { it.second - it.first >= 20 }.map { (from, to) ->
                        low.indices.firstOrNull { low[it] && secondsOf[it] >= from && secondsOf[it] <= to }?.let { (secondsOf[it] - from).toInt() } ?: (to - from).toInt()
                    }
                    return intArrayOf(hit, inBreak.count { it }, wrong, inBreak.count { !it }, delays.sum(), delays.size)
                }
                val here = score(lowered)
                val there = score(device)
                totals[0].indices.forEach { totals[0][it] += here[it]; totals[1][it] += there[it] }
                "ici : pubs baissées ${here[0]}/${here[1]} s, programme à tort ${here[2]} s, délai ${here[4]} s | appareil : ${there[0]}/${there[1]} s, à tort ${there[2]} s, délai ${there[4]} s"
            }
            println("$path : $summary")
        }
        }
        if (usePrints) println("mémoire des pubs : ${library.size} morceaux, ${library.seconds} s")
        for ((name, t) in listOf("ICI" to totals[0], "APPAREIL" to totals[1])) {
            if (t[1] > 0) println("TOTAL $name [$tag] : pubs baissées ${t[0]}/${t[1]} s (${100 * t[0] / t[1]} %), programme baissé à tort ${t[2]}/${t[3]} s, délai moyen ${t[4] / t[5].coerceAtLeast(1)} s sur ${t[5]} pages")
        }
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

    /** Mémoire des pubs faite du son des pages relevées à l'œil (vérité) : à l'essai, comme sur l'appareil. */
    private fun buildSeed(paths: List<String>, out: File) {
        val library = AdPrintLibrary()
        var pages = 0
        for ((index, path) in paths.withIndex()) {
            val base = File(path).let { File(it.parentFile, it.name.removeSuffix(".zip")) }
            val truth = File("${base.path}.truth.tsv").takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) null else (parts[0].toDoubleOrNull() ?: return@mapNotNull null) to (parts[1].toDoubleOrNull() ?: return@mapNotNull null)
            } ?: continue
            val recording = DeviceRecording.load(File(path))
            for ((from, to) in truth) {
                // Marge à chaque bout (`AD_SEED_MARGIN`, 5 s) : ni le programme juste avant ou après, ni les jingles et
                // parrainages de la chaîne en bord de page (« ÉDITION » de CNews), reconnus ensuite au retour du programme.
                val margin = System.getenv("AD_SEED_MARGIN")?.toDoubleOrNull() ?: 5.0
                val inside = recording.prints.filter { it.time / 1_000.0 in (from + margin)..(to - margin) }
                if (inside.size < AudioFingerprinter.PER_SECOND * 4) continue
                // Daté de son rang : la place manquant, ce sont les pubs des enregistrements les plus récents qui restent.
                library.learn(inside.map { it.print }.toIntArray(), inside.map { it.loud }.toBooleanArray(), confirmed = false, now = index.toLong())
                pages++
            }
        }
        // `AD_SEED_ADD` : autres mémoires à ajouter, les plus récentes (pages annoncées des directs RMC, `AdReplayTool`).
        System.getenv("AD_SEED_ADD")?.split(':')?.filter { it.isNotBlank() }?.forEach { extra ->
            val other = AdPrintLibrary(Int.MAX_VALUE).also { lib -> File(extra).inputStream().use { lib.restore(it) } }
            other.chunks().forEach { chunk -> library.learn(chunk.prints, chunk.loud, confirmed = false, now = paths.size.toLong()) }
        }
        out.writeBytes(library.encode())
        println("mémoire des pubs : $pages pages, ${library.size} morceaux, ${library.seconds} s de pub, ${out.length() / 1024} Ko")
    }

    private class Print(val time: Long, val print: Int, val loud: Boolean)

    /** Image en gris d'un JPEG de taille connue. */
    private fun decode(jpeg: ByteArray, width: Int, height: Int): ByteArray? {
        val image = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return null
        if (image.width != width || image.height != height) return null
        val out = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) out[y * width + x] = (image.getRGB(x, y) and 0xFF).toByte()
        return out
    }

    /** Image 480 × 270 refaite à partir de ses quatre coins (le reste en gris moyen) : ce que lit `LogoWatcher`. */
    private fun corners(jpeg: ByteArray): ByteArray? {
        val cw = (WIDTH * 0.275).toInt() + 1
        val ch = (HEIGHT * 0.24).toInt() + 1
        val composite = decode(jpeg, cw * 2, ch * 2) ?: return null
        val out = ByteArray(WIDTH * HEIGHT) { 128.toByte() }
        for (cy in 0..1) for (cx in 0..1) {
            val dx = if (cx == 0) 0 else WIDTH - cw
            val dy = if (cy == 0) 0 else HEIGHT - ch
            for (y in 0 until ch) System.arraycopy(composite, (cy * ch + y) * cw * 2 + cx * cw, out, (dy + y) * WIDTH + dx, cw)
        }
        return out
    }

    /** Enregistrement de l'appareil relu ([AdRecorder] de l'application). */
    private class DeviceRecording(
        val info: Map<String, String>,
        val slices: List<Slice>,
        val audio: FloatArray,
        /** Empreintes du son calculées par l'appareil (format 3). */
        val prints: List<Print>,
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
                // Format 3 : carré moyen et durée ; avant : aussi les échantillons de son enregistrés pour la tranche.
                val withSound = (info["format"]?.toIntOrNull() ?: 1) < 3
                read("levels.bin")?.let { bytes ->
                    val input = DataInputStream(ByteArrayInputStream(bytes))
                    try {
                        while (true) {
                            val square = input.readFloat()
                            val duration = input.readShort().toInt()
                            slices += Slice(square, duration, if (withSound) input.readShort().toInt() and 0xFFFF else 0)
                        }
                    } catch (e: EOFException) {
                        // Fin.
                    }
                }
                val prints = ArrayList<Print>()
                read("prints.bin")?.let { bytes ->
                    val input = DataInputStream(ByteArrayInputStream(bytes))
                    try {
                        while (true) prints += Print(input.readInt().toLong(), input.readInt(), input.readByte().toInt() == 1)
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
                // Changement de flux : la détection repart de zéro, son non baissé (avant 0.2.173, l'appareil ne le notait pas).
                val lowering = events.filter { it.second == "baisse" || it.second == "flux" }.map { it.first to (it.second == "baisse" && it.third == "1") }
                return DeviceRecording(info, slices, audio, prints, soundRates, frames.sortedBy { it.time }, lowering, events)
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
        const val QUALITY_SEPARATOR = " · "
        const val WIDTH = 480
        const val HEIGHT = 270
    }
}
