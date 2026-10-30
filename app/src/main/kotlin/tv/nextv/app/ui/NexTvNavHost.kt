package tv.nextv.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.nextv.app.ui.detail.DetailScreen
import tv.nextv.app.ui.editor.SourceEditorScreen
import tv.nextv.app.ui.editor.SourceEditorViewModel
import tv.nextv.app.ui.home.HomeScreen
import tv.nextv.app.ui.player.PlayerScreen
import tv.nextv.app.ui.player.PlayerViewModel
import tv.nextv.app.ui.settings.ProxySettingsScreen
import tv.nextv.app.ui.settings.SettingsScreen
import tv.nextv.app.ui.sources.SourcesScreen
import tv.nextv.app.ui.startup.StartupScreen
import tv.nextv.app.ui.startup.StartupViewModel
import tv.nextv.app.ui.startup.WelcomeScreen
import tv.nextv.core.model.ContentKind

private object Routes {
    const val STARTUP = "startup?${StartupViewModel.ARG_RELOAD}={${StartupViewModel.ARG_RELOAD}}"
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val EDITOR = "editor?${SourceEditorViewModel.ARG_SOURCE_ID}={${SourceEditorViewModel.ARG_SOURCE_ID}}"
    const val PLAYER = "player/{${PlayerViewModel.ARG_CHANNEL_ID}}"
    const val MOVIE = "movie/{$ARG_ITEM}"
    const val SERIES = "series/{$ARG_ITEM}"
    const val SETTINGS = "settings"
    const val PROXY = "proxy"

    fun startup(reload: Boolean = false) = "startup?${StartupViewModel.ARG_RELOAD}=$reload"
    fun editor(sourceId: Long? = null) = "editor?${SourceEditorViewModel.ARG_SOURCE_ID}=${sourceId ?: -1}"
    fun player(channelId: Long) = "player/$channelId"
    fun movie(channelId: Long) = "movie/$channelId"
    fun series(channelId: Long) = "series/$channelId"
}

private const val ARG_ITEM = "itemId"

/**
 * Démarrage → accueil (ou bienvenue sans playlist). Changer de playlist active repasse par
 * l'écran de démarrage, qui charge son contenu avec une progression réelle.
 */
@Composable
fun NexTvNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.STARTUP) {
        composable(
            Routes.STARTUP,
            arguments = listOf(
                navArgument(StartupViewModel.ARG_RELOAD) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) {
            StartupScreen(
                onHome = { navController.restartAt(Routes.HOME) },
                onWelcome = { navController.restartAt(Routes.WELCOME) },
                onChoosePlaylist = { navController.navigate(Routes.PLAYLISTS) },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(onAdd = { navController.navigate(Routes.editor()) })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onPlay = { navController.navigate(Routes.player(it)) },
                onMovie = { navController.navigate(Routes.movie(it)) },
                onSeries = { navController.navigate(Routes.series(it)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.MOVIE,
            arguments = listOf(navArgument(ARG_ITEM) { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                channelId = entry.arguments?.getLong(ARG_ITEM) ?: -1,
                kind = ContentKind.MOVIE,
                onPlay = { navController.navigate(Routes.player(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SERIES,
            arguments = listOf(navArgument(ARG_ITEM) { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                channelId = entry.arguments?.getLong(ARG_ITEM) ?: -1,
                kind = ContentKind.SERIES,
                onPlay = { navController.navigate(Routes.player(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument(PlayerViewModel.ARG_CHANNEL_ID) { type = NavType.LongType }),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onProxy = { navController.navigate(Routes.PROXY) },
            )
        }
        composable(Routes.PLAYLISTS) {
            SourcesScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.editor()) },
                onEdit = { navController.navigate(Routes.editor(it)) },
                onReload = { navController.restartAt(Routes.startup()) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument(SourceEditorViewModel.ARG_SOURCE_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            SourceEditorScreen(
                onClose = { navController.popBackStack() },
                onReload = { navController.restartAt(Routes.startup(reload = true)) },
            )
        }
        composable(Routes.PROXY) {
            ProxySettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** Ouvre [route] en vidant la pile : « Retour » quitte alors l'application. */
private fun NavHostController.restartAt(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
