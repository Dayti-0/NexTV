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

/**
 * Cinéma Premium et Streaming n'ont pas de catégories : une catégorie ouverte dans le Live Center
 * (« Français ») ne doit pas y rester, sinon l'interface s'affiche en catégories et grille.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeLayoutCategoryTest {

    private fun idle(until: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun rowsInterfacesShowTheirRowsAfterACategoryWasOpened() {
        val application = RuntimeEnvironment.getApplication()
        val container = AppContainer(application)
        runBlocking { container.settings.setHomeLayout(HomeLayout.LIVE_CENTER) }
        val viewModel = HomeViewModel(container, application.resources)
        idle { viewModel.layout.value == HomeLayout.LIVE_CENTER }
        viewModel.selectSection(HomeSection.MOVIES)
        viewModel.selectCategory(Category.Group("Français"))
        idle { viewModel.category.value == Category.Group("Français") }
        assertEquals(Category.Group("Français"), viewModel.category.value)

        runBlocking { container.settings.setHomeLayout(HomeLayout.PREMIUM) }
        idle { viewModel.layout.value == HomeLayout.PREMIUM && viewModel.category.value == Category.All }
        assertEquals(HomeLayout.PREMIUM, viewModel.layout.value)
        assertEquals(Category.All, viewModel.category.value)
    }

    /** Recherche : dans tous les films (la catégorie ouverte est quittée) ; une catégorie choisie efface la recherche. */
    @Test
    fun searchLooksEverywhereAndACategoryClearsIt() {
        val application = RuntimeEnvironment.getApplication()
        val container = AppContainer(application)
        runBlocking { container.settings.setHomeLayout(HomeLayout.LIVE_CENTER) }
        val viewModel = HomeViewModel(container, application.resources)
        idle { viewModel.layout.value == HomeLayout.LIVE_CENTER }
        viewModel.selectSection(HomeSection.MOVIES)
        viewModel.selectCategory(Category.Group("Action"))
        idle { viewModel.category.value == Category.Group("Action") }
        viewModel.setQuery("avatar")
        idle { viewModel.category.value == Category.All && viewModel.query.value == "avatar" }
        assertEquals(Category.All, viewModel.category.value)
        assertEquals("avatar", viewModel.query.value)
        viewModel.selectCategory(Category.Group("Français"))
        idle { viewModel.query.value.isEmpty() && viewModel.category.value == Category.Group("Français") }
        assertEquals("", viewModel.query.value)
        assertEquals(Category.Group("Français"), viewModel.category.value)
    }
}
