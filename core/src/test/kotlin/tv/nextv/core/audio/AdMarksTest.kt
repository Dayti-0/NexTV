package tv.nextv.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMarksTest {

    @Test
    fun `marks are read back as they were written`() {
        val marks = AdMarks(logo = byteArrayOf(1, 2, 3, 4), separator = ByteArray(72) { it.toByte() }, logoFollowsBreaks = true)
        val read = AdMarks.decode(marks.encode())!!
        assertArrayEquals(marks.logo, read.logo)
        assertArrayEquals(marks.separator, read.separator)
        assertTrue(read.logoFollowsBreaks)
        assertTrue(marks.sameAs(read))
    }

    @Test
    fun `missing parts stay missing`() {
        val read = AdMarks.decode(AdMarks(logo = null, separator = null, logoFollowsBreaks = false).encode())!!
        assertNull(read.logo)
        assertNull(read.separator)
        assertFalse(read.logoFollowsBreaks)
        assertTrue(read.isEmpty)
        assertFalse(AdMarks(byteArrayOf(1), null, false).isEmpty)
    }

    @Test
    fun `damaged or foreign data gives nothing`() {
        val bytes = AdMarks(byteArrayOf(1, 2, 3, 4), null, true).encode()
        assertNull(AdMarks.decode(bytes.copyOf(bytes.size - 3)))
        assertNull(AdMarks.decode(ByteArray(0)))
        assertNull(AdMarks.decode(byteArrayOf(9, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(AdMarks.decode(byteArrayOf(1, 0, 127, 127, 127, 127)))
    }

    @Test
    fun `learning something new is a change, a logo that only drifted is not`() {
        val known = AdMarks(ByteArray(40), null, false)
        assertTrue(known.sameAs(AdMarks(ByteArray(40) { 1 }, null, false)))
        assertFalse(known.sameAs(AdMarks(ByteArray(44), null, false)))
        assertFalse(known.sameAs(AdMarks(ByteArray(40), ByteArray(72), false)))
        assertFalse(known.sameAs(AdMarks(ByteArray(40), null, true)))
        assertFalse(known.sameAs(null))
        assertEquals(false, AdMarks(null, null, true).isEmpty)
    }
}
