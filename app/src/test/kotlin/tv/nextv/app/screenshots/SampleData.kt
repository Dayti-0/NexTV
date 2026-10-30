package tv.nextv.app.screenshots

import tv.nextv.app.ui.football.FootballMatch
import tv.nextv.app.ui.home.ChannelPreview
import tv.nextv.app.ui.football.FootballUiState
import tv.nextv.core.football.Competition
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.detail.DetailUiState
import tv.nextv.app.ui.detail.PlaylistItem
import tv.nextv.app.ui.detail.Season
import tv.nextv.app.ui.editor.EditorState
import tv.nextv.app.ui.editor.LocationMode
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.GuideUiState
import tv.nextv.app.ui.player.ChannelMenuState
import tv.nextv.app.ui.player.PlaybackInfo
import tv.nextv.app.ui.player.PlayerProgramme
import tv.nextv.app.ui.player.PlayerUiState
import tv.nextv.app.ui.settings.ProxyFormState
import tv.nextv.app.ui.settings.SettingsUiState
import tv.nextv.app.ui.sources.SourceItem
import tv.nextv.app.ui.sources.SourcesUiState
import tv.nextv.app.ui.startup.ContentSummary
import tv.nextv.app.ui.startup.StartupError
import tv.nextv.app.ui.startup.StartupStep
import tv.nextv.app.ui.startup.StartupUiState
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.MediaType
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.model.EpgStatus
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceStatus
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxyType
import tv.nextv.core.repository.SourceInputField

/** Données fictives des captures d'écran (noms de chaînes inventés). */
object SampleData {

    /** 29 octobre 2026, 20 h 47 (heure de Paris). */
    const val NOW = 1_793_303_220_000L
    private const val MINUTE = 60_000L

    val sources = SourcesUiState(
        loaded = true,
        activeId = 1,
        items = listOf(
            SourceItem(
                PlaylistSource(
                    id = 1,
                    name = "Bouquet Maison",
                    location = SourceLocation.Remote("https://iptv.example.com/get.php?username=alice&password=s3cret&type=m3u_plus"),
                    hasCredentials = true,
                    status = SourceStatus(
                        lastAttemptAtMillis = NOW - 12 * MINUTE,
                        lastSuccessAtMillis = NOW - 12 * MINUTE,
                        channelCount = 248,
                        epg = EpgStatus(
                            lastAttemptAtMillis = NOW - 12 * MINUTE,
                            lastSuccessAtMillis = NOW - 12 * MINUTE,
                            programmeCount = 3_412,
                            matchedChannelCount = 186,
                        ),
                    ),
                ),
                refreshing = false,
            ),
            SourceItem(
                PlaylistSource(
                    id = 2,
                    name = "Sport Live",
                    location = SourceLocation.Remote("https://sport.example.net/live.m3u8"),
                    status = SourceStatus(
                        lastAttemptAtMillis = NOW - 3 * 60 * MINUTE,
                        lastSuccessAtMillis = NOW - 3 * 60 * MINUTE,
                        channelCount = 42,
                    ),
                ),
                refreshing = true,
            ),
            SourceItem(
                PlaylistSource(
                    id = 3,
                    name = "Serveur du salon",
                    location = SourceLocation.Remote("http://192.168.1.20:8080/tv.m3u"),
                    status = SourceStatus(
                        lastAttemptAtMillis = NOW - 5 * MINUTE,
                        lastSuccessAtMillis = NOW - 2 * 24 * 60 * MINUTE,
                        channelCount = 64,
                        error = SourceError.ConnectionFailed,
                        warnings = setOf(SourceWarning.UNSUPPORTED_STREAMS),
                    ),
                ),
                refreshing = false,
            ),
            SourceItem(
                PlaylistSource(
                    id = 4,
                    name = "Chaînes locales",
                    location = SourceLocation.LocalDocument("content://docs/chaines.m3u", "chaines.m3u"),
                ),
                refreshing = false,
            ),
        ),
    )

    val groups = listOf(
        GroupRow("Information", 4),
        GroupRow("Sport", 3),
        GroupRow("Cinéma", 3),
        GroupRow("Découverte", 3),
        GroupRow("Jeunesse", 2),
        GroupRow("Musique", 2),
        GroupRow(null, 1),
    )

    private data class SampleChannel(
        val name: String,
        val group: String?,
        val now: String?,
        val startedMinutesAgo: Int = 30,
        val durationMinutes: Int = 60,
        val support: StreamSupport = StreamSupport.SUPPORTED,
        val issues: Set<StreamIssue> = emptySet(),
        val quality: String? = null,
        val variants: Int = 1,
    )

    private val sampleChannels = listOf(
        SampleChannel("Info 24", "Information", "Le journal du soir", 17, 45, quality = "FHD", variants = 3),
        SampleChannel("Monde Actu", "Information", "Grand reportage : les océans", 47, 90, quality = "HD", variants = 2),
        SampleChannel("Éco Direct", "Information", "La bourse en clair", 5, 30),
        SampleChannel("Météo Live", "Information", "Prévisions du week-end", 2, 15),
        SampleChannel("Sport+ 1", "Sport", "Ligue des champions : avant-match", 20, 40, quality = "FHD", variants = 4),
        SampleChannel("Sport+ 2", "Sport", "Rugby : le magazine", 35, 60),
        SampleChannel("Moto Arena", "Sport", null, support = StreamSupport.LIMITED, issues = setOf(StreamIssue.PROXY_NOT_APPLICABLE)),
        SampleChannel("Ciné Club", "Cinéma", "Le Grand Voyage", 40, 120, quality = "HD", variants = 2),
        SampleChannel("Séries Max", "Cinéma", "Enquêtes en série – S2E4", 12, 50),
        SampleChannel("Comédie !", "Cinéma", "Stand-up : la relève", 28, 60),
        SampleChannel("Docu Planète", "Découverte", "Les grands fauves", 15, 55),
        SampleChannel("Histoire TV", "Découverte", "Secrets de châteaux", 44, 52),
        SampleChannel("Voyage Monde", "Découverte", "Carnets d'Islande", 8, 30),
        SampleChannel("Jeunesse TV", "Jeunesse", "Les aventures de Pixel", 6, 25),
        SampleChannel("Mini Kids", "Jeunesse", null),
        SampleChannel("Musique Hits", "Musique", "Top 50 en direct", 33, 60),
        SampleChannel("Jazz Lounge", "Musique", null, support = StreamSupport.UNSUPPORTED, issues = setOf(StreamIssue.UNSUPPORTED_SCHEME)),
        SampleChannel("Chaîne test", null, null),
    )

    val channels: List<ChannelListRow> = sampleChannels.mapIndexed { index, channel ->
        val start = NOW - channel.startedMinutesAgo * MINUTE
        ChannelListRow(
            id = index + 1L,
            name = channel.name,
            title = channel.name,
            groupTitle = channel.group,
            logoUrl = null,
            channelNumber = (index + 1).toString(),
            streamSupport = channel.support.name,
            streamIssues = channel.issues.joinToString(",") { it.name },
            quality = channel.quality,
            variantCount = channel.variants,
            itemKey = "live:${channel.name.lowercase()}",
            nowTitle = channel.now,
            nowStart = channel.now?.let { start },
            nowStop = channel.now?.let { start + channel.durationMinutes * MINUTE },
        )
    }

    /** Favoris : quelques chaînes, dans l'ordre choisi. */
    val favoriteChannels: List<ChannelListRow> get() = listOf(channels[7], channels[0], channels[4], channels[10])

    val favoriteKeys: Set<String> get() = favoriteChannels.map { it.itemKey }.toSet() + movies[0].itemKey + movies[5].itemKey + series[0].itemKey

    private val movieTitles = listOf(
        "Le Grand Voyage" to 2023, "Nuit blanche à Lisbonne" to 2021, "Les Gardiens du phare" to 2019, "Opération Mistral" to 2024,
        "La Dernière Escale" to 2018, "Horizon 2080" to 2025, "Un été à Collioure" to 2022, "Le Chant des glaciers" to 2020,
        "Midi pile" to 2017, "Les Faussaires" to 2023, "Cap au nord" to 2016, "Les Ailes de papier" to 2024,
    )

    val movies: List<MovieRow> = movieTitles.mapIndexed { index, (title, year) ->
        MovieRow(
            id = 1_000L + index,
            title = title,
            year = year,
            logoUrl = null,
            groupTitle = if (index % 3 == 0) "Films | Aventure" else "Films | Drame",
            itemKey = "movie:${title.lowercase()}",
            streamSupport = "SUPPORTED",
        )
    }

    val movieGroups = listOf(GroupRow("Films | Aventure", 4), GroupRow("Films | Drame", 8), GroupRow("Films | Comédie", 12))

    private val seriesTitles = listOf(
        "Enquêtes en série" to 2022, "Les Héritiers" to 2020, "Station Orbitale" to 2024, "Brigade du port" to 2019,
        "La Maison d'en face" to 2021, "Code Atlas" to 2023, "Les Saisons de Marie" to 2018, "Frontières" to 2025,
    )

    val series: List<SeriesRow> = seriesTitles.mapIndexed { index, (title, year) ->
        SeriesRow(
            id = 2_000L + index * 100,
            title = title,
            year = year,
            logoUrl = null,
            groupTitle = "Séries | Policier",
            itemKey = "series:${title.lowercase()}",
            episodeCount = 6 + index * 3,
        )
    }

    val seriesGroups = listOf(GroupRow("Séries | Policier", 8), GroupRow("Séries | Science-fiction", 5))

    val movieDetail = DetailUiState(
        item = PlaylistItem(sourceId = 1, firstId = 1_000, title = "Le Grand Voyage", year = 2023, logoUrl = null, itemKey = "movie:le grand voyage"),
        metadata = MetadataState.Available(
            MediaDetails(
                tmdbId = 1,
                type = MediaType.MOVIE,
                title = "Le Grand Voyage",
                overview = "Trois amis d'enfance traversent l'Europe à bord d'un vieux combi pour tenir une promesse faite vingt ans plus tôt. " +
                    "Entre pannes, rencontres inattendues et secrets enfouis, le voyage les oblige à regarder en face ce qu'ils sont devenus.",
                tagline = "Certaines promesses ne connaissent pas de frontières.",
                year = 2023,
                rating = 7.6,
                voteCount = 1_842,
                genres = listOf("Aventure", "Comédie dramatique"),
                trailerYoutubeKey = "trailer",
                runtimeMinutes = 118,
            ),
        ),
        isFavorite = true,
    )

    val seriesDetail = DetailUiState(
        item = PlaylistItem(sourceId = 1, firstId = 2_000, title = "Enquêtes en série", year = 2022, logoUrl = null, itemKey = "series:enquetes en serie"),
        metadata = MetadataState.Unavailable(TmdbError.MISSING_KEY),
        seasons = listOf(1, 2).map { season ->
            Season(
                number = season,
                episodes = (1..(if (season == 1) 8 else 6)).map { episode ->
                    EpisodeRow(
                        id = 2_000L + season * 10 + episode,
                        name = "Enquêtes en série S0${season}E0$episode",
                        season = season,
                        episode = episode,
                        logoUrl = null,
                        streamSupport = "SUPPORTED",
                    )
                },
            )
        },
        selectedSeason = 1,
    )

    val startup = StartupUiState(
        percent = 68,
        step = StartupStep.EPG_DOWNLOAD,
        bytesReceived = 7_340_032,
        playlistName = "Bouquet Maison",
    )

    val startupError = StartupUiState(
        percent = 12,
        step = StartupStep.PLAYLIST,
        playlistName = "Bouquet Maison",
        error = StartupError("Le serveur ne répond pas (délai dépassé).", canContinue = true),
    )

    val startupSummary = ContentSummary(channels = 248, movies = 1_312, series = 186)

    val settings = SettingsUiState(
        loaded = true,
        playlist = sources.items.first().source,
        playlistCount = 4,
        autoQuality = true,
        tmdbKeySaved = true,
        guideDownloadedAt = NOW - 95 * MINUTE,
        proxyEnabled = false,
        version = "0.2.0",
    )

    val continueSeries = listOf(
        ProgressEntity(1, "series:enquêtes en série", "SERIES", 2_018, "Enquêtes en série", "S01E08", null, 754_000, 2_700_000, NOW),
        ProgressEntity(1, "series:les héritiers", "SERIES", 2_109, "Les Héritiers", "S02E03", null, 0, 0, NOW - 3_600_000),
        ProgressEntity(1, "series:code atlas", "SERIES", 2_501, "Code Atlas", "S01E02", null, 1_800_000, 3_000_000, NOW - 86_400_000),
    )

    val movieProgress = ProgressEntity(1, "movie:le grand voyage", "MOVIE", 1_000, "Le Grand Voyage", null, null, 2_530_000, 7_080_000, NOW)

    val playbackInfo = PlaybackInfo(
        videoMimeType = "video/hevc",
        width = 3840,
        height = 2160,
        frameRate = 50f,
        videoDecoder = "c2.example.hevc.decoder",
        hardwareDecoder = true,
        audioMimeType = "audio/eac3",
        audioChannels = 6,
        audioDecoder = "c2.android.eac3.decoder",
        droppedFrames = 37,
    )

    val menu = ChannelMenuState(open = true, currentChannelId = 8, initialIndex = 7)

    val guide: GuideUiState = run {
        val windowStart = NOW - NOW % (30 * MINUTE)
        val windowEnd = windowStart + 6 * 60 * MINUTE
        val titles = listOf(
            "Le journal du soir", "Débat de l'actualité", "Grand reportage", "Les grands fauves", "Le Grand Voyage",
            "Ligue des champions", "Enquêtes en série", "Top 50 en direct", "Secrets de châteaux", "Carnets d'Islande",
            "Météo", "Stand-up : la relève", "La bourse en clair", "Cuisine du monde", "Rétro foot", "Concert privé",
        )
        val durations = listOf(45, 30, 90, 55, 120, 60, 50, 35, 25, 75, 15, 40)
        val channels = sampleChannels.take(10).mapIndexed { index, channel ->
            var t = windowStart - ((index * 17L) % 50) * MINUTE
            var k = index
            val programmes = mutableListOf<GuideProgramme>()
            while (t < windowEnd) {
                val duration = durations[k % durations.size] * MINUTE
                programmes += GuideProgramme(
                    title = titles[(index * 3 + k) % titles.size],
                    startMillis = t,
                    stopMillis = t + duration,
                    description = "Un programme fictif utilisé pour les captures d'écran de NexTV, avec un résumé sur deux lignes.",
                    category = channel.group,
                )
                t += duration
                k++
            }
            GuideChannel(id = index + 1L, name = channel.name, number = (index + 1).toString(), logoUrl = null, programmes = programmes)
        }
        GuideUiState(loading = false, windowStart = windowStart, windowEnd = windowEnd, now = NOW, channels = channels)
    }

    val player = PlayerUiState(
        channelName = "Ciné Club",
        channelNumber = "8",
        sourceName = "Bouquet Maison",
        buffering = false,
        quality = "HD",
        qualityNotice = "Connexion lente : passage en HD",
        bandwidthMbps = 9.4,
        now = PlayerProgramme("Le Grand Voyage", NOW - 40 * MINUTE, NOW + 80 * MINUTE),
        next = PlayerProgramme("Court-métrages de minuit", NOW + 80 * MINUTE, NOW + 140 * MINUTE),
        channelVersion = 1,
    )

    val playerError = player.copy(
        channelName = "Sport+ 2",
        channelNumber = "6",
        now = null,
        next = null,
        quality = null,
        qualityNotice = null,
        bandwidthMbps = null,
        error = "Le serveur du flux ne répond pas (délai dépassé).\nLa lecture passe par le proxy configuré : vérifiez qu’il est joignable.",
    )

    val channelPreview = ChannelPreview(
        channel = channels[0],
        current = GuideProgramme(
            "Le 20h",
            NOW - 15 * MINUTE,
            NOW + 25 * MINUTE,
            description = "Toute l’actualité du jour en France et dans le monde, avec les invités du journal et la météo.",
        ),
        next = GuideProgramme("Météo", NOW + 25 * MINUTE, NOW + 30 * MINUTE),
    )

    val football = FootballUiState(
        loading = false,
        now = NOW,
        matches = listOf(
            Triple(Competition.LIGUE_1, "Canal+ Sport", "PSG / Marseille") to (NOW - 40 * MINUTE),
            Triple(Competition.PREMIER_LEAGUE, "Canal+ Foot", "Liverpool / Arsenal") to (NOW + 75 * MINUTE),
            Triple(Competition.LA_LIGA, null, "Real Madrid / Barça") to (NOW + 3 * 60 * MINUTE),
            Triple(Competition.CHAMPIONS_LEAGUE, "beIN Sports 1", "Bayern / Inter") to (NOW + 26 * 60 * MINUTE),
            Triple(Competition.EUROPA_LEAGUE, "Canal+ Live 2", "Lyon / AS Rome") to (NOW + 27 * 60 * MINUTE),
            Triple(Competition.LIGUE_1, "Ligue 1+", "Lens / Lille") to (NOW + 50 * 60 * MINUTE),
        ).mapIndexed { index, (match, start) ->
            val (competition, channel, teams) = match
            val (home, away) = teams.split(" / ")
            FootballMatch(
                competition = competition,
                home = home,
                away = away,
                kickoffMillis = start,
                endMillis = start + 115 * MINUTE,
                channel = channel?.let { GuideChannel(100L + index, it, null, null, emptyList()) },
                programme = channel?.let {
                    GuideProgramme("Football : ${competition.label}", start, start + 115 * MINUTE, teams, "Match de ${competition.label}.", "Football")
                },
                otherChannels = if (index == 0) 2 else 0,
            )
        },
    )

    val editor = EditorState(
        name = "Bouquet Maison",
        mode = LocationMode.URL,
        url = "https://iptv.example.com/get.php?type=m3u_plus",
        username = "alice",
        password = "s3cret",
    )

    val editorXtream = EditorState(
        name = "Bouquet Maison",
        mode = LocationMode.XTREAM,
        server = "http://iptv.example.com:8080",
        xtreamUsername = "alice",
        xtreamPassword = "s3cret",
    )

    val editorWithErrors = EditorState(
        name = "",
        mode = LocationMode.URL,
        url = "iptv.example.com/liste.m3u",
        errors = mapOf(SourceInputField.LOCATION to SourceError.InvalidUrl(InvalidUrlReason.MISSING_SCHEME)),
    )

    val proxy = ProxyFormState(
        loading = false,
        enabled = true,
        type = ProxyType.HTTP,
        host = "proxy.example.com",
        port = "3128",
        username = "salon",
        password = "motdepasse",
        scopes = setOf(ProxyScope.PLAYLISTS, ProxyScope.EPG, ProxyScope.STREAMS),
        bypassLocal = true,
    )
}
