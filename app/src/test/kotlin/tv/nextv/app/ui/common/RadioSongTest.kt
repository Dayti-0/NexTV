package tv.nextv.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSongTest {
    @Test
    fun radiosAreRecognisedByNameOrCategory() {
        assertTrue(isRadio("|FR| SkyRock FM", "|FR| DIVERS"))
        assertTrue(isRadio("|FR| Vibration FM", "|FR| RADIO"))
        assertTrue(isRadio("Groove Salad", "Web radios"))
        assertFalse(isRadio("|FR| TF1 FHD", "|FR| TNT"))
    }
}
