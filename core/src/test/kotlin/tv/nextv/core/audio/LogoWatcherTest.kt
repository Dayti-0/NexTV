package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class LogoWatcherTest {

    private val random = Random(7)
    private val watcher = LogoWatcher(WIDTH, HEIGHT, learnFrames = 20)

    /** Image qui change à chaque fois : grands pavés gris, décalés au hasard. */
    private fun scene(): ByteArray {
        val shiftX = random.nextInt(BLOCK)
        val shiftY = random.nextInt(BLOCK)
        val columns = WIDTH / BLOCK + 2
        val blocks = IntArray(columns * (HEIGHT / BLOCK + 2)) { random.nextInt(200) }
        return ByteArray(WIDTH * HEIGHT) { i ->
            blocks[(i / WIDTH + shiftY) / BLOCK * columns + (i % WIDTH + shiftX) / BLOCK].toByte()
        }
    }

    /** Logo à moitié transparent en haut à droite : un cadre de 28 × 20 points. */
    private fun ByteArray.withLogo(left: Int = WIDTH - 60, top: Int = 20) = apply {
        for (y in 0 until 20) for (x in 0 until 28) {
            if (x < 3 || x >= 25 || y < 3 || y >= 17) {
                val i = (top + y) * WIDTH + left + x
                this[i] = (((this[i].toInt() and 0xFF) + 255) / 2).toByte()
            }
        }
    }

    /** Texte serré d'une publicité sur tout le coin en haut à droite. */
    private fun ByteArray.withFineText() = apply {
        for (y in 0 until 70) for (x in WIDTH - 140 until WIDTH) this[y * WIDTH + x] = (if ((x / 2 + y / 2) % 2 == 0) 0 else 255).toByte()
    }

    private fun show(frames: Int, image: () -> ByteArray): LogoState {
        repeat(frames) { watcher.frame(image()) }
        return watcher.state
    }

    @Test
    fun `the logo is known after staying on screen, then followed when it leaves and returns`() {
        assertEquals("appris, pas encore sûr", LogoState.UNKNOWN, show(30) { scene().withLogo() })
        assertEquals(LogoState.VISIBLE, show(15) { scene().withLogo() })

        // Publicités : plus de logo.
        assertEquals("deux images sans logo ne suffisent pas", LogoState.VISIBLE, show(2) { scene() })
        assertEquals(LogoState.HIDDEN, show(2) { scene() })
        assertEquals(LogoState.HIDDEN, show(200) { scene() })

        // Reprise du programme.
        assertEquals(LogoState.VISIBLE, show(3) { scene().withLogo() })
    }

    @Test
    fun `an ad full of text where the logo was is not the logo`() {
        show(45) { scene().withLogo() }
        show(5) { scene() }
        assertEquals(LogoState.HIDDEN, show(30) { scene().withFineText() })
    }

    @Test
    fun `a still image teaches no logo`() {
        val still = scene().withLogo()
        assertEquals(LogoState.UNKNOWN, show(120) { still })
    }

    @Test
    fun `something that leaves before a minute was not a logo`() {
        // Mention commune à deux publicités, puis plus rien.
        show(25) { scene().withLogo() }
        assertEquals(LogoState.UNKNOWN, show(60) { scene() })
    }

    @Test
    fun `a logo gone for too long is learned again`() {
        show(45) { scene().withLogo() }
        assertEquals(LogoState.HIDDEN, show(700) { scene() })
        assertEquals(LogoState.UNKNOWN, show(40) { scene() })
        // Nouveau logo, ailleurs.
        assertEquals(LogoState.VISIBLE, show(45) { scene().withLogo(left = 30) })
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val BLOCK = 24
    }
}
