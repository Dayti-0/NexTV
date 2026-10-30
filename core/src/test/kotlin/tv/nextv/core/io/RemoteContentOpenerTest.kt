package tv.nextv.core.io

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.log.CapturingLogger
import tv.nextv.core.log.RedactingLogger
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType
import tv.nextv.core.net.RequestPurpose
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class RemoteContentOpenerTest {

    private val server = MockWebServer()
    private val captured = CapturingLogger()
    private val redactor = Redactor()
    private val logger = RedactingLogger(captured, redactor)
    private val network = NetworkSettings(connectTimeoutMillis = 2_000, readTimeoutMillis = 1_000)
    private lateinit var clients: HttpClientProvider
    private lateinit var opener: RemoteContentOpener

    @Before
    fun setUp() {
        server.start()
        clients = HttpClientProvider(logger = logger, networkSettings = network)
        opener = RemoteContentOpener(clients, logger)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String = "#EXTM3U\n", contentType: String = "audio/x-mpegurl") =
        MockResponse.Builder().code(200).addHeader("Content-Type", contentType).body(body).build()

    private fun redirect(location: String, code: Int = 302) =
        MockResponse.Builder().code(code).addHeader("Location", location).build()

    private fun unauthorized(scheme: String = "Basic realm=\"iptv\"") =
        MockResponse.Builder().code(401).addHeader("WWW-Authenticate", scheme).build()

    private fun open(url: String, request: OpenRequest = OpenRequest(RequestPurpose.PLAYLIST), with: RemoteContentOpener = opener): String =
        runBlocking { with.open(SourceLocation.Remote(url), request) }.use { it.stream.bufferedReader().readText() }

    private fun errorOf(block: () -> Unit): SourceError {
        try {
            block()
        } catch (e: SourceException) {
            return e.error
        }
        throw AssertionError("SourceException attendue")
    }

    @Test
    fun `follows redirects and exposes the effective url`() {
        server.enqueue(redirect("/moved"))
        server.enqueue(redirect("/final.m3u", code = 301))
        server.enqueue(ok("#EXTM3U\n#EXTINF:-1,A\nhttp://s/a\n"))

        val content = runBlocking { opener.open(SourceLocation.Remote(server.url("/start").toString()), OpenRequest(RequestPurpose.PLAYLIST)) }
        content.use {
            assertEquals(server.url("/final.m3u").toString(), it.effectiveUrl)
            assertEquals("audio/x-mpegurl", it.contentType)
            assertTrue(it.stream.bufferedReader().readText().contains("#EXTINF"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `redirect loops are reported`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = redirect("/loop")
        }

        assertEquals(SourceError.TooManyRedirects, errorOf { open(server.url("/loop").toString()) })
    }

    @Test
    fun `redirects to non http schemes are reported`() {
        server.enqueue(redirect("rtsp://camera.example/stream"))

        assertEquals(SourceError.UnsupportedRedirect(302), errorOf { open(server.url("/").toString()) })
    }

    @Test
    fun `http errors are mapped`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(SourceError.HttpStatus(404), errorOf { open(server.url("/missing").toString()) })
        assertEquals(SourceError.HttpStatus(503), errorOf { open(server.url("/down").toString()) })
    }

    @Test
    fun `authentication required without credentials`() {
        server.enqueue(unauthorized())

        assertEquals(SourceError.AuthenticationRequired, errorOf { open(server.url("/").toString()) })
    }

    @Test
    fun `basic authentication answers the challenge without leaking credentials in logs`() {
        redactor.registerSecret("hunter2")
        server.enqueue(unauthorized())
        server.enqueue(ok())

        open(server.url("/list.m3u").toString(), OpenRequest(RequestPurpose.PLAYLIST, Credentials("alice", "hunter2")))

        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals(okhttp3.Credentials.basic("alice", "hunter2"), server.takeRequest().headers["Authorization"])
        assertFalse(captured.allText().contains("hunter2"))
    }

    @Test
    fun `rejected credentials are reported once without looping`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = unauthorized()
        }

        val error = errorOf { open(server.url("/").toString(), OpenRequest(RequestPurpose.PLAYLIST, Credentials("a", "wrong"))) }

        assertEquals(SourceError.AuthenticationFailed, error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unsupported authentication schemes are reported`() {
        server.enqueue(unauthorized("Digest realm=\"x\", nonce=\"abc\""))

        val error = errorOf { open(server.url("/").toString(), OpenRequest(RequestPurpose.PLAYLIST, Credentials("a", "b"))) }

        assertEquals(SourceError.UnsupportedAuthScheme("Digest"), error)
    }

    @Test
    fun `credentials are never sent to another origin after a redirect`() {
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(unauthorized())
            server.enqueue(redirect(other.url("/elsewhere.m3u").toString()))

            errorOf { open(server.url("/").toString(), OpenRequest(RequestPurpose.PLAYLIST, Credentials("alice", "secret"))) }

            assertNull(other.takeRequest().headers["Authorization"])
            assertEquals(1, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun `slow responses time out`() {
        server.enqueue(ok().newBuilder().headersDelay(3, TimeUnit.SECONDS).build())

        assertEquals(SourceError.Timeout, errorOf { open(server.url("/slow").toString()) })
    }

    @Test
    fun `refused connections are reported`() {
        val port = ServerSocket(0).use { it.localPort }

        assertEquals(SourceError.ConnectionFailed, errorOf { open("http://127.0.0.1:$port/list.m3u") })
    }

    @Test
    fun `unknown hosts are reported`() {
        val noDns = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()
        val offline = RemoteContentOpener(HttpClientProvider(noDns, networkSettings = network))

        assertEquals(SourceError.UnknownHost("nowhere.example"), errorOf { open("http://nowhere.example/a.m3u", with = offline) })
    }

    @Test
    fun `invalid urls are rejected before any request`() {
        assertEquals(SourceError.UnsupportedScheme("ftp"), errorOf { open("ftp://example.com/a.m3u") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `user agent is configurable`() {
        server.enqueue(ok())
        server.enqueue(ok())

        open(server.url("/").toString())
        open(server.url("/").toString(), OpenRequest(RequestPurpose.PLAYLIST, userAgent = "VLC/3.0.18"))

        assertEquals("NexTV/1.0", server.takeRequest().headers["User-Agent"])
        assertEquals("VLC/3.0.18", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun `a refusal is retried as a common player and the accepted user agent is reported`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(ok())

        val content = runBlocking { opener.open(SourceLocation.Remote(server.url("/").toString()), OpenRequest(RequestPurpose.PLAYLIST)) }
        content.use { assertEquals("#EXTM3U\n", it.stream.bufferedReader().readText()) }

        assertEquals(RemoteContentOpener.FALLBACK_USER_AGENTS[0], content.userAgent)
        assertEquals("NexTV/1.0", server.takeRequest().headers["User-Agent"])
        assertEquals(RemoteContentOpener.FALLBACK_USER_AGENTS[0], server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun `a connection closed without response is retried as a common player`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers["User-Agent"].orEmpty().startsWith("VLC/")) ok()
                else MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }

        val content = runBlocking { opener.open(SourceLocation.Remote(server.url("/").toString()), OpenRequest(RequestPurpose.PLAYLIST)) }
        val text = content.use { it.stream.bufferedReader().readText() }
        assertEquals("#EXTM3U\n", text)
        assertEquals("NexTV/1.0", server.takeRequest().headers["User-Agent"])
        assertEquals(RemoteContentOpener.FALLBACK_USER_AGENTS[0], content.userAgent)
    }

    @Test
    fun `every fallback is tried before reporting the refusal`() {
        repeat(1 + RemoteContentOpener.FALLBACK_USER_AGENTS.size) { server.enqueue(MockResponse.Builder().code(403).build()) }

        val error = errorOf { open(server.url("/").toString()) }

        assertEquals(1 + RemoteContentOpener.FALLBACK_USER_AGENTS.size, server.requestCount)
        assertTrue(error.code, error !is SourceError.HttpStatus || error.status == 403)
    }

    @Test
    fun `a user agent chosen for the source is never replaced`() {
        server.enqueue(MockResponse.Builder().code(403).build())

        errorOf { open(server.url("/").toString(), OpenRequest(RequestPurpose.PLAYLIST, userAgent = "Mon lecteur/1.0")) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the default user agent is not reported when it is accepted`() {
        server.enqueue(ok())
        val content = runBlocking { opener.open(SourceLocation.Remote(server.url("/").toString()), OpenRequest(RequestPurpose.PLAYLIST)) }
        content.close()
        assertNull(content.userAgent)
    }

    @Test
    fun `logged urls are redacted`() {
        server.enqueue(ok())

        open(server.url("/get.php?username=bob&password=hunter2&type=m3u_plus").toString())

        val logs = captured.allText()
        assertTrue(logs.contains("password=***"))
        assertFalse(logs.contains("hunter2"))
    }

    // --- Proxy ------------------------------------------------------------------------------

    private fun proxy(
        port: Int = server.port,
        scopes: Set<ProxyScope> = ProxyScope.entries.toSet(),
        bypassLocal: Boolean = true,
        credentials: Credentials? = null,
    ) = ProxySettings(
        enabled = true,
        type = ProxyType.HTTP,
        host = "127.0.0.1",
        port = port,
        credentials = credentials,
        scopes = scopes,
        bypassLocalAddresses = bypassLocal,
    )

    @Test
    fun `remote requests go through the http proxy`() {
        clients.update(proxySettings = proxy())
        server.enqueue(ok())

        open("http://playlist.example/list.m3u")

        val recorded = server.takeRequest()
        assertEquals("GET http://playlist.example/list.m3u HTTP/1.1", recorded.requestLine)
    }

    @Test
    fun `proxy basic authentication`() {
        clients.update(proxySettings = proxy(credentials = Credentials("proxyuser", "proxypass")))
        server.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build())
        server.enqueue(ok())

        open("http://playlist.example/list.m3u")

        assertNull(server.takeRequest().headers["Proxy-Authorization"])
        assertEquals(okhttp3.Credentials.basic("proxyuser", "proxypass"), server.takeRequest().headers["Proxy-Authorization"])
    }

    @Test
    fun `proxy authentication failure is reported`() {
        clients.update(proxySettings = proxy())
        server.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build())

        assertEquals(SourceError.ProxyAuthRequired, errorOf { open("http://playlist.example/list.m3u") })
    }

    @Test
    fun `proxy applies only to the configured scopes`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        clients.update(proxySettings = proxy(port = deadPort, scopes = setOf(ProxyScope.STREAMS), bypassLocal = false))
        server.enqueue(ok())

        open(server.url("/list.m3u").toString()) // playlist : connexion directe
        val error = errorOf { open(server.url("/live.ts").toString(), OpenRequest(RequestPurpose.STREAM)) }

        assertEquals(SourceError.ProxyUnreachable, error)
        assertTrue(clients.usesProxy(RequestPurpose.STREAM))
        assertFalse(clients.usesProxy(RequestPurpose.PLAYLIST))
    }

    @Test
    fun `local addresses bypass the proxy when requested`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        server.enqueue(ok())

        clients.update(proxySettings = proxy(port = deadPort, bypassLocal = true))
        open(server.url("/list.m3u").toString())

        clients.update(proxySettings = proxy(port = deadPort, bypassLocal = false))
        assertEquals(SourceError.ProxyUnreachable, errorOf { open(server.url("/list.m3u").toString()) })
    }
}
