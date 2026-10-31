package tv.nextv.core.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.model.Programme

class EpgImporterTest {

    // 2024-01-01T12:00:00Z
    private val now = 1_704_110_400_000L
    private val importer = EpgImporter({ KXmlParser() }, EpgImportOptions(batchSize = 2), clock = { now })

    private val channels = listOf(
        EpgChannelRef(1, tvgId = "TF1.fr", tvgName = null, name = "TF1"),
        EpgChannelRef(2, tvgId = null, tvgName = "France 2", name = "F2 HD"),
        EpgChannelRef(3, tvgId = "absent.fr", tvgName = null, name = "Arté"),
        EpgChannelRef(4, tvgId = null, tvgName = null, name = "Inconnue"),
    )

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="tf1.fr"><display-name>TF1</display-name><icon src="http://logo/tf1.png"/></channel>
          <channel id="france2.fr"><display-name lang="fr">France 2</display-name></channel>
          <channel id="arte.fr"><display-name>ARTE</display-name></channel>
          <channel id="m6.fr"><display-name>M6</display-name></channel>
          <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="tf1.fr">
            <title lang="fr">Journal &amp; météo</title><desc>Infos <b>du</b> jour</desc><category>News</category><category>Météo</category><category>news</category>
          </programme>
          <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="TF1.FR"><title>Film</title></programme>
          <programme start="20240101120000 +0100" channel="france2.fr"><title>Sans fin 1</title></programme>
          <programme start="20240101140000 +0100" channel="france2.fr"><title>Sans fin 2</title></programme>
          <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="arte.fr"><title>Doc</title></programme>
          <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="m6.fr"><title>Non associé</title></programme>
          <programme start="20231201120000 +0000" stop="20231201130000 +0000" channel="tf1.fr"><title>Trop ancien</title></programme>
          <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="tf1.fr"><title>Trop lointain</title></programme>
          <programme start="invalide" channel="tf1.fr"><title>Horaire illisible</title></programme>
        </tv>
    """.trimIndent()

    private suspend fun run(content: String, excluded: Set<String> = emptySet()): Pair<EpgImportResult, List<Programme>> {
        val written = mutableListOf<Programme>()
        val result = importer.import(content.byteInputStream(), channels, { written += it }, excluded)
        return result to written
    }

    @Test
    fun `provider prefixes, quality marks and plus signs do not prevent the match`() {
        val guide = listOf(
            XmltvChannel("Ligue1Plus.fr", listOf("Ligue 1+")),
            XmltvChannel("Ligue1Plus5.fr", listOf("Ligue 1+ 5")),
            XmltvChannel("CanalPlusFoot.fr", listOf("Canal+ Foot")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ 5 FHD"),
            EpgChannelRef(2, tvgId = "", tvgName = null, name = "|FR| LIGUE 1+ HD"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "FR: Canal Plus Foot"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ 6 FHD"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(mapOf(1L to "ligue1plus5.fr", 2L to "ligue1plus.fr", 3L to "canalplusfoot.fr"), mapping)
    }

    @Test
    fun `numbers with leading zeros, extra mentions and word order are tolerated`() {
        val guide = listOf(
            XmltvChannel("Ligue1Plus.fr", listOf("Ligue 1+")),
            XmltvChannel("Ligue1Plus5.fr", listOf("Ligue 1+ 5")),
            XmltvChannel("DAZN.fr", listOf("DAZN 1")),
            XmltvChannel("Arte.fr", listOf("Arte")),
            XmltvChannel("beINSPORTS1.fr", listOf("beIN SPORTS 1")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ 05"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ MATCH 5 FHD"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "FR| DAZN 1 LIVE"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "FR| BEIN SPORT 1"),
            // Mots qui changent la chaîne : pas d'association hasardeuse.
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "ARTE CONCERT"),
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ 6"),
            EpgChannelRef(7, tvgId = "", tvgName = null, name = "Ligue1Plus5"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(
            mapOf(1L to "ligue1plus5.fr", 2L to "ligue1plus5.fr", 3L to "dazn.fr", 4L to "beinsports1.fr", 7L to "ligue1plus5.fr"),
            mapping,
        )
    }

    @Test
    fun `ligue 1+ match channels of a provider are linked to the numbered guide channels`() {
        val guide = listOf(
            XmltvChannel("Ligue1Plus.fr", listOf("Ligue 1+")),
            XmltvChannel("Ligue1Plus4.fr", listOf("Ligue 1+ 4")),
            XmltvChannel("Ligue1Plus5.fr", listOf("Ligue 1+ 5")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "LIGUE 1+| MATCH 5 (2 AUDIOS)"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "LIGUE 1+| MATCH 4 FR (1 AUDIO StadiumFX)"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "FR| LIGUE 1+ LA CHAINE 4K"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "LIGUE 1+| MATCH 8 (FHD)"),
            // Affiche du dimanche soir : sur la chaîne principale.
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "LIGUE 1+| MATCH DU DIMANCHE SOIR (HD)"),
            // Pas une chaîne du guide : laissés sans guide.
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "LIGUE 1+| MULTIPLEX"),
            EpgChannelRef(7, tvgId = null, tvgName = null, name = "LIGUE 1+| MATCH DU JOUR"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(mapOf(1L to "ligue1plus5.fr", 2L to "ligue1plus4.fr", 3L to "ligue1plus.fr", 5L to "ligue1plus.fr"), mapping)
    }

    @Test
    fun `football programmes of sports channels missing from the playlist are kept`() = runBlocking {
        val guide = """
            <tv>
              <channel id="tf1.fr"><display-name>TF1</display-name></channel>
              <channel id="Ligue1Plus5.fr"><display-name>Ligue 1+ 5</display-name></channel>
              <channel id="m6.fr"><display-name>M6</display-name></channel>
              <channel id="beINSPORTS1.ca"><display-name>beIN Sports Canada</display-name></channel>
              <programme start="20240101190000 +0000" stop="20240101215000 +0000" channel="Ligue1Plus5.fr">
                <title>Football : Ligue 1 McDonald's | Troyes / Le Mans</title><category>Football</category>
              </programme>
              <programme start="20240101170000 +0000" stop="20240101190000 +0000" channel="Ligue1Plus5.fr"><title>Ligue 1+ 5</title><category>Magazine sportif</category></programme>
              <programme start="20240101190000 +0000" stop="20240101210000 +0000" channel="m6.fr"><title>Football : match amical</title></programme>
              <programme start="20240101190000 +0000" stop="20240101210000 +0000" channel="beINSPORTS1.ca"><title>Soccer</title></programme>
              <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="tf1.fr"><title>Journal</title></programme>
            </tv>
        """.trimIndent()
        val written = mutableListOf<Programme>()
        val result = importer.import(guide.byteInputStream(), channels, { written += it })

        assertEquals(mapOf(1L to "tf1.fr"), result.mapping)
        assertEquals(mapOf("ligue1plus5.fr" to "Ligue 1+ 5"), result.guideOnlyChannels)
        assertEquals(listOf("Football : Ligue 1 McDonald's | Troyes / Le Mans", "Journal"), written.sortedBy { it.epgChannelId }.map { it.title })
        assertEquals(setOf("tf1.fr"), result.coveredEpgIds)
    }

    @Test
    fun `matches channels by id then by name and filters programmes`() = runBlocking {
        val (result, programmes) = run(xml)

        assertEquals(mapOf(1L to "tf1.fr", 2L to "france2.fr", 3L to "arte.fr"), result.mapping)
        assertEquals(setOf("Journal & météo", "Film", "Sans fin 1", "Doc"), programmes.map { it.title }.toSet())
        assertEquals(4, result.programmeCount)
        assertEquals(1, result.skippedProgrammes)

        val news = programmes.first { it.title == "Journal & météo" }
        assertEquals("Infos du jour", news.description)
        assertEquals("News, Météo", news.category)
        assertEquals("tf1.fr", news.epgChannelId)
    }

    @Test
    fun `missing stop times come from the next programme and are never invented`() = runBlocking {
        val (_, programmes) = run(xml)

        val first = programmes.first { it.title == "Sans fin 1" }
        assertEquals(now - 3_600_000, first.startMillis)
        assertEquals(now + 3_600_000, first.stopMillis) // début de « Sans fin 2 »
        // « Sans fin 2 » n'a ni fin ni programme suivant : ignoré plutôt que doté d'une durée fictive.
        assertTrue(programmes.none { it.title == "Sans fin 2" })
    }

    @Test
    fun `channels already covered by a previous guide are skipped`() = runBlocking {
        val (result, programmes) = run(xml, excluded = setOf("tf1.fr"))

        assertNull(result.mapping[1L])
        assertTrue(programmes.none { it.epgChannelId == "tf1.fr" })
    }

    @Test
    fun `guide without channel declarations falls back to tvg ids`() = runBlocking {
        val (result, programmes) = run(
            """<tv><programme start="20240101120000" stop="20240101130000" channel="TF1.fr"><title>X</title></programme></tv>""",
        )

        assertEquals(mapOf(1L to "tf1.fr"), result.mapping)
        assertEquals(1, programmes.size)
    }

    @Test
    fun `non xmltv documents are rejected`() {
        val error = runCatching { runBlocking { run("<html><body>Not found</body></html>") } }.exceptionOrNull()

        assertEquals(SourceError.InvalidXmltv, (error as SourceException).error)
    }

    @Test
    fun `xmltv time parsing`() {
        assertEquals(now, XmltvTime.parse("20240101120000 +0000"))
        assertEquals(now, XmltvTime.parse("20240101130000 +0100"))
        assertEquals(now, XmltvTime.parse("20240101063000-0530"))
        assertEquals(now, XmltvTime.parse("202401011200"))
        assertEquals(now, XmltvTime.parse("20240101120000 Z"))
        assertEquals(951_825_600_000L, XmltvTime.parse("20000229120000 +0000")) // année bissextile
        assertNull(XmltvTime.parse("20241301120000"))
        assertNull(XmltvTime.parse("hier"))
        assertNull(XmltvTime.parse(null))
    }

    @Test
    fun `name normalization ignores accents case and punctuation`() {
        assertEquals("arteHD".lowercase(), EpgImporter.normalizeName("  Arté  H.D "))
        assertEquals(EpgImporter.normalizeName("France 2"), EpgImporter.normalizeName("FRANCE-2"))
    }

    @Test
    fun `production year is read from the date element`() {
        val xml = """
            <tv>
              <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>Film</title><date>1999</date></programme>
              <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="a"><title>Doc</title><date>20230514</date></programme>
              <programme start="20240101140000 +0000" stop="20240101150000 +0000" channel="a"><title>Sans date</title><date>bientôt</date></programme>
            </tv>
        """.trimIndent()
        val reader = XmltvReader(xml.byteInputStream()) { KXmlParser() }
        val years = generateSequence { reader.next() }.filterIsInstance<XmltvProgramme>().map { it.year }.toList()
        assertEquals(listOf(1999, 2023, null), years)
    }
}
