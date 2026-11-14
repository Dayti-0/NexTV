package tv.nextv.app.ui

import android.app.Application
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.player.PlayerViewModel

/**
 * Route de l'écran du lecteur : la catégorie d'où une chaîne est ouverte doit arriver telle quelle au lecteur (haut/bas
 * y reste), quels que soient les caractères de son nom (« | », « + », « & », accents) ; sans catégorie, toutes les chaînes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerRouteTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theCategoryOfAChannelReachesThePlayer() {
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = "start") {
                composable("start") {}
                composable(Routes.PLAYER, arguments = playerArguments) {}
            }
        }
        /** Valeurs que le lecteur lit (son SavedStateHandle) pour la route [route]. */
        fun open(route: String): Map<String, Any?> = compose.runOnIdle {
            controller.navigate(route)
            val handle = checkNotNull(controller.currentBackStackEntry).savedStateHandle
            listOf(PlayerViewModel.ARG_CHANNEL_ID, PlayerViewModel.ARG_FAVORITES, PlayerViewModel.ARG_GROUP).associateWith { handle.get<Any>(it) }
        }

        val group = "FR| CANAL+ & CINÉ 100% / Sport ?#"
        open(Routes.groupPlayer(42, group)).let {
            assertEquals(42L, it[PlayerViewModel.ARG_CHANNEL_ID])
            assertEquals(group, it[PlayerViewModel.ARG_GROUP])
            assertEquals(false, it[PlayerViewModel.ARG_FAVORITES])
        }
        // Chaînes sans groupe.
        assertEquals("", open(Routes.groupPlayer(7, ""))[PlayerViewModel.ARG_GROUP])
        // « Toutes les chaînes », recherche… : pas de catégorie.
        assertNull(open(Routes.player(5))[PlayerViewModel.ARG_GROUP])
        open(Routes.favoritePlayer(6)).let {
            assertEquals(true, it[PlayerViewModel.ARG_FAVORITES])
            assertNull(it[PlayerViewModel.ARG_GROUP])
        }
    }
}
