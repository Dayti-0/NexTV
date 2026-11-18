package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class NowPlayingTest {

    @Test
    fun radioFranceGivesTheSongPlayingNow() {
        val body = """
            {"steps":{"a_7":{"title":"Avant","authors":"Quelqu'un","embedType":"song","end":1},
            "b_7":{"title":"Ecoute moi Camarade","authors":"Rachid Taha","embedType":"song","start":1794989031,"end":1794989433,
            "visual":"https://www.radiofrance.fr/s3/cover.jpg"}},
            "levels":[{"items":["a_7","b_7"],"position":1}],"stationId":7}
        """.trimIndent()
        val now = NowPlaying.fromRadioFrance(body)!!
        assertEquals("Rachid Taha – Ecoute moi Camarade", now.label)
        assertEquals("https://www.radiofrance.fr/s3/cover.jpg", now.cover)
        assertEquals(1_794_989_433_000L, now.untilMillis)
        // Émission parlée : son titre seul.
        val talk = """{"steps":{"x":{"title":"Le 9/9h30","authors":"","embedType":"expression"}},"levels":[{"items":["x"],"position":0}]}"""
        assertEquals("Le 9/9h30", NowPlaying.fromRadioFrance(talk)!!.label)
        assertNull(NowPlaying.fromRadioFrance("<html>"))
    }

    @Test
    fun recognitionGivesTheSongAndWhenItEnds() {
        val body = """
            {"status":"success","result":{"artist":"Eazy-E","title":"Real Muthaphuckkin' G's","timecode":"00:44",
            "apple_music":{"durationInMillis":334000,"artwork":{"url":"https://is1.mzstatic.com/x/{w}x{h}bb.jpg"}}}}
        """.trimIndent()
        val now = NowPlaying.fromAudd(body)!!
        assertEquals("Eazy-E – Real Muthaphuckkin' G's", now.label)
        assertEquals("https://is1.mzstatic.com/x/300x300bb.jpg", now.cover)
        assertEquals(290_000L, now.remainingMillis)
        assertNull(NowPlaying.fromAudd("""{"status":"success","result":null}"""))
        assertEquals(902, NowPlaying.recognitionError("""{"status":"error","error":{"error_code":902,"error_message":"limit"}}"""))
        assertNull(NowPlaying.recognitionError(body))
    }

    @Test
    fun theSnippetGivesTheLastSecondsAsWav() {
        val snippet = AudioSnippet(seconds = 10)
        val rate = 44_100
        val samples = FloatArray(rate) { i -> (0.5 * sin(2 * PI * 440 * i / rate)).toFloat() }
        assertNull(snippet.wav(8_000))
        repeat(9) { snippet.write(samples, samples.size, rate) }
        val wav = snippet.wav(8_000)!!
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + 8 * AudioSnippet.RATE * 2, wav.size)
        assertTrue(snippet.availableMillis in 8_900..9_000)
        snippet.clear()
        assertEquals(0L, snippet.availableMillis)
    }
}
