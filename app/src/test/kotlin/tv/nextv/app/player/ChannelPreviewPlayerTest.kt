package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ChannelEntity

/** Aperçu de l'accueil : un seul lecteur gardé d'une chaîne à l'autre, rendu quand l'accueil est quitté. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChannelPreviewPlayerTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())

    private fun channel(id: Long) = ChannelEntity(
        id = id,
        sourceId = 1,
        position = id.toInt(),
        name = "Chaîne $id",
        groupTitle = null,
        logoUrl = null,
        tvgId = null,
        tvgName = null,
        channelNumber = null,
        streamUrl = "http://127.0.0.1:9/$id.ts",
        streamHeaders = "",
        streamFormat = "PROGRESSIVE",
        streamSupport = "SUPPORTED",
        streamIssues = "",
    )

    @Test
    fun theSamePlayerShowsOneChannelAfterAnother() = runBlocking {
        val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository)
        previewer.volume = 0.2f
        previewer.show(channel(1))
        val first = checkNotNull(previewer.player.value)
        assertEquals(0.2f, first.volume, 0.001f)

        // Autre chaîne survolée : même lecteur, nouveau flux.
        previewer.show(channel(2))
        assertSame(first, previewer.player.value)

        // Aperçu arrêté (chaîne sans aperçu, section quittée) : plus rien à l'écran, le lecteur reste prêt.
        previewer.stop()
        assertNull(previewer.player.value)
        previewer.show(channel(1))
        assertSame(first, previewer.player.value)

        // Accueil quitté (plein écran) : le lecteur est rendu ; au retour, un autre est créé.
        previewer.release()
        assertNull(previewer.player.value)
        previewer.show(channel(2))
        assertNotSame(first, previewer.player.value)
        previewer.release()
    }

    @Test
    fun previewAndFullScreenUseTheSameSoundType() {
        val full = container.playerFactory.create()
        val preview = container.playerFactory.create(preview = true)
        try {
            // Plein écran : son d'un film. Aperçu : le même type de son (sans le demander au système) ; différent, le
            // passage de l'un à l'autre viderait la sortie son (coupure, image qui marque un temps d'arrêt).
            assertEquals(C.USAGE_MEDIA, full.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, full.audioAttributes.contentType)
            assertEquals(full.audioAttributes, preview.audioAttributes)
        } finally {
            full.release()
            preview.release()
        }
    }

    @Test
    fun thePreviewUsesTheFullScreenTrackChoices() = runBlocking {
        container.settings.setAudioLanguage("fr")
        val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)
        previewer.show(channel(1))
        val player = checkNotNull(previewer.player.value)
        // Langue du plein écran, sous-titres coupés (aucun choisi) : rien ne change quand il reprend l'aperçu.
        assertEquals(listOf("fr"), player.trackSelectionParameters.preferredAudioLanguages)
        assertEquals(true, C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        previewer.release()
    }

    @Test
    fun theHomePreviewContinuesOnAStreamHandedBack() = runBlocking {
        val previewer = ChannelPreviewPlayer(container.playerFactory, container.sourceRepository, container.settings)
        previewer.volume = 0.3f
        previewer.show(channel(1))
        val own = checkNotNull(previewer.player.value)
        // Direct du plein écran rendu à l'accueil (chaîne 2).
        val buffer = container.playerFactory.liveLoadControl(preview = false)
        val full = container.playerFactory.create(liveBuffer = buffer)
        val taps = StreamTaps()
        val request = PlaybackRequest(channel(2).toStream(), null, null, null)
        full.setMediaSource(container.playerFactory.mediaSource(request, taps = taps))
        full.prepare()
        previewer.adopt(PreviewHandoff(full, request, taps, buffer, setOf(2L), toPreview = true), 2)
        // L'aperçu continue sur ce lecteur, au volume de l'aperçu et sa mémoire tampon ; le sien est rendu.
        assertSame(full, previewer.player.value)
        assertNotSame(own, previewer.player.value)
        assertEquals(0.3f, full.volume, 0.001f)
        assertEquals(true, buffer.preview)
        assertEquals(true, taps.preview)
        // Sa vidéo ne le fait pas repartir au direct (ce qui rouvrirait la connexion), une seule fois.
        assertEquals(true, ContinuingPlayers.consume(full))
        assertEquals(false, ContinuingPlayers.consume(full))
        // Déjà la chaîne de l'aperçu : rien n'est rouvert.
        previewer.show(channel(2).copy())
        assertSame(full, previewer.player.value)
        // Puis OK dessus : il repart vers le plein écran.
        val again = checkNotNull(previewer.handOff(2))
        assertSame(full, again.player)
        assertEquals(setOf(2L), again.channelIds)
        assertEquals(false, again.toPreview)
        again.player.release()
        previewer.release()
    }
}
