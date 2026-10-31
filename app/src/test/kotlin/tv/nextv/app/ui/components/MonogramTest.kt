package tv.nextv.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramTest {

    @Test
    fun `badges show the channel, not the country prefix`() {
        assertEquals("BFM", monogram("FR| BFM NICE"))
        assertEquals("TP", monogram("FR| TELE PEASE"))
        assertEquals("TN", monogram("FR| TELE NANTES FHD"))
        assertEquals("TV78", monogram("FR| TV 78"))
        assertEquals("MT", monogram("FR| MOSELLE TV"))
        assertEquals("F2", monogram("France 2"))
        assertEquals("TF1", monogram("TF1 HD"))
        assertEquals("ARTE", monogram("|FR| ARTE"))
        assertEquals("CS", monogram("Canal+ Sport"))
        assertEquals("?", monogram("   "))
    }
}
