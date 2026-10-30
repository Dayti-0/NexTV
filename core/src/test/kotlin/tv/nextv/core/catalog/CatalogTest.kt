package tv.nextv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    }
}
