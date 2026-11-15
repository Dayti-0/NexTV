package tv.nextv.app.player

import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Musique des pubs : elle démarre et s'arrête avec la page de pubs, sans planter (radio injoignable ici). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdMusicPlayerTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `starts with the break and fades out with the programme`() {
        val music = AdMusicPlayer(context)
        music.start(AdMusicPlayer.Source.Radio(AdRadios.of("fip")))
        assertTrue(music.playing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        music.stop()
        assertFalse(music.playing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // Musique de l'appareil (fichier illisible ici : passe au suivant sans planter).
        val file = File(context.filesDir, "a.mp3").apply { writeBytes(ByteArray(1_000)) }
        music.start(AdMusicPlayer.Source.Tracks(listOf(file, file)))
        assertTrue(music.playing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        music.stop()
        music.release()
        assertFalse(music.playing)
    }

    @Test
    fun `radios are known by their key, the first by default`() {
        assertEquals("FIP Jazz", AdRadios.of("fip_jazz").name)
        assertEquals("FIP", AdRadios.of(null).name)
        assertEquals("FIP", AdRadios.of("inconnue").name)
    }
}
