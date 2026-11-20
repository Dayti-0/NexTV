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
        assertTrue(isRadio("|FR| NRJ", "|FR| DIVERS"))
        assertTrue(isRadio("|FR| Nostalgie", null))
        assertFalse(isRadio("|FR| TF1 FHD", "|FR| TNT"))
        assertFalse(isRadio("|FR| France 2", "|FR| GENERALISTE"))
    }
}
