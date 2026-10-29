package tv.nextv.core.m3u

import java.io.IOException
import java.io.Reader

/** Ligne plus longue que la limite : le contenu n'est vraisemblablement pas du texte ligne à ligne. */
class LineTooLongException(limit: Int) : IOException("Line longer than $limit characters")

/**
 * Lecture ligne à ligne (`\n`, `\r\n` ou `\r`) avec une longueur maximale, pour ne pas
 * accumuler en mémoire un contenu binaire dépourvu de retours à la ligne.
 */
internal class LineReader(private val reader: Reader, private val maxLineLength: Int) {

    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0
    private var skipNextLineFeed = false
    private val line = StringBuilder()

    fun readLine(): String? {
        line.setLength(0)
        var sawAny = false
        while (true) {
            if (position >= limit) {
                limit = reader.read(buffer, 0, buffer.size)
                position = 0
                if (limit <= 0) {
                    limit = 0
                    return if (sawAny) line.toString() else null
                }
            }
            val c = buffer[position++]
            if (skipNextLineFeed) {
                skipNextLineFeed = false
                if (c == '\n') continue
            }
            sawAny = true
            when (c) {
                '\n' -> return line.toString()
                '\r' -> {
                    skipNextLineFeed = true
                    return line.toString()
                }
                else -> {
                    if (line.length >= maxLineLength) throw LineTooLongException(maxLineLength)
                    line.append(c)
                }
            }
        }
    }
}
