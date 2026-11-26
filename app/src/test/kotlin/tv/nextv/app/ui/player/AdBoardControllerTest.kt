package tv.nextv.app.ui.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer

/** Écran pendant les pubs : ouverture, fermeture, fin prévue, et durée habituelle des pubs de la chaîne. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdBoardControllerTest {

    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    // Les relectures (guide, matchs, météo) ne tournent pas ici : seule la logique de l'écran est vérifiée.
    private val scope = CoroutineScope(StandardTestDispatcher())
    private var now = 1_795_000_000_000L
    private val programme = PlayerProgramme("Zone interdite", now - 30 * 60_000L, now + 60 * 60_000L)
    private val state = MutableStateFlow(PlayerUiState(channelName = "M6", now = programme))
    private val controller = AdBoardController(
        scope = scope,
        container = container,
        state = state,
        programmes = { emptyList() },
        clock = { now },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun adBreak(seconds: Int, channel: String = "M6", version: Int = 1) {
        controller.update(open = true, channelName = channel, channelVersion = version, music = null, cueRemainingMillis = null)
        now += seconds * 1_000L
        controller.update(open = false, channelName = channel, channelVersion = version, music = null, cueRemainingMillis = null)
    }

    @Test
    fun `opens with the programme and closes with the ad`() {
        controller.update(open = true, channelName = "M6", channelVersion = 1, music = AdBoardController.Music("FIP Groove"), cueRemainingMillis = null)
        val board = state.value.adBoard
        assertNotNull(board)
        assertEquals("Zone interdite", board!!.programme?.title)
        assertEquals(now, board.sinceMillis)
        assertEquals("FIP Groove", board.radio?.name)
        // Durée habituelle encore inconnue : pas de fin prévue.
        assertNull(board.endMillis)
        now += 150_000
        controller.update(open = false, channelName = "M6", channelVersion = 1, music = null, cueRemainingMillis = null)
        assertNull(state.value.adBoard)
    }

    @Test
    fun `usual length of the channel's breaks gives the planned end`() {
        adBreak(180)
        adBreak(240)
        adBreak(200)
        controller.update(open = true, channelName = "M6", channelVersion = 1, music = null, cueRemainingMillis = null)
        val board = state.value.adBoard!!
        assertEquals(board.sinceMillis + 200_000, board.endMillis)
        assertFalse(board.announced)
    }

    @Test
    fun `length announced by the stream wins`() {
        controller.update(open = true, channelName = "RMC Story", channelVersion = 1, music = null, cueRemainingMillis = 95_000)
        val board = state.value.adBoard!!
        assertEquals(now + 95_000, board.endMillis)
        assertTrue(board.announced)
    }

    @Test
    fun `not an ad, or another channel, is not counted`() {
        adBreak(180)
        adBreak(180)
        // « Ce n'est pas une pub ».
        controller.update(open = true, channelName = "M6", channelVersion = 1, music = null, cueRemainingMillis = null)
        now += 400_000
        controller.dismiss()
        assertNull(state.value.adBoard)
        controller.update(open = false, channelName = "M6", channelVersion = 1, music = null, cueRemainingMillis = null)
        // Chaîne changée pendant la pub : la page d'avant n'est pas finie.
        controller.update(open = true, channelName = "M6", channelVersion = 1, music = null, cueRemainingMillis = null)
        now += 300_000
        controller.update(open = true, channelName = "W9", channelVersion = 2, music = null, cueRemainingMillis = null)
        assertEquals(now, state.value.adBoard?.sinceMillis)
        assertNull(container.adBreakDurations.typical("M6"))
    }
}
