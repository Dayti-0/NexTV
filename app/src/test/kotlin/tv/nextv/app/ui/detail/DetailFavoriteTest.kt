package tv.nextv.app.ui.detail

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

/** Bouton « Ajouter aux favoris » de la fiche : le film devient favori, et le bouton le montre. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DetailFavoriteTest {

    private fun idle(until: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun favoriteButtonAddsAndRemovesTheMovie() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val (sourceId, movieId) = runBlocking {
            val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
            container.playlistStore.replaceChannels(
                id,
                listOf(
                    Channel(
                        position = 0, name = "À la dérive (2024)", stream = StreamDescriptor("http://example.com/movie/1.mkv"),
                        groupTitle = "Films", kind = ContentKind.MOVIE, title = "À la dérive", year = 2024,
                    ),
                ),
            ) {}
            id to container.channelDao.moviesByKey(id, listOf("movie:a la derive:2024")).single().id
        }
        val viewModel = DetailViewModel(container, movieId, ContentKind.MOVIE)
        idle { viewModel.state.value.item != null }
        viewModel.toggleFavorite()
        idle { viewModel.state.value.isFavorite }
        assertTrue(viewModel.state.value.isFavorite)
        assertEquals(listOf("movie:a la derive:2024"), runBlocking { container.favorites.observeKeys(sourceId).first() })
        viewModel.toggleFavorite()
        idle { !viewModel.state.value.isFavorite }
        assertEquals(false, viewModel.state.value.isFavorite)
    }
}
