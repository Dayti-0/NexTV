package tv.nextv.core.io

import java.io.FilterInputStream
import java.io.InputStream

/** Étapes d'un chargement, dans l'ordre où elles s'exécutent. */
enum class LoadStep {
    /** Téléchargement et analyse de la playlist (les deux se font en flux). */
    PLAYLIST,

    /** Enregistrement des chaînes, films et séries. */
    SAVING,

    /** Téléchargement du guide des programmes. */
    EPG_DOWNLOAD,

    /** Lecture du guide et association aux chaînes. */
    EPG_IMPORT,
}

/**
 * Avancement réel d'une étape.
 *
 * @property done quantité traitée (octets ou éléments).
 * @property total quantité totale si elle est connue (taille annoncée par le serveur, taille du fichier…).
 */
data class LoadProgress(val step: LoadStep, val done: Long, val total: Long?) {
    /** Fraction réalisée, ou `null` si le total est inconnu. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
}

fun interface ProgressListener {
    fun onProgress(progress: LoadProgress)
}

/**
 * Compte les octets lus et les signale (au plus toutes les [reportEvery] octets, et en fin de flux).
 */
class CountingInputStream(
    input: InputStream,
    private val reportEvery: Long = 64 * 1024,
    private val onCount: (Long) -> Unit,
) : FilterInputStream(input) {

    private var count = 0L
    private var lastReported = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1) else flush()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong()) else if (n < 0) flush()
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) advance(skipped)
        return skipped
    }

    override fun markSupported() = false

    private fun advance(n: Long) {
        count += n
        if (count - lastReported >= reportEvery) flush()
    }

    private fun flush() {
        if (count != lastReported) {
            lastReported = count
            onCount(count)
        }
    }
}
