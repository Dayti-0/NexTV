package tv.nextv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamQualityTest {
    /** Qualité d'après la taille de l'image : la largeur compte (formats cinéma, moins hauts). */
    @Test
    fun qualityFromImageSize() {
        assertEquals(StreamQuality.UHD, StreamQuality.fromSize(3840, 2160))
        assertEquals(StreamQuality.UHD, StreamQuality.fromSize(3840, 1600))
        assertEquals(StreamQuality.FHD, StreamQuality.fromSize(1920, 1080))
        assertEquals(StreamQuality.FHD, StreamQuality.fromSize(1920, 800))
        assertEquals(StreamQuality.HD, StreamQuality.fromSize(1280, 720))
        assertEquals(StreamQuality.HD, StreamQuality.fromSize(1280, 536))
        assertEquals(StreamQuality.SD, StreamQuality.fromSize(720, 576))
        assertEquals(StreamQuality.LOW, StreamQuality.fromSize(320, 240))
        assertEquals(StreamQuality.FHD, StreamQuality.fromSize(null, 1080))
        assertNull(StreamQuality.fromSize(null, null))
        assertNull(StreamQuality.fromSize(0, 0))
    }
}
