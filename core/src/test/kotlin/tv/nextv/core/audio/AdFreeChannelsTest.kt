package tv.nextv.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFreeChannelsTest {
    @Test
    fun roundTheClockChannelsOfM6PlusHaveNoAds() {
        assertTrue(AdFreeChannels.isAdFree("LES MARSEILLAIS 24/24"))
        assertTrue(AdFreeChannels.isAdFree("|FR| INCROYABLES TRANSFORMATIONS 24/24 HD"))
        assertTrue(AdFreeChannels.isAdFree("Turbo 24 / 24"))
        assertFalse(AdFreeChannels.isAdFree("M6"))
        assertFalse(AdFreeChannels.isAdFree("TF1 Séries Films"))
        assertFalse(AdFreeChannels.isAdFree("France 24"))
        assertFalse(AdFreeChannels.isAdFree(null))
    }
}
