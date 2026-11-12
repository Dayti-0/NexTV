package tv.nextv.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedTest {
    @Test
    fun `le flou reste opaque sous le menu et s'efface vers son bord droit`() {
        val width = 40
        val height = 3
        val pixels = IntArray(width * height) { 0xFF336699.toInt() }
        fadeRight(pixels, width, height, 0.25f)
        for (y in 0 until height) {
            // Les trois quarts de gauche : intacts.
            for (x in 0 until 30) assertEquals(0xFF336699.toInt(), pixels[y * width + x])
            // Le dernier quart : de moins en moins visible, jusqu'à rien, sans changer la couleur.
            val alphas = (30 until width).map { pixels[y * width + it] ushr 24 }
            assertTrue(alphas.zipWithNext().all { (a, b) -> b < a })
            assertTrue(alphas.first() < 255)
            assertEquals(0, alphas.last())
            for (x in 30 until width) assertEquals(0x336699, pixels[y * width + x] and 0xFFFFFF)
        }
    }

    @Test
    fun `une image opaque reste opaque une fois floutée`() {
        val width = 12
        val height = 8
        val pixels = IntArray(width * height) { if (it % 3 == 0) 0xFF00E5FF.toInt() else 0xFF101418.toInt() }
        boxBlur(pixels, width, height, 2)
        assertTrue(pixels.all { it ushr 24 == 255 })
    }
}
