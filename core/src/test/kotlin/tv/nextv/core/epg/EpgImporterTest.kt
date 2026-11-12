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
            <icon src="https://images.example/journal.jpg"/>
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
    fun `a channel from another country never takes the French programme of the same name`() {
        val guide = listOf(
            XmltvChannel("DAZN.fr", listOf("DAZN 1")),
            XmltvChannel("beINSPORTS1.fr", listOf("beIN SPORTS 1")),
            XmltvChannel("ZDF.de", listOf("ZDF")),
            XmltvChannel("AB3.fr", listOf("AB3")),
            XmltvChannel("LaUne.be", listOf("La Une")),
            XmltvChannel("MaChaineSport.fr", listOf("Ma Chaine Sport")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "|DE| DAZN 1 HD"),
            EpgChannelRef(2, tvgId = "beinsports1.fr", tvgName = null, name = "|AR| BEIN SPORTS 1 HD"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "|DE| ZDF HD"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "|PL| DAZN 1"),
            // Belgique : ses chaînes et les françaises qu'elle reprend.
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "|BE| AB3 FHD"),
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "|BE| LA UNE HD"),
            EpgChannelRef(7, tvgId = null, tvgName = null, name = "|FR| DAZN 1 HD"),
            EpgChannelRef(8, tvgId = null, tvgName = null, name = "|INT| DAZN 1"),
            // « MA » sans séparateur : un mot du nom, pas le Maroc.
            EpgChannelRef(9, tvgId = null, tvgName = null, name = "MA CHAINE SPORT HD"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(
            mapOf(3L to "zdf.de", 5L to "ab3.fr", 6L to "laune.be", 7L to "dazn.fr", 8L to "dazn.fr", 9L to "machainesport.fr"),
            mapping,
        )
    }

    @Test
    fun `other names used by the playlist for guide channels`() {
        val guide = listOf(
            XmltvChannel("ProvenceAzur.fr", listOf("BFM MARSEILLE PROVENCE")),
            XmltvChannel("AzurTV.fr", listOf("BFM NICE COTE D'AZUR")),
            XmltvChannel("CinePlusFamiz.fr", listOf("Ciné+ Family")),
            XmltvChannel("CinePlusPremier.fr", listOf("OCS")),
            XmltvChannel("NatGeoWild.fr", listOf("NatGeoWild")),
            XmltvChannel("Guyane1.fr", listOf("Guyane 1ère")),
            XmltvChannel("RMCSport4.fr", listOf("RMCSport4.fr")),
            XmltvChannel("Melody.fr", listOf("MELODY TV")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "|FR| BFM MARSEILLE HD"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "|FR| BFM NICE FHD"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "|FR| CINE FAMILY FHD+ (Resolution Exclusive)"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "|FR| CINE OCS HD"),
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "|FR| NATIONAL GEO WILD FHD+"),
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "|FR| GUYANE PREMIERE HD"),
            EpgChannelRef(7, tvgId = null, tvgName = null, name = "|FR| RMC SPORT LIVE 4 FHD"),
            EpgChannelRef(8, tvgId = null, tvgName = null, name = "|FR| TELE MELODY HD"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(
            mapOf(
                1L to "provenceazur.fr", 2L to "azurtv.fr", 3L to "cineplusfamiz.fr", 4L to "cinepluspremier.fr",
                5L to "natgeowild.fr", 6L to "guyane1.fr", 7L to "rmcsport4.fr", 8L to "melody.fr",
            ),
            mapping,
        )
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
    fun `channels named differently by the provider are linked to the guide`() {
        val guide = listOf(
            XmltvChannel("TV5Monde.fr", listOf("TV5 Monde")),
            XmltvChannel("TV5MondeInfo.fr", listOf("TV5 Monde Info")),
            XmltvChannel("LEquipe21.fr", listOf("La chaine l’Équipe")),
            XmltvChannel("FranceInfo.fr", listOf("franceinfo:")),
            XmltvChannel("Europe1TV.fr", listOf("Europe 1 TV")),
            XmltvChannel("BSmartTV.fr", listOf("BSmartTV")),
            XmltvChannel("CanalPlusCinema.ch", listOf("Canal+ Cinema")),
            XmltvChannel("CanalPlusCinema.fr", listOf("Canal+ Cinema")),
            XmltvChannel("ScienceEtVieTV.fr", listOf("Science et Vie TV")),
            XmltvChannel("Turbo2424.fr", listOf("Turbo 24/24")),
            XmltvChannel("NationalGeographic.fr", listOf("National Geographic")),
            XmltvChannel("PLANETEJustice.fr", listOf("Planète+ Crime Investigation")),
            XmltvChannel("DiscoveryChannel.fr", listOf("Discovery Channel")),
            XmltvChannel("LaUne.be", listOf("La Une")),
        )
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "FR| TV5 NEWS"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "FR| L'EQUIPE FHD"),
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "FR| FRANCE INFO TV"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "FR| EUROPE 1"),
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "FR| B SMART"),
            // Même nom en France et en Suisse : la chaîne française.
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "FR| CANAL+ CINEMA(S) FHD"),
            EpgChannelRef(7, tvgId = null, tvgName = null, name = "FR| SCIENCE & VIE TV"),
            EpgChannelRef(8, tvgId = null, tvgName = null, name = "FR| TURBO"),
            EpgChannelRef(9, tvgId = null, tvgName = null, name = "FR| NAT GEO HD"),
            EpgChannelRef(10, tvgId = null, tvgName = null, name = "FR| PLANETE+ CI"),
            EpgChannelRef(11, tvgId = null, tvgName = null, name = "FR| DISCOVERY FHD"),
            EpgChannelRef(12, tvgId = null, tvgName = null, name = "BE| RTBF LA UNE"),
            EpgChannelRef(13, tvgId = null, tvgName = null, name = "FR| TV5 MONDE FBS"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(
            mapOf(
                1L to "tv5mondeinfo.fr", 2L to "lequipe21.fr", 3L to "franceinfo.fr", 4L to "europe1tv.fr", 5L to "bsmarttv.fr",
                6L to "canalpluscinema.fr", 7L to "scienceetvietv.fr", 8L to "turbo2424.fr", 9L to "nationalgeographic.fr",
                10L to "planetejustice.fr", 11L to "discoverychannel.fr", 12L to "laune.be", 13L to "tv5monde.fr",
            ),
            mapping,
        )
    }

    @Test
    fun `france 3 regional editions are linked whatever their spelling`() {
        val guide = listOf(
            XmltvChannel("France3.fr", listOf("France 3")),
            XmltvChannel("France3BasseNormandie.fr", listOf("France 3 Basse-Normandie")),
            XmltvChannel("France3HauteNormandie.fr", listOf("France 3 Haute-Normandie")),
            XmltvChannel("France3LanguedocRoussillon.fr", listOf("France 3 Languedoc-Roussillon")),
            XmltvChannel("France3ChampagneArdenne.fr", listOf("France 3 Champagne-Ardenne")),
            XmltvChannel("France3NordPasDeCalais.fr", listOf("France 3 Nord-Pas-de-Calais")),
            XmltvChannel("France3PoitouCharentes.fr", listOf("France 3 Poitou-Charentes")),
            XmltvChannel("France3ProvenceAlpes.fr", listOf("France 3 Provence-Alpes")),
            XmltvChannel("France3RhoneAlpes.fr", listOf("France 3 Rhône-Alpes")),
            XmltvChannel("France3Alpes.fr", listOf("France 3 Alpes")),
            // Écrit seulement par son identifiant.
            XmltvChannel("France3ParisIleDeFrance.fr", emptyList()),
            XmltvChannel("France3PaysDeLaLoire.fr", listOf("France 3 Pays de la Loire")),
            XmltvChannel("France2.fr", listOf("France 2")),
        )
        // Les noms des photos, sans programme jusque-là.
        val names = listOf(
            "FR| FRANCE 3 BNORMANDIE HD" to "france3bassenormandie.fr",
            "FR| FRANCE 3 HAUTE-NORMANDIE FHD" to "france3hautenormandie.fr",
            "FR| FRANCE 3 LANGUE-DOC HD" to "france3languedocroussillon.fr",
            "FR| FRANCE 3 LANGUEDOC-ROUSILLON FHD" to "france3languedocroussillon.fr",
            "FR| FRANCE 3 CHAMP ARDENNE HD" to "france3champagneardenne.fr",
            "FR| FRANCE 3 NORD PDC HD" to "france3nordpasdecalais.fr",
            "FR| FRANCE 3 POITOUCHAR HD" to "france3poitoucharentes.fr",
            "FR| FRANCE 3 POTOU-CHARENTES FHD" to "france3poitoucharentes.fr",
            "FR| FRANCE 3 PROV ALPES HD" to "france3provencealpes.fr",
            "FR| FRANCE 3 RHONES-ALPES FHD" to "france3rhonealpes.fr",
            "FR| FRANCE 3 ALPES FHD" to "france3alpes.fr",
            "FR| FRANCE 3 PARIS IDF HD" to "france3parisiledefrance.fr",
            "FR| FRANCE 3 Pays de Loire FHD" to "france3paysdelaloire.fr",
            // Antenne absente du guide : le programme national, le même hors décrochages.
            "FR| FRANCE 3 PICARDIE FHD" to "france3.fr",
            "FR| FRANCE 3 FHD" to "france3.fr",
            "FR| FRANCE 2 FHD" to "france2.fr",
        )
        val channels = names.mapIndexed { index, (name, _) -> EpgChannelRef(index.toLong(), tvgId = null, tvgName = null, name = name) }

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        for ((index, entry) in names.withIndex()) assertEquals(entry.first, entry.second, mapping[index.toLong()])
    }

    @Test
    fun `le figaro tv ile de france is linked under its names`() {
        val channels = listOf(
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "FR| LE FIGARO TV IDF HD"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "FR| LE FIGARO IDF FHD"),
        )
        val importer = EpgImporter(parserFactory = { KXmlParser() })
        // Guide à jour, ou resté à l'ancien nom de la chaîne.
        for (guide in listOf(XmltvChannel("LeFigaroTVIDF.fr", listOf("Le Figaro TV Île-de-France")), XmltvChannel("BFMParis.fr", listOf("BFM Paris Île-de-France")))) {
            val other = XmltvChannel("BFMTV.fr", listOf("BFM TV"))
            assertEquals(guide.id, mapOf(1L to guide.id.lowercase(), 2L to guide.id.lowercase()), importer.match(channels, listOf(other, guide), emptySet()))
        }
    }

    @Test
    fun `a name that only looks like a guide channel is left without guide`() {
        val guide = listOf(
            XmltvChannel("InfoTV.af", listOf("InfoTV")),
            XmltvChannel("Arte.fr", listOf("Arte")),
            XmltvChannel("France2.fr", listOf("France 2")),
            XmltvChannel("France24.fr", listOf("France 24")),
            XmltvChannel("Europe1TV.fr", listOf("Europe 1 TV")),
            XmltvChannel("TV5Monde.fr", listOf("TV5 Monde")),
            XmltvChannel("EvasionCanada.ca", listOf("Evasion Channel")),
            XmltvChannel("EvasionAfrique.af", listOf("Evasion TV")),
        )
        val channels = listOf(
            // « La chaîne info » (LCI) : pas « Info TV ».
            EpgChannelRef(1, tvgId = null, tvgName = null, name = "FR| LA CHAINE INFO"),
            EpgChannelRef(2, tvgId = null, tvgName = null, name = "FR| ARTE CONCERT"),
            // Les numéros ne se collent pas : « 2 » puis « 4K » n'est pas « 24 ».
            EpgChannelRef(3, tvgId = null, tvgName = null, name = "FR| FRANCE 2 4K"),
            EpgChannelRef(4, tvgId = null, tvgName = null, name = "FR| EUROPE 2"),
            EpgChannelRef(5, tvgId = null, tvgName = null, name = "FR| TV5 MONDE CINEMA"),
            // Deux chaînes étrangères du même nom, aucune française : dans le doute, rien.
            EpgChannelRef(6, tvgId = null, tvgName = null, name = "FR| EVASION"),
        )

        val mapping = EpgImporter(parserFactory = { KXmlParser() }).match(channels, guide, emptySet())

        assertEquals(mapOf(3L to "france2.fr"), mapping)
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
        // Image du programme, quand le guide en donne une.
        assertEquals("https://images.example/journal.jpg", news.imageUrl)
        assertEquals(null, programmes.first { it.title == "Film" }.imageUrl)
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

    @Test
    fun `unknown genres are not kept`() {
        val xml = """
            <tv>
              <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>Enquêtes</title><category>Inconnu</category></programme>
              <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="a"><title>Doc</title><category>Unknown</category><category>Documentaire</category></programme>
            </tv>
        """.trimIndent()
        val reader = XmltvReader(xml.byteInputStream()) { KXmlParser() }
        val genres = generateSequence { reader.next() }.filterIsInstance<XmltvProgramme>().map { it.category }.toList()
        assertEquals(listOf(null, "Documentaire"), genres)
    }
}
