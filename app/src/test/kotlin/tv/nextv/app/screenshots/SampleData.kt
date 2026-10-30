package tv.nextv.app.screenshots

import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.ui.editor.EditorState
import tv.nextv.app.ui.editor.EpgMode
import tv.nextv.app.ui.editor.LocationMode
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.guide.GuideUiState
import tv.nextv.app.ui.player.PlayerProgramme
import tv.nextv.app.ui.player.PlayerUiState
import tv.nextv.app.ui.settings.ProxyFormState
import tv.nextv.app.ui.sources.SourceItem
import tv.nextv.app.ui.sources.SourcesUiState
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
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
    )

    private val sampleChannels = listOf(
        SampleChannel("Info 24", "Information", "Le journal du soir", 17, 45),
        SampleChannel("Monde Actu", "Information", "Grand reportage : les océans", 47, 90),
        SampleChannel("Éco Direct", "Information", "La bourse en clair", 5, 30),
        SampleChannel("Météo Live", "Information", "Prévisions du week-end", 2, 15),
        SampleChannel("Sport+ 1", "Sport", "Ligue des champions : avant-match", 20, 40),
        SampleChannel("Sport+ 2", "Sport", "Rugby : le magazine", 35, 60),
        SampleChannel("Moto Arena", "Sport", null, support = StreamSupport.LIMITED, issues = setOf(StreamIssue.PROXY_NOT_APPLICABLE)),
        SampleChannel("Ciné Club", "Cinéma", "Le Grand Voyage", 40, 120),
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
            groupTitle = channel.group,
            logoUrl = null,
            channelNumber = (index + 1).toString(),
            streamSupport = channel.support.name,
            streamIssues = channel.issues.joinToString(",") { it.name },
            nowTitle = channel.now,
            nowStart = channel.now?.let { start },
            nowStop = channel.now?.let { start + channel.durationMinutes * MINUTE },
        )
    }

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
        now = PlayerProgramme("Le Grand Voyage", NOW - 40 * MINUTE, NOW + 80 * MINUTE),
        next = PlayerProgramme("Court-métrages de minuit", NOW + 80 * MINUTE, NOW + 140 * MINUTE),
        channelVersion = 1,
    )

    val playerError = player.copy(
        channelName = "Sport+ 2",
        channelNumber = "6",
        now = null,
        next = null,
        error = "Le serveur du flux ne répond pas (délai dépassé).\nLa lecture passe par le proxy configuré : vérifiez qu’il est joignable.",
    )

    val editor = EditorState(
        name = "Bouquet Maison",
        mode = LocationMode.URL,
        url = "https://iptv.example.com/get.php?type=m3u_plus",
        username = "alice",
        password = "s3cret",
        epgMode = EpgMode.URL,
        epgUrl = "https://iptv.example.com/xmltv.php",
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
