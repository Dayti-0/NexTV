package tv.nextv.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fils de lecture de la playlist et des guides : deux au plus, un peu moins prioritaires que l'affichage. Sur la box
 * (Raspberry Pi 5), une actualisation du guide en arrière-plan prenait près de la moitié du processeur de l'appli, et
 * changer de section en Streaming faisait attendre certaines images 1 à 3 s (mesuré le 25/11/2026). Priorité 5 et
 * pas « arrière-plan » (10) : sur les anciennes versions d'Android (Fire TV), celle-ci range le fil dans un groupe
 * limité à quelques pour cent du processeur, et le premier chargement de la playlist n'en finirait plus.
 */
object LoadingThreads {
    private val count = AtomicInteger()

    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(THREADS) { work ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(PRIORITY) }
            work.run()
        }, "nixtv-chargement-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private const val THREADS = 2
    private const val PRIORITY = 5
}
