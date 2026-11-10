package tv.nextv.app.player

import android.app.Application
import androidx.media3.common.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun onlyTheFullScreenPlayerAsksForTheSound() {
        val full = container.playerFactory.create()
        val preview = container.playerFactory.create(preview = true)
        try {
            // Plein écran : son d'un film, demandé au système. Aperçu : réglage par défaut, sans demande.
            assertEquals(C.USAGE_MEDIA, full.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, full.audioAttributes.contentType)
            assertNotEquals(C.AUDIO_CONTENT_TYPE_MOVIE, preview.audioAttributes.contentType)
        } finally {
            full.release()
            preview.release()
        }
    }
}
