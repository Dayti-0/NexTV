package tv.nextv.app.ui.player

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput

/**
 * Écran du lecteur, de son ouverture à sa fermeture : il doit s'ouvrir sur un film comme sur une chaîne (une
 * variable utilisée avant d'être prête faisait planter l'application à chaque ouverture), puis traverser la mise
 * en veille et le retour sans erreur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerViewModelTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val container = AppContainer(application)

    private fun idle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** Playlist d'une chaîne et d'un film ; rend leurs identifiants (chaîne, film). */
    private fun playlist(): Pair<Long, Long> = runBlocking {
        val sourceId = container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u")))
        container.playlistStore.replaceChannels(
            sourceId,
            listOf(
                Channel(position = 0, name = "TF1", stream = StreamDescriptor("http://127.0.0.1:9/live/1.ts"), kind = ContentKind.LIVE, title = "TF1"),
                Channel(position = 1, name = "Le Film (2020)", stream = StreamDescriptor("http://127.0.0.1:9/movie/2.mkv"), kind = ContentKind.MOVIE, title = "Le Film", year = 2020),
            ),
        )
        val live = checkNotNull(container.channelDao.primaryIdByKey(sourceId, "live:tf1"))
        val movie = container.channelDao.moviesByKey(sourceId, listOf("movie:le film:2020")).single().id
        live to movie
    }

    private fun open(channelId: Long): Pair<PlayerViewModel, ViewModelStore> {
        val viewModel = PlayerViewModel(container, SavedStateHandle(mapOf(PlayerViewModel.ARG_CHANNEL_ID to channelId)), application)
        // Fermeture de l'écran : comme le fait la navigation.
        val store = ViewModelStore().apply { put("player", viewModel) }
        return viewModel to store
    }

    @Test
    fun opensAMovieAndClosesIt() {
        val (_, movie) = playlist()
        val (viewModel, store) = open(movie)
        viewModel.onStart()
        idle()
        assertEquals("Le Film", viewModel.state.value.channelName)
        assertTrue(!viewModel.state.value.isLive)
        // Mise en veille puis retour : un film est mis en pause et reprend.
        viewModel.onStop()
        idle()
        viewModel.onStart()
        idle()
        store.clear()
    }

    @Test
    fun opensAChannelAndReopensItAfterStandby() {
        val (live, _) = playlist()
        val (viewModel, store) = open(live)
        viewModel.onStart()
        idle()
        assertEquals("TF1", viewModel.state.value.channelName)
        assertTrue(viewModel.state.value.isLive)
        assertNotNull(viewModel.player.value)
        // Mise en veille : le flux du direct est fermé (connexion et décodeur rendus)…
        viewModel.onStop()
        idle()
        assertNull(viewModel.player.value)
        // … et la chaîne est rouverte au retour.
        viewModel.onStart()
        idle()
        assertNotNull(viewModel.player.value)
        assertEquals("TF1", viewModel.state.value.channelName)
        store.clear()
    }

    @Test
    fun theRemoteKeysOfALiveChannelNeverCrash() {
        val (live, _) = playlist()
        val (viewModel, store) = open(live)
        viewModel.onStart()
        idle()
        // Pause (différé), reprise, retour en arrière, retour au direct, zapping, menu, nouvel essai : le flux
        // de test ne répond pas, chaque commande doit le supporter.
        viewModel.togglePause()
        idle()
        viewModel.togglePause()
        idle()
        viewModel.rewindLive(10)
        idle()
        viewModel.backToLive()
        idle()
        viewModel.zap(1)
        viewModel.zap(-1)
        viewModel.previousChannel()
        idle()
        viewModel.openMenu()
        viewModel.closeMenu()
        viewModel.retry()
        idle()
        viewModel.onStop()
        viewModel.onStart()
        idle()
        assertEquals("TF1", viewModel.state.value.channelName)
        store.clear()
    }

    @Test
    fun theRemoteKeysOfAMovieNeverCrash() {
        val (_, movie) = playlist()
        val (viewModel, store) = open(movie)
        viewModel.onStart()
        idle()
        viewModel.togglePause()
        viewModel.seekBy(10_000)
        viewModel.seekTo(60_000)
        viewModel.fastSeek(1)
        viewModel.stopFastSeek()
        viewModel.jump(1)
        viewModel.togglePause()
        viewModel.retry()
        idle()
        viewModel.onStop()
        idle()
        store.clear()
    }

    @Test
    fun aMissingChannelShowsAMessage() {
        val (viewModel, store) = open(987_654L)
        viewModel.onStart()
        idle()
        assertNotNull(viewModel.state.value.error)
        store.clear()
    }
}
