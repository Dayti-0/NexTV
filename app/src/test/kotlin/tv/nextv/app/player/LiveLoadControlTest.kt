package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.core.net.HttpClientProvider

/**
 * Mémoire tampon d'un direct qui passe de l'aperçu au plein écran : en aperçu, le flux est lu en continu jusqu'à
 * quelques secondes d'avance (sans pause de lecture de plusieurs secondes) ; en plein écran, comme un direct ouvert
 * normalement.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveLoadControlTest {

    private val factory = PlayerFactory(RuntimeEnvironment.getApplication(), HttpClientProvider())
    private val playerId = PlayerId.UNSET

    private fun parameters(bufferedSeconds: Double, rebuffering: Boolean = false) = LoadControl.Parameters(
        playerId,
        Timeline.EMPTY,
        LoadControl.EMPTY_MEDIA_PERIOD_ID,
        /* playbackPositionUs = */ 0,
        (bufferedSeconds * 1_000_000).toLong(),
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ true,
        rebuffering,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
    )

    private fun prepared(preview: Boolean): LiveLoadControl = factory.liveLoadControl(preview).apply {
        onPrepared(playerId)
        onTracksSelected(parameters(0.0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
    }

    @Test
    fun thePreviewReadsContinuouslyUpToAFewSeconds() {
        val control = prepared(preview = true)
        assertTrue(control.shouldContinueLoading(parameters(2.0)))
        // Limite atteinte : la lecture du flux s'arrête…
        assertFalse(control.shouldContinueLoading(parameters(8.0)))
        // … et reprend aussitôt en dessous (l'ancienne attendait de redescendre à 4 s).
        assertTrue(control.shouldContinueLoading(parameters(7.5)))
        control.onReleased(playerId)
    }

    @Test
    fun theFullScreenTakesTheBiggerBufferWithoutANewPlayer() {
        val control = prepared(preview = true)
        assertFalse(control.shouldContinueLoading(parameters(9.0)))
        // Repris par le plein écran : il continue de charger d'avance, comme un direct ouvert en plein écran.
        control.preview = false
        assertTrue(control.shouldContinueLoading(parameters(9.0)))
        assertTrue(control.shouldContinueLoading(parameters(20.0)))
        // Démarrage et reprise après une coupure : les mêmes dans les deux cas.
        assertFalse(control.shouldStartPlayback(parameters(1.0)))
        assertTrue(control.shouldStartPlayback(parameters(1.6)))
        assertFalse(control.shouldStartPlayback(parameters(2.0, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(3.1, rebuffering = true)))
        control.onReleased(playerId)
    }

    @Test
    fun aPreviewStreamFallingAgainAndAgainStopsRetrying() {
        val taps = StreamTaps().apply { preview = true }
        // Deux reprises par minute au plus : la troisième efface l'aperçu au lieu de rejouer le début du flux.
        assertTrue(taps.allowPreviewRetry(now = 1_000))
        assertTrue(taps.allowPreviewRetry(now = 2_000))
        assertFalse(taps.allowPreviewRetry(now = 3_000))
        // Une minute plus tard, de nouveau permis.
        assertTrue(taps.allowPreviewRetry(now = 63_000))
    }
}
