package tv.nextv.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.settings.homeLayoutLabel

const val LAYOUT_DEMO_TAG = "layout_demo"

/**
 * Démonstration d'une interface (configuration rapide) : le vrai accueil, avec des chaînes, films et séries
 * inventés, à parcourir à la télécommande comme l'accueil réel. Rien ne se lit : OK sur un élément le rappelle.
 * « Retour » (une fois revenu au début de l'interface) propose de garder cette interface ou de revenir au choix.
 */
@Composable
fun LayoutDemo(layout: HomeLayout, onKeep: () -> Unit, onClose: () -> Unit) {
    // Streaming : la démo s'ouvre sur les films, où ce style se voit le mieux (grande affiche, rangées).
    var section by remember { mutableStateOf(if (layout == HomeLayout.STREAMING) HomeSection.MOVIES else HomeSection.TV) }
    var category by remember { mutableStateOf<Category>(Category.All) }
    var premiumMenu by remember { mutableStateOf(layout == HomeLayout.PREMIUM) }
    var askLeave by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ChannelPreview?>(DemoData.preview(DemoData.channels.first())) }
    var spotlight by remember { mutableStateOf<Spotlight?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notice = stringResource(R.string.layout_demo_play)
    val tell: () -> Unit = { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(notice) } }

    // Déclarés du plus général au plus précis : le dernier actif passe en premier.
    BackHandler { askLeave = true }
    BackHandler(enabled = layout == HomeLayout.PREMIUM && !premiumMenu) { premiumMenu = true }
    BackHandler(enabled = layout != HomeLayout.LIVE_CENTER && section.kind() != null && category != Category.All) { category = Category.All }

    val actions = HomeActions(
        onSection = {
            section = it
            category = Category.All
            spotlight = null
        },
        onCategory = { category = it },
        onPlayChannel = { tell() },
        onOpenMovie = { tell() },
        onOpenSeries = { tell() },
        onSettings = tell,
        onResume = { tell() },
        onChannelFocus = { preview = DemoData.preview(it) },
        onPosterFocus = { spotlight = Spotlight(it, playlistSeasons = if (it.episodeCount > 0) 2 else null) },
    )

    Box(Modifier.fillMaxSize().testTag(LAYOUT_DEMO_TAG)) {
        if (premiumMenu) {
            PremiumMenu(
                current = section,
                onSection = {
                    actions.onSection(it)
                    premiumMenu = false
                },
                onFavorites = tell,
                onContinue = tell,
                onSettings = tell,
                onSearch = tell,
                counts = mapOf(
                    HomeSection.TV to DemoData.channels.size,
                    HomeSection.MOVIES to DemoData.movies.size,
                    HomeSection.SERIES to DemoData.series.size,
                ),
            )
        } else {
            val picked = (category as? Category.Group)?.title
            val inGroup = { group: String?, _: String -> picked == null || picked == group }
            val channels = remember(category) {
                flowOf(PagingData.from(DemoData.channels.filter { inGroup(it.groupTitle, "") }))
            }.collectAsLazyPagingItems()
            val movies = remember(category) { flowOf(PagingData.from(DemoData.movies.filter { inGroup(it.groupTitle, "") })) }.collectAsLazyPagingItems()
            val series = remember(category) { flowOf(PagingData.from(DemoData.series.filter { inGroup(it.groupTitle, "") })) }.collectAsLazyPagingItems()
            HomeContent(
                onMenu = if (layout == HomeLayout.PREMIUM) ({ premiumMenu = true }) else null,
                section = section,
                groups = DemoData.groups(section),
                category = category,
                query = "",
                channels = channels,
                favoriteChannels = DemoData.channels.take(3),
                movies = movies,
                favoriteMovies = DemoData.movies.take(2),
                series = series,
                favoriteSeries = DemoData.series.take(2),
                favoriteKeys = emptySet(),
                reorder = ReorderState(),
                now = DemoData.now,
                snackbarHostState = snackbar,
                actions = actions,
                onSearch = tell,
                guide = { DemoPlaceholder(it) },
                football = { DemoPlaceholder(it) },
                channelCount = DemoData.channels.size,
                preview = preview,
                layout = layout,
                rows = DemoData.rows,
                discover = Discover(
                    trending = (if (section == HomeSection.SERIES) DemoData.series.map { it.toPoster() } else DemoData.movies.map { it.toPoster() }).take(10),
                ),
                spotlight = spotlight,
            )
        }
        // Classique : boutons en bas de son menu, le rappel va en haut.
        val bannerAt = if (layout == HomeLayout.PREMIUM) Modifier.align(Alignment.TopCenter).padding(top = 28.dp) else Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
        DemoBanner(layout, bannerAt)
    }

    if (askLeave) {
        val keep = remember { FocusRequester() }
        // « Garder celle-ci » sélectionné : la fenêtre pose d'abord le focus sur son premier bouton, on le reprend.
        LaunchedEffect(Unit) {
            repeat(3) {
                withFrameNanos { }
                runCatching { keep.requestFocus() }
            }
        }
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.layout_demo_leave_title, homeLayoutLabel(layout))) },
            text = { Text(stringResource(R.string.layout_demo_leave_text)) },
            // « Garder celle-ci » en premier (à gauche) : la fenêtre pose le focus sur son premier bouton.
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.layout_demo_back)) }
            },
            dismissButton = {
                TextButton(onClick = onKeep, modifier = Modifier.focusRequester(keep)) { Text(stringResource(R.string.layout_demo_keep)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/** Rappel en bas de l'écran : c'est une démonstration, « Retour » pour finir. */
@Composable
private fun DemoBanner(layout: HomeLayout, modifier: Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.layout_demo_badge, homeLayoutLabel(layout)).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.layout_demo_hint), style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** Guide TV et Foot dans la démo : ils se remplissent avec la playlist et le guide. */
@Composable
private fun DemoPlaceholder(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
            Text(
                stringResource(R.string.layout_demo_guide),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Contenu inventé de la démonstration. */
internal object DemoData {
    private const val MINUTE = 60_000L
    val now: Long get() = System.currentTimeMillis() / MINUTE * MINUTE

    private val channelSpecs = listOf(
        Triple("Info 24", "Information", "Le journal"), Triple("Monde Actu", "Information", "Grand reportage"),
        Triple("Éco Direct", "Information", "La bourse en clair"), Triple("Sport+ 1", "Sport", "Football : avant-match"),
        Triple("Sport+ 2", "Sport", "Rugby : le magazine"), Triple("Moto Arena", "Sport", "Grand prix"),
        Triple("Ciné Club", "Cinéma", "Le Grand Voyage"), Triple("Séries Max", "Cinéma", "Enquêtes en série"),
        Triple("Comédie !", "Cinéma", "Stand-up : la relève"), Triple("Docu Planète", "Découverte", "Les grands fauves"),
        Triple("Histoire TV", "Découverte", "Secrets de châteaux"), Triple("Voyage Monde", "Découverte", "Carnets d'Islande"),
        Triple("Jeunesse TV", "Jeunesse", "Les aventures de Pixel"), Triple("Musique Hits", "Musique", "Top 50 en direct"),
    )

    val channels: List<ChannelListRow> = channelSpecs.mapIndexed { index, (name, group, programme) ->
        val start = now - (10 + index * 7 % 40) * MINUTE
        ChannelListRow(
            id = index + 1L, name = name, title = name, groupTitle = group, logoUrl = null, channelNumber = (index + 1).toString(),
            streamSupport = "SUPPORTED", streamIssues = "", quality = listOf("FHD", "HD", null)[index % 3], variantCount = 1,
            itemKey = "demo:live:$index", nowTitle = programme, nowStart = start, nowStop = start + 60 * MINUTE,
        )
    }

    private val movieTitles = listOf(
        "Le Grand Voyage" to 2023, "Nuit blanche à Lisbonne" to 2021, "Les Gardiens du phare" to 2019, "Opération Mistral" to 2024,
        "La Dernière Escale" to 2018, "Horizon 2080" to 2025, "Un été à Collioure" to 2022, "Le Chant des glaciers" to 2020,
        "Midi pile" to 2017, "Les Faussaires" to 2023, "Cap au nord" to 2016, "Les Ailes de papier" to 2024,
    )

    val movies: List<MovieRow> = movieTitles.mapIndexed { index, (title, year) ->
        MovieRow(
            id = 1_000L + index, title = title, year = year, logoUrl = null,
            groupTitle = if (index % 2 == 0) "Aventure" else "Drame", itemKey = "demo:movie:$index",
            streamSupport = "SUPPORTED", quality = listOf("FHD", "HD", null)[index % 3],
        )
    }

    private val seriesTitles = listOf(
        "Enquêtes en série" to 2022, "Les Héritiers" to 2020, "Station Orbitale" to 2024, "Brigade du port" to 2019,
        "La Maison d'en face" to 2021, "Code Atlas" to 2023, "Les Saisons de Marie" to 2018, "Frontières" to 2025,
    )

    val series: List<SeriesRow> = seriesTitles.mapIndexed { index, (title, year) ->
        SeriesRow(
            id = 2_000L + index, title = title, year = year, logoUrl = null,
            groupTitle = if (index % 2 == 0) "Policier" else "Science-fiction", itemKey = "demo:series:$index",
            episodeCount = 8 + index * 2, quality = listOf("FHD", null)[index % 2],
        )
    }

    fun groups(section: HomeSection): List<GroupRow> {
        val titles = when (section) {
            HomeSection.TV -> channels.map { it.groupTitle }
            HomeSection.MOVIES -> movies.map { it.groupTitle }
            HomeSection.SERIES -> series.map { it.groupTitle }
            else -> emptyList()
        }
        return titles.groupingBy { it }.eachCount().map { (title, count) -> GroupRow(title, count) }
    }

    val rows = HomeRows(
        channels = { group, limit -> channels.filter { it.groupTitle == group }.take(limit) },
        movies = { group, limit -> movies.filter { it.groupTitle == group }.take(limit) },
        series = { group, limit -> series.filter { it.groupTitle == group }.take(limit) },
    )

    fun preview(channel: ChannelListRow): ChannelPreview {
        val start = channel.nowStart ?: now
        val stop = channel.nowStop ?: (now + 60 * MINUTE)
        return ChannelPreview(
            channel = channel,
            current = GuideProgramme(channel.nowTitle ?: channel.title, start, stop),
            next = GuideProgramme("Le magazine du soir", stop, stop + 45 * MINUTE),
        )
    }
}
