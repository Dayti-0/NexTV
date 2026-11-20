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

    /** Mention d'une émission, en bas à gauche : des lettres blanches (barres verticales) sur toute une ligne. */
    private fun ByteArray.withCaption(shift: Int = 0) = apply {
        for (y in HEIGHT - 30 until HEIGHT - 22) for (x in 20 + shift until 110 + shift) if ((x - shift) % 6 < 3) this[y * WIDTH + x] = 255.toByte()
    }

    /** Mentions légales d'une publicité au même endroit : un autre texte, plus serré. */
    private fun ByteArray.withLegalLine() = apply {
        for (y in HEIGHT - 31 until HEIGHT - 21) for (x in 10 until 125) if (x % 4 < 2 && y % 5 != 0) this[y * WIDTH + x] = 255.toByte()
    }

    /** Fond presque blanc partout : un logo blanc ne s'y voit plus. */
    private fun whiteOut() = ByteArray(WIDTH * HEIGHT) { (250 + random.nextInt(6)).toByte() }

    private fun show(frames: Int, image: () -> ByteArray): LogoState = show(watcher, frames, image)

    private fun show(target: LogoWatcher, frames: Int, image: () -> ByteArray): LogoState {
        repeat(frames) { target.frame(image()) }
        return target.state
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

    @Test
    fun `the legal line of an ad where a caption was is not that caption`() {
        // RMC Découverte : le titre de l'émission, en bas à gauche, était pris pour revenu sous les mentions d'une pub.
        assertEquals(LogoState.VISIBLE, show(45) { scene().withCaption() })
        assertEquals(LogoState.HIDDEN, show(5) { scene() })
        assertEquals(LogoState.HIDDEN, show(30) { scene().withLegalLine() })
        // Le même titre, décalé de quelques points : ce n'est plus lui non plus.
        assertEquals(LogoState.HIDDEN, show(10) { scene().withCaption(shift = 3) })
        assertEquals(LogoState.VISIBLE, show(2) { scene().withCaption() })
    }

    @Test
    fun `a white background hides the logo without saying it left`() {
        show(45) { scene().withLogo() }
        assertEquals(LogoState.VISIBLE, show(20) { whiteOut().withLogo() })
        assertEquals(LogoState.VISIBLE, show(3) { scene().withLogo() })
    }

    @Test
    fun `another version of the stream must show the logo again, or it is learned again where this version puts it`() {
        show(45) { scene().withLogo() }
        assertEquals(LogoState.VISIBLE, watcher.state)
        // Même cadrage : le logo est revu en deux images.
        watcher.recheck(frames = 30)
        assertEquals("à revoir sur cette version", LogoState.HIDDEN, watcher.state)
        assertEquals(LogoState.VISIBLE, show(2) { scene().withLogo() })
        // Autre cadrage : logo ailleurs. Jamais revu en 30 images : oublié, puis appris à sa nouvelle place.
        watcher.recheck(frames = 30)
        assertEquals(LogoState.HIDDEN, show(29) { scene().withLogo(left = WIDTH - 90, top = 40) })
        assertEquals(LogoState.UNKNOWN, show(1) { scene().withLogo(left = WIDTH - 90, top = 40) })
        assertEquals(LogoState.VISIBLE, show(45) { scene().withLogo(left = WIDTH - 90, top = 40) })
        assertEquals(LogoState.HIDDEN, show(5) { scene() })
    }

    @Test
    fun `a logo kept from a previous viewing is known at once, and absent until seen again`() {
        show(45) { scene().withLogo() }
        val kept = watcher.export()!!

        val later = LogoWatcher(WIDTH, HEIGHT, learnFrames = 20)
        later.restore(kept)
        assertEquals("pas encore revu : en pleine page de pubs, peut-être", LogoState.HIDDEN, later.state)
        assertEquals(LogoState.HIDDEN, show(later, 30) { scene() })
        assertEquals("une image ne suffit pas", LogoState.HIDDEN, show(later, 1) { scene().withLogo() })
        assertEquals(LogoState.VISIBLE, show(later, 1) { scene().withLogo() })
    }

    @Test
    fun `marks kept for another picture size or damaged are ignored`() {
        show(45) { scene().withLogo() }
        val kept = watcher.export()!!
        val other = LogoWatcher(WIDTH * 2, HEIGHT * 2)
        other.restore(kept)
        assertEquals(LogoState.UNKNOWN, other.state)

        val same = LogoWatcher(WIDTH, HEIGHT)
        same.restore(kept.copyOf(kept.size / 2))
        assertEquals(LogoState.UNKNOWN, same.state)
        same.restore(ByteArray(3))
        assertEquals(LogoState.UNKNOWN, same.state)
    }

    @Test
    fun `nothing is kept before a logo has proved itself`() {
        show(25) { scene().withLogo() }
        assertEquals(null, watcher.export())
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val BLOCK = 24
    }
}
