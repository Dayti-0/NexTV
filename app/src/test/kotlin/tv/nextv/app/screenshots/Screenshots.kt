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
import tv.nextv.app.data.db.GroupVisibilityRow
import tv.nextv.app.data.settings.GridColumns
import tv.nextv.app.ui.detail.DetailContent
import tv.nextv.app.ui.editor.SourceEditorContent
import tv.nextv.app.ui.guide.GuideContent
import tv.nextv.app.ui.guide.GuideViewModel
import tv.nextv.app.ui.guide.ProgrammeSheetContent
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.home.HomeActions
import tv.nextv.app.ui.home.HomeContent
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.app.ui.home.HomeSection
import tv.nextv.app.ui.home.ReorderState
import tv.nextv.app.ui.player.MenuChannel
import tv.nextv.app.ui.player.ChannelMenu
import tv.nextv.app.ui.player.PlaybackInfoPanel
import tv.nextv.app.ui.player.ResumePrompt
import tv.nextv.app.ui.player.ResumePromptCard
import tv.nextv.app.ui.player.PlayerOverlay
import tv.nextv.app.ui.football.FootballContent
import tv.nextv.app.ui.football.StandingsState
import tv.nextv.app.ui.football.TeamSheetContent
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.core.football.Competition
import tv.nextv.app.ui.settings.ChannelGroupsContent
import tv.nextv.app.ui.settings.ProxySettingsContent
import tv.nextv.app.ui.settings.SettingsContent
import tv.nextv.app.ui.sources.SourcesContent
import tv.nextv.app.ui.startup.StartupContent
import tv.nextv.app.ui.startup.StartupStep
import tv.nextv.app.ui.startup.WelcomeScreen
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.app.ui.player.PlayerMatch
import tv.nextv.app.ui.player.PlayerProgramme
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

    private fun capture(name: String, theme: ThemeStyle = ThemeStyle.NIGHT, content: @Composable () -> Unit) {
        captureRoboImage("build/screenshots/${device}_$name.png") {
            NexTvTheme(theme) { content() }
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
    fun homeTvPreview() = capture("05c_accueil_apercu") {
        Home(HomeSection.TV, Category.All, preview = true)
    }

    @Test
    fun homeTvDense() = capture("05b_accueil_tv_9_par_ligne") {
        Home(HomeSection.TV, Category.All, grid = GridColumns(channels = 9))
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
    fun homeMoviesDense() = capture("07b_accueil_films_10_par_ligne") {
        Home(HomeSection.MOVIES, Category.All, grid = GridColumns(posters = 10))
    }

    @Test
    fun homeContinue() = capture("08b_reprendre") {
        Home(HomeSection.SERIES, Category.ContinueWatching)
    }

    @Test
    fun homeCinema() = capture("05d_accueil_cinema", ThemeStyle.CINEMA) {
        Home(HomeSection.TV, Category.All)
    }

    @Test
    fun homeList() = capture("05e_accueil_liste") {
        Home(HomeSection.TV, Category.All, grid = GridColumns(channelList = true))
    }

    @Test
    fun homeViolet() = capture("05f_accueil_violet", ThemeStyle.VIOLET) {
        Home(HomeSection.MOVIES, Category.All)
    }

    @Test
    fun homeStreaming() = capture("05g_accueil_streaming") {
        Home(HomeSection.TV, Category.All, preview = true, layout = HomeLayout.STREAMING)
    }

    @Test
    fun homeStreamingMovies() = capture("05h_accueil_streaming_films", ThemeStyle.CINEMA) {
        Home(HomeSection.MOVIES, Category.All, layout = HomeLayout.STREAMING)
    }

    @Test
    fun homeBox() = capture("05i_accueil_box", ThemeStyle.BLACK) {
        Home(HomeSection.TV, Category.All, preview = true, layout = HomeLayout.BOX)
    }

    @Test
    fun guide() = capture("09_guide") {
        Home(HomeSection.GUIDE, Category.All)
    }

    @Test
    fun football() = capture("09b_foot") {
        Home(HomeSection.FOOTBALL, Category.All)
    }

    @Test
    fun guideTonight() = capture("09e_guide_ce_soir") {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GuideContent(
                state = SampleData.guide,
                groups = SampleData.groups,
                selectedGroup = GuideViewModel.TONIGHT,
                onSelectGroup = {},
                onPlay = {},
                tonight = SampleData.tonight,
            )
        }
    }

    @Test
    fun footballStandings() = capture("09c_foot_classement") {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FootballContent(
                SampleData.football,
                competition = Competition.LIGUE_1,
                onCompetition = {},
                onPlay = {},
                favoriteTeams = setOf("Olympique de Marseille"),
                standings = StandingsState.Loaded(SampleData.standings),
                showStandings = true,
            )
        }
    }

    @Test
    fun footballTeam() = capture("09d_foot_club") {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(24.dp)) {
                TeamSheetContent(SampleData.teamMatches, SampleData.NOW, followed = true, onToggleTeam = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun programmeReplay() = capture("10b_programme_replay") {
        val channel = SampleData.guide.channels[7].copy(catchupDays = 7)
        val programme = GuideProgramme(
            "Journal de 13 h",
            SampleData.NOW - 4 * 60 * 60_000L,
            SampleData.NOW - 3 * 60 * 60_000L - 20 * 60_000L,
            description = "Le point sur l'actualité de la mi-journée.",
            category = "Information",
        )
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
                ProgrammeSheetContent(channel, programme, SampleData.NOW, onWatch = {}, onDismiss = {}, onReplay = {})
            }
        }
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
    fun movieInProgress() = capture("11b_fiche_film_reprise") {
        DetailContent(
            state = SampleData.movieDetail.copy(progress = SampleData.movieProgress),
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
    fun playerReplay() = capture("13g_lecteur_replay") {
        FakeVideo()
        PlayerOverlay(
            // Replay d'un programme passé : bandeau, progression et saut de 3 minutes en cours (pub avant le programme).
            state = SampleData.player.copy(
                isLive = false,
                replay = "ven. 30 oct., 20:58 – 22:50",
                seekable = true,
                now = PlayerProgramme("Le Grand Voyage", SampleData.NOW - 26 * 60 * 60_000L, SampleData.NOW - 24 * 60 * 60_000L),
                next = null,
                watchedAt = SampleData.NOW - 26 * 60 * 60_000L + 4 * 60_000L,
                pendingJumpSeconds = 90,
                jumpStepSeconds = 30,
                qualityNotice = null,
            ),
            now = SampleData.NOW,
            visible = true,
            showTouchControls = false,
            onBack = {},
            onZap = {},
            onRetry = {},
        )
    }

    @Test
        fun playerRestart() = capture("13f_lecteur_depuis_le_debut") {
        FakeVideo()
        PlayerOverlay(
            // « Depuis le début » d'un programme commencé il y a 25 min : bandeau du direct, différé affiché.
            state = SampleData.player.copy(liveDelayMs = 25 * 60_000L + 12_000, seekable = true, qualityNotice = null),
            now = SampleData.NOW,
            visible = true,
            showTouchControls = false,
            onBack = {},
            onZap = {},
            onRetry = {},
        )
    }

    @Test
        fun playerMatch() = capture("13e_lecteur_match") {
        FakeVideo()
        PlayerOverlay(
            state = SampleData.player.copy(match = PlayerMatch("PSG", "Marseille", null, null, "1 – 0", live = true), adMuted = true),
            now = SampleData.NOW,
            visible = true,
            showTouchControls = false,
            onBack = {},
            onZap = {},
            onRetry = {},
        )
    }

    @Test
    fun playerInfo() = capture("13b_lecteur_infos") {
        FakeVideo()
        val state = SampleData.player.copy(bandwidthMbps = 21.6, quality = "4K", info = SampleData.playbackInfo)
        PlayerOverlay(state = state, now = SampleData.NOW, visible = false, showTouchControls = false, onBack = {}, onZap = {}, onRetry = {})
        Box(Modifier.fillMaxSize()) {
            PlaybackInfoPanel(state = state, visible = true, modifier = Modifier.align(Alignment.TopEnd))
        }
    }

    @Test
    fun playerPaused() = capture("13c_lecteur_pause") {
        FakeVideo()
        val state = SampleData.player.copy(paused = true, pausedAtMillis = SampleData.NOW - 83_000, qualityNotice = null)
        PlayerOverlay(state = state, now = SampleData.NOW, visible = true, showTouchControls = device == "phone", onBack = {}, onZap = {}, onRetry = {})
    }

    @Test
    fun resumePrompt() = capture("13d_reprise") {
        FakeVideo()
        Box(Modifier.fillMaxSize()) {
            ResumePromptCard(ResumePrompt("Enquêtes en série", "S01E08", 754_000, "S01E01"), onAnswer = {}, modifier = Modifier.align(Alignment.Center))
        }
    }

    @Test
    fun playerMenu() = capture("14_lecteur_menu") {
        FakeVideo()
        val channels = remember {
            flowOf(PagingData.from(SampleData.channels.map { MenuChannel(SampleData.menu.category, it) }))
        }.collectAsLazyPagingItems()
        ChannelMenu(
            menu = SampleData.menu,
            groups = SampleData.groups,
            channels = channels,
            favorites = SampleData.favoriteChannels,
            now = SampleData.NOW,
            onCategory = {},
            onPlay = {},
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
    fun editorXtream() = capture("18b_edition_xtream") {
        SourceEditorContent(
            state = SampleData.editorXtream,
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

    @Test
    fun channelGroups() = capture("21_categories_chaines") {
        ChannelGroupsContent(
            groups = SampleData.groups.mapIndexed { index, group ->
                GroupVisibilityRow(group.title, group.count, hidden = index == 2 || index == 4)
            },
            onVisibility = { _, _ -> },
            onShowAll = {},
            onBack = {},
        )
    }

    @Composable
    private fun Home(
        section: HomeSection,
        category: Category,
        reorder: ReorderState = ReorderState(),
        grid: GridColumns = GridColumns(),
        preview: Boolean = false,
        layout: HomeLayout = HomeLayout.CLASSIC,
    ) {
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
            continueSeries = SampleData.continueSeries,
            continueMovies = listOf(SampleData.movieProgress),
            gridColumns = grid,
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
            football = { modifier ->
                FootballContent(SampleData.football, competition = null, onCompetition = {}, onPlay = {}, modifier = modifier)
            },
            recentChannels = SampleData.channels.take(3),
            showPreview = preview,
            preview = SampleData.channelPreview.takeIf { preview },
            layout = layout,
            rows = SampleData.rows,
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
