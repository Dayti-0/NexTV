package tv.nextv.app.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.nextv.app.ui.channels.ChannelsContent
import tv.nextv.app.ui.editor.SourceEditorContent
import tv.nextv.app.ui.guide.GuideContent
import tv.nextv.app.ui.player.PlayerOverlay
import tv.nextv.app.ui.settings.ProxySettingsContent
import tv.nextv.app.ui.sources.SourcesContent
import tv.nextv.app.ui.sources.SourcesUiState
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.core.log.Redactor
import java.util.TimeZone

/**
 * Captures d'écran des écrans principaux, générées par `./gradlew :app:recordRoborazziDebug`
 * dans `app/build/screenshots/`. Les écrans sont rendus avec des données fictives ([SampleData]).
 */
abstract class ScreenshotSuite(private val device: String) {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/screenshots/${device}_$name.png") {
            NexTvTheme { content() }
        }
    }

    @Test
    fun playlists() = capture("01_playlists") {
        SourcesContent(
            state = SampleData.sources,
            redactor = Redactor(),
            snackbarHostState = remember { SnackbarHostState() },
            onAdd = {},
            onOpen = {},
            onGuide = {},
            onRefresh = {},
            onRefreshAll = {},
            onEdit = {},
            onDelete = {},
            onSettings = {},
            now = SampleData.NOW,
        )
    }

    @Test
    fun playlistsEmpty() = capture("02_playlists_vide") {
        SourcesContent(
            state = SourcesUiState(loaded = true),
            redactor = Redactor(),
            snackbarHostState = remember { SnackbarHostState() },
            onAdd = {},
            onOpen = {},
            onGuide = {},
            onRefresh = {},
            onRefreshAll = {},
            onEdit = {},
            onDelete = {},
            onSettings = {},
            now = SampleData.NOW,
        )
    }

    @Test
    fun channels() = capture("03_chaines") {
        val items = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
        ChannelsContent(
            sourceName = "Bouquet Maison",
            sourceError = null,
            loaded = true,
            hasGuide = true,
            groups = SampleData.groups,
            selectedGroup = null,
            query = "",
            channels = items,
            refreshing = false,
            now = SampleData.NOW,
            onBack = {},
            onRefresh = {},
            onGuide = {},
            onQueryChange = {},
            onSelectGroup = {},
            onPlay = {},
        )
    }

    @Test
    fun guide() = capture("04_guide") {
        GuideContent(
            state = SampleData.guide,
            sourceName = "Bouquet Maison",
            groups = SampleData.groups,
            selectedGroup = null,
            onBack = {},
            onSelectGroup = {},
            onPlay = {},
        )
    }

    @Test
    fun player() = capture("05_lecteur") {
        FakeVideo()
        PlayerOverlay(
            state = SampleData.player,
            now = SampleData.NOW,
            visible = true,
            showTouchControls = device == "phone",
            onBack = {},
            onZap = {},
            onRetry = {},
        )
    }

    @Test
    fun playerError() = capture("06_lecteur_erreur") {
        FakeVideo()
        PlayerOverlay(
            state = SampleData.playerError,
            now = SampleData.NOW,
            visible = true,
            showTouchControls = device == "phone",
            onBack = {},
            onZap = {},
            onRetry = {},
        )
    }

    @Test
    fun editor() = capture("07_edition") {
        SourceEditorContent(
            state = SampleData.editor,
            snackbarHostState = remember { SnackbarHostState() },
            onEdit = {},
            onPick = {},
            onSave = {},
            onBack = {},
        )
    }

    @Test
    fun editorErrors() = capture("08_edition_erreur") {
        SourceEditorContent(
            state = SampleData.editorWithErrors,
            snackbarHostState = remember { SnackbarHostState() },
            onEdit = {},
            onPick = {},
            onSave = {},
            onBack = {},
        )
    }

    @Test
    fun settings() = capture("09_reglages") {
        ProxySettingsContent(
            state = SampleData.proxy,
            snackbarHostState = remember { SnackbarHostState() },
            onEdit = {},
            onToggleScope = { _, _ -> },
            onSave = {},
            onBack = {},
        )
    }

    /** Fond imitant une image vidéo, pour juger la lisibilité du bandeau. */
    @Composable
    private fun FakeVideo() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF3B5A7A), Color(0xFF8C6A4F), Color(0xFF1E2B22)))),
        )
    }
}

private const val SDK = 35

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], application = Application::class, qualifiers = "fr-rFR-w411dp-h891dp-port-xxhdpi")
class PhoneScreenshots : ScreenshotSuite("phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class TvScreenshots : ScreenshotSuite("tv")
