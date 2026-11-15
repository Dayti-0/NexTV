package tv.nextv.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallFrameTest {

    private fun frame(level: (x: Int, y: Int) -> Int) = ByteArray(SmallFrame.SIZE) { level(it % SmallFrame.WIDTH, it / SmallFrame.WIDTH).toByte() }

    @Test
    fun `the player's picture is reduced by averaging blocks of five by five`() {
        val gray = ByteArray(480 * 270) { i -> if ((i % 480) / 5 % 2 == 0) 200.toByte() else 100.toByte() }
        // Un point clair isolé dans le premier pavé : il compte pour un vingt-cinquième.
        gray[0] = 250.toByte()
        val small = SmallFrame.reduce(gray, 480, 270)!!
        assertEquals(SmallFrame.SIZE, small.size)
        assertEquals(202, small[0].toInt() and 0xFF)
        assertEquals(100, small[1].toInt() and 0xFF)
        assertEquals(200, small[2].toInt() and 0xFF)
    }

    @Test
    fun `a picture of another shape is refused`() {
        assertNull(SmallFrame.reduce(ByteArray(640 * 360), 640, 360))
        assertNull(SmallFrame.reduce(ByteArray(100), 480, 270))
        assertFalse(SmallFrame.reduce(ByteArray(480 * 270), 480, 270, ByteArray(10)))
    }

    @Test
    fun `the same shot barely changes, another shot changes almost everywhere`() {
        val shot = frame { x, y -> (x * 2 + y) % 256 }
        val sameShot = frame { x, y -> ((x * 2 + y) % 256 + 5).coerceAtMost(255) }
        val otherShot = frame { x, y -> (255 - x * 2 + y * 3).mod(256) }
        assertEquals(0f, SmallFrame.changedShare(shot, sameShot), 0.01f)
        assertTrue(SmallFrame.changedShare(shot, otherShot) > 0.6f)
    }

    @Test
    fun `a card is flat, a filmed picture is not`() {
        val card = frame { x, y -> if (x in 40..55 && y in 22..31) 230 else 60 }
        val filmed = frame { x, y -> (x * 5 + y * 3) % 256 }
        assertTrue(SmallFrame.flatShare(card) > 0.9f)
        assertTrue(SmallFrame.flatShare(filmed) < 0.2f)
        assertEquals(1f, SmallFrame.flatShare(frame { _, _ -> 0 }), 0f)
    }

    @Test
    fun `the same card is recognised, another one is not`() {
        val card = frame { x, y -> if (x in 40..55 && y in 22..31) 230 else 60 }
        val again = frame { x, y -> if (x in 40..55 && y in 22..31) 226 else 63 }
        val otherColour = frame { x, y -> if (x in 40..55 && y in 22..31) 230 else 120 }
        val noLogo = frame { _, _ -> 60 }
        val signature = SmallFrame.signature(card)
        assertEquals(72, signature.size)
        assertTrue(SmallFrame.sameSignature(signature, SmallFrame.signature(again)))
        assertFalse(SmallFrame.sameSignature(signature, SmallFrame.signature(otherColour)))
        assertFalse(SmallFrame.sameSignature(signature, SmallFrame.signature(noLogo)))
        assertArrayEquals(signature, SmallFrame.signature(card))
    }
}
