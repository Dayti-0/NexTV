package tv.nextv.core.audio

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Ce que la détection des pubs a appris d'une chaîne, gardé d'une lecture à l'autre : à la prochaine ouverture,
 * le logo est reconnu tout de suite, sans la minute et demie d'apprentissage.
 *
 * @property logo dessin du logo de la chaîne ([LogoWatcher.export]).
 * @property separator carton que la chaîne met entre deux spots ([AdVolumeGuard.separator]).
 * @property logoFollowsBreaks le logo a déjà été vu revenir à la fin d'une page de pubs ([AdVolumeGuard.logoFollowsBreaks]).
 */
class AdMarks(val logo: ByteArray?, val separator: ByteArray?, val logoFollowsBreaks: Boolean) {

    val isEmpty: Boolean get() = logo == null && separator == null && !logoFollowsBreaks

    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(2 + 4 + (logo?.size ?: 0) + 4 + (separator?.size ?: 0))
        out.put(FORMAT).put(if (logoFollowsBreaks) 1 else 0)
        out.putInt(logo?.size ?: 0).put(logo ?: EMPTY)
        out.putInt(separator?.size ?: 0).put(separator ?: EMPTY)
        return out.array()
    }

    /** Mêmes repères (le logo suit de petits changements à chaque lecture : inutile de réécrire pour si peu). */
    fun sameAs(other: AdMarks?): Boolean =
        other != null && logoFollowsBreaks == other.logoFollowsBreaks && (logo == null) == (other.logo == null) &&
            (logo?.size ?: 0) == (other.logo?.size ?: 0) && separator.contentEquals(other.separator)

    companion object {
        private const val FORMAT: Byte = 1
        private const val MAX_BYTES = 64 * 1024
        private val EMPTY = ByteArray(0)

        /** Repères relus ; `null` si les données ne sont pas lisibles (autre version, fichier abîmé). */
        fun decode(bytes: ByteArray): AdMarks? = try {
            val input = ByteBuffer.wrap(bytes)
            if (input.get() != FORMAT) {
                null
            } else {
                val follows = input.get().toInt() == 1
                val logo = input.block()
                val separator = input.block()
                AdMarks(logo, separator, follows)
            }
        } catch (e: BufferUnderflowException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun ByteBuffer.block(): ByteArray? {
            val size = int
            require(size in 0..MAX_BYTES)
            if (size == 0) return null
            return ByteArray(size).also { get(it) }
        }
    }
}
