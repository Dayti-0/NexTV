package tv.nextv.app.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import tv.nextv.app.ui.detail.DetailContent
import tv.nextv.app.ui.editor.SourceEditorContent
import tv.nextv.app.ui.guide.GuideContent
import tv.nextv.app.ui.guide.ProgrammeSheetContent
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.home.HomeActions
import tv.nextv.app.ui.home.HomeContent
import tv.nextv.app.ui.home.HomeSection
import tv.nextv.app.ui.home.ReorderState
import tv.nextv.app.ui.player.ChannelMenu
import tv.nextv.app.ui.player.PlayerOverlay
import tv.nextv.app.ui.settings.ProxySettingsContent
import tv.nextv.app.ui.settings.SettingsContent
import tv.nextv.app.ui.sources.SourcesContent
import tv.nextv.app.ui.startup.StartupContent
import tv.nextv.app.ui.startup.StartupStep
import tv.nextv.app.ui.startup.WelcomeScreen
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.ContentKind
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
    fun startup() = capture("01_demarrage") {
        StartupContent(SampleData.startup, onRetry = {}, onContinue = {}, onChoosePlaylist = {}, animate = false)
    }

    @Test
    fun startupDone() = capture("02_demarrage_pret") {
        StartupContent(
            SampleData.startup.copy(percent = 100, step = StartupStep.READY, bytesReceived = null, summary = SampleData.startupSummary),
            onRetry = {},
            onContinue = {},
            onChoosePlaylist = {},
            animate = false,
        )
    }

    @Test
    fun startupError() = capture("03_demarrage_erreur") {
        StartupContent(SampleData.startupError, onRetry = {}, onContinue = {}, onChoosePlaylist = {}, animate = false)
    }

    @Test
    fun welcome() = capture("04_bienvenue") {
        WelcomeScreen(onAdd = {}, animate = false)
    }

    @Test
    fun homeTv() = capture("05_accueil_tv") {
        Home(HomeSection.TV, Category.All)
    }

    @Test
    fun homeFavorites() = capture("06_accueil_favoris") {
        Home(HomeSection.TV, Category.Favorites, reorder = ReorderState(active = true, movingKey = SampleData.favoriteChannels[1].itemKey))
    }

    @Test
    fun homeMovies() = capture("07_accueil_films") {
        Home(HomeSection.MOVIES, Category.All)
    }

    @Test
    fun homeSeries() = capture("08_accueil_series") {
        Home(HomeSection.SERIES, Category.All)
    }

    @Test
    fun guide() = capture("09_guide") {
        Home(HomeSection.GUIDE, Category.All)
    }

    @Test
    fun programme() = capture("10_programme") {
        val channel = SampleData.guide.channels[7]
        val programme = channel.programmes.first { it.startMillis <= SampleData.NOW && it.stopMillis > SampleData.NOW }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
                ProgrammeSheetContent(channel, programme, SampleData.NOW, onWatch = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun movie() = capture("11_fiche_film") {
        DetailContent(
            state = SampleData.movieDetail,
            kind = ContentKind.MOVIE,
            snackbarHostState = remember { SnackbarHostState() },
            onPlay = {},
            onTrailer = {},
            onToggleFavorite = {},
            onSelectSeason = {},
            onBack = {},
        )
    }

    @Test
    fun series() = capture("12_fiche_serie") {
        DetailContent(
            state = SampleData.seriesDetail,
            kind = ContentKind.SERIES,
            snackbarHostState = remember { SnackbarHostState() },
            onPlay = {},
            onTrailer = {},
            onToggleFavorite = {},
            onSelectSeason = {},
            onBack = {},
        )
    }

    @Test
    fun player() = capture("13_lecteur") {
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
    fun playerMenu() = capture("14_lecteur_menu") {
        FakeVideo()
        val channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
        ChannelMenu(
            menu = SampleData.menu,
            groups = SampleData.groups,
            channels = channels,
            favorites = SampleData.favoriteChannels,
            now = SampleData.NOW,
            onCategory = {},
            onPlay = {},
            onHome = {},
        )
    }

    @Test
    fun playerError() = capture("15_lecteur_erreur") {
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
    fun settings() = capture("16_reglages") {
        SettingsContent(
            state = SampleData.settings,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onPlaylists = {},
            onRefreshPlaylist = {},
            onRefreshGuide = {},
            onAutoQuality = {},
            onSaveTmdbKey = {},
            onClearTmdbKey = {},
            onProxy = {},
            now = SampleData.NOW,
        )
    }

    @Test
    fun playlists() = capture("17_playlists") {
        SourcesContent(
            state = SampleData.sources,
            redactor = Redactor(),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onAdd = {},
            onActivate = {},
            onRefresh = {},
            onRefreshAll = {},
            onEdit = {},
            onDelete = {},
            now = SampleData.NOW,
        )
    }

    @Test
    fun editor() = capture("18_edition") {
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
    fun editorErrors() = capture("19_edition_erreur") {
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
    fun proxy() = capture("20_proxy") {
        ProxySettingsContent(
            state = SampleData.proxy,
            snackbarHostState = remember { SnackbarHostState() },
            onEdit = {},
            onToggleScope = { _, _ -> },
            onSave = {},
            onBack = {},
        )
    }

    @Composable
    private fun Home(section: HomeSection, category: Category, reorder: ReorderState = ReorderState()) {
        val channels = remember { flowOf(PagingData.from(SampleData.channels)) }.collectAsLazyPagingItems()
        val movies = remember { flowOf(PagingData.from(SampleData.movies)) }.collectAsLazyPagingItems()
        val series = remember { flowOf(PagingData.from(SampleData.series)) }.collectAsLazyPagingItems()
        HomeContent(
            section = section,
            sourceName = "Bouquet Maison",
            groups = when (section) {
                HomeSection.MOVIES -> SampleData.movieGroups
                HomeSection.SERIES -> SampleData.seriesGroups
                else -> SampleData.groups
            },
            category = category,
            query = "",
            channels = channels,
            favoriteChannels = SampleData.favoriteChannels,
            movies = movies,
            favoriteMovies = listOf(SampleData.movies[0], SampleData.movies[5]),
            series = series,
            favoriteSeries = listOf(SampleData.series[0]),
            favoriteKeys = SampleData.favoriteKeys,
            reorder = reorder,
            now = SampleData.NOW,
            snackbarHostState = remember { SnackbarHostState() },
            actions = HomeActions(),
            guide = { modifier ->
                GuideContent(
                    state = SampleData.guide,
                    groups = SampleData.groups,
                    selectedGroup = null,
                    onSelectGroup = {},
                    onPlay = {},
                    modifier = modifier,
                )
            },
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
