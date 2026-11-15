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
 * « Chaînes françaises seulement » : les chaînes des autres pays (belges, arabes, espagnoles…) et leurs catégories
 * disparaissent des listes, mais la section Foot les trouve toujours (match diffusé sur une chaîne étrangère). Et les
 * chaînes déjà enregistrées sont rangées de nouveau sans relire la playlist (catégories par thème, nationalité).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FrenchOnlyTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao

    private val entries = listOf(
        "FR TV SD (FRANCE)" to "|FR| TF1 SD", "FR TV HD (France)" to "|FR| TF1 HD", "FR TV FULL HD|4K (France)" to "|FR| TF1 FHD",
        "FR TV HD (France)" to "|FR| FRANCE 2 HD", "FR TV FULL HD|4K (France)" to "|FR| CINE FRISSON FHD",
        "FR TV FULL HD|4K (France)" to "|INT| NHK WORLD JAPAN FHD",
        "FR SPORTS (France)" to "|FR| BEIN SPORTS 1 FHD",
        "PLUTO TV (FRANCE)" to "|FR| PLUTO TV FILMS",
        "BELGES (FR-SPORTS-FLAMAND)" to "|BE| LA UNE HD", "BELGES (FR-SPORTS-FLAMAND)" to "|BE| RTL TVI HD",
        "ARABES" to "|AR| MBC 1 HD",
        "DAZN SPAIN ( LIVE ONLY )" to "|ES| DAZN LALIGA 1",
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

    private suspend fun all(sourceId: Long) = dao.pageChannels(sourceId, 0, "", "", 0).all().map { it.title }

    private suspend fun groups(sourceId: Long) = dao.observeGroups(sourceId, "LIVE").first().map { it.title }

    private fun settings(frenchOnly: Boolean) = ChannelFilters.Settings(hideUhd = false, france3Region = null, mergeChannels = true, frenchOnly = frenchOnly)

    @Test
    fun foreignChannelsAreHiddenButTheFootballSectionStillFindsThem() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.channelFilters.apply(id, settings(frenchOnly = false))
        assertTrue(groups(id).containsAll(listOf("BELGES (FR-SPORTS-FLAMAND)", "ARABES", "DAZN SPAIN ( LIVE ONLY )")))
        assertTrue(all(id).toString(), all(id).any { "LA UNE" in it.uppercase() })

        container.channelFilters.apply(id, settings(frenchOnly = true))
        // Ni chaînes ni catégories des autres pays ; les chaînes françaises, Pluto TV compris, restent.
        assertEquals(listOf("TNT", "Cinéma", "Sport", "PLUTO TV (FRANCE)"), groups(id))
        assertEquals(listOf("TF1", "France 2", "Ciné+ Frisson", "beIN Sports 1", "PLUTO TV FILMS"), all(id))
        assertEquals(5, dao.observeListedCount(id).first())
        // Section Foot : la chaîne espagnole d'un match reste trouvée (après les chaînes visibles).
        assertEquals(listOf("|ES| DAZN LALIGA 1"), dao.eventChannelCandidates(id, 0, Long.MAX_VALUE).map { it.name })

        container.channelFilters.apply(id, settings(frenchOnly = false))
        assertTrue("ARABES" in groups(id))
    }

    /**
     * Chaînes enregistrées par une version qui ne les rangeait pas encore (catégories du fournisseur « FR TV SD »…,
     * toutes françaises) : rangées de nouveau à partir de la base, comme à la lecture de la playlist.
     */
    @Test
    fun storedChannelsAreArrangedAgainWithoutReadingThePlaylist() = runBlocking {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, load()) {}
        container.channelFilters.apply(id, settings(frenchOnly = true))
        val arranged = groups(id) to all(id)

        // Comme avant la migration : ni thème, ni nom propre, ni nationalité (toutes françaises), ordre de la playlist.
        dao.lineupRows(id).forEach { dao.setArrangement(id, it.position, it.position, null, null, true) }
        container.channelFilters.apply(id, settings(frenchOnly = true))
        assertTrue("FR TV FULL HD|4K (France)" in groups(id))
        assertTrue("ARABES" in groups(id))

        container.channelFilters.rearrange(id)
        container.channelFilters.apply(id, settings(frenchOnly = true))
        assertEquals(arranged, groups(id) to all(id))
    }
}
