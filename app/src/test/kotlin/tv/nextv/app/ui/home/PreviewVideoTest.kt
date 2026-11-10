package tv.nextv.app.ui.home

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Aperçu d'une chaîne dont le son joue sans image (écran de l'aperçu changé pendant le démarrage du flux) :
 * il repart au direct au bout de quelques secondes, au plus deux fois ; jamais quand l'image est là.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreviewVideoTest {

    @get:Rule
    val compose = createComposeRule()

    /** Lecteur qui joue une chaîne avec vidéo, sans jamais rendre d'image ; compte les retours au direct. */
    private class SilentVideoPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var seeks = 0
        private val tracks = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(ImmutableList.of(MediaItemData.Builder("chaine").setTracks(tracks).setIsDynamic(true).build()))
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks++
            return Futures.immediateVoidFuture()
        }

        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Test
    fun soundWithoutImageRestartsAtTheLiveEdge() {
        val player = SilentVideoPlayer()
        compose.setContent { PreviewVideo(player, Modifier.size(320.dp, 180.dp)) }
        compose.waitForIdle()
        // Aperçu qui jouait déjà : retour au direct en arrivant sur ce nouvel écran (déjà fait avant).
        val start = player.seeks
        // Une seconde sans image : on attend encore.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(start, player.seeks)
        // Plus de deux secondes : retour au direct.
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
        assertEquals(start + 1, player.seeks)
        // Toujours rien : une seconde reprise, puis plus aucune.
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertEquals(start + 2, player.seeks)
    }
}
