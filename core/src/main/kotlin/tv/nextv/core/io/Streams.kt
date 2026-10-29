package tv.nextv.core.io

import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/** Interrompt la lecture au-delà de [maxBytes] (fichier démesuré, flux sans fin, bombe gzip). */
class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {

    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
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
        if (count > maxBytes) throw SourceException(SourceError.TooLarge(maxBytes))
    }
}

object Streams {

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Décompresse le flux s'il commence par la signature gzip (`1F 8B`), quel que soit
     * le nom du fichier ou le type MIME annoncé (`.xml.gz` servi en `application/octet-stream`…).
     */
    fun decompressIfGzip(input: InputStream): InputStream {
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input, BUFFER_SIZE)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, BUFFER_SIZE), BUFFER_SIZE) else buffered
    }

    /** Lit jusqu'à [size] octets sans les consommer. */
    fun peek(input: BufferedInputStream, size: Int): ByteArray {
        input.mark(size)
        val buffer = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = input.read(buffer, total, size - total)
            if (n < 0) break
            total += n
        }
        input.reset()
        return buffer.copyOf(total)
    }

    fun buffered(input: InputStream): BufferedInputStream =
        input as? BufferedInputStream ?: BufferedInputStream(input, BUFFER_SIZE)
}

/** Jeu de caractères déduit d'une marque d'ordre des octets, avec le nombre d'octets à ignorer. */
data class DetectedCharset(val charset: Charset, val bomLength: Int)

object TextCharsets {
    /**
     * Détecte le jeu de caractères d'un texte : BOM, puis paramètre `charset` du type MIME,
     * puis UTF-8 par défaut (les playlists M3U8 sont en UTF-8 ; les caractères invalides
     * sont remplacés plutôt que de faire échouer la lecture).
     */
    fun detect(head: ByteArray, contentType: String?): DetectedCharset {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> DetectedCharset(Charsets.UTF_8, 3)
            at(0) == 0xFF && at(1) == 0xFE -> DetectedCharset(Charsets.UTF_16LE, 2)
            at(0) == 0xFE && at(1) == 0xFF -> DetectedCharset(Charsets.UTF_16BE, 2)
            else -> DetectedCharset(charsetFromContentType(contentType) ?: Charsets.UTF_8, 0)
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset? {
        val name = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', '\'', ' ')
            ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }
}
