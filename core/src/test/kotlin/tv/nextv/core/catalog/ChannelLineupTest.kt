package tv.nextv.core.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.catalog.ChannelLineup.Section
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.source.FakeDocumentOpener
import tv.nextv.core.source.M3uPlaylistLoader

class ChannelLineupTest {

    private fun key(name: String) = LiveVariants.key(QualityParser.parse(name).variantKey)

    @Test
    fun `spellings of one name from one quality to another are one key`() {
        assertEquals(key("|FR| TELETOON +1 SD"), key("|FR| TELETOON+ 1 HD"))
        assertEquals(key("|FR| DISNEY CHANNEL+1 HD"), key("|FR| DISNEY CHANNEL +1 FHD"))
        assertEquals(key("|FR| CHASSE & PÊCHE SD"), key("|FR| CHASSE ET PECHE HD"))
        assertEquals(key("|FR| SEASON SD"), key("|FR| SEASONS HD"))
        assertEquals(key("|FR| DISCOVERY INVESTIGATIONS SD"), key("|FR| DISCOVERY INVESTIGATION HD"))
        assertEquals(key("|FR| PLANETE + SD"), key("|FR| PLANETE+ FHD"))
        assertEquals(key("CANAL PLUS"), key("CANAL+"))
        assertEquals(key("|FR| CANAL+ CINEMA.(S) HD"), key("|FR| CANAL+ CINEMAS FHD"))
        assertEquals(key("LIGUE 1+ MATCH 05"), key("LIGUE 1+ MATCH 5"))
        assertEquals(key("|FR| E! ENTERTAINMENT SD"), key("|FR| E! HD"))
        assertEquals(key("|FR| STANGRAY CLASSICA HD"), key("|FR| STINGRAY CLASSICA HD"))
        // Clés déjà enregistrées (favoris) inchangées.
        assertEquals("fr canal+", key("|FR| CANAL+ FHD"))
        assertEquals("fr tf1", key("|FR| TF1 FHD"))
        assertEquals("fr paris premiere", key("|FR| PARIS PREMIERE FHD"))
        assertEquals("fr france 3 paris ile de france", key("|FR| FRANCE 3 PARIS IDF HD"))
        // Chaînes différentes.
        assertNotEquals(key("|FR| DISNEY+ 1 (LIVE ONLY) FHD"), key("|FR| DISNEY CHANNEL +1 FHD"))
        assertNotEquals(key("|FR| FRANCE 2 FHD"), key("|FR| FRANCE 24 FHD"))
        assertNotEquals(key("|FR| BEIN SPORTS 1 FHD"), key("|FR| BEIN SPORTS 2 FHD"))
        assertNotEquals(key("|FR| TELETOON+ FHD"), key("|FR| TELETOON +1 FHD"))
    }

    @Test
    fun `separators of the playlist are recognised`() {
        listOf(
            "▀▄ FR ▀▄▀▄  REGIONALES ▄▀▄▀▄▀", "▀▄ FR ▀▄▀▄  CANAL+ ▄▀▄▀▄", "------▼|FR|-SPORTS-|FR|▼------",
            "▼|FR|BASKET LIGUE NATIONAL LIVE UNIQUEMENT|FR|▼", "|AR| ▀▄▀▄▀ [ BAHREIN ] ▀▄▀▄▀", "##### FRANCE #####", "★★★ SPORT ★★★",
        ).forEach { assertTrue(it, ChannelLineup.isSeparator(it)) }
        listOf(
            "|FR| TF1 HD", "|FR| CANAL+ CINEMA.(S) FHD+ (Resolution Exclusive)", "|FR| TURBO 24/24 SD", "|FR| E! HD", "|FR| A+ FHD",
            "|LIGUE 1+| MATCH 1 FHD+ (MULTI AUDIO StadiumFX)", "|FR| GLORY FIGHT FIGHT FIGHT !!! (PPV KICKBOXING PREMIUM)",
        ).forEach { assertFalse(it, ChannelLineup.isSeparator(it)) }
    }

    @Test
    fun `sections of channels by their name`() {
        assertEquals(ChannelLineup.Placement(Section.TNT, 30), ChannelLineup.placementOf("|FR| FRANCE 3 FHD"))
        assertEquals(ChannelLineup.Placement(Section.TNT, 31), ChannelLineup.placementOf("|FR| FRANCE 3 ALPES HD"))
        assertEquals(ChannelLineup.Placement(Section.TNT, 210), ChannelLineup.placementOf("|FR| LA CHAINE L'EQUIPE FHD"))
        assertEquals(ChannelLineup.Placement(Section.TNT, 250), ChannelLineup.placementOf("|FR| RMC LIFE FHD"))
        assertEquals(Section.CANAL, ChannelLineup.placementOf("|FR| CANAL+ CINEMA.(S) FHD DOLBY DIGITAL").section)
        assertEquals(Section.CINEMA, ChannelLineup.placementOf("|FR| CINE FRISSON FHD (DOUBLE AUDIO)").section)
        assertEquals(Section.SERIES, ChannelLineup.placementOf("|FR| ZONE INTERDITE 24/24 HD").section)
        assertEquals(Section.SPORT, ChannelLineup.placementOf("|FR| EQUIDIA LIVE FHD").section)
        assertEquals(Section.MUSIQUE, ChannelLineup.placementOf("|FR| MEZZO LIVE HD").section)
        assertEquals(Section.INFO, ChannelLineup.placementOf("|FR| EURONEWS").section)
        assertEquals(Section.REGIONAL, ChannelLineup.placementOf("|FR| BFM MARSEILLE FHD").section)
        assertEquals(Section.OUTREMER, ChannelLineup.placementOf("|FR| CANAL+ CARAIBES HD").section)
        listOf("|FR| CANAL+ LIVE 1 FHD", "|FR| DAZN PPV 3 FHD", "|FR| FANSEAT 1 (LIVE UNIQUEMENT) FHD", "|FR| F1TV ONBOARD 4 Lewis HAMILTON HD")
            .forEach { assertEquals(it, Section.EVENTS, ChannelLineup.placementOf(it).section) }
        // Autres pays : pas de rubrique, l'ordre de la playlist.
        listOf("|BE| LA UNE HD", "|PL| CANAL+ SPORT FHD", "|INT| NHK WORLD JAPAN FHD", "|C+AF| CANAL+ CINEMA")
            .forEach { assertEquals(it, Section.OTHER, ChannelLineup.placementOf(it).section) }
    }

    /** Playlist rangée comme celle d'un fournisseur courant (vrais noms), lue par le vrai chargeur. */
    private fun load(vararg entries: Pair<String, String>): List<Channel> {
        val text = buildString {
            appendLine("#EXTM3U")
            entries.forEachIndexed { index, (group, name) ->
                appendLine("#EXTINF:-1 group-title=\"$group\",$name")
                appendLine("http://example.com/live/u/p/$index.ts")
            }
        }
        val opener = CompositeContentOpener(listOf(FakeDocumentOpener(mapOf("doc" to text.toByteArray()))))
        return runBlocking {
            M3uPlaylistLoader(opener).load(PlaylistSource(name = "P", location = SourceLocation.LocalDocument("doc", "p.m3u")), null).channels
        }
    }

    /** Une ligne par chaîne (sa meilleure version), sans séparateurs ni éditions régionales, dans l'ordre rangé. */
    private fun lineup(channels: List<Channel>): List<String> = channels
        .filter { it.kind == ContentKind.LIVE && !LiveVariants.isSeparatorKey(it.variantKey) && FranceRegions.regionOf(it.name) == null }
        .groupBy { it.variantKey }.values
        .map { versions -> versions.minWith(compareByDescending<Channel> { StreamQuality.rankOf(it.quality) }.thenBy { it.position }) }
        .sortedBy { it.lineup }
        .map { it.title.removePrefix("FR| ").removePrefix("LIGUE 1+| ").removePrefix("BE| ") }

    @Test
    fun `channels are listed like on a television`() {
        val channels = load(
            "FR TV SD" to "|FR| TF1 SD", "FR TV HD" to "|FR| TF1 HD", "FR TV FULL HD|4K" to "|FR| TF1 FHD",
            "FR TV FULL HD|4K" to "|FR| TF1 4K HDR UHD (Résolution Exclus)", "FR TV FULL HD|4K" to "|FR| FRANCE 2 FHD",
            "FR TV FULL HD|4K" to "|FR| FRANCE 3 FHD", "FR TV FULL HD|4K" to "|FR| FRANCE 4 FHD", "FR TV FULL HD|4K" to "|FR| FRANCE 5 FHD",
            "FR TV FULL HD|4K" to "|FR| M6 FHD", "FR TV FULL HD|4K" to "|FR| T18 FHD", "FR TV FULL HD|4K" to "|FR| ARTE FHD",
            "FR TV FULL HD|4K" to "|FR| W9 FHD", "FR TV FULL HD|4K" to "|FR| STAR ACADEMY FHD", "FR TV FULL HD|4K" to "|FR| TMC FHD",
            "FR TV FULL HD|4K" to "|FR| TFX FHD", "FR TV FULL HD|4K" to "|FR| NOVO19 FHD", "FR TV HD" to "|FR| TURBO 24/24 HD",
            "FR TV FULL HD|4K" to "|FR| LCP FHD", "FR TV FULL HD|4K" to "|FR| BFM TV FHD", "FR TV HD" to "|FR| BFM BUSINESS FHD/HD",
            "FR TV FULL HD|4K" to "|FR| CNEWS FHD", "FR TV FULL HD|4K" to "|FR| LCI FHD", "FR TV FULL HD|4K" to "|FR| FRANCE INFO FHD",
            "FR TV FULL HD|4K" to "|FR| FRANCE 24 FHD", "FR TV FULL HD|4K" to "|FR| CSTAR FHD", "FR TV FULL HD|4K" to "|FR| TF1 SERIES FILMS FHD",
            "FR TV FULL HD|4K" to "|FR| 6TER FHD", "FR TV FULL HD|4K" to "|FR| RMC STORY FHD", "FR TV FULL HD|4K" to "|FR| RMC LIFE FHD",
            "FR TV FULL HD|4K" to "|FR| PARIS PREMIERE FHD", "FR TV FULL HD|4K" to "|FR| KTO FHD",
            "FR TV FULL HD|4K" to "▀▄ FR ▀▄▀▄  REGIONALES ▄▀▄▀▄▀", "FR TV FULL HD|4K" to "|FR| FRANCE 3 ALPES FHD",
            "FR TV FULL HD|4K" to "|FR| TV8 MONT BLANC FHD", "FR TV FULL HD|4K" to "|FR| BFM MARSEILLE FHD",
            "FR TV HD" to "▀▄ FR ▀▄▀▄▀▄  OUTRE MER ▀▄▀▄", "FR TV HD" to "|FR| CANAL+ CARAIBES HD", "FR TV HD" to "|FR| MARTINIQUE 1ERE HD",
            "FR TV FULL HD|4K" to "▀▄ FR ▀▄▀▄  CANAL+ ▄▀▄▀▄▀", "FR TV FULL HD|4K" to "|FR| CANAL+ FHD (DOUBLE AUDIO)",
            "FR TV FULL HD|4K" to "|FR| CANAL+ BOX OFFICE FHD", "FR TV FULL HD|4K" to "|FR| CANAL+ GRAND ECRAN FHD",
            "FR TV FULL HD|4K" to "|FR| CANAL+ KIDS FHD (DOUBLE AUDIO)", "FR TV FULL HD|4K" to "|FR| CANAL+ CINEMA.(S) FHD (DOUBLE AUDIO)",
            "FR TV CINEMA FHD ( DOLBY DIGITAL)" to "|FR| CANAL+ CINEMA.(S) FHD DOLBY DIGITAL",
            "FR SPORTS (France)" to "|FR| CANAL+ SPORT FHD", "FR SPORTS (France)" to "|FR| CANAL+ FOOT FHD",
            "FR CANAL+ LIVE | HBOX MAX (France)" to "|FR| CANAL+ LIVE 1 FHD", "FR SPORTS (France)" to "|FR| CANAL+ SPORT 360 FHD",
            "FR SPORTS (France)" to "|FR| CANAL+ SPORT 1 HD", "FR SPORTS (France)" to "|FR| CANAL+ SPORT 2 HD",
            "FR SPORTS (France)" to "|FR| CANAL+ SPORT 3 HD", "FR SPORTS (France)" to "|FR| CANAL+ SPORT 4 HD",
            "FR TV FULL HD|4K" to "▀▄ FR ▀▄▀▄  CINEMA  ▄▀▄▀▄▀", "FR TV FULL HD|4K" to "|FR| CINE FAMILY FHD (DOUBLE AUDIO)",
            "FR TV FULL HD|4K" to "|FR| CINE FRISSON FHD (DOUBLE AUDIO)", "FR TV FULL HD|4K" to "|FR| DRIVE IN MOVIE FHD",
            "FR TV FULL HD|4K" to "▀▄ FR ▀▄▀▄  DECOUVERTE ▄▀▄▀▄▀", "FR TV FULL HD|4K" to "|FR| RMC DECOUVERTE FHD",
            "FR TV FULL HD|4K" to "|FR| NATIONAL GEO FHD", "FR TV FULL HD|4K" to "|FR| TOP SANTE FHD",
            "FR TV FULL HD|4K" to "▀▄ FR ▀▄▀▄  JEUNESSE ▄▀▄▀▄▀", "FR TV FULL HD|4K" to "|FR| GULLI FHD",
            "FR TV FULL HD|4K" to "|FR| CANAL J FHD", "FR TV HD" to "▀▄ FR ▀▄▀▄▀▄  JEUNESSE  ▄▀▄▀▄▀", "FR TV HD" to "|FR| DRAGON BALL HD",
            "" to "------▼|FR|-SPORTS-|FR|▼------",
            "LIGUE 1+ FRANCE | DAZN| MAGNUS TV | FANSEAT FRANCE" to "|FR| LIGUE 1+ LA CHAINE FHD",
            "LIGUE 1+ FRANCE | DAZN| MAGNUS TV | FANSEAT FRANCE" to "|LIGUE 1+| MATCH 1 FHD+ (MULTI AUDIO StadiumFX)",
            "FR SPORTS (France)" to "|FR| LA CHAINE L'EQUIPE FHD", "FR SPORTS (France)" to "|FR| BEIN SPORTS 1 FHD",
            "FR SPORTS (France)" to "|FR| ZACK NANI FOOT FHD", "FR SPORTS (France)" to "|FR| EUROSPORT 1 FHD",
            "PLUTO TV (FRANCE)" to "▀▄ PLUTO TV ▀▄ FILMS ▄▀▄", "PLUTO TV (FRANCE)" to "|FR| CINÉ WESTERN BY PLUTO TV HD",
            "PLUTO TV (FRANCE)" to "|FR| EURONEWS HD", "BELGES (FR-SPORTS-FLAMAND)" to "|BE| LA UNE FHD",
        )
        assertEquals(
            listOf(
                // TNT dans l'ordre des numéros.
                "TF1", "FRANCE 2", "FRANCE 3", "FRANCE 4", "FRANCE 5", "M6", "ARTE", "LCP", "W9", "TMC", "TFX", "GULLI", "BFM TV", "CNEWS",
                "LCI", "FRANCE INFO", "CSTAR", "T18", "NOVO19", "TF1 SERIES FILMS", "LA CHAINE L'EQUIPE", "6TER", "RMC STORY",
                "RMC DECOUVERTE", "RMC LIFE",
                // Canal+.
                "CANAL+", "CANAL+ CINEMA.(S)", "CANAL+ SPORT", "CANAL+ FOOT", "CANAL+ SPORT 360", "CANAL+ GRAND ECRAN", "CANAL+ BOX OFFICE",
                "CANAL+ KIDS",
                // Cinéma (connues, puis celles de la rubrique).
                "CINE FRISSON", "CINE FAMILY", "DRIVE IN MOVIE",
                // Séries et divertissement.
                "PARIS PREMIERE", "STAR ACADEMY", "TURBO 24/24",
                // Sport.
                "BEIN SPORTS 1", "EUROSPORT 1", "LIGUE 1+ LA CHAINE", "ZACK NANI FOOT",
                // Découverte, jeunesse, info.
                "NATIONAL GEO", "TOP SANTE", "CANAL J", "DRAGON BALL", "BFM BUSINESS", "FRANCE 24", "EURONEWS",
                // Régions, outre-mer.
                "TV8 MONT BLANC", "BFM MARSEILLE", "CANAL+ CARAIBES", "MARTINIQUE 1ERE",
                // Chaînes « événement ».
                "CANAL+ LIVE 1", "CANAL+ SPORT 1", "CANAL+ SPORT 2", "CANAL+ SPORT 3", "CANAL+ SPORT 4", "MATCH 1 (StadiumFX)",
                // Autres chaînes françaises, puis les autres pays.
                "KTO", "CINÉ WESTERN BY PLUTO TV", "LA UNE",
            ),
            lineup(channels),
        )
    }

    @Test
    fun `a playlist without country prefix is ranged by the names of its channels`() {
        val channels = load(
            "Généralistes" to "M6 HD", "Généralistes" to "Canal+ HD", "Généralistes" to "France 2 HD", "Généralistes" to "TF1 HD",
            "Généralistes" to "Paris Première HD", "Sport" to "beIN Sports 1 HD", "Jeunesse" to "Une chaîne pour enfants",
        )
        assertEquals(listOf("TF1", "France 2", "M6", "Canal+", "Paris Première", "beIN Sports 1", "Une chaîne pour enfants"), lineup(channels))
    }
}
