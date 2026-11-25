package tv.nextv.app.data.db

import android.app.Application
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInput

/**
 * Outil (ignoré sans `COUNT_PLAYLIST` = adresse locale d'une vraie playlist) : les nombres affichés (tuiles du menu,
 * catégories) correspondent-ils aux éléments que les listes montrent vraiment ? Chaque écart est écrit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaylistCountsTool {

    @Test
    fun counts() = runBlocking {
        val url = System.getenv("COUNT_PLAYLIST")
        assumeTrue(url != null)
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val id = container.sourceRepository.add(SourceInput(name = "P", location = SourceLocation.Remote(url!!)))
        container.sourceRepository.refresh(id, includeEpg = false)
        val dao = container.channelDao
        for (frenchOnly in listOf(false, true)) {
            container.channelFilters.apply(id, hideUhd = false, france3Region = null, mergeChannels = true, frenchOnly = frenchOnly)
            println("===== Chaînes françaises seulement : $frenchOnly")
            val now = System.currentTimeMillis()
            fun <T : Any> all(source: PagingSource<Int, T>): List<T> = runBlocking {
                (source.load(PagingSource.LoadParams.Refresh(null, 1_000_000, false)) as PagingSource.LoadResult.Page).data
            }
            // Chaînes.
            val liveTile = dao.observeListedCount(id).first()
            val liveAll = all(dao.pageChannels(id, 0, "", "", now))
            println("Chaînes : tuile $liveTile, liste « Toutes » ${liveAll.size}, distinctes ${liveAll.map { it.itemKey }.distinct().size}")
            val liveGroups = dao.observeGroups(id, ContentKind.LIVE.name).first()
            report("Chaînes", liveGroups) { group -> all(dao.pageChannels(id, 1, group, "", now)).size }
            report("Rangées de chaînes", liveGroups) { group -> dao.channelRow(id, group, now, 1_000_000).size }
            // Films.
            val movieGroups = dao.observeGroups(id, ContentKind.MOVIE.name).first()
            val moviesAll = all(dao.pageMovies(id, 0, "", ""))
            println("Films : tuile ${dao.observeVisibleMovieCount(id).first()} (somme des catégories ${movieGroups.sumOf { it.count }}), liste « Tous » ${moviesAll.size}, distincts ${moviesAll.map { it.itemKey }.distinct().size}")
            report("Films", movieGroups) { group -> all(dao.pageMovies(id, 1, group, "")).size }
            report("Rangées de films", movieGroups) { group -> dao.movieRow(id, group, 1_000_000).size }
            // Séries.
            val seriesGroups = dao.observeGroups(id, ContentKind.SERIES.name).first()
            val seriesAll = all(dao.pageSeries(id, 0, "", ""))
            println("Séries : tuile ${dao.observeVisibleSeriesCount(id).first()} (somme des catégories ${seriesGroups.sumOf { it.count }}), liste « Toutes » ${seriesAll.size}, distinctes ${seriesAll.map { it.itemKey }.distinct().size}")
            report("Séries", seriesGroups) { group -> all(dao.pageSeries(id, 1, group, "")).size }
            report("Rangées de séries", seriesGroups) { group -> dao.seriesRow(id, group, 1_000_000).size }
        }
    }

    private suspend fun report(kind: String, groups: List<GroupRow>, listed: suspend (String) -> Int) {
        var different = 0
        for (group in groups) {
            val shown = listed(group.title.orEmpty())
            if (shown != group.count) {
                different++
                if (different <= 25) println("   $kind / « ${group.title} » : annoncé ${group.count}, listé $shown")
            }
        }
        println("   $kind : ${groups.size} catégories, $different avec un nombre différent de la liste")
    }
}
