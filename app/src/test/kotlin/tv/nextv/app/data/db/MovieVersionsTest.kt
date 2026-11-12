package tv.nextv.app.data.db

import android.app.Application
import android.os.Looper
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.AppContainer
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.ui.detail.DetailViewModel
import tv.nextv.app.ui.detail.movieVersions
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.catalog.QualityParser
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput

/**
 * Un film que la playlist propose en plusieurs copies (« À la dérive (Truefrench) » et « À la dérive (FR) », la même
 * en VOSTFR ou en 4K dans une autre catégorie) : une seule affiche dans les listes et la recherche, les versions se
 * choisissent sur la fiche, et les favoris ou reprises enregistrés sous l'ancien titre suivent le film.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MovieVersionsTest {

    private val container = AppContainer(RuntimeEnvironment.getApplication())
    private val dao get() = container.channelDao
    private var position = 0

    /** Film tel que la playlist l'écrit : titre, année et qualité viennent du vrai nettoyage du nom. */
    private fun movie(name: String, group: String = "Films"): Channel {
        val index = position++
        val url = "http://example.com/movie/u/p/$index.mkv"
        val result = ContentClassifier.classify(name, url, group)
        assertEquals(name, ContentKind.MOVIE, result.kind)
        return Channel(
            position = index, name = name, stream = StreamDescriptor(url), groupTitle = group, kind = ContentKind.MOVIE,
            title = result.title, year = result.year, quality = QualityParser.parse(name).quality,
        )
    }

    private suspend fun playlist(vararg channels: Channel): Long {
        val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
        container.playlistStore.replaceChannels(id, channels.toList()) {}
        return id
    }

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    private suspend fun listed(sourceId: Long, query: String = "") = dao.pageMovies(sourceId, 0, "", query).all().map { it.title }
    private suspend fun inGroup(sourceId: Long, group: String) = dao.pageMovies(sourceId, 1, group, "").all().map { it.title }
    private suspend fun groups(sourceId: Long) = dao.observeGroups(sourceId, "MOVIE").first().associate { it.title to it.count }
    private suspend fun nameOf(id: Long) = checkNotNull(dao.get(id)).name

    @Test
    fun theTwoCopiesOfThePhotoAreOneFilm() = runBlocking {
        val id = playlist(movie("À la dérive (Truefrench)"), movie("À la dérive (FR)"), movie("Arrival (2016)"))

        // Une seule affiche, partout : « Tous les films », la recherche, la catégorie, la rangée de l'accueil.
        assertEquals(listOf("À la dérive", "Arrival"), listed(id))
        assertEquals(listOf("À la dérive"), listed(id, "a la derive"))
        assertEquals(listOf("À la dérive", "Arrival"), inGroup(id, "Films"))
        assertEquals(listOf("À la dérive", "Arrival"), dao.movieRow(id, "Films", 10).map { it.title })
        assertEquals(mapOf("Films" to 2), groups(id))
        assertEquals(2, dao.count(id, "MOVIE"))

        // Les deux versions sur la fiche, celle qui est ouverte en premier.
        val shown = dao.pageMovies(id, 0, "", "").all().first()
        assertEquals("À la dérive (Truefrench)", nameOf(shown.id))
        val versions = movieVersions(dao.movieVersions(id, shown.itemKey, shown.id), shown.id)
        assertEquals(listOf("TRUEFRENCH", "FR"), versions.map { it.label })
        assertEquals(shown.id, versions.first().id)
    }

    @Test
    fun theFrenchVersionIsShownBeforeABetterQualityInAnotherLanguage() = runBlocking {
        val id = playlist(
            movie("Avatar (2009) VOSTFR 4K", "Films VOSTFR"),
            movie("Avatar (2009) HD", "Films FR"),
            movie("Avatar (2009) FHD", "Films FR"),
            movie("EN: Avatar (2009) 4K", "Films EN"),
        )

        // Hors catégorie : la version française de la meilleure qualité, et non la 4K sous-titrée.
        val shown = dao.pageMovies(id, 0, "", "").all().single()
        assertEquals("Avatar (2009) FHD", nameOf(shown.id))
        assertEquals("FHD", shown.quality)
        assertEquals("Avatar (2009) FHD", nameOf(checkNotNull(dao.movieIdByKey(id, shown.itemKey))))
        assertEquals("Avatar (2009) FHD", nameOf(dao.moviesByKey(id, listOf(shown.itemKey)).first().id))
        assertEquals(listOf("Avatar"), dao.newestMovies(id, 2000, 10).map { it.title })

        // Chaque catégorie garde le film, une fois, avec sa propre copie.
        assertEquals(mapOf("Films VOSTFR" to 1, "Films FR" to 1, "Films EN" to 1), groups(id))
        assertEquals("Avatar (2009) VOSTFR 4K", nameOf(dao.pageMovies(id, 1, "Films VOSTFR", "").all().single().id))
        assertEquals("Avatar (2009) FHD", nameOf(dao.pageMovies(id, 1, "Films FR", "").all().single().id))
        assertEquals("Avatar (2009) FHD", nameOf(dao.movieRow(id, "Films FR", 10).single().id))

        // Catégorie de la version française masquée : le film reste dans « Tous les films », avec une autre copie.
        container.hiddenGroupDao.hide(HiddenGroupEntity(id, "MOVIE", "Films FR"))
        assertEquals("Avatar (2009) VOSTFR 4K", nameOf(dao.pageMovies(id, 0, "", "").all().single().id))
        assertEquals(mapOf("Films VOSTFR" to 1, "Films EN" to 1), groups(id))

        // La fiche : la copie ouverte d'abord, puis les autres ; celles de la catégorie masquée n'y sont pas.
        val opened = dao.pageMovies(id, 1, "Films EN", "").all().single().id
        val versions = movieVersions(dao.movieVersions(id, shown.itemKey, opened), opened)
        assertEquals(listOf("EN · 4K", "VOSTFR · 4K"), versions.map { it.label })
    }

    @Test
    fun trueFrWrittenShortIsTheSameFilm() = runBlocking {
        val id = playlist(movie("À la dérive (True FR) 2018"), movie("À la dérive  (FR) 2018"))
        assertEquals(listOf("À la dérive"), listed(id))
        val shown = dao.pageMovies(id, 0, "", "").all().single()
        assertEquals(listOf("TRUEFRENCH", "FR"), movieVersions(dao.movieVersions(id, shown.itemKey, shown.id), shown.id).map { it.label })
    }

    @Test
    fun withUhdHiddenTheSheetOffersTheOtherVersionFirst() = runBlocking {
        val id = playlist(movie("Hunger Games (2012) 4K"), movie("Hunger Games (2012) FHD"))

        // Affiche : la 4K (meilleure qualité). Fiche avec « Masquer les chaînes 4K » : la FHD d'abord, la 4K ensuite.
        val shown = dao.pageMovies(id, 0, "", "").all().single()
        assertEquals("Hunger Games (2012) 4K", nameOf(shown.id))
        val rows = dao.movieVersions(id, shown.itemKey, shown.id)
        assertEquals(listOf("4K", "FHD"), movieVersions(rows, shown.id).map { it.label })
        assertEquals(listOf("FHD", "4K"), movieVersions(rows, shown.id, avoidUhd = true).map { it.label })
    }

    @Test
    fun aFilmWithoutYearJoinsItsDatedCopy() = runBlocking {
        val id = playlist(
            movie("Dune (2021)"), movie("Dune (FR)"),
            // Le titre existe à deux dates : rien n'est supposé pour la copie sans année.
            movie("Solaris (1972)"), movie("Solaris (2002)"), movie("Solaris"),
        )
        assertEquals(listOf("Dune", "Solaris", "Solaris", "Solaris"), listed(id))
        assertEquals(listOf("movie:dune:2021"), dao.itemKeysBetween(id, "MOVIE", "movie:dune:", "movie:dune;"))
        assertEquals(2021, dao.get(checkNotNull(dao.movieIdByKey(id, "movie:dune:2021")))?.year)
    }

    @Test
    fun titlesInAnotherScriptStaySeparateFilms() = runBlocking {
        val id = playlist(movie("الفيل الأزرق", "Films AR"), movie("ولاد رزق", "Films AR"), movie("Брат 2", "Films RU"), movie("Брат", "Films RU"))
        assertEquals(4, listed(id).size)
        assertEquals(4, dao.pageMovies(id, 0, "", "").all().map { it.itemKey }.distinct().size)
        assertEquals(mapOf("Films AR" to 2, "Films RU" to 2), groups(id))
    }

    @Test
    fun copiesOfTheSameStreamAndSameLabelAreToldApart() = runBlocking {
        val id = playlist(movie("Heat (1995)", "Films Action"), movie("Heat (1995)", "Films Policier"), movie("Alien (1979)"))
        val shown = dao.pageMovies(id, 0, "", "").all().first()
        // Rien d'écrit dans les noms : la catégorie distingue les deux copies.
        assertEquals(listOf("Films Action", "Films Policier"), movieVersions(dao.movieVersions(id, shown.itemKey, shown.id), shown.id).map { it.label })
        // Un film en une seule copie n'a pas de versions à proposer.
        val alien = checkNotNull(dao.movieIdByKey(id, "movie:alien:1979"))
        assertEquals(1, movieVersions(dao.movieVersions(id, "movie:alien:1979", alien), alien).size)
    }

    @Test
    fun favouritesResumeAndWatchedFollowTheFilmWhenItsTitleIsCleaned() = runBlocking {
        val id = playlist(movie("À la dérive (Truefrench)"), movie("À la dérive (FR)"), movie("Dune (2021)"), movie("Dune (FR)"), movie("Arrival (2016)"))
        // Enregistrés par une version précédente, quand les mentions de version restaient dans le titre.
        suspend fun favorites() = container.favorites.observeKeys(id).first().toSet()
        container.favorites.toggle(id, ContentKind.MOVIE, "movie:a la derive truefrench:", "À la dérive (Truefrench", null)
        container.favorites.toggle(id, ContentKind.MOVIE, "movie:a la derive fr:", "À la dérive (FR)", null)
        container.favorites.toggle(id, ContentKind.MOVIE, "movie:arrival:2016", "Arrival", null)
        container.favorites.toggle(id, ContentKind.MOVIE, "movie:retire:2019", "Retiré", null)
        container.progress.save(ProgressEntity(id, "movie:dune fr:", "MOVIE", 999, "Dune (FR)", null, null, 600_000, 3_600_000, 1_000))
        container.watched.mark(id, "movie:a la derive fr:")

        container.itemKeyRelinker.relink(id)

        // Les deux favoris du même film n'en font plus qu'un ; les autres ne bougent pas (le film retiré non plus).
        assertEquals(setOf("movie:a la derive:", "movie:arrival:2016", "movie:retire:2019"), favorites())
        assertEquals(listOf("À la dérive", "Arrival"), dao.observeFavoriteMovies(id).first().map { it.title })
        // La reprise suit le film, qui a pris l'année de son autre copie.
        assertNull(container.progress.get(id, "movie:dune fr:"))
        val resumed = checkNotNull(container.progress.get(id, "movie:dune:2021"))
        assertEquals(600_000, resumed.positionMs)
        container.progress.relink(id, dao)
        assertEquals(dao.movieIdByKey(id, "movie:dune:2021"), container.progress.get(id, "movie:dune:2021")?.channelId)
        assertEquals(setOf("movie:a la derive:"), container.watched.observeKeys(id).first())

        // Une seconde passe ne change rien.
        container.itemKeyRelinker.relink(id)
        assertEquals(3, favorites().size)
    }

    @Test
    fun theSheetOffersTheVersionsAndPlaysTheChosenOne() {
        val (id, first) = runBlocking {
            val id = playlist(movie("À la dérive (Truefrench)"), movie("À la dérive (FR)"), movie("À la dérive (VOSTFR) 4K"))
            id to dao.pageMovies(id, 0, "", "").all().single().id
        }
        fun idle(until: () -> Boolean) {
            repeat(300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (until()) return
                Thread.sleep(10)
            }
        }
        val viewModel = DetailViewModel(container, first, ContentKind.MOVIE)
        idle { viewModel.state.value.versions.isNotEmpty() }
        val state = viewModel.state.value
        assertEquals(listOf("TRUEFRENCH", "FR", "VOSTFR · 4K"), state.versions.map { it.label })
        assertEquals(0, state.selectedVersion)
        assertEquals(first, state.item?.firstId)

        // « Version » passe à la suivante : « Lecture » la lit, et la qualité affichée est la sienne.
        viewModel.nextVersion()
        viewModel.nextVersion()
        val chosen = viewModel.state.value
        assertEquals(2, chosen.selectedVersion)
        assertEquals("À la dérive (VOSTFR) 4K", runBlocking { nameOf(checkNotNull(chosen.item).firstId) })
        assertEquals("UHD", chosen.item?.quality)
        assertNotEquals(first, chosen.item?.firstId)
        viewModel.nextVersion()
        assertEquals(first, viewModel.state.value.item?.firstId)

        // Film déjà commencé dans une autre version : la fiche la propose d'elle-même.
        val vostfr = chosen.item!!.firstId
        runBlocking { container.progress.save(ProgressEntity(id, state.item!!.itemKey, "MOVIE", vostfr, "À la dérive", null, null, 600_000, 3_600_000, 1_000)) }
        val reopened = DetailViewModel(container, first, ContentKind.MOVIE)
        idle { reopened.state.value.progress != null && reopened.state.value.versions.isNotEmpty() }
        assertEquals(vostfr, reopened.state.value.item?.firstId)
        assertTrue(reopened.state.value.selectedVersion > 0)

        // « Masquer les chaînes 4K » : la 4K passe en dernier.
        runBlocking { container.settings.setHideUhd(true) }
        val noUhd = DetailViewModel(container, first, ContentKind.MOVIE)
        idle { noUhd.state.value.versions.isNotEmpty() }
        assertEquals("UHD", noUhd.state.value.versions.last().quality)
    }
}
