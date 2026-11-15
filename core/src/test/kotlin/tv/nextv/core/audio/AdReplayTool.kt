package tv.nextv.core.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Outil de mise au point (pas un test) : rejoue des enregistrements réels dans la détection des pubs, comme le
 * lecteur la fait tourner sur l'appareil, et écrit seconde par seconde ce qu'elle répond.
 *
 * - le son par tranches de 20 ms (`levels.f32`) ;
 * - une image par seconde pour le logo (`frames.gray`, 480 × 270) ;
 * - des images réduites (`small.gray`, 96 × 54 à 12,5 par seconde) : une par seconde, et une dizaine par seconde
 *   quand la détection les demande ;
 * - le son est traité avant d'être entendu : l'image montrée est en retard de `AD_LATENCY` millisecondes.
 *
 * Ne tourne que si `AD_SAMPLES` désigne des dossiers d'enregistrements (séparés par « : »).
 */
class AdReplayTool {

    @Test
    fun replay() {
        val folders = System.getenv("AD_SAMPLES")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(folders.isNotEmpty())
        val tag = System.getenv("AD_TAG") ?: "current"
        val latency = System.getenv("AD_LATENCY")?.toLongOrNull() ?: 400L
        val pictures = System.getenv("AD_NO_PICTURES") == null
        val logos = System.getenv("AD_NO_LOGO") == null
        val framesName = System.getenv("AD_FRAMES") ?: "frames.gray"
        for (folder in folders) {
            val levels = File(folder, "levels.f32").readBytes().let { bytes ->
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(buffer.remaining()).also(buffer::get)
            }
            val logoFrames = File(folder, framesName).takeIf { it.exists() && pictures && logos }?.let { RandomAccessFile(it, "r") }
            val smallFrames = File(folder, "small.gray").takeIf { it.exists() && pictures }?.let { RandomAccessFile(it, "r") }
            var watcher = LogoWatcher(WIDTH, HEIGHT)
            var guard = AdVolumeGuard()
            var events = StringBuilder()
            guard.trace = { events.append(it).append('\n') }
            var out = StringBuilder("second\tattenuating\tlogo\n")
            val logoFrame = ByteArray(WIDTH * HEIGHT)
            val small = ByteArray(SmallFrame.SIZE)
            val seconds = levels.size / SLICES
            var nextBurst = 0L
            // Chaîne déjà regardée (`AD_KNOWN`) : un premier passage apprend le logo et le carton, le second les connaît d'avance.
            val passes = if (System.getenv("AD_KNOWN") != null) 2 else 1
            for (pass in 1..passes) {
            if (pass == 2) {
                val logo = watcher.export()
                val separator = guard.separator
                val follows = guard.logoFollowsBreaks
                watcher = LogoWatcher(WIDTH, HEIGHT).also { fresh -> logo?.let(fresh::restore) }
                guard = AdVolumeGuard().also {
                    it.separator = separator
                    it.logoFollowsBreaks = follows
                    it.logo = watcher.state
                }
                events = StringBuilder("logo connu d'avance : ${logo != null} (suit les coupures : $follows) ; carton connu d'avance : ${separator != null}\n")
                val log = events
                guard.trace = { log.append(it).append('\n') }
                out = StringBuilder("second\tattenuating\tlogo\n")
                nextBurst = 0L
            }
            for (second in 0 until seconds) {
                var attenuated = 0
                for (i in 0 until SLICES) {
                    val now = second * 1_000L + i * SLICE_MILLIS
                    val shown = now - latency
                    // Image régulière, une par seconde (décalée du début de la seconde), puis images rapprochées à la demande.
                    val regular = i == REGULAR_SLICE
                    if (shown >= 0 && (regular || (guard.wantsFrames && now >= nextBurst))) {
                        if (smallFrames != null && read(smallFrames, shown * SMALL_RATE_TIMES_TEN / 10_000, small)) guard.frame(small)
                        if (regular && logoFrames != null && read(logoFrames, shown / 1_000, logoFrame)) guard.logo = watcher.frame(logoFrame)
                        nextBurst = now + BURST_MILLIS
                    }
                    guard.process(levels[second * SLICES + i].toDouble(), SLICE_MILLIS)
                    if (guard.attenuating) attenuated++
                }
                out.append(second).append('\t').append(if (attenuated * 2 >= SLICES) 1 else 0).append('\t').append(guard.logo.name).append('\n')
            }
            }
            logoFrames?.close()
            smallFrames?.close()
            File(folder, "timeline_$tag.tsv").writeText(out.toString())
            File(folder, "events_$tag.txt").writeText(events.toString())
            println("$folder : $seconds s rejouées -> timeline_$tag.tsv")
        }
    }

    private fun read(file: RandomAccessFile, index: Long, into: ByteArray): Boolean {
        val position = index * into.size
        if (position + into.size > file.length()) return false
        file.seek(position)
        file.readFully(into)
        return true
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SLICES = 50
        const val SLICE_MILLIS = 20L
        const val REGULAR_SLICE = 18
        const val BURST_MILLIS = 100L

        /** 12,5 images par seconde. */
        const val SMALL_RATE_TIMES_TEN = 125L
    }
}
