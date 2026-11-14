package tv.nextv.app.data.db

import android.app.Application
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.app.data.catalog.ChannelFilters
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.source.M3uPlaylistLoader

/**
 * « Rassembler les chaînes » sur une playlist rangée comme celle d'un fournisseur courant : chaque chaîne en SD, HD,
 * FHD et 4K dans trois catégories, des séparateurs (« ▀▄ FR ▀▄ CANAL+ ▄▀ »), les éditions régionales de France 3 avant
 * Canal+, et Canal+ Cinéma(s) en double audio, en Dolby Digital et en « FHD+ ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MergeChannelsTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao

    private val entries = listOf(
        "FR TV SD" to "|FR| TF1 SD", "FR TV HD" to "|FR| TF1 HD", "FR TV FHD" to "|FR| TF1 FHD",
        "FR TV SD" to "|FR| FRANCE 2 SD", "FR TV FHD" to "|FR| FRANCE 2 FHD",
        "FR TV HD" to "|FR| FRANCE 3 HD", "FR TV FHD" to "|FR| FRANCE 3 FHD",
        "FR TV FHD" to "|FR| FRANCE 4 FHD", "FR TV FHD" to "|FR| M6 FHD", "FR TV FHD" to "|FR| T18 FHD",
        "FR TV FHD" to "|FR| ARTE FHD", "FR TV FHD" to "|FR| STAR ACADEMY FHD", "FR TV FHD" to "|FR| BFM TV FHD",
        "FR TV FHD" to "|FR| LCP FHD", "FR TV FHD" to "|FR| RMC LIFE FHD",
        "FR TV HD" to "▀▄ FR ▀▄▀▄▀▄  REGIONALES ▄▀▄▀▄▀", "FR TV FHD" to "▀▄ FR ▀▄▀▄  REGIONALES ▄▀▄▀▄▀",
        "FR TV HD" to "|FR| FRANCE 3 ALPES HD", "FR TV FHD" to "|FR| FRANCE 3 ALPES FHD",
        "FR TV HD" to "|FR| FRANCE 3 PARIS IDF HD", "FR TV FHD" to "|FR| FRANCE 3 PARIS ILE DE FRANCE FHD",
        "FR TV FHD" to "|FR| BFM MARSEILLE FHD",
        "FR TV HD" to "▀▄ FR ▀▄▀▄  OUTRE MER ▀▄▀▄", "FR TV HD" to "|FR| MARTINIQUE 1ERE HD",
        "FR TV SD" to "▀▄ FR ▀▄▀▄  CANAL+ ▄▀▄▀▄", "FR TV FHD" to "▀▄ FR ▀▄▀▄  CANAL+ ▄▀▄▀▄▀",
        "FR TV SD" to "|FR| CANAL+ SD (DOUBLE AUDIO)", "FR TV FHD" to "|FR| CANAL+ FHD (DOUBLE AUDIO)",
        "FR TV FHD" to "|FR| CANAL+ 4K HDR UHD (Résolution Exclusive)",
        "FR TV HD" to "|FR| CANAL+ CINEMA.(S) HD (DOUBLE AUDIO)", "FR TV FHD" to "|FR| CANAL+ CINEMA.(S) FHD (DOUBLE AUDIO)",
        "FR TV FHD" to "|FR| CANAL+ CINEMA.(S) FHD+ (Resolution Exclusive)",
        "FR TV CINEMA FHD ( DOLBY DIGITAL)" to "|FR| CANAL+ CINEMA.(S) FHD DOLBY DIGITAL",
        "FR TV FHD" to "▀▄ FR ▀▄▀▄  CINEMA  ▄▀▄▀▄▀", "FR TV FHD" to "|FR| CINE FRISSON FHD (DOUBLE AUDIO)",
        "FR TV FHD" to "▀▄ FR ▀▄▀▄  JEUNESSE ▄▀▄▀▄▀", "FR TV FHD" to "|FR| GULLI FHD",
        "FR TV SD" to "|FR| CHASSE & PÊCHE SD", "FR TV FHD" to "|FR| CHASSE ET PECHE FHD",
        "FR SPORTS" to "|FR| CANAL+ LIVE 1 FHD", "FR SPORTS" to "|FR| BEIN SPORTS 1 FHD",
    )

    private val playlist = buildString {
        appendLine("#EXTM3U")
        entries.forEachIndexed { index, (group, name) ->
            appendLine("#EXTINF:-1 group-title=\"$group\",$name")
            appendLine("http://example.com/live/u/p/$index.ts")
        }
    }

    private fun load(): List<tv.nextv.core.model.Channel> = runBlocking {
        val opener = object : ContentOpener {
            override fun supports(location: SourceLocation) = true
            override suspend fun open(location: SourceLocation, request: OpenRequest) = OpenedContent(playlist.toByteArray().inputStream())
        }
        M3uPlaylistLoader(opener).load(PlaylistSource(name = "P", location = SourceLocation.LocalDocument("doc", "p.m3u")), null).channels
    }

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    /** « Toutes les chaînes » telle que l'accueil l'affiche : nom affiché de chaque ligne. */
    private suspend fun all(sourceId: Long) = dao.pageChannels(sourceId, 0, "", "", 0).all().map { it.title }

    private suspend fun group(sourceId: Long, group: String) = dao.pageChannels(sourceId, 1, group, "", 0).all().map { it.title }

    @Test
    fun mergedChannelsAreOneLineEachInTheOrderOfATelevision() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = true))

        // La TNT dans l'ordre des numéros, puis Canal+, le cinéma… ; ni séparateurs, ni éditions régionales de France 3 ;
        // le nom propre de chaque chaîne, sans « FR| ».
        assertEquals(
            listOf(
                "TF1", "France 2", "France 3", "France 4", "M6", "Arte", "LCP", "Gulli", "BFMTV", "T18", "RMC Life",
                "Canal+", "Canal+ Cinéma(s)",
                "Ciné+ Frisson",
                "Star Academy",
                "beIN Sports 1",
                "Chasse & Pêche",
                "BFM Marseille Provence",
                "MARTINIQUE 1ERE",
                "CANAL+ LIVE 1",
            ),
            all(id),
        )
        // Des catégories par thème, dans le même ordre, au lieu de celles du fournisseur (« FR TV FHD »…).
        assertEquals(
            listOf("TNT", "Canal+", "Cinéma", "Séries et divertissement", "Sport", "Découverte", "Régions", "Outre-mer", "Matchs en direct"),
            dao.observeGroups(id, "LIVE").first().map { it.title },
        )
        assertEquals(emptyList<String>(), group(id, "FR TV FHD"))
        // La recherche trouve une chaîne par le nom affiché (« BFM MARSEILLE » dans la playlist).
        assertEquals(listOf("BFM Marseille Provence"), dao.pageChannels(id, 0, "", "Provence", 0).all().map { it.title })
        // Canal+ Cinéma(s) une seule fois (double audio, Dolby Digital, FHD+ réunis), qui s'ouvre en double audio.
        val canal = dao.pageChannels(id, 1, "Canal+", "", 0).all()
        assertEquals(listOf("Canal+", "Canal+ Cinéma(s)"), canal.map { it.title })
        assertEquals("|FR| CANAL+ CINEMA.(S) FHD (DOUBLE AUDIO)", canal.last().name)
        val tnt = dao.pageChannels(id, 1, "TNT", "", 0).all()
        assertEquals(all(id).take(11), tnt.map { it.title })

        // Zapping dans le même ordre : après RMC Life (25), Canal+.
        val rmcLife = dao.get(tnt.first { it.title == "RMC Life" }.id)!!
        val next = dao.nextChannelId(id, 0, "", dao.listedOrder(id, 0, "", rmcLife.variantKey)!!)
        assertEquals("|FR| CANAL+ 4K HDR UHD (Résolution Exclusive)", next?.let { dao.get(it) }?.name)
        // Zapping dans une catégorie (chaîne ouverte depuis elle) : il n'en sort pas. RMC Life est la dernière de la TNT
        // (le lecteur repart de la première), TF1 la première.
        assertEquals(null, dao.nextChannelId(id, 1, "TNT", dao.listedOrder(id, 1, "TNT", rmcLife.variantKey)!!))
        assertEquals(tnt.first().id, dao.nextChannelId(id, 1, "TNT", Int.MIN_VALUE))
        // Canal+ : de la première (Canal+), « bas » ne trouve rien avant elle dans la catégorie (le lecteur va à la
        // dernière, Canal+ Cinéma(s), telle que la catégorie l'affiche) ; « haut » va à Canal+ Cinéma(s).
        val canalPlus = dao.get(canal.first().id)!!
        val canalOrder = dao.listedOrder(id, 1, "Canal+", canalPlus.variantKey)!!
        assertEquals(null, dao.previousChannelId(id, 1, "Canal+", canalOrder))
        assertEquals(canal.last().id, dao.previousChannelId(id, 1, "Canal+", Int.MAX_VALUE))
        assertEquals(canal.last().id, dao.nextChannelId(id, 1, "Canal+", canalOrder))
        // Hors de la catégorie, pas de place : le lecteur part du rang de la chaîne.
        assertEquals(null, dao.listedOrder(id, 1, "Canal+", rmcLife.variantKey))

        // Région choisie : son édition juste après France 3, dans la TNT.
        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = "alpes", mergeChannels = true))
        assertEquals(listOf("TF1", "France 2", "France 3", "France 3 Alpes", "France 4"), all(id).take(5))
        assertEquals(listOf("TF1", "France 2", "France 3", "France 3 Alpes", "France 4"), group(id, "TNT").take(5))
    }

    /**
     * Catégories masquées : une catégorie du fournisseur masquée le reste (et reste dans les réglages pour la remontrer) ;
     * ses chaînes restent dans leur catégorie par thème par leurs versions des autres catégories.
     */
    @Test
    fun hiddenCategoriesStayHiddenWhenChannelsAreMerged() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = true))
        container.hiddenGroupDao.hide(HiddenGroupEntity(id, "LIVE", "FR TV FHD"))

        // TF1 par sa version HD, France 2 par sa version SD ; France 4 et M6 n'existent qu'en FHD.
        val tnt = dao.pageChannels(id, 1, "TNT", "", 0).all()
        assertEquals(listOf("TF1", "France 2", "France 3"), tnt.map { it.title })
        assertEquals("|FR| TF1 HD", tnt.first().name)
        assertEquals(listOf("TF1", "France 2", "France 3"), all(id).take(3))
        // Réglages des catégories : les catégories par thème, puis celle du fournisseur masquée, pour la remontrer.
        val settings = dao.observeAllGroups(id, "LIVE").first()
        assertEquals("TNT", settings.first().title)
        assertEquals(listOf("FR TV FHD"), settings.filter { it.hidden }.map { it.title })
        assertEquals(1, container.hiddenGroupDao.observeCount(id, "LIVE").first())

        // Une catégorie par thème masquée : ni dans les catégories, ni dans « Toutes les chaînes ».
        container.hiddenGroupDao.show(id, "LIVE", "FR TV FHD")
        container.hiddenGroupDao.hide(HiddenGroupEntity(id, "LIVE", "Canal+"))
        assertTrue("Canal+" !in dao.observeGroups(id, "LIVE").first().map { it.title })
        assertTrue(all(id).none { it.startsWith("Canal+") })
        assertEquals(listOf("Canal+"), dao.observeAllGroups(id, "LIVE").first().filter { it.hidden }.map { it.title })
    }

    /** Favoris et chaînes récentes enregistrés sous l'ancienne écriture (« (S) », pluriel, « & ») : ils suivent la chaîne. */
    @Test
    fun savedChannelsFollowTheSingleSpelling() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.favorites.toggle(id, tv.nextv.core.model.ContentKind.LIVE, "live:fr canal+ cinema s", "FR| CANAL+ CINEMA.(S)", null)
        container.favorites.toggle(id, tv.nextv.core.model.ContentKind.LIVE, "live:fr bein sports 1", "FR| BEIN SPORTS 1", null)
        container.settings.addRecentChannel(id, "live:fr chasse et peche")

        container.itemKeyRelinker.relink(id)

        assertEquals(
            listOf("Canal+ Cinéma(s)", "beIN Sports 1"),
            dao.observeFavoriteChannels(id, 0).first().map { it.title },
        )
        // Réglages communs aux tests : d'autres chaînes récentes peuvent suivre.
        val recent = container.settings.recentChannels(id).first()
        assertEquals("live:fr chasse peche", recent.first())
        assertTrue("live:fr chasse et peche" !in recent)
    }

    @Test
    fun withoutMergingChannelsAreListedAsThePlaylistWritesThem() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = true))
        val merged = all(id)

        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = false))
        val split = all(id)
        // Ordre de la playlist, séparateurs et éditions régionales compris ; versions de son à part, et nommées.
        assertEquals("FR| TF1", split.first())
        assertTrue(split.indexOf("FR| STAR ACADEMY") < split.indexOf("FR| FRANCE 3 ALPES"))
        assertTrue(split.indexOf("FR| FRANCE 3 ALPES") < split.indexOf("FR| CANAL+ (Double audio)"))
        assertTrue(split.any { "REGIONALES" in it })
        assertTrue("FR| FRANCE 3 PARIS ILE DE FRANCE" in split)
        assertEquals(
            listOf("FR| CANAL+ (Double audio)", "FR| CANAL+", "FR| CANAL+ CINEMA.(S) (Double audio)", "FR| CANAL+ CINEMA.(S)", "FR| CANAL+ CINEMA.(S) (Dolby)"),
            split.filter { it.startsWith("FR| CANAL+") && "LIVE" !in it },
        )
        // Le séparateur « CANAL+ » n'est jamais une version de Canal+.
        assertEquals(1, dao.variants(id, "fr canal+").size)
        // Les catégories du fournisseur, dans l'ordre de la playlist.
        assertEquals(
            listOf("FR TV SD", "FR TV HD", "FR TV FHD", "FR TV CINEMA FHD ( DOLBY DIGITAL)", "FR SPORTS"),
            dao.observeGroups(id, "LIVE").first().map { it.title },
        )
        assertEquals(emptyList<String>(), group(id, "TNT"))

        // Et de nouveau rassemblées : comme avant.
        container.channelFilters.apply(id, ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = true))
        assertEquals(merged, all(id))
    }
}
