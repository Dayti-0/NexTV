package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Couleurs de l'animation des radios tirées de la pochette du morceau. */
class CoverColorsTest {

    private fun hue(color: Int): Float {
        val hsv = FloatArray(3)
        CoverColors.toHsv((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)
        return hsv[0]
    }

    private fun value(color: Int): Float {
        val hsv = FloatArray(3)
        CoverColors.toHsv((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)
        return hsv[2]
    }

    @Test
    fun `a red, yellow and blue cover gives its three colours, red first`() {
        val pixels = IntArray(2304) { i ->
            when {
                i < 1200 -> 0xFFD32F2F.toInt() // rouge
                i < 1800 -> 0xFFFBC02D.toInt() // jaune
                i < 2100 -> 0xFF1976D2.toInt() // bleu
                else -> 0xFF101010.toInt()
            }
        }
        val colors = CoverColors.extract(pixels)!!
        assertTrue(hue(colors.a) < 15f || hue(colors.a) > 345f)
        assertTrue(hue(colors.b) in 35f..60f)
        assertTrue(hue(colors.c) in 195f..225f)
        // Fond très sombre, teinté de rouge.
        assertTrue(value(colors.background) < 0.12f)
        assertTrue(hue(colors.background) < 15f || hue(colors.background) > 345f)
    }

    @Test
    fun `a black and white cover keeps the radio colours`() {
        val pixels = IntArray(2304) { i -> if (i % 2 == 0) 0xFF000000.toInt() else 0xFFE0E0E0.toInt() }
        assertNull(CoverColors.extract(pixels))
        assertNull(CoverColors.extract(IntArray(0)))
    }

    @Test
    fun `a cover of a single colour still gives three shades of it`() {
        val pixels = IntArray(2304) { 0xFF7B1FA2.toInt() }
        val colors = CoverColors.extract(pixels)!!
        assertEquals(hue(colors.a), hue(0xFF7B1FA2.toInt()), 3f)
        assertTrue(colors.a != colors.b && colors.b != colors.c)
    }
}
