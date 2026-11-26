package tv.nextv.core.audio

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pubs déjà entendues, gardées par leurs empreintes ([AudioFingerprinter]) : commune à toutes les chaînes, une pub
 * apprise sur l'une est reconnue sur les autres.
 *
 * Les empreintes sont gardées par morceaux (le son d'une page de pubs, moins ce qui était déjà connu). Un morceau
 * appris d'une page annoncée par le flux ([AdCues]) est sûr (`confirmed`) ; appris de la détection seule, il est à
 * l'essai : il ne compte que sans logo à l'image, et il est oublié s'il repasse sous le logo (c'était le programme).
 *
 * [match] peut être appelé de n'importe quel fil pendant que [learn] (plus long : à faire hors du fil du son) prépare
 * une nouvelle table, échangée d'un coup.
 *
 * @param maxPrints nombre d'empreintes gardées au plus (4 octets chacune, 12 de plus dans la table de recherche).
 */
class AdPrintLibrary(private val maxPrints: Int = DEFAULT_MAX_PRINTS) {

    class Chunk(val id: Int, val prints: IntArray, val loud: BooleanArray, var confirmed: Boolean, var lastUsed: Long)

    /** Morceau reconnu : lequel, où, et avec quelle ressemblance (part de bits différents, 0 = identique). */
    class Match(val chunk: Chunk, val offset: Int, val errorRate: Double)

    private class Table(val chunks: List<Chunk>, val keys: LongArray)

    @Volatile
    private var table = Table(emptyList(), LongArray(0))
    private var nextId = 1

    /** Morceaux connus. */
    val size: Int get() = table.chunks.size

    /** Morceaux connus (lecture seule). */
    fun chunks(): List<Chunk> = table.chunks

    /**
     * Ajoute les pubs d'une autre mémoire (bibliothèque fournie avec l'appli) : ce qui n'est pas déjà connu, à l'essai,
     * avec sa date. La bibliothèque fournie est datée de petits nombres (rang de l'enregistrement) : quand la place
     * manque, les pubs apprises par l'appareil (datées en minutes) restent prioritaires, puis les plus récentes fournies.
     * @return nombre d'empreintes nouvelles gardées.
     */
    fun merge(other: AdPrintLibrary): Int =
        other.table.chunks.sumOf { chunk -> learn(chunk.prints, chunk.loud, confirmed = false, now = chunk.lastUsed) }

    /** Durée de pub connue, en secondes. */
    val seconds: Int get() = (table.chunks.sumOf { it.prints.size } / AudioFingerprinter.PER_SECOND).toInt()

    /**
     * Cherche les dernières empreintes entendues ([prints], [loud] : les [count] dernières, la plus récente en
     * dernier) parmi les pubs connues. `null` si rien ne ressemble assez.
     */
    fun match(prints: IntArray, loud: BooleanArray, count: Int = prints.size, from: Int = 0): Match? {
        val table = table
        if (table.keys.isEmpty()) return null
        val votes = HashMap<Long, Int>()
        var heard = 0
        for (j in from until from + count) {
            if (!loud[j]) continue
            heard++
            val print = prints[j]
            val first = firstIndex(table.keys, print)
            var hits = 0
            var i = first
            while (i < table.keys.size && (table.keys[i] ushr 32).toInt() == print) {
                i++
                hits++
            }
            // Empreinte trop courante (son très simple) : elle ne départage rien.
            if (hits > MAX_HITS) continue
            for (k in first until first + hits) {
                val key = table.keys[k]
                val chunk = ((key ushr OFFSET_BITS) and CHUNK_MASK).toInt()
                val offset = (key and OFFSET_MASK).toInt()
                val start = offset - (j - from)
                val candidate = (chunk.toLong() shl 32) or (start.toLong() and 0xFFFFFFFFL)
                votes[candidate] = (votes[candidate] ?: 0) + 1
            }
        }
        if (heard < count * MIN_LOUD_SHARE) return null
        var best: Match? = null
        votes.entries.filter { it.value >= MIN_VOTES }.sortedByDescending { it.value }.take(CANDIDATES).forEach { (candidate, _) ->
            val chunk = table.chunks.getOrNull((candidate ushr 32).toInt()) ?: return@forEach
            val start = candidate.toInt()
            var bits = 0
            var compared = 0
            for (j in 0 until count) {
                val position = start + j
                if (position < 0 || position >= chunk.prints.size) continue
                if (!loud[from + j] || !chunk.loud[position]) continue
                bits += Integer.bitCount(prints[from + j] xor chunk.prints[position])
                compared++
            }
            if (compared < count * MIN_COMPARED_SHARE) return@forEach
            val rate = bits / (32.0 * compared)
            if (rate <= MAX_ERROR_RATE && (best == null || rate < best!!.errorRate)) best = Match(chunk, start, rate)
        }
        return best
    }

    /**
     * Apprend le son d'une page de pubs : ce qui n'est pas déjà connu devient un nouveau morceau (au moins
     * [MIN_CHUNK_SECONDS] secondes d'un seul tenant). Les plus anciens morceaux sont oubliés au-delà de [maxPrints].
     * @param now horloge (minutes, ou ce que l'appelant veut) qui dit quels morceaux ont servi récemment.
     * @return nombre d'empreintes nouvelles gardées.
     */
    @Synchronized
    fun learn(prints: IntArray, loud: BooleanArray, confirmed: Boolean, now: Long): Int {
        val known = BooleanArray(prints.size)
        var position = 0
        while (position + BLOCK <= prints.size) {
            val match = match(prints, loud, BLOCK, position)
            if (match != null) {
                for (j in position until position + BLOCK) known[j] = true
                // Jamais plus ancien : une pub de la bibliothèque fournie (petite date) ne vieillit pas celle de l'appareil.
                match.chunk.lastUsed = maxOf(match.chunk.lastUsed, now)
                if (confirmed) match.chunk.confirmed = true
            }
            position += BLOCK / 4
        }
        val added = ArrayList<Chunk>()
        var start = 0
        while (start < prints.size) {
            if (known[start]) {
                start++
                continue
            }
            var end = start
            while (end < prints.size && !known[end]) end++
            // Un morceau surtout silencieux ne servirait à rien : il n'est pas gardé.
            val audible = (start until end).count { loud[it] }
            if (end - start >= MIN_CHUNK_SECONDS * AudioFingerprinter.PER_SECOND && audible >= (end - start) * MIN_LOUD_SHARE) {
                added += Chunk(nextId++, prints.copyOfRange(start, end), loud.copyOfRange(start, end), confirmed, now)
            }
            start = end
        }
        if (added.isEmpty()) return 0
        rebuild(table.chunks + added)
        return added.sumOf { it.prints.size }
    }

    /** Morceau à l'essai entendu sous le logo de la chaîne : c'était le programme, il est oublié. */
    @Synchronized
    fun forget(chunk: Chunk) {
        if (chunk.confirmed) return
        rebuild(table.chunks.filter { it !== chunk })
    }

    private fun rebuild(all: List<Chunk>) {
        // Les plus anciens (ni appris ni reconnus récemment) partent les premiers ; les morceaux à l'essai avant les sûrs.
        val kept = ArrayList<Chunk>()
        var total = 0
        for (chunk in all.sortedWith(compareByDescending<Chunk> { it.lastUsed }.thenByDescending { it.confirmed })) {
            if (total + chunk.prints.size > maxPrints) continue
            kept += chunk
            total += chunk.prints.size
        }
        kept.sortBy { it.id }
        val keys = LongArray(kept.sumOf { chunk -> chunk.loud.count { it } })
        var n = 0
        kept.forEachIndexed { index, chunk ->
            for (offset in chunk.prints.indices) {
                if (!chunk.loud[offset] || offset > OFFSET_MASK) continue
                keys[n++] = (chunk.prints[offset].toLong() shl 32) or (index.toLong() shl OFFSET_BITS) or offset.toLong()
            }
        }
        val sorted = keys.copyOf(n)
        // Tri sur l'empreinte vue comme un nombre signé : celui de la recherche ([firstIndex]).
        sorted.sort()
        table = Table(kept, sorted)
    }

    /** Pour la sauvegarde : tous les morceaux connus. */
    @Synchronized
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(nextId)
            val chunks = table.chunks
            out.writeInt(chunks.size)
            for (chunk in chunks) {
                out.writeInt(chunk.id)
                out.writeBoolean(chunk.confirmed)
                out.writeLong(chunk.lastUsed)
                out.writeInt(chunk.prints.size)
                for (i in chunk.prints.indices) out.writeInt(chunk.prints[i])
                // Son audible ou non : un bit par empreinte.
                val mask = ByteArray((chunk.loud.size + 7) / 8)
                for (i in chunk.loud.indices) if (chunk.loud[i]) mask[i / 8] = (mask[i / 8].toInt() or (1 shl (i % 8))).toByte()
                out.write(mask)
            }
        }
        return bytes.toByteArray()
    }

    /** Reprend les morceaux d'une sauvegarde ; `false` (rien de changé) si elle n'est pas lisible. */
    @Synchronized
    fun restore(input: InputStream): Boolean = try {
        val data = DataInputStream(input)
        if (data.readInt() != FORMAT) {
            false
        } else {
            val next = data.readInt()
            val count = data.readInt()
            require(count in 0..MAX_CHUNKS)
            val chunks = ArrayList<Chunk>(count)
            repeat(count) {
                val id = data.readInt()
                val confirmed = data.readBoolean()
                val lastUsed = data.readLong()
                val size = data.readInt()
                require(size in 1..OFFSET_MASK.toInt())
                val prints = IntArray(size) { data.readInt() }
                val mask = ByteArray((size + 7) / 8).also { data.readFully(it) }
                val loud = BooleanArray(size) { i -> mask[i / 8].toInt() and (1 shl (i % 8)) != 0 }
                chunks += Chunk(id, prints, loud, confirmed, lastUsed)
            }
            nextId = maxOf(next, (chunks.maxOfOrNull { it.id } ?: 0) + 1)
            rebuild(chunks)
            true
        }
    } catch (e: EOFException) {
        false
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    companion object {
        private const val FORMAT = 1
        const val DEFAULT_MAX_PRINTS = 300_000

        /** Empreintes comparées d'un coup : 2,5 s de son. */
        const val BLOCK = 160

        /** Ressemblance suffisante : au plus cette part de bits différents (un autre son en donne environ la moitié). */
        const val MAX_ERROR_RATE = 0.28

        private const val MIN_VOTES = 2
        private const val CANDIDATES = 8
        private const val MAX_HITS = 24
        private const val MIN_LOUD_SHARE = 0.5
        private const val MIN_COMPARED_SHARE = 0.4
        private const val MIN_CHUNK_SECONDS = 4

        private const val OFFSET_BITS = 20
        private const val OFFSET_MASK = (1L shl OFFSET_BITS) - 1
        private const val CHUNK_MASK = (1L shl 12) - 1
        private const val MAX_CHUNKS = 4_000

        /** Premier indice de [keys] dont l'empreinte vaut au moins [print]. */
        private fun firstIndex(keys: LongArray, print: Int): Int {
            val target = print.toLong() shl 32
            var low = 0
            var high = keys.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (keys[middle] < target) low = middle + 1 else high = middle
            }
            return low
        }
    }
}
