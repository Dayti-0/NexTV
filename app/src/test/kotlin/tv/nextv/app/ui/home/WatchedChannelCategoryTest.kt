package tv.nextv.app.ui.home

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
 * Retour du lecteur sur Live TV : la chaîne regardée a été choisie dans une autre catégorie du menu des chaînes, ou
 * atteinte en zappant hors de la catégorie ouverte. L'accueil passe à une catégorie qui la contient, pour que la
 * sélection revienne sur elle ; sans changement de catégorie, rien ne bouge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchedChannelCategoryTest {

    private val application = RuntimeEnvironment.getApplication()
    private val container = AppContainer(application)

    private fun idle(until: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
    }

    private var position = 0

    private fun live(name: String, group: String): Channel {
        val parsed = QualityParser.parse(name)
        val index = position++
        return Channel(
            position = index, name = name, stream = StreamDescriptor("http://example.com/live/u/p/$index.ts"), groupTitle = group,
            kind = ContentKind.LIVE, title = parsed.baseName, variantKey = LiveVariants.key(parsed.variantKey), quality = parsed.quality,
        )
    }

    private fun home(): HomeViewModel {
        runBlocking {
            val id = container.sourceRepository.add(SourceInput("P", SourceLocation.Remote("http://example.com/p.m3u")))
            container.playlistStore.replaceChannels(
                id,
                listOf(
                    live("|FR| TF1 HD", "FR TV HD"), live("|FR| FRANCE 2 HD", "FR TV HD"), live("|FR| CANAL+ HD", "FR TV HD"),
                    live("|FR| BEIN SPORTS 1 FHD", "FR SPORTS"), live("|FR| RMC SPORT 1 FHD", "FR SPORTS"),
                ),
            ) {}
            container.settings.setActivePlaylist(id)
            container.settings.setHomeLayout(HomeLayout.LIVE_CENTER)
        }
        val viewModel = HomeViewModel(container, application.resources)
        idle { viewModel.sourceId.value != null && viewModel.layout.value == HomeLayout.LIVE_CENTER }
        viewModel.selectSection(HomeSection.TV)
        viewModel.selectCategory(Category.Group("FR TV HD"))
        idle { viewModel.category.value == Category.Group("FR TV HD") }
        // Chaîne ouverte depuis l'accueil : le lecteur cache l'accueil (comme PlayerViewModel à son ouverture).
        container.playerOpened()
        idle { container.playbackOpen.value }
        container.lastLiveCategory.value = null
        container.lastLiveChannel.value = "live:fr tf1"
        return viewModel
    }

    @Test
    fun aChannelPickedInAnotherCategoryOfThePlayerMenuBringsTheHomeToIt() {
        val viewModel = home()
        // Menu des chaînes du lecteur : catégorie « FR SPORTS », beIN Sports 1.
        container.lastLiveCategory.value = Category.Group("FR SPORTS")
        container.lastLiveChannel.value = "live:fr bein sport 1"
        idle { viewModel.category.value == Category.Group("FR SPORTS") }
        assertEquals(Category.Group("FR SPORTS"), viewModel.category.value)
    }

    @Test
    fun zappingOutOfTheCategoryBringsTheHomeToAllChannels() {
        val viewModel = home()
        container.lastLiveChannel.value = "live:fr rmc sport 1"
        idle { viewModel.category.value == Category.All }
        assertEquals(Category.All, viewModel.category.value)
    }

    /**
     * Accueil déjà affiché (lecteur fermé) quand la catégorie de la chaîne regardée est trouvée : la liste ne change pas
     * sous les yeux (elle emportait la sélection, qu'Android donnait au menu de gauche de Streaming).
     */
    @Test
    fun theHomeKeepsItsCategoryOnceShown() {
        val viewModel = home()
        container.playerClosed()
        idle { !container.playbackOpen.value }
        container.lastLiveChannel.value = "live:fr rmc sport 1"
        idle { false }
        assertEquals(Category.Group("FR TV HD"), viewModel.category.value)
    }

    @Test
    fun zappingInsideTheCategoryKeepsIt() {
        val viewModel = home()
        container.lastLiveChannel.value = "live:fr france 2"
        idle { false }
        assertEquals(Category.Group("FR TV HD"), viewModel.category.value)
    }
}
