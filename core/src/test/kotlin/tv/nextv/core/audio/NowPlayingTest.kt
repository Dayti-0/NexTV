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
    fun skyrockNovaAndLesIndesGiveTheirSong() {
        val skyrock = """
            {"schedule":[
             {"type":"record","info":{"title":"Fille à Papa","start_ts":"100","end_ts":"200","cover_mid_uri":"https://media.skyrock.fm/a.jpg"}},
             {"type":"record","info":{"title":"Sicario","start_ts":"200","end_ts":"350","cover_mid_uri":"https://media.skyrock.fm/b.jpg"}},
             {"type":"program","info":{"title":"Rap & RnB Non Stop"}}]}
        """.trimIndent()
        val sky = NowPlaying.fromSkyrock(skyrock, nowSeconds = 250)!!
        assertEquals("Sicario", sky.label)
        assertEquals("https://media.skyrock.fm/b.jpg", sky.cover)
        assertEquals(350_000L, sky.untilMillis)
        // Entre deux morceaux : le dernier commencé, sans heure de fin.
        assertEquals("Sicario", NowPlaying.fromSkyrock(skyrock, nowSeconds = 400)!!.label)

        val nova = """
            [{"radio":{"name":"Radio Nova","stream_url":"https:\/\/novazz.ice.infomaniak.ch\/novazz-128.mp3"},
              "currentTrack":{"title":"THE GHETTO","artist":"DONNY HATHAWAY","media_url":"https://www.lesinrocks.com/c.jpeg"}},
             {"radio":{"name":"Nova Reggae","stream_url":"https:\/\/nova-reggae.ice.infomaniak.ch\/nova-reggae-128.mp3"},
              "currentTrack":{"title":"ANOTHER NIGHT","artist":"PUPAJIM","media_url":""}}]
        """.trimIndent()
        assertEquals("PUPAJIM – ANOTHER NIGHT", NowPlaying.fromNova(nova, "https://nova-reggae.ice.infomaniak.ch/nova-reggae-128.mp3")!!.label)
        assertEquals("https://www.lesinrocks.com/c.jpeg", NowPlaying.fromNova(nova, "http://novazz.ice.infomaniak.ch/novazz-128.mp3")!!.cover)
        assertNull(NowPlaying.fromNova(nova, "https://ailleurs.example/flux"))

        val indes = """{"1":{"artist":"MADELEINE PEYROUX","title":"Walkin' after midnight","cover":"https:\/\/covers.eg-ad.fr\/m.jpg"}}"""
        val jazz = NowPlaying.fromIndes(indes, "1")!!
        assertEquals("MADELEINE PEYROUX – Walkin' after midnight", jazz.label)
        assertEquals("https://covers.eg-ad.fr/m.jpg", jazz.cover)
        assertNull(NowPlaying.fromIndes(indes, "2"))
    }

    @Test
    fun europe2RadiokingAndRtlGiveWhatIsOnAir() {
        val europe2 = """{"nowplaying":[{"artist":"KAVINSKY","title":"Nightcall","time":"2026-11-18T09:11:07.000Z","imageUrl":"https://cdn.europe2.lanmedia.fr/c.jpg",
            "status":"playing","raw":{"duration":"00:02:57"}},{"artist":"MASTER KG","title":"Jerusalema","status":"history"}]}"""
        val e2 = NowPlaying.fromLagardere(europe2)!!
        assertEquals("KAVINSKY – Nightcall", e2.label)
        assertEquals(java.time.Instant.parse("2026-11-18T09:14:04Z").toEpochMilli(), e2.untilMillis)
        assertNull(NowPlaying.fromLagardere("""{"nowplaying":[]}"""))

        val king = """{"artist":"Derrick Parker","title":"Dead Sound","end_at":"2026-11-18T09:31:00+0000",
            "cover":"https://image.radioking.io/c.jpg","default_cover":false}"""
        val rk = NowPlaying.fromRadioking(king)!!
        assertEquals("Derrick Parker – Dead Sound", rk.label)
        assertEquals("https://image.radioking.io/c.jpg", rk.cover)
        assertEquals(java.time.Instant.parse("2026-11-18T09:31:00Z").toEpochMilli(), rk.untilMillis)

        val rtl = """{"title":"Ça peut vous arriver","hosts":"Julien Courbet","cover":"https://images.rtl.fr/c.jpg","schedule":{"end":1794997800000}}"""
        val program = NowPlaying.fromRtlProgram(rtl)!!
        assertEquals("Ça peut vous arriver – Julien Courbet", program.label)
        assertEquals(1_794_997_800_000L, program.untilMillis)
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
