package tv.nextv.core.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Outil de mise au point (pas un test) : rejoue des enregistrements réels dans la détection des pubs, comme le
 * lecteur la fait tourner sur l'appareil, écrit seconde par seconde ce qu'elle répond et la note.
 *
 * Dans chaque dossier :
 * - le son : `audio.s16` (11 025 Hz, mono, 16 bits) ou, à défaut, `levels.f32` (carré moyen par tranche de 20 ms ;
 *   sans lui, pas de reconnaissance des pubs déjà entendues) ;
 * - une image par seconde pour le logo (`frames.gray`, 480 × 270) ;
 * - des images réduites (`small.gray`, 96 × 54 à 12,5 par seconde) : une par seconde, et une dizaine par seconde
 *   quand la détection les demande ;
 * - `tags.tsv` (facultatif) : les balises de la liste HLS avec leur instant dans l'enregistrement. Les pages de pub
 *   qu'elles annoncent sont la vérité de référence de la note (`score_<tag>.txt`, et le total en fin de sortie).
 *
 * Réglages (variables d'environnement) :
 * - `AD_SAMPLES` : dossiers, séparés par « : » (sans eux, l'outil ne tourne pas) ;
 * - `AD_CUES` : la détection reçoit les repères du flux (comme sur l'appareil quand le flux en porte) ;
 * - `AD_PRINTS` : reconnaissance des pubs déjà entendues, avec une mémoire commune à tous les dossiers ;
 * - `AD_TEACH` : la mémoire apprend aussi les pages de pub de la vérité (ce qu'une chaîne à repères lui apprendrait) ;
 * - `AD_PASSES` : passages sur l'ensemble (la mémoire des pubs, le logo et le carton de chaque chaîne sont gardés d'un
 *   passage à l'autre ; seul le dernier est noté) ;
 * - `AD_LATENCY` : retard de l'image sur le son traité (le son est traité avant d'être entendu), 400 ms par défaut.
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
        val useCues = System.getenv("AD_CUES") != null
        val usePrints = System.getenv("AD_PRINTS") != null
        val teach = System.getenv("AD_TEACH") != null
        val passes = System.getenv("AD_PASSES")?.toIntOrNull() ?: 1
        val framesName = System.getenv("AD_FRAMES") ?: "frames.gray"
        val library = AdPrintLibrary()
        val total = Score()
        // Ce que chaque chaîne a appris (logo, carton) : connu d'avance au passage suivant, comme sur l'appareil.
        val learned = HashMap<String, AdMarks>()
        val window = System.getenv("AD_WINDOW")?.toIntOrNull()
        val scoreFrom = System.getenv("AD_SCORE_FROM")?.toIntOrNull() ?: 0
        val recordings = folders.associateWith { Recording.load(File(it), framesName, pictures, logos) }
        val longest = recordings.values.maxOf { it.seconds }
        val windows = if (window == null) listOf(0 until longest) else (0 until longest step window).map { it until minOf(it + window, longest) }
        for (pass in 1..passes) {
            val lowered = folders.associateWith { BooleanArray(recordings.getValue(it).seconds) }
            val outs = folders.associateWith { StringBuilder("second\tattenuating\ttruth\tlogo\tprint\n") }
            val logs = folders.associateWith { StringBuilder() }
            // Chaînes regardées tour à tour, par tranches de AD_WINDOW secondes : la mémoire des pubs se remplit au fil du temps.
            for (range in windows) for (folder in folders) {
                val recording = recordings.getValue(folder)
                val seconds = range.first until minOf(range.last + 1, recording.seconds)
                if (seconds.isEmpty()) continue
                val audio = recording.audio
                val levels = recording.levels
                val truth = recording.truth
                val known = learned[folder]
                val watcher = LogoWatcher(WIDTH, HEIGHT).also { fresh -> known?.logo?.let(fresh::restore) }
                val guard = AdVolumeGuard().also {
                    it.separator = known?.separator
                    it.logoFollowsBreaks = known?.logoFollowsBreaks == true
                    it.logo = watcher.state
                }
                val events = logs.getValue(folder)
                val offset = seconds.first * 1_000L
                guard.trace = { events.append("[+${offset / 1_000} s] ").append(it).append('\n') }
                val watch = if (usePrints && audio != null) {
                    AdPrintWatch(library, guard, learner = { prints, loud, confirmed ->
                        val added = library.learn(prints, loud, confirmed, now = pass * 1_000_000L + (offset + guard.now) / 60_000)
                        events.append("appris ${prints.size} empreintes (${if (confirmed) "sûres" else "à l'essai"}) : $added nouvelles, ${library.size} morceaux\n")
                    }, forget = { chunk ->
                        library.forget(chunk)
                        events.append("oublié un morceau à l'essai\n")
                    })
                } else {
                    null
                }
                val out = outs.getValue(folder)
                val logoFrame = ByteArray(WIDTH * HEIGHT)
                val small = ByteArray(SmallFrame.SIZE)
                val samples = FloatArray(SAMPLE_RATE / SLICES + 2)
                var nextBurst = 0L
                for (second in seconds) {
                    var attenuated = 0
                    var recognized = false
                    for (i in 0 until SLICES) {
                        val now = second * 1_000L + i * SLICE_MILLIS
                        val shown = now - latency
                        val regular = i == REGULAR_SLICE
                        if (shown >= 0 && (regular || (guard.wantsFrames && now >= nextBurst))) {
                            recording.small?.let { if (read(it, shown * SMALL_RATE_TIMES_TEN / 10_000, small)) guard.frame(small) }
                            if (regular) recording.frames?.let { if (read(it, shown / 1_000, logoFrame)) guard.logo = watcher.frame(logoFrame) }
                            nextBurst = now + BURST_MILLIS
                        }
                        if (useCues && truth != null) guard.cue = truth.state(now)
                        val slice = second * SLICES + i
                        guard.process(levels[slice].toDouble(), SLICE_MILLIS)
                        if (watch != null && audio != null) {
                            val from = (slice.toLong() * SAMPLE_RATE / SLICES).toInt()
                            val to = ((slice + 1L) * SAMPLE_RATE / SLICES).toInt().coerceAtMost(audio.size)
                            for (k in from until to) samples[k - from] = audio[k] / 32_768f
                            watch.feed(samples, to - from, SAMPLE_RATE, guard.now)
                            if (watch.recognized) recognized = true
                        }
                        if (guard.attenuating) attenuated++
                    }
                    lowered.getValue(folder)[second] = attenuated * 2 >= SLICES
                    val inBreak = truth?.state(second * 1_000L + 500) == CueState.BREAK
                    out.append(second).append('\t').append(if (attenuated * 2 >= SLICES) 1 else 0).append('\t').append(if (inBreak) 1 else 0)
                        .append('\t').append(guard.logo.name).append('\t').append(if (recognized) 1 else 0).append('\n')
                }
                learned[folder] = AdMarks(watcher.export() ?: known?.logo, guard.separator ?: known?.separator, guard.logoFollowsBreaks || known?.logoFollowsBreaks == true)
                // Pages de pub de la vérité finies dans cette tranche, apprises après coup (une chaîne à repères les apprendrait ainsi).
                if (teach && truth != null && audio != null) teachTruth(library, truth, audio, pass, seconds)
            }
            for (folder in folders) {
                val recording = recordings.getValue(folder)
                File(folder, "timeline_$tag.tsv").writeText(outs.getValue(folder).toString())
                File(folder, "events_$tag.txt").writeText(logs.getValue(folder).toString())
                val truth = recording.truth
                if (truth != null && pass == passes) {
                    val score = Score.of(lowered.getValue(folder), truth, scoreFrom)
                    total.add(score)
                    File(folder, "score_$tag.txt").writeText(score.describe())
                    println("$folder : ${score.describe()}")
                } else {
                    println("$folder : ${recording.seconds} s rejouées (passage $pass)")
                }
            }
        }
        recordings.values.forEach { it.close() }
        if (usePrints) println("mémoire des pubs : ${library.size} morceaux, ${library.seconds} s")
        println("TOTAL [$tag] : ${total.describe()}")
    }

    /** Ce qu'une page annoncée par le flux apprend à la mémoire : son son entier (moins les bords). */
    private fun teachTruth(library: AdPrintLibrary, truth: AdCues, audio: ShortArray, pass: Int, seconds: IntRange) {
        for (interval in truth.intervals()) {
            if (interval[1] / 1_000 !in seconds) continue
            val from = ((interval[0] + 600) * SAMPLE_RATE / 1_000).toInt().coerceIn(0, audio.size)
            val to = ((interval[1] - 600) * SAMPLE_RATE / 1_000).toInt().coerceIn(0, audio.size)
            if (to - from < SAMPLE_RATE * 5) continue
            val prints = ArrayList<Int>()
            val loud = ArrayList<Boolean>()
            val fingerprinter = AudioFingerprinter(SAMPLE_RATE)
            val chunk = FloatArray(4_096)
            var k = from
            while (k < to) {
                val n = minOf(chunk.size, to - k)
                for (j in 0 until n) chunk[j] = audio[k + j] / 32_768f
                fingerprinter.feed(chunk, n) { print, audible ->
                    prints += print
                    loud += audible
                }
                k += n
            }
            library.learn(prints.toIntArray(), loud.toBooleanArray(), confirmed = true, now = pass * 1_000_000L + interval[0] / 60_000)
        }
    }

    private fun read(file: RandomAccessFile, index: Long, into: ByteArray): Boolean {
        val position = index * into.size
        if (position + into.size > file.length()) return false
        file.seek(position)
        file.readFully(into)
        return true
    }

    /** Un enregistrement chargé : son, niveaux, vérité, images (lues à la demande). */
    private class Recording(
        val audio: ShortArray?,
        val levels: FloatArray,
        val truth: AdCues?,
        val frames: RandomAccessFile?,
        val small: RandomAccessFile?,
    ) {
        val seconds = levels.size / SLICES

        fun close() {
            frames?.close()
            small?.close()
        }

        companion object {
            fun load(folder: File, framesName: String, pictures: Boolean, logos: Boolean): Recording {
                val audio = File(folder, "audio.s16").takeIf { it.exists() }?.readBytes()?.let { bytes ->
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    ShortArray(buffer.remaining()).also(buffer::get)
                }
                val levels = if (audio != null) levelsOf(audio) else File(folder, "levels.f32").readBytes().let { bytes ->
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    FloatArray(buffer.remaining()).also(buffer::get)
                }
                return Recording(
                    audio,
                    levels,
                    File(folder, "tags.tsv").takeIf { it.exists() }?.let(::truthOf),
                    File(folder, framesName).takeIf { it.exists() && pictures && logos }?.let { RandomAccessFile(it, "r") },
                    File(folder, "small.gray").takeIf { it.exists() && pictures }?.let { RandomAccessFile(it, "r") },
                )
            }
        }
    }

    /** Note d'un enregistrement, à la seconde : pubs dont le son a été baissé, programme baissé à tort. */
    private class Score {
        var adSeconds = 0
        var adLowered = 0
        var programmeSeconds = 0
        var programmeLowered = 0
        var breaks = 0
        var breaksFound = 0
        var onsetTotal = 0L
        var falseStarts = 0

        fun add(other: Score) {
            adSeconds += other.adSeconds
            adLowered += other.adLowered
            programmeSeconds += other.programmeSeconds
            programmeLowered += other.programmeLowered
            breaks += other.breaks
            breaksFound += other.breaksFound
            onsetTotal += other.onsetTotal
            falseStarts += other.falseStarts
        }

        fun describe(): String = String.format(
            Locale.ROOT,
            "pubs baissées %d/%d s (%.1f %%), programme baissé à tort %d/%d s (%.2f %%), pages trouvées %d/%d, délai moyen %.1f s, baisses à tort %d",
            adLowered, adSeconds, 100.0 * adLowered / adSeconds.coerceAtLeast(1), programmeLowered, programmeSeconds,
            100.0 * programmeLowered / programmeSeconds.coerceAtLeast(1), breaksFound, breaks,
            onsetTotal / breaksFound.coerceAtLeast(1).toDouble(), falseStarts,
        )

        companion object {
            fun of(lowered: BooleanArray, truth: AdCues, from: Int = 0): Score {
                val score = Score()
                val inBreak = BooleanArray(lowered.size) { truth.state(it * 1_000L + 500) == CueState.BREAK }
                for (s in from until lowered.size) {
                    if (inBreak[s]) {
                        score.adSeconds++
                        if (lowered[s]) score.adLowered++
                    } else {
                        score.programmeSeconds++
                        if (lowered[s]) score.programmeLowered++
                    }
                    // Baisse qui commence hors d'une page de pubs.
                    if (lowered[s] && (s == 0 || !lowered[s - 1]) && !inBreak[s]) score.falseStarts++
                }
                for (interval in truth.intervals()) {
                    val start = (interval[0] / 1_000).toInt().coerceAtLeast(0)
                    val to = ((interval[1] + 999) / 1_000).toInt().coerceAtMost(lowered.size)
                    if (to - start < 10 || start < from) continue
                    val from = start
                    score.breaks++
                    val first = (from until to).firstOrNull { lowered[it] } ?: continue
                    score.breaksFound++
                    score.onsetTotal += first - from
                }
                return score
            }
        }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SLICES = 50
        const val SLICE_MILLIS = 20L
        const val REGULAR_SLICE = 18
        const val BURST_MILLIS = 100L
        const val SAMPLE_RATE = 11_025

        /** 12,5 images par seconde. */
        const val SMALL_RATE_TIMES_TEN = 125L

        /** Carré moyen par tranche de 20 ms, comme le traitement du son du lecteur. */
        private fun levelsOf(audio: ShortArray): FloatArray {
            val slices = (audio.size.toLong() * SLICES / SAMPLE_RATE).toInt()
            return FloatArray(slices) { slice ->
                val from = (slice.toLong() * SAMPLE_RATE / SLICES).toInt()
                val to = ((slice + 1L) * SAMPLE_RATE / SLICES).toInt().coerceAtMost(audio.size)
                var energy = 0.0
                for (k in from until to) {
                    val sample = audio[k] / 32_768.0
                    energy += sample * sample
                }
                (energy / (to - from).coerceAtLeast(1)).toFloat()
            }
        }

        /** Pages de pub annoncées dans `tags.tsv` (instant dans l'enregistrement, balise), comme le lecteur les lit. */
        private fun truthOf(file: File): AdCues {
            val tags = ArrayList<String>()
            var cursor = 0.0
            for (line in file.readLines()) {
                val time = line.substringBefore('\t').toDoubleOrNull() ?: continue
                val tag = line.substringAfter('\t')
                if (time > cursor) {
                    tags += "#EXTINF:${time - cursor},"
                    cursor = time
                }
                tags += tag
            }
            return AdCues().also { it.hlsPlaylist(tags, 0) }
        }
    }
}
