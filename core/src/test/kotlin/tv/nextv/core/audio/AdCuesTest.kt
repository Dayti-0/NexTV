package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pages de pub annoncées par le flux : balises HLS réelles (RMC Story, 15 novembre 2026) et formats courants. */
class AdCuesTest {

    @Test
    fun nothingAnnouncedSaysNothing() {
        val cues = AdCues()
        cues.hlsPlaylist(listOf("#EXT-X-PROGRAM-DATE-TIME:2026-11-15T13:06:31.680Z", "#EXTINF:5.76,", "#EXTINF:5.76,"), 0)
        assertEquals(CueState.NONE, cues.state(1_000))
    }

    @Test
    fun dateRangeWithScte35OutOpensABreakAtItsDate() {
        val cues = AdCues()
        // Premier morceau daté à 13:11:10.000, page de pubs annoncée à 13:11:17.967 pour 183,2 s.
        val tags = listOf(
            "#EXT-X-PROGRAM-DATE-TIME:2026-11-15T13:11:10.000Z",
            "#EXTINF:5.76,",
            "#EXT-X-DATERANGE:ID=\"splice-0000E4CC\",START-DATE=\"2026-11-15T13:11:17.967Z\",DURATION=183.2,SCTE35-OUT=0xFC3025",
            "#EXTINF:5.76,",
            "#EXTINF:5.76,",
        )
        cues.hlsPlaylist(tags, 100_000)
        assertEquals(CueState.PROGRAMME, cues.state(107_900))
        assertEquals(CueState.BREAK, cues.state(108_000))
        assertEquals(CueState.BREAK, cues.state(291_000))
        assertEquals(CueState.PROGRAMME, cues.state(291_200))
        // La même balise revue dans la liste suivante (décalée d'un morceau) : toujours une seule page.
        cues.hlsPlaylist(tags.drop(2).let { listOf("#EXT-X-PROGRAM-DATE-TIME:2026-11-15T13:11:15.760Z") + it }, 105_760)
        assertEquals(1, cues.intervals().size)
    }

    @Test
    fun cueOutAndCueInMarkSegments() {
        val cues = AdCues()
        cues.hlsPlaylist(listOf("#EXTINF:6,", "#EXT-X-CUE-OUT:30", "#EXTINF:6,", "#EXTINF:6,"), 0)
        assertEquals(CueState.PROGRAMME, cues.state(5_000))
        assertEquals(CueState.BREAK, cues.state(6_000))
        assertEquals(CueState.BREAK, cues.state(35_000))
        assertEquals(CueState.PROGRAMME, cues.state(36_000))

        val open = AdCues()
        open.hlsPlaylist(listOf("#EXTINF:6,", "#EXT-X-CUE-OUT", "#EXTINF:6,", "#EXTINF:6,"), 0)
        assertEquals(CueState.BREAK, open.state(100_000))
        open.hlsPlaylist(listOf("#EXTINF:6,", "#EXT-X-CUE-IN", "#EXTINF:6,"), 120_000)
        assertEquals(CueState.BREAK, open.state(125_000))
        assertEquals(CueState.PROGRAMME, open.state(126_000))
    }

    @Test
    fun joiningInTheMiddleOfABreak() {
        val cues = AdCues()
        cues.hlsPlaylist(listOf("#EXT-X-CUE-OUT-CONT:ElapsedTime=12.0,Duration=30", "#EXTINF:6,"), 50_000)
        assertEquals(CueState.BREAK, cues.state(50_000))
        assertEquals(CueState.PROGRAMME, cues.state(68_500))

        val short = AdCues()
        short.hlsPlaylist(listOf("#EXT-X-CUE-OUT-CONT:10/40", "#EXTINF:6,"), 0)
        assertEquals(CueState.BREAK, short.state(29_000))
        assertEquals(CueState.PROGRAMME, short.state(30_500))
    }

    @Test
    fun spliceInsertFromAContinuousStream() {
        val cues = AdCues()
        cues.splice(out = true, atMs = 10_000, durationMs = 120_000)
        assertEquals(CueState.PROGRAMME, cues.state(9_000))
        assertEquals(CueState.BREAK, cues.state(60_000))
        assertEquals(CueState.PROGRAMME, cues.state(131_000))
        cues.splice(out = true, atMs = 400_000, durationMs = null)
        cues.splice(out = false, atMs = 500_000, durationMs = null)
        assertEquals(CueState.BREAK, cues.state(499_000))
        assertEquals(CueState.PROGRAMME, cues.state(500_000))
    }

    @Test
    fun isoDatesWithOffsets() {
        val a = AdCues()
        a.hlsPlaylist(
            listOf(
                "#EXT-X-PROGRAM-DATE-TIME:2026-11-15T14:00:00+01:00",
                "#EXTINF:4,",
                "#EXT-X-DATERANGE:ID=\"x\",START-DATE=\"2026-11-15T13:00:10.5Z\",PLANNED-DURATION=20,SCTE35-OUT=0xFC",
                "#EXTINF:4,",
            ),
            0,
        )
        assertEquals(CueState.PROGRAMME, a.state(10_400))
        assertEquals(CueState.BREAK, a.state(10_600))
        assertEquals(CueState.PROGRAMME, a.state(30_600))
    }
}
