package tv.nextv.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Travaux de fond lourds (relecture de la playlist et du guide : des dizaines de Mo à télécharger, puis à écrire dans
 * la base) tenus à l'écart de la lecture. Faits pendant un film, ils lui prennent du débit et du processeur : sur une
 * petite box, le film s'arrête et reprend toutes les quelques secondes.
 *
 * - Lecteur ouvert ([playing]) : le travail attend sa fermeture, au plus [maxWaitMillis] (une chaîne laissée allumée
 *   toute la journée ne prive pas le guide de sa mise à jour).
 * - Lecture lancée pendant le travail : il est abandonné, puis refait en entier à la fermeture du lecteur ; après
 *   [maxInterruptions] abandons, il va jusqu'au bout.
 */
class BackgroundWork(
    private val playing: StateFlow<Boolean>,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
    private val maxInterruptions: Int = MAX_INTERRUPTIONS,
) {
    suspend fun <T> run(block: suspend () -> T): T {
        var interruptions = 0
        while (true) {
            val quiet = withTimeoutOrNull(maxWaitMillis) { playing.first { !it } } != null
            if (!quiet || interruptions >= maxInterruptions) return block()
            var result: Result<T>? = null
            coroutineScope {
                val work = async { block() }
                val watcher = launch {
                    playing.first { it }
                    work.cancel()
                }
                try {
                    result = Result.success(work.await())
                } catch (e: CancellationException) {
                    // Annulé de l'extérieur (application fermée) : on s'arrête ; par la lecture : nouvel essai plus tard.
                    currentCoroutineContext().ensureActive()
                } finally {
                    watcher.cancel()
                }
            }
            result?.let { return it.getOrThrow() }
            interruptions++
        }
    }

    private companion object {
        /** Un film et sa marge. */
        const val MAX_WAIT_MILLIS = 3 * 60 * 60_000L
        const val MAX_INTERRUPTIONS = 3
    }
}
