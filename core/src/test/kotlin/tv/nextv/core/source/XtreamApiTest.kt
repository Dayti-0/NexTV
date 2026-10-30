package tv.nextv.core.source

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkSettings

class XtreamApiTest {

    private val primary = MockWebServer()
    private val backup = MockWebServer()
    private val api = XtreamApi(HttpClientProvider(networkSettings = NetworkSettings(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)))

    @Before
    fun setUp() {
        primary.start()
        backup.start()
    }

    @After
    fun tearDown() {
        primary.close()
        backup.close()
    }

    private val active = """
        {"user_info":{"username":"u","password":"p","auth":1,"status":"Active","exp_date":"1798761600","is_trial":"0",
        "active_cons":"1","max_connections":"2"},"server_info":{"timezone":"Europe/Paris","timestamp_now":1700000000}}
    """.trimIndent()

    @Test
    fun `account details are read from player_api`() {
        val info = XtreamApi.parseAccountInfo(active)!!

        assertTrue(info.active)
        assertEquals(1_798_761_600_000L, info.expiresAtMillis)
        assertFalse(info.trial)
        assertEquals(1, info.activeConnections)
        assertEquals(2, info.maxConnections)
        assertEquals("Europe/Paris", info.serverTimeZone)
    }

    @Test
    fun `refused or expired accounts are not active`() {
        val refused = XtreamApi.parseAccountInfo("""{"user_info":{"auth":0}}""")!!
        assertFalse(refused.authenticated)
        assertFalse(refused.active)

        val expired = XtreamApi.parseAccountInfo("""{"user_info":{"auth":1,"status":"Expired","exp_date":null,"is_trial":1}}""")!!
        assertFalse(expired.active)
        assertNull(expired.expiresAtMillis)
        assertTrue(expired.trial)

        assertNull(XtreamApi.parseAccountInfo("<html>Erreur</html>"))
    }

    @Test
    fun `the backup server answers when the main one fails`() = runBlocking {
        primary.enqueue(MockResponse.Builder().code(503).build())
        backup.enqueue(MockResponse.Builder().code(200).body(active).build())
        val source = PlaylistSource(
            name = "Test",
            location = SourceLocation.Remote(primary.url("/get.php?username=u&password=p&type=m3u_plus").toString()),
            mirrors = listOf(backup.url("/").toString().trimEnd('/')),
        )

        val info = api.accountInfo(source)

        assertEquals(2, info?.maxConnections)
        val request = backup.takeRequest()
        assertEquals("/player_api.php", request.url.encodedPath)
        assertEquals("u", request.url.queryParameter("username"))
        assertEquals("p", request.url.queryParameter("password"))
    }

    @Test
    fun `replay days are read from the list of live streams`() {
        val streams = """
            [{"num":1,"name":"TF1 \"HD\"","stream_type":"live","stream_id":101,"category_ids":[1,2],"tv_archive":1,
              "direct_source":"","tv_archive_duration":"7"},
             {"num":2,"name":"France 2","stream_id":"102","tv_archive":0,"tv_archive_duration":0},
             {"num":3,"name":"M6 {replay}","stream_id":103,"tv_archive":"1","tv_archive_duration":3,"extra":{"a":[{"b":"]"}]}},
             {"num":4,"stream_id":104,"tv_archive":1,"tv_archive_duration":null}]
        """.trimIndent()

        assertEquals(mapOf("101" to 7, "103" to 3), XtreamApi.readArchiveDays(streams.reader()))
        assertEquals(emptyMap<String, Int>(), XtreamApi.readArchiveDays("[]".reader()))
        assertNull(XtreamApi.readArchiveDays("""{"user_info":{"auth":0}}""".reader()))
    }

    @Test
    fun `replay days are requested with the live streams action`() = runBlocking {
        primary.enqueue(MockResponse.Builder().code(200).body("""[{"stream_id":5,"tv_archive":1,"tv_archive_duration":"2"}]""").build())
        val source = PlaylistSource(
            name = "Test",
            location = SourceLocation.Remote(primary.url("/get.php?username=u&password=p&type=m3u_plus").toString()),
        )

        assertEquals(mapOf("5" to 2), api.archiveDays(source))
        assertEquals("get_live_streams", primary.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun `playlists that are not Xtream accounts have no account details`() = runBlocking {
        assertNull(api.accountInfo(PlaylistSource(name = "M3U", location = SourceLocation.Remote(primary.url("/liste.m3u").toString()))))
    }
}
