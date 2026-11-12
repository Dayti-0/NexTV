package tv.nextv.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relecture de la playlist et du guide en arrière-plan : jamais pendant une lecture (un film regardé en même temps
 * s'arrêtait et reprenait toutes les quelques secondes).
 */
class BackgroundWorkTest {

    private val playing = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun workWaitsForTheEndOfPlayback() {
        playing.value = true
        var runs = 0
        val work = scope.async { BackgroundWork(playing).run { ++runs } }
        Thread.sleep(200)
        assertEquals("Lancé pendant la lecture", 0, runs)
        assertFalse(work.isCompleted)

        playing.value = false
        assertEquals(1, wait { work.await() })
    }

    @Test
    fun workInterruptedByPlaybackIsDoneAgainAfterwards() {
        val started = CompletableDeferred<Unit>()
        var runs = 0
        var cancelled = 0
        val work = scope.async {
            BackgroundWork(playing).run {
                runs++
                if (runs == 1) {
                    started.complete(Unit)
                    // Long téléchargement, abandonné quand le film démarre.
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                    }
                }
                "fait"
            }
        }
        wait { started.await() }
        playing.value = true
        Thread.sleep(200)
        assertEquals("Le travail en cours n'a pas été abandonné", 1, cancelled)
        assertFalse("Refait pendant la lecture", work.isCompleted)

        playing.value = false
        assertEquals("fait", wait { work.await() })
        assertEquals(2, runs)
    }

    @Test
    fun workIsNotPutOffForever() {
        // Chaîne laissée allumée : passé le délai, le guide est mis à jour quand même.
        playing.value = true
        assertEquals("fait", wait { BackgroundWork(playing, maxWaitMillis = 100).run { "fait" } })

        // Lectures lancées coup sur coup : après quelques abandons, le travail va jusqu'au bout.
        playing.value = false
        var runs = 0
        val result = wait {
            BackgroundWork(playing, maxInterruptions = 2).run {
                runs++
                if (runs <= 2) {
                    playing.value = true
                    scope.async { delay(50); playing.value = false }
                    awaitCancellation()
                }
                // Troisième essai : la lecture qui démarre ne l'interrompt plus.
                playing.value = true
                delay(100)
                runs
            }
        }
        assertEquals(3, result)
        assertTrue(playing.value)
    }
}
