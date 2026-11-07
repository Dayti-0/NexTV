package tv.nextv.core.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.net.HttpClientProvider
import java.security.MessageDigest

class UpdateCheckerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private var releasesStatus = 200
    private var publishedHash: String = sha256(apk)
    private lateinit var checker: UpdateChecker

    private fun releasesJson() = """
        [
          {"tag_name":"v0.3.0","draft":true,"body":"","assets":[{"name":"NexTV-v0.3.0.apk","size":1,"browser_download_url":"${server.url("/draft.apk")}"}]},
          {"tag_name":"v0.2.10","draft":false,"prerelease":true,"body":"Nouveautés","assets":[
            {"name":"NexTV-v0.2.10.apk","size":${apk.size},"browser_download_url":"${server.url("/NexTV-v0.2.10.apk")}"},
            {"name":"NexTV-v0.2.10.apk.sha256","size":90,"browser_download_url":"${server.url("/NexTV-v0.2.10.apk.sha256")}"}
          ]},
          {"tag_name":"v0.2.9","draft":false,"body":"","assets":[{"name":"NexTV-v0.2.9.apk","size":1,"browser_download_url":"${server.url("/old.apk")}"}]},
          {"tag_name":"v0.1.0","draft":false,"body":"","assets":[]}
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/repos/owner/app/releases" ->
                    if (releasesStatus == 200) MockResponse.Builder().code(200).body(releasesJson()).build()
                    else MockResponse.Builder().code(releasesStatus).build()
                "/NexTV-v0.2.10.apk" -> MockResponse.Builder().code(200).body(okio.Buffer().write(apk)).build()
                "/NexTV-v0.2.10.apk.sha256" -> MockResponse.Builder().code(200).body("$publishedHash  NexTV-v0.2.10.apk\n").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        checker = UpdateChecker(HttpClientProvider(), "owner/app", apiBaseUrl = server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `newest published version with an apk is offered, drafts are ignored`() = runBlocking {
        val result = checker.check("0.2.2") as UpdateCheck.Available
        assertEquals("0.2.10", result.release.version)
        assertEquals("Nouveautés", result.release.notes)
        assertEquals(apk.size.toLong(), result.release.apkSize)
    }

    @Test
    fun `same or newer installed version is up to date`() = runBlocking {
        assertEquals(UpdateCheck.UpToDate, checker.check("0.2.10"))
        assertEquals(UpdateCheck.UpToDate, checker.check("0.3.1"))
    }

    @Test
    fun `private repository is reported as not published`() = runBlocking {
        releasesStatus = 404
        assertEquals(UpdateCheck.Failed(UpdateError.NOT_PUBLISHED), checker.check("0.2.2"))
    }

    @Test
    fun `download verifies the published checksum`() = runBlocking {
        val release = (checker.check("0.2.2") as UpdateCheck.Available).release
        val target = folder.root.resolve("update.apk")
        var progress = 0L
        assertNull(checker.download(release, target) { downloaded, _ -> progress = downloaded })
        assertTrue(target.readBytes().contentEquals(apk))
        assertEquals(apk.size.toLong(), progress)
    }

    @Test
    fun `corrupted download is rejected and deleted`() = runBlocking {
        publishedHash = "0".repeat(64)
        val release = (checker.check("0.2.2") as UpdateCheck.Available).release
        val target = folder.root.resolve("update.apk")
        assertEquals(UpdateError.CORRUPTED, checker.download(release, target))
        assertFalse(target.exists())
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(UpdateChecker.compareVersions("0.2.10", "0.2.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "0.9.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("0.2", "0.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.0.0-beta", "1.0.0") < 0)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
