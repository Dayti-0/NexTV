package tv.nextv.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.nextv.app.ui.channels.ChannelsScreen
import tv.nextv.app.ui.channels.ChannelsViewModel
import tv.nextv.app.ui.editor.SourceEditorScreen
import tv.nextv.app.ui.editor.SourceEditorViewModel
import tv.nextv.app.ui.guide.GuideScreen
import tv.nextv.app.ui.guide.GuideViewModel
import tv.nextv.app.ui.player.PlayerScreen
import tv.nextv.app.ui.player.PlayerViewModel
import tv.nextv.app.ui.settings.ProxySettingsScreen
import tv.nextv.app.ui.sources.SourcesScreen

private object Routes {
    const val SOURCES = "sources"
    const val EDITOR = "editor?${SourceEditorViewModel.ARG_SOURCE_ID}={${SourceEditorViewModel.ARG_SOURCE_ID}}"
    const val CHANNELS = "channels/{${ChannelsViewModel.ARG_SOURCE_ID}}"
    const val PLAYER = "player/{${PlayerViewModel.ARG_CHANNEL_ID}}"
    const val GUIDE = "guide/{${GuideViewModel.ARG_SOURCE_ID}}"
    const val SETTINGS = "settings"

    fun editor(sourceId: Long? = null) = "editor?${SourceEditorViewModel.ARG_SOURCE_ID}=${sourceId ?: -1}"
    fun channels(sourceId: Long) = "channels/$sourceId"
    fun player(channelId: Long) = "player/$channelId"
    fun guide(sourceId: Long) = "guide/$sourceId"
}

@Composable
fun NexTvNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SOURCES) {
        composable(Routes.SOURCES) {
            SourcesScreen(
                onAdd = { navController.navigate(Routes.editor()) },
                onEdit = { navController.navigate(Routes.editor(it)) },
                onOpen = { navController.navigate(Routes.channels(it)) },
                onGuide = { navController.navigate(Routes.guide(it)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
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
            SourceEditorScreen(onDone = { navController.popBackStack() })
        }
        composable(
            Routes.CHANNELS,
            arguments = listOf(navArgument(ChannelsViewModel.ARG_SOURCE_ID) { type = NavType.LongType }),
        ) {
            ChannelsScreen(
                onBack = { navController.popBackStack() },
                onPlay = { navController.navigate(Routes.player(it)) },
                onGuide = { navController.navigate(Routes.guide(it)) },
            )
        }
        composable(
            Routes.GUIDE,
            arguments = listOf(navArgument(GuideViewModel.ARG_SOURCE_ID) { type = NavType.LongType }),
        ) {
            GuideScreen(
                onBack = { navController.popBackStack() },
                onPlay = { navController.navigate(Routes.player(it)) },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument(PlayerViewModel.ARG_CHANNEL_ID) { type = NavType.LongType }),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            ProxySettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
