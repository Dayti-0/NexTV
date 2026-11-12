package tv.nextv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.EpisodeInfo
import tv.nextv.core.model.StreamQuality

class QualityParserTest {

    @Test
    fun `quality tags are detected and removed from the base name`() {
        val fhd = QualityParser.parse("FR| TF1 FHD")
        val hd = QualityParser.parse("FR| TF1 HD")
        val sd = QualityParser.parse("FR| TF1 SD")
        val low = QualityParser.parse("FR| TF1 LOW")

        assertEquals(StreamQuality.FHD, fhd.quality)
        assertEquals(StreamQuality.HD, hd.quality)
        assertEquals(StreamQuality.SD, sd.quality)
        assertEquals(StreamQuality.LOW, low.quality)
        assertEquals("FR| TF1", fhd.baseName)
        assertEquals(fhd.variantKey, hd.variantKey)
        assertEquals(hd.variantKey, low.variantKey)
    }

    @Test
    fun `superscript and bracketed tags`() {
        assertEquals(StreamQuality.FHD, QualityParser.parse("TF1 ᶠᴴᴰ").quality)
        assertEquals(StreamQuality.HD, QualityParser.parse("TF1ᴴᴰ").quality)
        assertEquals(StreamQuality.UHD, QualityParser.parse("France 2 [4K]").quality)
        assertEquals(StreamQuality.HD, QualityParser.parse("M6 (720p)").quality)
        assertEquals(StreamQuality.FHD, QualityParser.parse("Arte Full HD").quality)
        assertEquals("TF1", QualityParser.parse("TF1ᴴᴰ").baseName)
        assertEquals(QualityParser.parse("TF1 ᶠᴴᴰ").variantKey, QualityParser.parse("TF1 HD").variantKey)
    }

    @Test
    fun `names without quality keep no quality and are not mistaken`() {
        val plain = QualityParser.parse("HD1")
        assertNull(plain.quality)
        assertEquals("HD1", plain.baseName)
        assertNull(QualityParser.parse("Arte").quality)
        assertNotEquals(QualityParser.parse("TF1 HD").variantKey, QualityParser.parse("TF1 Séries Films HD").variantKey)
    }

    @Test
    fun `hdr and exclusive versions are grouped with the channel`() {
        val tf1 = QualityParser.parse("FR| TF1").variantKey
        val hdr = QualityParser.parse("FR| TF1 HDR (EXCLUS)")
        assertEquals(tf1, hdr.variantKey)
        assertEquals(StreamQuality.UHD, hdr.quality)
        assertEquals("FR| TF1", hdr.baseName)
        assertEquals(QualityParser.parse("FR| M6").variantKey, QualityParser.parse("FR| M6 HDR (EXCLUS)").variantKey)
        assertEquals(QualityParser.parse("FR| M6").variantKey, QualityParser.parse("FR| M6 UHD").variantKey)
        assertEquals(QualityParser.parse("DE| RTL").variantKey, QualityParser.parse("DE| RTL HD+").variantKey)
        // Chaînes différentes : jamais mélangées.
        assertNotEquals(tf1, QualityParser.parse("FR| TF1 Séries Films HDR").variantKey)
        assertNotEquals(QualityParser.parse("FR| France 2").variantKey, QualityParser.parse("FR| France 3 HDR").variantKey)
    }

    @Test
    fun `backup and very high resolution versions are grouped with the channel`() {
        val rmc = QualityParser.parse("FR| RMC SPORT 1 FHD")
        val backup = QualityParser.parse("FR| RMC SPORT 1 BKP FHD")
        assertEquals(rmc.variantKey, backup.variantKey)
        assertEquals(StreamQuality.FHD, backup.quality)
        assertEquals("FR| RMC SPORT 1", backup.baseName)
        // Secours seulement en HD : une version HD de la même chaîne.
        val backupHd = QualityParser.parse("FR| RMC SPORT 1 BKP HD")
        assertEquals(rmc.variantKey, backupHd.variantKey)
        assertEquals(StreamQuality.HD, backupHd.quality)
        assertEquals(rmc.variantKey, QualityParser.parse("FR| RMC SPORT 1 (BACKUP2)").variantKey)

        val bein = QualityParser.parse("FR| BEIN SPORTS 2 FHD")
        val veryHigh = QualityParser.parse("FR| BEIN SPORTS 2 FHD++ (Trés haute résolution)")
        assertEquals(bein.variantKey, veryHigh.variantKey)
        assertEquals(StreamQuality.FHD, veryHigh.quality)
        assertEquals("FR| BEIN SPORTS 2", veryHigh.baseName)
        // Chaînes différentes : jamais mélangées.
        assertNotEquals(rmc.variantKey, QualityParser.parse("FR| RMC SPORT ACCESS 1 FHD").variantKey)
        assertNotEquals(bein.variantKey, QualityParser.parse("FR| BEIN SPORTS 3 FHD++ (Trés haute résolution)").variantKey)
        assertEquals("FR| CANAL+", QualityParser.parse("FR| CANAL+ FHD").baseName)
    }

    @Test
    fun `haute stays in a channel name unless it qualifies the resolution`() {
        // « France 3 Haute-Normandie FHD » s'affichait « France 3 -Normandie ».
        val region = QualityParser.parse("FR| FRANCE 3 HAUTE-NORMANDIE FHD")
        assertEquals("FR| FRANCE 3 HAUTE-NORMANDIE", region.baseName)
        assertEquals(StreamQuality.FHD, region.quality)
        assertEquals("fr france 3 haute normandie", region.variantKey)
        assertEquals("FR| TV8 HAUTE SAVOIE", QualityParser.parse("FR| TV8 HAUTE SAVOIE HD").baseName)
        // Avec « résolution », « définition » ou « qualité » : une mention de qualité, retirée.
        assertEquals("FR| TF1", QualityParser.parse("FR| TF1 4K (Très haute définition)").baseName)
        assertEquals("FR| M6", QualityParser.parse("FR| M6 FHD Haute qualité").baseName)
    }

    @Test
    fun `channels whose names differ only by tv are one channel`() {
        var position = 0
        fun live(name: String) = QualityParser.parse(name).let { parsed ->
            tv.nextv.core.model.Channel(
                position = position++, name = name, stream = tv.nextv.core.model.StreamDescriptor("http://example.com/live/$position.ts"),
                kind = ContentKind.LIVE, title = parsed.baseName, variantKey = LiveVariants.key(parsed.variantKey), quality = parsed.quality,
            )
        }
        val channels = LiveVariants.unify(
            listOf(
                // Les deux vignettes de la photo : la même chaîne en HD et en FHD.
                live("FR| LE FIGARO TV IDF HD"), live("FR| LE FIGARO IDF FHD"),
                live("FR| BFM TV FHD"), live("FR| TV BREIZH HD"), live("FR| TF1 FHD"),
                live("FR| USHUAIA TV HD"), live("FR| TV USHUAIA FHD"),
                // Éditions régionales de France 3 écrites de deux façons.
                live("FR| FRANCE 3 PARIS IDF HD"), live("FR| FRANCE 3 PARIS ILE DE FRANCE FHD"),
                live("FR| FRANCE 3 POITOUCHAR HD"), live("FR| FRANCE 3 POTOU-CHARENTES FHD"),
                live("FR| FRANCE 3 BNORMANDIE HD"), live("FR| FRANCE 3 BASSE-NORMANDIE FHD"), live("FR| FRANCE 3 HAUTE-NORMANDIE FHD"),
                live("FR| FRANCE 3 FHD"),
            ),
        ).associate { it.name to it.variantKey }

        assertEquals("fr le figaro idf", channels["FR| LE FIGARO TV IDF HD"])
        assertEquals("fr le figaro idf", channels["FR| LE FIGARO IDF FHD"])
        assertEquals(channels["FR| USHUAIA TV HD"], channels["FR| TV USHUAIA FHD"])
        // Seule à porter son nom : sa clé ne change pas (ses favoris non plus).
        assertEquals("fr bfm tv", channels["FR| BFM TV FHD"])
        assertEquals("fr tv breizh", channels["FR| TV BREIZH HD"])
        assertEquals("fr tf1", channels["FR| TF1 FHD"])

        assertEquals("fr france 3 paris ile de france", channels["FR| FRANCE 3 PARIS IDF HD"])
        assertEquals(channels["FR| FRANCE 3 PARIS IDF HD"], channels["FR| FRANCE 3 PARIS ILE DE FRANCE FHD"])
        assertEquals(channels["FR| FRANCE 3 POITOUCHAR HD"], channels["FR| FRANCE 3 POTOU-CHARENTES FHD"])
        assertEquals(channels["FR| FRANCE 3 BNORMANDIE HD"], channels["FR| FRANCE 3 BASSE-NORMANDIE FHD"])
        assertNotEquals(channels["FR| FRANCE 3 BASSE-NORMANDIE FHD"], channels["FR| FRANCE 3 HAUTE-NORMANDIE FHD"])
        assertEquals("fr france 3", channels["FR| FRANCE 3 FHD"])
    }

    @Test
    fun `fifa plus qualities are separate channels`() {
        // Un flux différent par qualité : pas des versions de la même chaîne.
        val fhd = QualityParser.parse("FR| FIFA+ FHD")
        assertNotEquals(fhd.variantKey, QualityParser.parse("FR| FIFA+ HD").variantKey)
        assertEquals("FR| FIFA+", fhd.baseName)
        // Son secours de même qualité reste avec elle.
        assertEquals(fhd.variantKey, QualityParser.parse("FR| FIFA+ BKP FHD").variantKey)
    }

    @Test
    fun `resolution wording around the quality is grouped with the channel`() {
        val tf1 = QualityParser.parse("FR| TF1 FHD")
        val uhd = QualityParser.parse("FR| TF1 (Résolution 4K)")
        assertEquals(tf1.variantKey, uhd.variantKey)
        assertEquals(StreamQuality.UHD, uhd.quality)
        assertEquals("FR| TF1", uhd.baseName)
        assertEquals("FR| TF1", QualityParser.parse("FR| TF1 (Resolution: UHD)").baseName)
        assertEquals(tf1.variantKey, QualityParser.parse("FR| TF1 HDR (Résolution Exclusive)").variantKey)
        assertEquals("FR| TF1", QualityParser.parse("FR| TF1 HDR (Résolution Exclusive)").baseName)
        assertEquals(tf1.variantKey, QualityParser.parse("FR| TF1 [Qualité réelle 4K]").variantKey)
        // Sans mention de qualité, le mot fait partie du nom.
        assertNotEquals(QualityParser.parse("Résolution TV").variantKey, QualityParser.parse("TV").variantKey)
        assertEquals("Résolution TV", QualityParser.parse("Résolution TV").baseName)
    }

    @Test
    fun `codec tags are ignored for grouping`() {
        assertEquals(QualityParser.parse("Canal+ HEVC FHD").variantKey, QualityParser.parse("Canal+ SD").variantKey)
        assertEquals(StreamQuality.FHD, QualityParser.parse("Canal+ HEVC FHD").quality)
    }
}

class ContentClassifierTest {

    @Test
    fun `xtream paths decide the kind`() {
        assertEquals(ContentKind.LIVE, ContentClassifier.classify("TF1", "http://h:8080/live/u/p/1.ts", "FR").kind)
        assertEquals(ContentKind.MOVIE, ContentClassifier.classify("Avatar (2009)", "http://h:8080/movie/u/p/2.mkv", "VOD").kind)
        assertEquals(ContentKind.SERIES, ContentClassifier.classify("Dark S01 E01", "http://h:8080/series/u/p/3.mp4", null).kind)
        // Chaînes 24h/24 d'un groupe « Séries » : flux en direct Xtream, sans « /live/ ».
        assertEquals(ContentKind.LIVE, ContentClassifier.classify("FR| Friends 24/7", "http://h:8080/u/p/4", "FR| SÉRIES 24/7").kind)
        assertEquals(ContentKind.LIVE, ContentClassifier.classify("Kaamelott S01E01", "http://h:8080/u/p/5.ts", "SERIES TV").kind)
    }

    @Test
    fun `tvg-type attribute wins`() {
        assertEquals(
            ContentKind.MOVIE,
            ContentClassifier.classify("X", "http://h/live/x.m3u8", null, mapOf("tvg-type" to "movie")).kind,
        )
    }

    @Test
    fun `group titles and file extensions`() {
        assertEquals(ContentKind.MOVIE, ContentClassifier.classify("Le Dîner de cons", "http://h/a/b.m3u8", "VOD | Comédie").kind)
        assertEquals(ContentKind.SERIES, ContentClassifier.classify("Lupin S02E03", "http://h/a/b.m3u8", "SÉRIES Netflix").kind)
        assertEquals(ContentKind.MOVIE, ContentClassifier.classify("Film maison", "http://h/films/film.mp4", null).kind)
        assertEquals(ContentKind.LIVE, ContentClassifier.classify("Ciné+ Premier", "http://h/c/premier.m3u8", "FR | CINEMA").kind)
    }

    @Test
    fun `movie titles are cleaned and their year extracted`() {
        val movie = ContentClassifier.classify("FR: Avatar (2009) FHD", "http://h/movie/u/p/1.mkv", "VOD")

        assertEquals("Avatar", movie.title)
        assertEquals(2009, movie.year)
        assertEquals(2017, ContentClassifier.classify("Blade Runner 2049 (2017)", "http://h/movie/1.mkv", null).year)
        assertEquals("Blade Runner 2049", ContentClassifier.classify("Blade Runner 2049 (2017)", "http://h/movie/1.mkv", null).title)
    }

    @Test
    fun `episodes are parsed`() {
        val episode = ContentClassifier.classify("FR: La Casa de Papel (2017) S03 E05", "http://h/series/1.mp4", null)

        assertEquals(ContentKind.SERIES, episode.kind)
        assertEquals("La Casa de Papel", episode.title)
        assertEquals(2017, episode.year)
        assertEquals(EpisodeInfo("La Casa de Papel", 3, 5), episode.episode)
        assertEquals(EpisodeInfo("Dark", 1, 12), ContentClassifier.classify("Dark 1x12", "http://h/series/2.mp4", null).episode)
        assertEquals(
            EpisodeInfo("Lupin", 2, 4),
            ContentClassifier.classify("Lupin Saison 2 Épisode 4", "http://h/series/3.mp4", null).episode,
        )
    }

    @Test
    fun `live channel names are left untouched`() {
        val live = ContentClassifier.classify("FR| TF1 FHD", "http://h/live/u/p/1.ts", "FR")

        assertEquals("FR| TF1 FHD", live.title)
        assertNull(live.episode)
    }

    @Test
    fun `version tags in brackets are removed without breaking the title`() {
        val series = ContentClassifier.classify(
            "Celui qui s'appelait Matthew Perry (MULTI) S01 E01",
            "http://p.example/series/u/p/1.mkv",
            "SERIES | DOCUMENTAIRES",
        )
        assertEquals("Celui qui s'appelait Matthew Perry", series.title)
        assertEquals(1, series.episode?.season)
        assertEquals(1, series.episode?.episode)

        val movie = ContentClassifier.classify("Le Grand Voyage [VOSTFR] (2019)", "http://p.example/movie/u/p/2.mkv", "VOD | DRAME")
        assertEquals("Le Grand Voyage", movie.title)
        assertEquals(2019, movie.year)

        val unclosed = ContentClassifier.classify("Un Titre (MULTI", "http://p.example/movie/u/p/3.mkv", "VOD | DRAME")
        assertEquals("Un Titre", unclosed.title)
    }

    @Test
    fun `parentheses that belong to the title are kept`() {
        val series = ContentClassifier.classify("Rick and Morty (US) S01E02", "http://p.example/series/u/p/4.mkv", "SERIES | ANIMATION")
        assertEquals("Rick and Morty (US)", series.title)
        // « (FR) » ne désigne la version française que pour un film.
        assertEquals("Skam (FR)", ContentClassifier.classify("Skam (FR) S01E02", "http://p.example/series/u/p/5.mkv", "SERIES").title)
        assertEquals("Tron (Legacy)", movie("Tron (Legacy) (2010)").title)
        assertEquals("The French Dispatch", movie("The French Dispatch (2021)").title)
    }

    private fun movie(name: String) = ContentClassifier.classify(name, "http://p.example/movie/u/p/1.mkv", "VOD | FILMS")

    @Test
    fun `versions of a film share its title and year`() {
        // Les deux copies de la photo : la même affiche, deux mentions de version.
        val names = listOf(
            "À la dérive (Truefrench)",
            "À la dérive (FR)",
            "À la dérive (Truefrench 2018)",
            "À la dérive (TRUEFRENCH HDLight 1080p)",
            "À la dérive (True French)",
            "À la dérive [FR]",
            "À la dérive - FR",
            "FR - À la dérive (VF HD)",
            "À la dérive (Truefrench",
            "À la dérive (BluRay 1080p)",
            "À la dérive (2018) (TRUEFRENCH)",
            "À la dérive (True FR)",
            "À la dérive (TrueFR)",
        )
        for (name in names) assertEquals(name, "À la dérive", movie(name).title)
        // Mention de version avant celle de qualité.
        assertEquals("Avatar", movie("Avatar (2009) VOSTFR 4K").title)
        assertEquals(2009, movie("Avatar (2009) VOSTFR 4K").year)
        assertEquals("Avatar", movie("Avatar MULTI FHD").title)
        assertEquals(2018, movie("À la dérive (Truefrench 2018)").year)
        assertEquals(2018, movie("À la dérive (FR) (2018)").year)
        assertEquals(2018, movie("À la dérive (2018) (TRUEFRENCH)").year)
        assertNull(movie("À la dérive (FR)").year)
        // Écritures réelles d'une playlist : la même affiche pour les deux.
        assertEquals("À la dérive", movie("À la dérive (True FR) 2018").title)
        assertEquals("À la dérive", movie("À la dérive  (FR) 2018").title)
        assertEquals(2018, movie("À la dérive (True FR) 2018").year)
    }

    @Test
    fun `the version of a film is read from its name`() {
        fun tag(name: String) = ContentClassifier.versionOf(name).tag
        fun rank(name: String) = ContentClassifier.versionOf(name).rank

        assertEquals("TRUEFRENCH", tag("À la dérive (Truefrench)"))
        assertEquals("TRUEFRENCH", tag("À la dérive (True French HDLight)"))
        assertEquals("FR", tag("À la dérive (FR)"))
        assertEquals("TRUEFRENCH", tag("À la dérive (True FR) 2018"))
        // « True » seul dans un titre n'est pas une mention.
        assertNull(tag("True Lies (1994)"))
        assertEquals("FR", tag("FR - À la dérive (2018)"))
        assertEquals("VOSTFR", tag("FR: Avatar (2009) VOSTFR"))
        assertEquals("MULTI", tag("Avatar (MULTI) 4K"))
        assertEquals("EN", tag("EN: Avatar (2009)"))
        assertNull(tag("Avatar (2009) FHD"))
        // Un mot du titre n'est pas une mention de version.
        assertNull(tag("The French Dispatch (2021)"))
        assertEquals("VOSTFR", tag("The French Dispatch [VOSTFR]"))

        // Avec le titre déjà nettoyé (import de la playlist) : même résultat, sans le nettoyer à nouveau.
        assertEquals("TRUEFRENCH", ContentClassifier.versionOf("À la dérive (Truefrench 2018)", "À la dérive").tag)
        assertEquals("VOSTFR", ContentClassifier.versionOf("FR: Avatar (2009) VOSTFR 4K", "Avatar").tag)
        assertNull(ContentClassifier.versionOf("Avatar (2009) FHD", "Avatar").tag)
        assertNull(ContentClassifier.versionOf("The French Dispatch (2021)", "The French Dispatch").tag)

        // Version française (ou sans mention) d'abord, puis plusieurs langues, sous-titrée, autre langue.
        assertEquals(rank("Avatar (2009)"), rank("Avatar (Truefrench)"))
        assertEquals(rank("Avatar (FR)"), rank("Avatar (VF)"))
        assertTrue(rank("Avatar (VF)") > rank("Avatar (VFQ)"))
        assertTrue(rank("Avatar (VFQ)") > rank("Avatar (MULTI)"))
        assertTrue(rank("Avatar (MULTI)") > rank("Avatar (VOSTFR)"))
        assertTrue(rank("Avatar (VOSTFR)") > rank("EN: Avatar"))
        assertEquals(rank("Avatar (VO)"), rank("EN: Avatar"))
    }
}

class VersionWordsTest {

    @Test
    fun `version words that end a stored title are removed`() {
        assertEquals("a la derive", ContentClassifier.withoutVersionWords("a la derive truefrench", movie = true))
        assertEquals("a la derive", ContentClassifier.withoutVersionWords("a la derive fr", movie = true))
        assertEquals("a la derive", ContentClassifier.withoutVersionWords("a la derive truefrench hdlight 1080p", movie = true))
        assertEquals("avatar", ContentClassifier.withoutVersionWords("avatar", movie = true))
        // Série : « (FR) » fait partie du titre.
        assertEquals("skam fr", ContentClassifier.withoutVersionWords("skam fr", movie = false))
        assertEquals("lupin", ContentClassifier.withoutVersionWords("lupin multi", movie = false))
        // Un mot de version ailleurs qu'à la fin reste dans le titre.
        assertEquals("the french dispatch", ContentClassifier.withoutVersionWords("the french dispatch", movie = true))
    }
}

class FranceRegionsTest {

    @Test
    fun `regional editions are recognised`() {
        assertEquals("alpes", FranceRegions.regionOf("FR| FRANCE 3 ALPES")?.key)
        assertEquals("Alpes", FranceRegions.regionOf("FR| FRANCE 3 ALPES")?.label)
        assertEquals("paris ile de france", FranceRegions.regionOf("FR| France 3 Paris Île-de-France FHD")?.key)
        assertEquals("cote d azur", FranceRegions.regionOf("France3 Côte d'Azur HD")?.key)
        assertEquals("bretagne", FranceRegions.regionOf("FR: F3 Bretagne")?.key)
        assertEquals("Corse ViaStella", FranceRegions.regionOf("FR| France 3 Corse ViaStella")?.label)
        // Nom mal écrit par la playlist : corrigé.
        assertEquals("Languedoc-Roussillon", FranceRegions.regionOf("FR| FRANCE 3 LANGUE-DOC")?.label)
        assertEquals("Languedoc-Roussillon", FranceRegions.regionOf("FR| France 3 Langue Doc HD")?.label)
        assertEquals("Languedoc-Roussillon", FranceRegions.regionOf("FR| FRANCE 3 LANGUEDOC")?.label)
    }

    @Test
    fun `the spellings of a region all name the same antenna`() {
        // Les écritures des photos : une en HD, une autre en FHD, pour la même antenne.
        val spellings = mapOf(
            "basse normandie" to listOf("BNORMANDIE", "BASSE-NORMANDIE", "B-NORMANDIE", "Basse Normandie", "NORMANDIE CAEN", "Caen"),
            "haute normandie" to listOf("HAUTE-NORMANDIE", "HNORMANDIE", "H NORMANDIE", "Normandie Rouen"),
            "languedoc roussillon" to listOf("LANGUE-DOC", "LANGUEDOC", "LANGUEDOC-ROUSSILLON", "LANGUEDOC ROUSILLON", "LANGUEDOC ROUS", "Occitanie Montpellier"),
            "champagne ardenne" to listOf("CHAMP ARDENNE", "CHAMP ARDE", "Champagne-Ardenne", "CHAMPAGNE ARDENNES", "CHAMPAGNE", "Grand Est Reims"),
            "nord pas de calais" to listOf("NORD PDC", "NORD PAS-DE-CALAIS", "NORD PAS DE CALAIS", "NPDC", "NORD", "Hauts-de-France Lille"),
            "paris ile de france" to listOf("PARIS IDF", "PARIS ILE DE FRANCE", "Paris Île-de-France", "IDF", "ILE DE FRANCE", "PARIS"),
            "pays de la loire" to listOf("PAYS DE LA LOIRE", "Pays de Loire", "PAYS LOIRE", "PDL", "Nantes"),
            "poitou charentes" to listOf("POITOUCHAR", "POTOU-CHARENTES", "POITOU-CHARENTES", "POITOU CHARENTE", "POITOU"),
            "provence alpes" to listOf("PROV ALPES", "PROVENCE-ALPES", "PROVENCE ALPES", "PROVENCE", "Marseille"),
            "rhone alpes" to listOf("RHONES-ALPES", "RHONE ALPES", "Rhône-Alpes", "Auvergne-Rhône-Alpes Lyon"),
            "cote d azur" to listOf("COTE-D'AZUR", "COTE AZUR", "Côte d'Azur", "COTEDAZUR", "Nice"),
            "corse via stella" to listOf("CORSE VIA STELLA", "Corse ViaStella", "CORSE", "VIA STELLA"),
            "midi pyrenees" to listOf("MIDI PYRENEES", "MIDI-PYRENEES", "MIDI PYR", "Midi-Pyrénées", "Toulouse"),
            "franche comte" to listOf("Franche-Comté", "FRANCHE COMTE", "F COMTE"),
            "noa" to listOf("NOA", "NoA", "Nouvelle-Aquitaine"),
            "alpes" to listOf("ALPES", "Grenoble"),
            "centre" to listOf("CENTRE", "Centre-Val de Loire", "CENTRE VAL DE LOIRE"),
            "bretagne" to listOf("BRETAGNE", "BRET"),
        )
        for ((key, names) in spellings) {
            for (name in names) assertEquals(name, key, FranceRegions.regionOf("FR| FRANCE 3 $name FHD")?.key)
        }
        assertEquals("Nord-Pas-de-Calais", FranceRegions.regionOf("FR| FRANCE 3 NORD PDC")?.label)
        assertEquals("Paris Île-de-France", FranceRegions.regionOf("FR| FRANCE 3 PARIS IDF HD")?.label)
        // Identifiant du guide, écrit sans espaces.
        assertEquals("basse normandie", FranceRegions.regionOfGuide("France3BasseNormandie")?.key)
        assertEquals("noa", FranceRegions.regionOfGuide("France 3 NoA")?.key)
        assertEquals("corse via stella", FranceRegions.regionOfGuide("France3CorseViaStella")?.key)
    }

    @Test
    fun `a name that fits no antenna, or several, stays as written`() {
        // « Normandie » seule : Basse ou Haute, rien n'est supposé. De même pour une lettre ou deux.
        assertEquals("normandie", FranceRegions.regionOf("FR| FRANCE 3 NORMANDIE")?.key)
        assertEquals("b", FranceRegions.regionOf("FR| FRANCE 3 B")?.key)
        assertEquals("al", FranceRegions.regionOf("FR| FRANCE 3 AL")?.key)
        assertEquals("provence alpes cote d azur", FranceRegions.regionOf("FR| FRANCE 3 Provence-Alpes-Côte d'Azur")?.key)
        assertEquals("hauts de france", FranceRegions.regionOf("FR| FRANCE 3 HAUTS-DE-FRANCE")?.key)
        assertEquals("Sat", FranceRegions.regionOf("FR| FRANCE 3 SAT")?.label)
        // Deux antennes distinctes ne sont pas confondues.
        assertEquals("alpes", FranceRegions.regionOf("FRANCE 3 ALPES")?.key)
        assertEquals("aquitaine", FranceRegions.regionOf("FRANCE 3 AQUITAINE")?.key)
        assertEquals("alsace", FranceRegions.regionOf("FRANCE 3 ALSACE")?.key)
    }

    @Test
    fun `the version key of a regional edition carries the name of its antenna`() {
        assertEquals("fr france 3 paris ile de france", FranceRegions.variantKey("fr france 3 paris idf"))
        assertEquals("fr france 3 paris ile de france", FranceRegions.variantKey("fr f3 paris ile de france"))
        assertEquals("fr france 3 basse normandie", FranceRegions.variantKey("fr france 3 bnormandie"))
        assertEquals("france 3 alsace", FranceRegions.variantKey("france 3 alsace"))
        // Chaîne nationale, région inconnue, autre chaîne : pas une édition régionale connue.
        assertNull(FranceRegions.variantKey("fr france 3"))
        assertNull(FranceRegions.variantKey("fr france 3 +1"))
        assertNull(FranceRegions.variantKey("fr france 3 normandie"))
        assertNull(FranceRegions.variantKey("fr france 24"))
        assertNull(FranceRegions.variantKey("fr tf1"))
        // Réglage « Région de France 3 » enregistré avec l'ancienne écriture.
        assertEquals("paris ile de france", FranceRegions.keyOf("paris idf"))
        assertEquals("normandie", FranceRegions.keyOf("normandie"))
    }

    @Test
    fun `national channel and other channels are not regions`() {
        assertNull(FranceRegions.regionOf("FR| FRANCE 3"))
        assertNull(FranceRegions.regionOf("FR| FRANCE 3 HD"))
        assertNull(FranceRegions.regionOf("FR| FRANCE 3 HDR (EXCLUS)"))
        assertNull(FranceRegions.regionOf("FR| France 3 +1"))
        assertNull(FranceRegions.regionOf("FR| France 2"))
        assertNull(FranceRegions.regionOf("FR| France 5"))
        assertNull(FranceRegions.regionOf("FR| France 3 Backup"))
    }
}
