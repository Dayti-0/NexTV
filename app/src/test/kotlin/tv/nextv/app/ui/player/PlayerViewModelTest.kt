package tv.nextv.app.ui.player

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.app.player.ChannelPreviewPlayer
import tv.nextv.app.ui.home.Category
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
    private fun playlist(liveUrl: String = "http://127.0.0.1:9/live/1.ts"): Pair<Long, Long> = runBlocking {
        val sourceId = container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u")))
        container.playlistStore.replaceChannels(
            sourceId,
            listOf(
                Channel(position = 0, name = "TF1", stream = StreamDescriptor(liveUrl), kind = ContentKind.LIVE, title = "TF1"),
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
        assertTrue(!container.playbackOpen.value)
        val (viewModel, store) = open(movie)
        viewModel.onStart()
        idle()
        assertEquals("Le Film", viewModel.state.value.channelName)
        assertTrue(!viewModel.state.value.isLive)
        // Lecteur ouvert : les actualisations automatiques de la playlist et du guide attendent sa fermeture.
        assertTrue(container.playbackOpen.value)
        // Mise en veille puis retour : un film est mis en pause et reprend.
        viewModel.onStop()
        idle()
        viewModel.onStart()
        idle()
        store.clear()
        idle()
        assertTrue(!container.playbackOpen.value)
    }

    @Test
    fun aChannelOpenedFromItsPreviewKeepsThePreviewStream() {
        // Serveur qui accepte la connexion sans répondre : le flux reste « en chargement », sans erreur.
        java.net.ServerSocket(0).use { server ->
            val (live, _) = playlist("http://127.0.0.1:${server.localPort}/live/1.ts")
            val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)
            previewer.volume = 0.2f
            runBlocking { previewer.show(checkNotNull(container.channelDao.get(live))) }
            val shown = checkNotNull(previewer.player.value)
            // Une autre chaîne que celle de l'aperçu : rien n'est confié.
            assertNull(previewer.handOff(live + 1000))
            // OK sur la chaîne de l'aperçu : son lecteur est confié au plein écran, l'aperçu ne l'a plus.
            val handoff = checkNotNull(previewer.handOff(live))
            assertTrue(handoff.buffer.preview)
            container.previewHandoff.offer(handoff)
            assertNull(previewer.player.value)
            previewer.release()

            val (viewModel, store) = open(live)
            // Montré dès l'ouverture de l'écran, avant le chargement de la chaîne : pas d'image noire entre les deux.
            assertSame(shown, viewModel.player.value)
            assertNull(container.previewHandoff.waiting.value)
            viewModel.onStart()
            idle()
            // Le plein écran continue sur le même lecteur (même connexion), au son normal.
            assertSame(shown, viewModel.player.value)
            assertEquals(1f, shown.volume, 0.001f)
            assertEquals(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE, shown.audioAttributes.contentType)
            assertEquals("TF1", viewModel.state.value.channelName)
            // Mémoire tampon et reprises du plein écran (celles de l'aperçu faisaient croire à une connexion lente).
            assertTrue(!handoff.buffer.preview)
            assertTrue(!handoff.taps.preview)
            assertNull(container.previewHandoff.take())
            // Mise en veille puis retour : le flux est rouvert sur un lecteur du plein écran.
            viewModel.onStop()
            idle()
            viewModel.onStart()
            idle()
            assertNotNull(viewModel.player.value)
            assertNotSame(shown, viewModel.player.value)
            store.clear()
        }
    }

    @Test
    fun aPreviewOfAnotherStreamIsClosedBeforePlaying() {
        java.net.ServerSocket(0).use { server ->
            val (live, _) = playlist()
            val other = tv.nextv.app.player.PlaybackRequest(StreamDescriptor("http://127.0.0.1:${server.localPort}/other.ts"), null, null, null)
            val buffer = container.playerFactory.liveLoadControl(preview = true)
            val player = container.playerFactory.create(preview = true, liveBuffer = buffer)
            container.previewHandoff.offer(tv.nextv.app.player.PreviewHandoff(player, other, tv.nextv.app.player.StreamTaps(), buffer, setOf(live), toPreview = false))
            val (viewModel, store) = open(live)
            viewModel.onStart()
            idle()
            assertNotNull(viewModel.player.value)
            assertNotSame(player, viewModel.player.value)
            assertNull(container.previewHandoff.take())
            store.clear()
        }
    }

    @Test
    fun backToHomeTheChannelStreamGoesToThePreview() {
        java.net.ServerSocket(0).use { server ->
            val (live, _) = playlist("http://127.0.0.1:${server.localPort}/live/1.ts")
            val (viewModel, store) = open(live)
            viewModel.onStart()
            idle()
            val playing = checkNotNull(viewModel.player.value)
            // Retour à l'accueil : le lecteur en marche est confié à l'aperçu de la chaîne, réglé pour lui.
            viewModel.keepStreamForHome()
            val handoff = checkNotNull(container.previewHandoff.waiting.value)
            assertSame(playing, handoff.player)
            assertTrue(handoff.toPreview)
            assertTrue(live in handoff.channelIds)
            assertTrue(handoff.buffer.preview)
            assertTrue(handoff.taps.preview)
            assertNull(handoff.taps.listener)
            assertNull(handoff.taps.recording)
            assertNull(viewModel.player.value)
            // Fermeture de l'écran : ce lecteur n'est pas libéré, l'aperçu le reprend.
            viewModel.onStop()
            store.clear()
            idle()
            val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)
            previewer.adopt(checkNotNull(container.previewHandoff.takeFor(live, toPreview = true)), live)
            assertSame(playing, previewer.player.value)
            assertEquals(androidx.media3.common.Player.STATE_BUFFERING, playing.playbackState)
            previewer.release()
        }
    }

    @Test
    fun aMovieIsNotHandedToThePreview() {
        val (_, movie) = playlist()
        val (viewModel, store) = open(movie)
        viewModel.onStart()
        idle()
        // Film : rien n'est confié à l'aperçu (seul un direct peut continuer dans l'accueil).
        viewModel.keepStreamForHome()
        assertNull(container.previewHandoff.waiting.value)
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

    /**
     * Chaîne ouverte depuis les favoris : haut/bas zappe dans les favoris (dans leur ordre, en boucle) et « Retour »
     * ouvre le menu sur les favoris ; ouverte d'ailleurs, le menu reste sur sa catégorie.
     */
    @Test
    fun aChannelOpenedFromTheFavoritesStaysInTheFavorites() {
        val sourceId = runBlocking {
            val id = container.sourceRepository.add(SourceInput(name = "Favoris", location = SourceLocation.Remote("http://127.0.0.1:9/fav.m3u")))
            container.playlistStore.replaceChannels(
                id,
                listOf("TF1" to "TNT", "France 2" to "TNT", "M6" to "TNT", "Arte" to "TNT").mapIndexed { index, (name, group) ->
                    Channel(position = index, name = name, stream = StreamDescriptor("http://127.0.0.1:9/live/$index.ts"), groupTitle = group, title = name)
                },
            )
            // Favoris dans l'ordre choisi : Arte, puis TF1 (France 2 et M6 n'en sont pas).
            listOf("live:arte", "live:tf1").forEach { key -> container.favorites.toggle(id, ContentKind.LIVE, key, key, null) }
            id
        }
        val tf1 = runBlocking { checkNotNull(container.channelDao.primaryIdByKey(sourceId, "live:tf1")) }
        val viewModel = PlayerViewModel(
            container,
            SavedStateHandle(mapOf(PlayerViewModel.ARG_CHANNEL_ID to tf1, PlayerViewModel.ARG_FAVORITES to true)),
            application,
        )
        val store = ViewModelStore().apply { put("player", viewModel) }
        viewModel.onStart()
        idle()
        assertEquals("TF1", viewModel.state.value.channelName)
        // Bas depuis TF1 (dernière favorite) : retour à Arte, pas France 2.
        viewModel.zap(1)
        idle()
        assertEquals("Arte", viewModel.state.value.channelName)
        viewModel.zap(-1)
        idle()
        assertEquals("TF1", viewModel.state.value.channelName)
        viewModel.openMenu()
        idle()
        assertEquals(Category.Favorites, viewModel.menu.value.category)
        assertEquals(1, viewModel.menu.value.initialIndex)
        // Chaîne choisie dans une autre catégorie du menu : zapping dans toute la playlist.
        viewModel.selectMenuCategory(Category.Group("TNT"))
        viewModel.playFromMenu(runBlocking { checkNotNull(container.channelDao.primaryIdByKey(sourceId, "live:france 2")) })
        idle()
        assertEquals("France 2", viewModel.state.value.channelName)
        viewModel.zap(1)
        idle()
        assertEquals("M6", viewModel.state.value.channelName)
        viewModel.openMenu()
        idle()
        assertEquals(Category.Group("TNT"), viewModel.menu.value.category)
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
    fun aMovieResumesEvenWhenItsRowNumberChanged() {
        val (_, movie) = playlist()
        runBlocking {
            val entity = checkNotNull(container.channelDao.get(movie))
            // Reprise enregistrée avant une actualisation de la playlist : elle vise un numéro de ligne qui n'existe plus.
            container.progress.save(
                tv.nextv.app.data.progress.ProgressEntity(entity.sourceId, entity.itemKey, entity.kind, 424_242L, "Le Film", null, null, 600_000, 3_600_000, 1),
            )
        }
        val (viewModel, store) = open(movie)
        viewModel.onStart()
        idle()
        // La question « reprendre / recommencer » est posée pour ce film…
        assertNotNull(viewModel.state.value.resumePrompt)
        viewModel.answerResume(true)
        idle()
        // … et « Reprendre » lit ce film, au lieu d'ouvrir un titre introuvable.
        assertNull(viewModel.state.value.resumePrompt)
        assertEquals("Le Film", viewModel.state.value.channelName)
        assertTrue(viewModel.state.value.error != application.getString(tv.nextv.app.R.string.player_channel_missing))
        store.clear()
    }

    @Test
    fun aSeriesResumesItsEpisodeEvenWhenItsRowNumberChanged() {
        val (first, second) = runBlocking {
            val sourceId = container.sourceRepository.add(SourceInput(name = "Séries", location = SourceLocation.Remote("http://127.0.0.1:9/series.m3u")))
            container.playlistStore.replaceChannels(
                sourceId,
                listOf(1, 2).map { number ->
                    Channel(
                        position = number,
                        name = "Lupin S01 E0$number",
                        stream = StreamDescriptor("http://127.0.0.1:9/series/$number.mkv"),
                        kind = ContentKind.SERIES,
                        title = "Lupin",
                        episode = tv.nextv.core.model.EpisodeInfo("Lupin", 1, number),
                    )
                },
            )
            val one = checkNotNull(container.channelDao.episodeIdByKey(sourceId, "series:lupin", 1, 1))
            val two = checkNotNull(container.channelDao.episodeIdByKey(sourceId, "series:lupin", 1, 2))
            // Épisode 2 en cours, noté sous un numéro de ligne qui n'existe plus (playlist actualisée depuis).
            container.progress.save(
                tv.nextv.app.data.progress.ProgressEntity(sourceId, "series:lupin", ContentKind.SERIES.name, 424_242L, "Lupin", "S01E02", null, 600_000, 3_000_000, 1),
            )
            one to two
        }
        // La série est ouverte par son premier épisode : l'épisode en cours est retrouvé par sa saison et son numéro.
        val (viewModel, store) = open(first)
        viewModel.onStart()
        idle()
        val prompt = checkNotNull(viewModel.state.value.resumePrompt)
        assertEquals("S01E02", prompt.resumeLabel)
        viewModel.answerResume(true)
        idle()
        assertEquals(second, runBlocking { container.progress.get(checkNotNull(container.channelDao.get(second)).sourceId, "series:lupin") }?.channelId)
        assertTrue(viewModel.state.value.error != application.getString(tv.nextv.app.R.string.player_channel_missing))
        assertTrue(viewModel.state.value.vod?.subtitle.orEmpty().startsWith("S01E02"))
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
