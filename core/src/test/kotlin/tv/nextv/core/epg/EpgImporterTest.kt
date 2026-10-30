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
            <title lang="fr">Journal &amp; météo</title><desc>Infos <b>du</b> jour</desc><category>News</category>
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
    fun `matches channels by id then by name and filters programmes`() = runBlocking {
        val (result, programmes) = run(xml)

        assertEquals(mapOf(1L to "tf1.fr", 2L to "france2.fr", 3L to "arte.fr"), result.mapping)
        assertEquals(setOf("Journal & météo", "Film", "Sans fin 1", "Doc"), programmes.map { it.title }.toSet())
        assertEquals(4, result.programmeCount)
        assertEquals(1, result.skippedProgrammes)

        val news = programmes.first { it.title == "Journal & météo" }
        assertEquals("Infos du jour", news.description)
        assertEquals("News", news.category)
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
}
