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

    /**
     * Attend (au plus quelques secondes réelles) que le lecteur passe à une autre chaîne que [before] : le zapping et
     * l'ouverture de la chaîne lisent la base hors du fil principal (plus lents sur la CI qu'un temps d'attente fixe).
     */
    private fun awaitOtherChannel(viewModel: PlayerViewModel, before: String?) {
        val deadline = System.nanoTime() + 8_000_000_000L
        do idle() while (viewModel.state.value.channelName == before && System.nanoTime() < deadline)
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
            container.previewHandoff.offer(
                tv.nextv.app.player.PreviewHandoff(player, other, tv.nextv.app.player.StreamTaps(), buffer, tv.nextv.app.player.AdVolumeControl(), setOf(live), toPreview = false),
            )
            val (viewModel, store) = open(live)
            viewModel.onStart()
            idle()
            assertNotNull(viewModel.player.value)
            assertNotSame(player, viewModel.player.value)
            assertNull(container.previewHandoff.take())
            store.clear()
        }
    }

    /**
     * Chaîne en plusieurs versions, qualité visée HD, « Baisser le son des pubs » activé : l'aperçu joue la version
     * que le plein écran ouvrira (HD), et lui confie son lecteur. Avant, il jouait la meilleure version (FHD) et
     * n'avait rien à confier avec ce réglage : le plein écran rouvrait le flux (« Connexion lente »).
     */
    @Test
    fun aChannelInSeveralVersionsIsPreviewedInTheVersionTheFullScreenOpens() {
        java.net.ServerSocket(0).use { server ->
            runBlocking {
                container.settings.setQualityTarget(tv.nextv.core.stream.QualityTarget.HD)
                container.settings.setLowerAdVolume(true)
            }
            val sourceId = runBlocking { container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u"))) }
            fun live(name: String, n: Int): Channel {
                val parsed = tv.nextv.core.catalog.QualityParser.parse(name)
                return Channel(
                    position = n, name = name, stream = StreamDescriptor("http://127.0.0.1:${server.localPort}/live/$n.ts"), kind = ContentKind.LIVE,
                    title = parsed.baseName, variantKey = tv.nextv.core.catalog.LiveVariants.key(parsed.variantKey), quality = parsed.quality,
                )
            }
            runBlocking { container.playlistStore.replaceChannels(sourceId, tv.nextv.core.catalog.LiveVariants.unify(listOf(live("FR| TF1 FHD", 0), live("FR| TF1 HD", 1)))) }
            val key = tv.nextv.core.catalog.LiveVariants.key(tv.nextv.core.catalog.QualityParser.parse("FR| TF1 FHD").variantKey)
            val versions = runBlocking { container.channelDao.variants(sourceId, key) }
            val fhd = checkNotNull(runBlocking { container.channelDao.get(versions.first { it.quality == "FHD" }.id) })
            val hd = checkNotNull(runBlocking { container.channelDao.get(versions.first { it.quality == "HD" }.id) })
            // Qualité visée HD : le plein écran ouvrira la version HD de la chaîne choisie (la FHD dans l'accueil).
            assertEquals(hd.id, runBlocking { channelStart(container, fhd, fhd, isLive = true) }.startId)
            val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)
            runBlocking { previewer.show(hd, rowId = fhd.id) }
            val shown = checkNotNull(previewer.player.value)
            container.previewHandoff.offer(checkNotNull(previewer.handOff(fhd.id)))
            previewer.release()

            val (viewModel, store) = open(fhd.id)
            viewModel.onStart()
            idle()
            assertSame(shown, viewModel.player.value)
            assertEquals(PreviewStart.TAKEN, viewModel.state.value.info.previewStart)
            store.clear()
        }
    }

    /**
     * Chaîne rassemblée en double audio et en son normal : les options du lecteur proposent les deux versions du son.
     * Le son normal choisi, la chaîne repart tout de suite sur sa version, et s'ouvre ensuite directement sur elle.
     */
    @Test
    fun theSoundVersionChosenInTheOptionsIsPlayedAndRemembered() {
        java.net.ServerSocket(0).use { server ->
            val sourceId = runBlocking { container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u"))) }
            fun live(name: String, n: Int): Channel {
                val parsed = tv.nextv.core.catalog.QualityParser.parse(name)
                return Channel(
                    position = n, name = name, stream = StreamDescriptor("http://127.0.0.1:${server.localPort}/live/$n.ts"), kind = ContentKind.LIVE,
                    title = parsed.baseName, variantKey = tv.nextv.core.catalog.LiveVariants.key(parsed.variantKey), quality = parsed.quality,
                    audio = parsed.audioRank,
                )
            }
            runBlocking {
                container.settings.setMergeChannels(true)
                container.playlistStore.replaceChannels(
                    sourceId,
                    tv.nextv.core.catalog.LiveVariants.unify(listOf(live("FR| CANAL+ FHD (DOUBLE AUDIO)", 0), live("FR| CANAL+ FHD", 1))),
                )
            }
            val listed = checkNotNull(runBlocking { container.channelDao.primaryIdByKey(sourceId, "live:fr canal+") })
            val (viewModel, store) = open(listed)
            viewModel.onStart()
            idle()
            // Sans choix : la version double audio (ouverte avant le son normal).
            assertEquals(listOf(0, tv.nextv.core.catalog.QualityParser.AUDIO_MULTI), viewModel.state.value.soundVersions)
            assertEquals(tv.nextv.core.catalog.QualityParser.AUDIO_MULTI, viewModel.state.value.soundVersion)

            viewModel.setSoundVersion(0)
            idle()
            assertEquals(0, viewModel.state.value.soundVersion)
            assertEquals(listOf(0, tv.nextv.core.catalog.QualityParser.AUDIO_MULTI), viewModel.state.value.soundVersions)
            assertEquals(0, runBlocking { container.settings.channelSound(sourceId, "live:fr canal+") })
            // Prochaine ouverture (plein écran ou aperçu de l'accueil) : directement la version au son normal.
            val primary = checkNotNull(runBlocking { container.channelDao.get(listed) })
            val start = runBlocking { channelStart(container, primary, primary, isLive = true) }
            assertEquals("FR| CANAL+ FHD", runBlocking { container.channelDao.get(start.startId) }?.name)
            store.clear()
        }
    }

    /**
     * Dernière qualité qui a fonctionné (ici SD, les versions FHD et HD en panne) : la chaîne repart d'elle juste après,
     * mais plus quelques heures plus tard : de la meilleure version. Elle repartait de SD, puis remontait peu à peu.
     */
    @Test
    fun theRememberedQualityIsUsedForAnHourOnly() {
        val sourceId = runBlocking { container.sourceRepository.add(SourceInput(name = "Test", location = SourceLocation.Remote("http://127.0.0.1:9/list.m3u"))) }
        fun live(name: String, n: Int): Channel {
            val parsed = tv.nextv.core.catalog.QualityParser.parse(name)
            return Channel(
                position = n, name = name, stream = StreamDescriptor("http://127.0.0.1:9/live/$n.ts"), kind = ContentKind.LIVE,
                title = parsed.baseName, variantKey = tv.nextv.core.catalog.LiveVariants.key(parsed.variantKey), quality = parsed.quality,
            )
        }
        val names = listOf("FR| RMC SPORT 1 FHD", "FR| RMC SPORT 1 HD", "FR| RMC SPORT 1 SD")
        runBlocking {
            container.settings.setAutoQuality(true)
            container.settings.setQualityTarget(tv.nextv.core.stream.QualityTarget.BEST)
            container.playlistStore.replaceChannels(sourceId, tv.nextv.core.catalog.LiveVariants.unify(names.mapIndexed { n, name -> live(name, n) }))
        }
        val key = tv.nextv.core.catalog.LiveVariants.key(tv.nextv.core.catalog.QualityParser.parse(names.first()).variantKey)
        val versions = runBlocking { container.channelDao.variants(sourceId, key) }
        val fhd = checkNotNull(runBlocking { container.channelDao.get(versions.first { it.quality == "FHD" }.id) })
        val sd = versions.first { it.quality == "SD" }.id
        fun start() = runBlocking { channelStart(container, fhd, fhd, isLive = true) }.startId
        // SD a joué une minute sans problème cet après-midi : juste après, la chaîne repart de SD.
        runBlocking { container.settings.rememberWorkingQuality(sourceId, key, "SD") }
        assertEquals(sd, start())
        // Trois heures plus tard : de la meilleure version.
        runBlocking { container.settings.rememberWorkingQuality(sourceId, key, "SD", now = System.currentTimeMillis() - 3 * 60 * 60_000L) }
        assertEquals(fhd.id, start())
    }

    /** Aperçu qui n'a rien pu confier : le plein écran le dit dans ses infos de lecture (rouvert, et pourquoi). */
    @Test
    fun theInfoPanelTellsWhyThePreviewStreamWasReopened() {
        val (live, _) = playlist()
        container.previewHandoff.missed(live, tv.nextv.app.player.HandoffMiss.OTHER_CHANNEL)
        val (viewModel, store) = open(live)
        viewModel.onStart()
        idle()
        assertEquals(PreviewStart.OTHER_CHANNEL, viewModel.state.value.info.previewStart)
        store.clear()
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
            // Sa couche vidéo part avec lui : le décodeur ne change pas de destination en passant dans l'aperçu.
            assertTrue(tv.nextv.app.player.VideoLayers.has(playing))
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
    fun withoutSmoothSwitchingTheStreamIsClosedBackToHome() {
        java.net.ServerSocket(0).use { server ->
            kotlinx.coroutines.runBlocking { container.settings.setPreviewHandoff(false) }
            val (live, _) = playlist("http://127.0.0.1:${server.localPort}/live/1.ts")
            val (viewModel, store) = open(live)
            viewModel.onStart()
            idle()
            val playing = checkNotNull(viewModel.player.value)
            // Réglage coupé : pas de couche vidéo, le direct n'est pas confié à l'aperçu (fermé puis rouvert, comme avant).
            assertTrue(!tv.nextv.app.player.VideoLayers.has(playing))
            viewModel.keepStreamForHome()
            assertNull(container.previewHandoff.waiting.value)
            store.clear()
            kotlinx.coroutines.runBlocking { container.settings.setPreviewHandoff(true) }
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
     * Pause, reprise, pause, reprise sur un direct continu (aussi très vite) : la lecture reprend chaque fois depuis le
     * direct gardé, sur la même connexion. La seconde pause arrêtait le lecteur et ouvrait une autre connexion ; la
     * reprise relançait un lecteur arrêté (image figée jusqu'au changement de chaîne).
     */
    @Test
    fun pausingALiveChannelAgainKeepsItPlaying() {
        val server = mockwebserver3.MockWebServer()
        // Flux MPEG-TS sans fin (paquets vides) : la connexion reste ouverte comme celle d'un vrai direct.
        val packet = ByteArray(188).also {
            it[0] = 0x47
            it[1] = 0x1F
            it[2] = 0xFF.toByte()
            it[3] = 0x10
        }
        val body = okio.Buffer().apply { repeat(20_000) { write(packet) } }
        server.enqueue(mockwebserver3.MockResponse.Builder().body(body).throttleBody(188L * 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        server.start()
        try {
            val (live, _) = playlist(server.url("/live/1.ts").toString())
            val (viewModel, store) = open(live)
            viewModel.onStart()
            idle()
            fun assertPlaying(step: String) {
                val player = checkNotNull(viewModel.player.value)
                assertTrue("$step : lecteur arrêté", player.playbackState != androidx.media3.common.Player.STATE_IDLE)
                assertTrue("$step : pas en lecture", player.playWhenReady)
                assertTrue("$step : encore en pause", !viewModel.state.value.paused)
            }
            repeat(2) { round ->
                viewModel.togglePause()
                idle()
                assertTrue(viewModel.state.value.paused)
                viewModel.togglePause()
                idle()
                assertPlaying("reprise ${round + 1}")
            }
            // Très vite, sans attendre entre les appuis.
            repeat(4) { viewModel.togglePause() }
            idle()
            assertPlaying("appuis rapides")
            // Toujours la connexion du départ : pas de seconde connexion (souvent refusée par l'abonnement).
            assertEquals(1, server.requestCount)
            store.clear()
        } finally {
            server.close()
        }
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
        // Ouverte depuis l'accueil : aucune catégorie du menu à suivre au retour.
        assertEquals(null, container.lastLiveCategory.value)
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
        // Au retour, l'accueil passera à cette catégorie, sur la chaîne regardée.
        assertEquals(Category.Group("TNT"), container.lastLiveCategory.value)
        assertEquals("live:france 2", container.lastLiveChannel.value)
        viewModel.zap(1)
        idle()
        assertEquals("M6", viewModel.state.value.channelName)
        viewModel.openMenu()
        idle()
        assertEquals(Category.Group("TNT"), viewModel.menu.value.category)
        store.clear()
    }

    /**
     * Chaîne ouverte depuis une catégorie (accueil, guide) : haut/bas reste dans cette catégorie, en boucle. Depuis la
     * première chaîne de « CANAL+ », « bas » va à la dernière de « CANAL+ », et non à la chaîne d'avant dans toute la
     * playlist (France 2). Une chaîne choisie ensuite dans le menu zappe dans la catégorie du menu où elle a été choisie.
     */
    @Test
    fun aChannelOpenedFromACategoryZapsInThatCategory() {
        val sourceId = runBlocking {
            val id = container.sourceRepository.add(SourceInput(name = "Catégories", location = SourceLocation.Remote("http://127.0.0.1:9/groups.m3u")))
            container.playlistStore.replaceChannels(
                id,
                listOf(
                    "TF1" to "TNT", "France 2" to "TNT",
                    "Canal+" to "CANAL+", "Canal+ Sport" to "CANAL+", "Canal+ Cinéma" to "CANAL+",
                    "beIN Sports 1" to "SPORT",
                ).mapIndexed { index, (name, group) ->
                    Channel(position = index, name = name, stream = StreamDescriptor("http://127.0.0.1:9/live/$index.ts"), groupTitle = group, title = name)
                },
            )
            id
        }
        fun idOf(key: String) = runBlocking { checkNotNull(container.channelDao.primaryIdByKey(sourceId, key)) }
        val viewModel = PlayerViewModel(
            container,
            SavedStateHandle(mapOf(PlayerViewModel.ARG_CHANNEL_ID to idOf("live:canal"), PlayerViewModel.ARG_GROUP to "CANAL+")),
            application,
        )
        val store = ViewModelStore().apply { put("player", viewModel) }
        viewModel.onStart()
        idle()
        assertEquals("Canal+", viewModel.state.value.channelName)
        fun zap(direction: Int): String? {
            val before = viewModel.state.value.channelName
            viewModel.zap(direction)
            awaitOtherChannel(viewModel, before)
            return viewModel.state.value.channelName
        }
        fun pick(category: Category, key: String) {
            val before = viewModel.state.value.channelName
            viewModel.selectMenuCategory(category)
            viewModel.playFromMenu(idOf(key))
            awaitOtherChannel(viewModel, before)
        }
        // Bas depuis la première chaîne de la catégorie : la dernière de la catégorie.
        assertEquals("Canal+ Cinéma", zap(-1))
        // Haut depuis la dernière : retour à la première, pas beIN Sports 1 (catégorie suivante).
        assertEquals("Canal+", zap(1))
        assertEquals("Canal+ Sport", zap(1))
        assertEquals("Canal+", zap(-1))
        // « Retour » : le menu s'ouvre sur cette catégorie.
        viewModel.openMenu()
        idle()
        assertEquals(Category.Group("CANAL+"), viewModel.menu.value.category)

        // Chaîne choisie dans « Toutes les chaînes » du menu : haut/bas parcourt toute la playlist.
        pick(Category.All, "live:bein sports 1")
        assertEquals("beIN Sports 1", viewModel.state.value.channelName)
        assertEquals("TF1", zap(1))

        // Chaîne choisie dans une autre catégorie du menu : le zapping reste dans celle-ci.
        viewModel.openMenu()
        idle()
        pick(Category.Group("TNT"), "live:france 2")
        assertEquals("France 2", viewModel.state.value.channelName)
        assertEquals("TF1", zap(1))
        assertEquals("France 2", zap(1))
        store.clear()
    }

    /** Chaîne ouverte d'ailleurs (« Toutes les chaînes », recherche, chaînes récentes) : haut/bas parcourt toute la playlist. */
    @Test
    fun aChannelOpenedFromAllChannelsZapsThroughThePlaylist() {
        val sourceId = runBlocking {
            val id = container.sourceRepository.add(SourceInput(name = "Toutes", location = SourceLocation.Remote("http://127.0.0.1:9/all.m3u")))
            container.playlistStore.replaceChannels(
                id,
                listOf("TF1" to "TNT", "France 2" to "TNT", "Canal+" to "CANAL+").mapIndexed { index, (name, group) ->
                    Channel(position = index, name = name, stream = StreamDescriptor("http://127.0.0.1:9/live/$index.ts"), groupTitle = group, title = name)
                },
            )
            id
        }
        val france2 = runBlocking { checkNotNull(container.channelDao.primaryIdByKey(sourceId, "live:france 2")) }
        val (viewModel, store) = open(france2)
        viewModel.onStart()
        idle()
        assertEquals("France 2", viewModel.state.value.channelName)
        viewModel.zap(1)
        awaitOtherChannel(viewModel, "France 2")
        assertEquals("Canal+", viewModel.state.value.channelName)
        viewModel.zap(1)
        awaitOtherChannel(viewModel, "Canal+")
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
