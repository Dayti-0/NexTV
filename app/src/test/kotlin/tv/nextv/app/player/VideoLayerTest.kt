package tv.nextv.app.player

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer

/**
 * Couche vidéo d'un direct : donnée une seule fois au décodeur, puis placée sous la zone vidéo de l'écran qui montre
 * le lecteur (aperçu, plein écran). Changer d'écran ne change jamais la destination du décodeur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoLayerTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())

    @Test
    fun aLiveStreamThatCanChangeScreenDrawsInItsOwnLayer() {
        val layered = container.playerFactory.create(preview = true, layered = true)
        val plain = container.playerFactory.create()
        try {
            assertTrue(VideoLayers.has(layered))
            assertNotNull(VideoLayers.of(layered))
            assertFalse(VideoLayers.has(plain))
            assertNull(VideoLayers.of(plain))
        } finally {
            layered.release()
            plain.release()
        }
        // Lecteur rendu : sa couche aussi.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(VideoLayers.has(layered))
    }

    @Test
    fun theDecoderKeepsItsLayerWhenTheScreenChanges() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val player = container.playerFactory.create(preview = true, layered = true)
        val preview = VideoViews.create(activity, hideWithoutImage = true)
        val full = VideoViews.create(activity)
        root.addView(preview, ViewGroup.LayoutParams(320, 180))
        root.addView(full, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val outputs = mutableListOf<Any?>()
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                outputs += output
            }
        })
        try {
            // Aperçu, puis plein écran (OK), puis retour à l'accueil : le même lecteur dans trois vues.
            VideoViews.bind(preview, player)
            assertSame(player, preview.player)
            VideoViews.bind(full, player)
            assertSame(player, full.player)
            // Le plein écran rend le direct à l'accueil : son image reste là jusqu'à ce que l'aperçu la prenne.
            VideoViews.bind(full, null)
            assertSame(player, VideoViews.playerOf(full))
            VideoViews.bind(preview, null)
            VideoViews.bind(preview, player)
            // Aucune vue n'a donné sa propre zone vidéo au décodeur (la couche reste sa seule destination).
            assertNull(full.videoSurfaceView)
            assertNull(preview.videoSurfaceView)
            assertEquals(VideoLayers.of(player)?.surface, VideoViews.frameSurface(preview))
            VideoViews.release(full)
            VideoViews.release(preview)
            assertNull(preview.player)
        } finally {
            player.release()
        }
    }

    @Test
    fun aFilmDrawsDirectlyInTheScreen() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = VideoViews.create(activity)
        activity.setContentView(view)
        val film = container.playerFactory.create(film = true)
        try {
            VideoViews.bind(view, film)
            assertSame(film, view.player)
            assertFalse(VideoLayers.has(film))
            // Pas de couche : la zone vidéo de la vue est la destination de son décodeur, comme avant.
            VideoViews.bind(view, null)
            assertNull(view.player)
            assertNull(VideoViews.playerOf(view))
        } finally {
            film.release()
        }
    }

}
