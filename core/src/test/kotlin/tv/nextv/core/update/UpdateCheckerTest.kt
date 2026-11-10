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
          {"tag_name":"v0.2.10","draft":false,"prerelease":true,"body":"Version 0.2.10 de NexTV.\r\n\r\n**Nouveautés**\r\n- Foot : chaînes probables\r\n\r\n**Installation** : téléchargez l'APK.","assets":[
            {"name":"NexTV-v0.2.10.apk","size":${apk.size},"browser_download_url":"${server.url("/NexTV-v0.2.10.apk")}"},
            {"name":"NexTV-v0.2.10.apk.sha256","size":90,"browser_download_url":"${server.url("/NexTV-v0.2.10.apk.sha256")}"}
          ]},
          {"tag_name":"v0.2.9","draft":false,"body":"**Nouveautés**\n- Guide : textes sans balises","assets":[{"name":"NexTV-v0.2.9.apk","size":1,"browser_download_url":"${server.url("/old.apk")}"}]},
          {"tag_name":"v0.2.8","draft":false,"body":"**Fonctionnalités**\n- Toute la liste","assets":[{"name":"NexTV-v0.2.8.apk","size":1,"browser_download_url":"${server.url("/older.apk")}"}]},
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
        val result = checker.check("0.2.9") as UpdateCheck.Available
        assertEquals("0.2.10", result.release.version)
        // Seulement les nouveautés, sans la partie installation.
        assertEquals("- Foot : chaînes probables", result.release.changes)
        assertEquals(apk.size.toLong(), result.release.apkSize)
    }

    @Test
    fun `changes of the skipped versions are listed too, newest first`() = runBlocking {
        val result = checker.check("0.2.2") as UpdateCheck.Available
        // 0.2.8 n'a que la liste complète des fonctionnalités (anciennes notes) : rien à en dire.
        assertEquals("Version 0.2.10\n- Foot : chaînes probables\n\nVersion 0.2.9\n- Guide : textes sans balises", result.release.changes)
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

    /** Version publiée avec l'APK universel et un APK par processeur (liste triée par nom, comme GitHub la renvoie). */
    private fun splitRelease(): String {
        val names = listOf(
            "NexTV-v0.2.103-mapping.txt",
            "NexTV-v0.2.103.apk", "NexTV-v0.2.103.apk.sha256",
            "NexTV-v0.2.103.arm64-v8a.apk", "NexTV-v0.2.103.arm64-v8a.apk.sha256",
            "NexTV-v0.2.103.armeabi-v7a.apk", "NexTV-v0.2.103.armeabi-v7a.apk.sha256",
            "NexTV.apk",
        )
        val assets = names.joinToString(",") { name ->
            val size = when {
                name.endsWith(".sha256") -> 90
                name.contains("arm64") -> 30_000_000
                name.contains("armeabi") -> 26_000_000
                else -> 51_000_000
            }
            """{"name":"$name","size":$size,"browser_download_url":"https://example.com/$name"}"""
        }
        return """[{"tag_name":"v0.2.103","draft":false,"body":"","assets":[$assets]}]"""
    }

    @Test
    fun `the apk of the device processor is chosen, with its own checksum`() {
        val arm64 = UpdateChecker.parseReleases(splitRelease(), listOf("arm64-v8a", "armeabi-v7a", "armeabi")).single()
        assertEquals("https://example.com/NexTV-v0.2.103.arm64-v8a.apk", arm64.apkUrl)
        assertEquals(30_000_000L, arm64.apkSize)
        assertEquals("https://example.com/NexTV-v0.2.103.arm64-v8a.apk.sha256", arm64.sha256Url)
        // Fire TV en 32 bits.
        val arm32 = UpdateChecker.parseReleases(splitRelease(), listOf("armeabi-v7a", "armeabi")).single()
        assertEquals("https://example.com/NexTV-v0.2.103.armeabi-v7a.apk", arm32.apkUrl)
        assertEquals("https://example.com/NexTV-v0.2.103.armeabi-v7a.apk.sha256", arm32.sha256Url)
        // Processeur sans APK à lui (ou inconnu) : l'APK universel.
        for (abis in listOf(listOf("x86_64", "x86"), emptyList())) {
            val universal = UpdateChecker.parseReleases(splitRelease(), abis).single()
            assertEquals("https://example.com/NexTV-v0.2.103.apk", universal.apkUrl)
            assertEquals("https://example.com/NexTV-v0.2.103.apk.sha256", universal.sha256Url)
        }
    }

    /**
     * Les versions 0.2.102 et avant prennent le premier APK et la première empreinte de la liste (triée par nom) :
     * avec les noms publiés, ce sont l'APK universel et la sienne, jamais ceux d'un processeur.
     */
    @Test
    fun `older versions still find the universal apk first`() {
        val names = Regex("\"name\":\"([^\"]+)\"").findAll(splitRelease()).map { it.groupValues[1] }.toList().sorted()
        assertEquals("NexTV-v0.2.103.apk", names.first { it.endsWith(".apk") })
        assertEquals("NexTV-v0.2.103.apk.sha256", names.first { it.endsWith(".apk.sha256") })
        assertNull(UpdateChecker.abiOf("NexTV-v0.2.103.apk"))
        assertNull(UpdateChecker.abiOf("NexTV.apk"))
        assertEquals("arm64-v8a", UpdateChecker.abiOf("NexTV-v0.2.103.arm64-v8a.apk"))
        assertEquals("armeabi-v7a", UpdateChecker.abiOf("NexTV-v0.2.103.armeabi-v7a.apk"))
    }
}
