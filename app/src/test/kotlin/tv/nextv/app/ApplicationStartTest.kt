package tv.nextv.app

import android.os.Looper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Lancement réel de l'application (sa classe `Application`, son conteneur, ses tâches de fond) puis de son
 * activité : une erreur à ce moment-là fermerait l'application avant même l'accueil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NexTvApplication::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class ApplicationStartTest {

    private val application = RuntimeEnvironment.getApplication() as NexTvApplication

    private fun idle() {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun theApplicationStartsWithItsBackgroundJobs() = runBlocking {
        idle()
        // Conteneur prêt, réglages lisibles, aucun incident noté par un premier lancement.
        assertEquals(ThemeStyle.NIGHT, application.container.settings.themeStyle.first())
        assertTrue(application.container.sourceRepository.sources.first().isEmpty())
        assertNull(application.crashLog.last())
    }

    @Test
    fun theActivityOpensAndLeavesWithoutError() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        // Mise en veille puis retour, puis fermeture : la session notée pour « Dernier incident » suit.
        controller.pause().stop()
        idle()
        controller.start().resume()
        idle()
        controller.pause().stop().destroy()
        idle()
        assertNull(application.crashLog.last())
    }
}
