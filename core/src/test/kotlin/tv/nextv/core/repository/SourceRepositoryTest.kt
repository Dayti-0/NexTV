package tv.nextv.core.repository

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.io.CompositeContentOpener
import tv.nextv.core.io.RemoteContentOpener
import tv.nextv.core.log.CapturingLogger
import tv.nextv.core.log.RedactingLogger
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.source.FakeDocumentOpener
import tv.nextv.core.source.M3uPlaylistLoader
import tv.nextv.core.source.PlaylistLoaderRegistry
import tv.nextv.core.source.gzip
import java.util.concurrent.ConcurrentHashMap

class SourceRepositoryTest {

    private val now = 1_704_110_400_000L // 2024-01-01T12:00:00Z
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, MockResponse>()
    private val captured = CapturingLogger()
    private val redactor = Redactor()
    private val store = InMemoryPlaylistStore()
    private val credentials = InMemoryCredentialStore()
    private val documents = mutableMapOf<String, ByteArray>()
    private val released = mutableListOf<String>()
    private lateinit var repository: SourceRepository

    private val playlist = """
        #EXTM3U url-tvg="%EPG%"
        #EXTINF:-1 tvg-id="tf1.fr",TF1
        http://s/tf1.m3u8
        #EXTINF:-1,France 2
        http://s/f2.m3u8
    """.trimIndent()

    private val guide = """
        <tv>
          <channel id="tf1.fr"><display-name>TF1</display-name></channel>
          <channel id="france2.fr"><display-name>France 2</display-name></channel>
          <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="tf1.fr"><title>JT</title></programme>
          <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="france2.fr"><title>Série</title></programme>
        </tv>
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
        }
        server.start()
        val logger = RedactingLogger(captured, redactor)
        val clients = HttpClientProvider(logger = logger, networkSettings = NetworkSettings(readTimeoutMillis = 2_000))
        val opener = CompositeContentOpener(listOf(RemoteContentOpener(clients, logger), FakeDocumentOpener(documents)))
        repository = SourceRepository(
            store = store,
            credentialStore = credentials,
            loaders = PlaylistLoaderRegistry(listOf(M3uPlaylistLoader(opener))),
            epgLoader = EpgLoader(opener, EpgImporter({ KXmlParser() }, clock = { now })),
            redactor = redactor,
            logger = logger,
            documentAccess = { released += it.uri },
            clock = { now },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun route(path: String, body: String, contentType: String = "audio/x-mpegurl") {
        routes[path] = MockResponse.Builder().code(200).addHeader("Content-Type", contentType).body(body).build()
    }

    private fun playlistWithEpg(epgPath: String) = playlist.replace("%EPG%", server.url(epgPath).toString())

    @Test
    fun `add validates input and stores embedded credentials separately`() = runBlocking {
        val url = server.url("/get.php").newBuilder().username("alice").password("hunter2").build()

        val id = repository.add(SourceInput(name = " ", location = SourceLocation.Remote(url.toString())))

        val source = store.source(id)
        assertEquals(SourceLocation.Remote(server.url("/get.php").toString()), source.location)
        assertEquals(server.hostName, source.name)
        assertTrue(source.hasCredentials)
        assertEquals(Credentials("alice", "hunter2"), credentials.values[id])
    }

    @Test
    fun `invalid input is rejected with field level problems`() {
        val error = runCatching {
            runBlocking {
                repository.add(
                    SourceInput(
                        name = "X",
                        location = SourceLocation.Remote("rtsp://cam/stream"),
                        epg = EpgSettings(location = SourceLocation.Remote("guide.xml")),
                        userAgent = "Agenté",
                    ),
                )
            }
        }.exceptionOrNull() as InvalidSourceInputException

        assertEquals(
            listOf(SourceInputField.LOCATION, SourceInputField.EPG_LOCATION, SourceInputField.USER_AGENT),
            error.problems.map { it.field },
        )
        assertEquals(SourceError.UnsupportedScheme("rtsp"), error.problems[0].error)
        assertEquals(SourceError.InvalidUrl(InvalidUrlReason.MISSING_SCHEME), error.problems[1].error)
    }

    @Test
    fun `refresh loads channels and the guide announced by the playlist`() = runBlocking {
        route("/list.m3u", playlistWithEpg("/epg.xml.gz"))
        routes["/epg.xml.gz"] = MockResponse.Builder().code(200).addHeader("Content-Type", "application/gzip")
            .body(Buffer().write(gzip(guide))).build()
        val id = repository.add(SourceInput(name = "IPTV", location = SourceLocation.Remote(server.url("/list.m3u").toString())))

        val result = repository.refresh(id) as RefreshResult.Success

        assertEquals(2, result.channelCount)
        val status = store.source(id).status
        assertEquals(now, status.lastSuccessAtMillis)
        assertNull(status.error)
        assertEquals(listOf(server.url("/epg.xml.gz").toString()), status.headerEpgUrls)
        assertEquals(2, status.epg.programmeCount)
        assertEquals(2, status.epg.matchedChannelCount)
        assertEquals(setOf("tf1.fr", "france2.fr"), store.epgMapping.getValue(id).values.toSet())
        assertFalse(repository.refreshing.value.contains(id))
    }

    @Test
    fun `a failed refresh keeps previous channels and records the error`() = runBlocking {
        route("/list.m3u", playlistWithEpg("/none.xml"))
        val id = repository.add(SourceInput(name = "IPTV", location = SourceLocation.Remote(server.url("/list.m3u").toString())))
        repository.refresh(id)
        val before = store.channels.getValue(id)

        routes["/list.m3u"] = MockResponse.Builder().code(500).build()
        val result = repository.refresh(id)

        assertEquals(RefreshResult.Failure(SourceError.HttpStatus(500)), result)
        assertEquals(before, store.channels.getValue(id))
        val status = store.source(id).status
        assertEquals(SourceError.HttpStatus(500), status.error)
        assertEquals(2, status.channelCount)
        assertEquals(now, status.lastSuccessAtMillis)
    }

    @Test
    fun `an epg failure does not fail the playlist`() = runBlocking {
        route("/list.m3u", playlistWithEpg("/missing.xml"))
        val id = repository.add(SourceInput(name = "IPTV", location = SourceLocation.Remote(server.url("/list.m3u").toString())))

        val result = repository.refresh(id) as RefreshResult.Success

        assertEquals(2, result.channelCount)
        assertEquals(SourceError.HttpStatus(404), result.epg.error)
        assertNull(store.source(id).status.error)
    }

    @Test
    fun `explicit local epg file is used and header guide can be disabled`() = runBlocking {
        route("/list.m3u", playlistWithEpg("/missing.xml"))
        documents["content://epg/guide.xml.gz"] = gzip(guide)
        val id = repository.add(
            SourceInput(
                name = "IPTV",
                location = SourceLocation.Remote(server.url("/list.m3u").toString()),
                epg = EpgSettings(location = SourceLocation.LocalDocument("content://epg/guide.xml.gz", "guide.xml.gz"), useHeaderUrls = false),
            ),
        )

        val result = repository.refresh(id) as RefreshResult.Success

        assertNull(result.epg.error)
        assertEquals(2, result.epg.programmeCount)
        assertTrue(server.takeRequestPaths().none { it == "/missing.xml" })
    }

    @Test
    fun `credentials are applied to the playlist and never logged`() = runBlocking {
        routes["/secure.m3u"] = MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"tv\"").build()
        val id = repository.add(
            SourceInput(
                name = "Privée",
                location = SourceLocation.Remote(server.url("/secure.m3u?password=topsecret").toString()),
                credentials = CredentialsChange.Replace(Credentials("alice", "hunter2")),
            ),
        )

        val result = repository.refresh(id)

        assertEquals(RefreshResult.Failure(SourceError.AuthenticationFailed), result)
        val logs = captured.allText()
        assertFalse(logs.contains("hunter2"))
        assertFalse(logs.contains("topsecret"))
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `update reports whether a reload is needed and manages credentials`() = runBlocking {
        val url = server.url("/list.m3u").toString()
        val id = repository.add(
            SourceInput(name = "A", location = SourceLocation.Remote(url), credentials = CredentialsChange.Replace(Credentials("u", "p"))),
        )

        assertFalse(repository.update(id, SourceInput(name = "B", location = SourceLocation.Remote(url))))
        assertEquals("B", store.source(id).name)
        assertTrue(store.source(id).hasCredentials)

        assertTrue(repository.update(id, SourceInput(name = "B", location = SourceLocation.Remote(url), credentials = CredentialsChange.Remove)))
        assertFalse(store.source(id).hasCredentials)
        assertNull(credentials.values[id])

        assertTrue(repository.update(id, SourceInput(name = "B", location = SourceLocation.Remote("$url?v=2"))))
    }

    @Test
    fun `backup servers are normalised and an invalid one is rejected`() = runBlocking {
        val url = server.url("/list.m3u").toString()
        val id = repository.add(
            SourceInput(
                name = "A",
                location = SourceLocation.Remote(url),
                mirrors = listOf(" r56mail.top ", "http://backup.example:8080/get.php?username=u", "r56mail.top", ""),
            ),
        )

        assertEquals(listOf("http://r56mail.top", "http://backup.example:8080"), store.source(id).mirrors)
        // Le dernier chargement a réussi (jamais tenté ici) : changer les secours ne recharge pas.
        assertFalse(repository.update(id, SourceInput(name = "A", location = SourceLocation.Remote(url))))
        assertEquals(emptyList<String>(), store.source(id).mirrors)

        val error = runCatching {
            runBlocking { repository.update(id, SourceInput(name = "A", location = SourceLocation.Remote(url), mirrors = listOf("pas une adresse"))) }
        }.exceptionOrNull() as InvalidSourceInputException
        assertEquals(listOf(SourceInputField.MIRRORS), error.problems.map { it.field })
    }

    @Test
    fun `delete removes credentials and releases unused local documents`() = runBlocking {
        val shared = SourceLocation.LocalDocument("content://docs/shared.m3u", "shared.m3u")
        val first = repository.add(SourceInput(name = "1", location = shared))
        val second = repository.add(SourceInput(name = "2", location = shared))
        val remote = repository.add(
            SourceInput(
                name = "3",
                location = SourceLocation.Remote(server.url("/x.m3u").toString()),
                credentials = CredentialsChange.Replace(Credentials("u", "p")),
            ),
        )

        repository.delete(first)
        assertTrue(released.isEmpty()) // encore utilisé par la source 2
        repository.delete(second)
        assertEquals(listOf(shared.uri), released)
        repository.delete(remote)
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.sourcesSnapshot().isEmpty())
    }

    @Test
    fun `local sources ignore credentials`() = runBlocking {
        val id = repository.add(
            SourceInput(
                name = "Local",
                location = SourceLocation.LocalDocument("content://docs/a.m3u"),
                credentials = CredentialsChange.Replace(Credentials("u", "p")),
            ),
        )

        assertFalse(store.source(id).hasCredentials)
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun `shared guide is imported once and again only when it changes`() = runBlocking {
        route("/list.m3u", playlistWithEpg("/ignored.xml"))
        val guideFile = java.io.File.createTempFile("guide", ".xml.gz").apply { writeBytes(gzip(guide)); deleteOnExit() }
        var feedUpdatedAt = now - 1_000
        var obtained = 0
        val feed = object : tv.nextv.core.epg.EpgFeedProvider {
            override suspend fun obtain(force: Boolean, progress: tv.nextv.core.io.ProgressListener?): tv.nextv.core.epg.EpgFeed {
                obtained++
                return tv.nextv.core.epg.EpgFeed(guideFile, feedUpdatedAt, now)
            }

            override fun cached() = null
        }
        val logger = RedactingLogger(captured, redactor)
        val opener = CompositeContentOpener(
            listOf(RemoteContentOpener(HttpClientProvider(logger = logger), logger), FakeDocumentOpener(documents)),
        )
        val withFeed = SourceRepository(
            store = store,
            credentialStore = credentials,
            loaders = PlaylistLoaderRegistry(listOf(M3uPlaylistLoader(opener))),
            epgLoader = EpgLoader(opener, EpgImporter({ KXmlParser() }, clock = { now })),
            redactor = redactor,
            logger = logger,
            clock = { now },
            epgFeed = feed,
        )
        val steps = mutableListOf<tv.nextv.core.io.LoadStep>()
        val id = withFeed.add(SourceInput(name = "IPTV", location = SourceLocation.Remote(server.url("/list.m3u").toString())))

        val first = withFeed.refresh(id) { steps += it.step } as RefreshResult.Success
        assertEquals(2, first.epg.programmeCount)
        assertEquals(
            listOf(tv.nextv.core.io.LoadStep.PLAYLIST, tv.nextv.core.io.LoadStep.SAVING, tv.nextv.core.io.LoadStep.EPG_IMPORT),
            steps.distinct(),
        )
        assertTrue(server.takeRequestPaths().none { it == "/ignored.xml" }) // le guide de l'en-tête est ignoré

        // Guide inchangé : pas de réimport.
        store.programmes.remove(id)
        withFeed.refreshEpg(id)
        assertEquals(null, store.programmes[id])

        // Guide mis à jour : réimport.
        feedUpdatedAt = now + 1
        withFeed.refreshEpg(id)
        assertEquals(2, store.programmes[id]?.size)
        assertEquals(3, obtained)
    }

    private suspend fun SourceRepository.sourcesSnapshot() = store.getSources()

    private fun MockWebServer.takeRequestPaths(): List<String> =
        generateSequence { takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.url.encodedPath }.toList()

    @Test
    fun `a user agent accepted after a refusal is kept for the source and its streams`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers["User-Agent"].orEmpty().startsWith("VLC/")) {
                    MockResponse.Builder().code(200).addHeader("Content-Type", "audio/x-mpegurl").body(playlist.replace("%EPG%", "")).build()
                } else {
                    MockResponse.Builder().code(403).build()
                }
        }
        val id = repository.add(SourceInput(name = "IPTV", location = SourceLocation.Remote(server.url("/get.php").toString())))

        val result = repository.refresh(id, includeEpg = false)

        assertTrue(result.toString(), result is RefreshResult.Success)
        assertEquals(RemoteContentOpener.FALLBACK_USER_AGENTS[0], store.source(id).userAgent)
    }

    @Test
    fun `an address given as name never shows credentials`() = runBlocking {
        val url = "http://www.r56mail.top/get.php?username=bob&password=hunter2&type=m3u_plus"
        assertEquals("www.r56mail.top", SourceInputValidator.sanitizeName(url))
        assertEquals("Ma playlist", SourceInputValidator.sanitizeName("  Ma playlist "))

        val id = repository.add(SourceInput(name = url, location = SourceLocation.Remote(server.url("/get.php").toString())))
        assertEquals("www.r56mail.top", store.source(id).name)

        // Nom enregistré par une version précédente : nettoyé au démarrage.
        store.updateSourceDefinition(store.source(id).copy(name = url))
        repository.cleanStoredNames()
        assertEquals("www.r56mail.top", store.source(id).name)
    }
}
