package tv.nextv.app.data.db

import android.app.Application
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.catalog.LiveVariants
import tv.nextv.core.catalog.QualityParser
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput

/**
 * Chaînes que la playlist écrit autrement d'une qualité à l'autre (« FRANCE 3 PARIS IDF » en HD et « FRANCE 3 PARIS
 * ILE DE FRANCE » en FHD, « LE FIGARO TV IDF » et « LE FIGARO IDF ») : une seule vignette, la meilleure qualité, et
 * les favoris, « L'essentiel », les chaînes récentes et la région choisie suivent le nouveau nom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveVersionsTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao
    private var position = 0

    /** Chaîne telle que la playlist l'écrit : nom affiché, qualité et clé de version viennent des vraies règles. */
    private fun live(name: String): Channel {
        val parsed = QualityParser.parse(name)
        val index = position++
        return Channel(
            position = index, name = name, stream = StreamDescriptor("http://example.com/live/u/p/$index.ts"), groupTitle = "FR",
            kind = ContentKind.LIVE, title = parsed.baseName, variantKey = LiveVariants.key(parsed.variantKey), quality = parsed.quality,
        )
    }

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    private suspend fun listed(sourceId: Long) = dao.pageChannels(sourceId, 0, "", "", 0).all().map { it.name }

    @Test
    fun spellingsOfAChannelAreOneTileAndWhatWasSavedFollows() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        val channels = LiveVariants.unify(
            listOf(
                live("FR| TF1 FHD"),
                live("FR| FRANCE 3 ALSACE FHD"),
                live("FR| FRANCE 3 PARIS IDF HD"), live("FR| FRANCE 3 PARIS ILE DE FRANCE FHD"),
                live("FR| FRANCE 3 POITOUCHAR HD"), live("FR| FRANCE 3 POTOU-CHARENTES FHD"),
                live("FR| LE FIGARO TV IDF HD"), live("FR| LE FIGARO IDF FHD"),
                live("FR| BFM TV FHD"),
            ),
        )
        container.playlistStore.replaceChannels(id, channels) {}

        // Une vignette par chaîne : sa meilleure qualité.
        assertEquals(
            listOf("FR| TF1 FHD", "FR| FRANCE 3 ALSACE FHD", "FR| FRANCE 3 PARIS ILE DE FRANCE FHD", "FR| FRANCE 3 POTOU-CHARENTES FHD", "FR| LE FIGARO IDF FHD", "FR| BFM TV FHD"),
            listed(id),
        )
        assertEquals(2, dao.variants(id, "fr france 3 paris ile de france").size)

        // Enregistrés par une version précédente, sous l'ancienne écriture de chaque chaîne.
        container.favorites.toggle(id, ContentKind.LIVE, "live:fr france 3 paris idf", "FR| FRANCE 3 PARIS IDF", null)
        container.favorites.toggle(id, ContentKind.LIVE, "live:fr le figaro tv idf", "FR| LE FIGARO TV IDF", null)
        container.favorites.toggle(id, ContentKind.LIVE, "live:fr bfm tv", "FR| BFM TV", null)
        container.favorites.toggle(id, ContentKind.LIVE, "live:fr retiree", "FR| RETIREE", null)
        container.favorites.toggleGuideSelection(id, "live:fr france 3 poitouchar", "FR| FRANCE 3 POITOUCHAR", null)
        container.settings.addRecentChannel(id, "live:fr tf1")
        container.settings.addRecentChannel(id, "live:fr le figaro tv idf")

        container.itemKeyRelinker.relink(id)

        assertEquals(
            setOf("live:fr france 3 paris ile de france", "live:fr le figaro idf", "live:fr bfm tv", "live:fr retiree"),
            container.favorites.observeKeys(id).first().toSet(),
        )
        assertEquals(
            listOf("FR| FRANCE 3 PARIS ILE DE FRANCE FHD", "FR| LE FIGARO IDF FHD", "FR| BFM TV FHD"),
            dao.observeFavoriteChannels(id, 0).first().map { it.name },
        )
        assertEquals(listOf("live:fr france 3 poitou charentes"), container.favorites.observeGuideSelection(id).first())
        assertEquals(listOf("live:fr le figaro idf", "live:fr tf1"), container.settings.recentChannels(id).first())

        // Région choisie quand elle s'écrivait « paris idf » : les autres éditions restent masquées, pas elle.
        container.settings.setFrance3Region("paris idf")
        val region = container.settings.france3Region.first()
        assertEquals("paris ile de france", region)
        container.channelFilters.apply(id, hideUhd = false, france3Region = region)
        assertEquals(listOf("FR| TF1 FHD", "FR| FRANCE 3 PARIS ILE DE FRANCE FHD", "FR| LE FIGARO IDF FHD", "FR| BFM TV FHD"), listed(id))
        assertEquals(listOf("alsace", "paris ile de france", "poitou charentes"), container.channelFilters.france3Regions(id).map { it.key })
    }

    /** 4K masquée : le lecteur, les favoris et les chaînes récentes montrent la version FHD et son logo, pas « M6 4K ». */
    @Test
    fun hiddenUhdVersionLendsNeitherNameNorLogo() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        val channels = LiveVariants.unify(
            listOf(
                live("FR| M6 4K").copy(logoUrl = "http://example.com/m6-4k.png"),
                live("FR| M6 FHD").copy(logoUrl = "http://example.com/m6.png"),
                live("FR| ARTE 4K").copy(logoUrl = "http://example.com/arte-4k.png"),
            ),
        )
        container.playlistStore.replaceChannels(id, channels) {}
        val m6 = dao.pageChannels(id, 0, "", "", 0).all().first { it.name == "FR| M6 4K" }
        val variantKey = dao.get(m6.id)!!.variantKey
        container.favorites.toggle(id, ContentKind.LIVE, m6.itemKey, "FR| M6", null)
        container.favorites.toggle(id, ContentKind.LIVE, "live:fr arte", "FR| ARTE", null)
        container.settings.addRecentChannel(id, m6.itemKey)

        // 4K visible : la meilleure version, la 4K.
        assertEquals("http://example.com/m6-4k.png", dao.primaryOf(id, variantKey)?.logoUrl)

        container.channelFilters.apply(id, hideUhd = true, france3Region = null)
        assertEquals("FR| M6 FHD", dao.primaryOf(id, variantKey)?.name)
        assertEquals("http://example.com/m6.png", dao.primaryOf(id, variantKey)?.logoUrl)
        // Une chaîne qui n'existe qu'en 4K reste dans les favoris.
        assertEquals(
            listOf("FR| M6 FHD" to "http://example.com/m6.png", "FR| ARTE 4K" to "http://example.com/arte-4k.png"),
            dao.observeFavoriteChannels(id, 0).first().map { it.name to it.logoUrl },
        )
        assertEquals(listOf("FR| M6 FHD"), dao.observeChannelsByKey(id, listOf(m6.itemKey), 0).first().map { it.name })
    }
}
