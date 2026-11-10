package tv.nextv.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Version publiée de l'application (release GitHub avec un APK). */
data class AppRelease(
    /** « 0.2.3 » (sans le « v » du tag). */
    val version: String,
    val notes: String?,
    /** APK à télécharger : celui du processeur de l'appareil s'il est publié, sinon l'APK universel. */
    val apkUrl: String,
    val apkSize: Long,
    /** Empreinte SHA-256 publiée avec l'APK (fichier `.sha256`), si présente. */
    val sha256Url: String?,
    /**
     * Nouveautés à afficher avant l'installation : celles de cette version et des versions sautées depuis
     * celle installée (voir [UpdateChecker.changesSince]).
     */
    val changes: String? = null,
)

enum class UpdateError {
    /** Aucune version publique : dépôt privé ou rien de publié. */
    NOT_PUBLISHED,
    NETWORK,
    SERVER,

    /** Fichier téléchargé différent de celui publié (empreinte SHA-256). */
    CORRUPTED,
}

sealed interface UpdateCheck {
    data class Available(val release: AppRelease) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val error: UpdateError) : UpdateCheck
}

/**
 * Mises à jour publiées sur GitHub ([repository] : « propriétaire/dépôt »). Lecture anonyme de
 * l'API : les versions doivent être publiques. Les pré-versions (0.x) sont prises en compte.
 *
 * @param supportedAbis processeurs de l'appareil, du préféré au moins bon (`Build.SUPPORTED_ABIS`) : l'APK publié
 * pour le premier d'entre eux est téléchargé (deux fois plus léger que l'APK universel).
 */
class UpdateChecker(
    private val clients: HttpClientProvider,
    private val repository: String,
    private val apiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val logger: Logger = Logger.None,
    private val supportedAbis: List<String> = emptyList(),
) {

    /** Version la plus récente, si elle est plus récente que [currentVersion]. */
    suspend fun check(currentVersion: String): UpdateCheck = withContext(Dispatchers.IO) { checkOnIo(currentVersion) }

    private suspend fun checkOnIo(currentVersion: String): UpdateCheck = try {
        val url = apiBaseUrl.newBuilder()
            .addPathSegments("repos/$repository/releases")
            .addQueryParameter("per_page", "20")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client().newCall(request).await().use { response ->
            when {
                response.code == 404 -> UpdateCheck.Failed(UpdateError.NOT_PUBLISHED)
                !response.isSuccessful -> UpdateCheck.Failed(UpdateError.SERVER)
                else -> {
                    val releases = parseReleases(response.body.string(), supportedAbis)
                    val latest = releases.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                    when {
                        latest == null -> UpdateCheck.Failed(UpdateError.NOT_PUBLISHED)
                        compareVersions(latest.version, currentVersion) > 0 ->
                            UpdateCheck.Available(latest.copy(changes = changesSince(releases, currentVersion)))
                        else -> UpdateCheck.UpToDate
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Recherche de mise à jour impossible", e)
        UpdateCheck.Failed(UpdateError.NETWORK)
    } catch (e: JSONException) {
        logger.warn(TAG, "Liste des versions illisible", e)
        UpdateCheck.Failed(UpdateError.SERVER)
    }

    /**
     * Télécharge l'APK de [release] dans [target] et vérifie son empreinte.
     * @return `null` si le fichier est prêt, sinon l'erreur (le fichier est alors supprimé).
     */
    suspend fun download(release: AppRelease, target: File, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): UpdateError? =
        withContext(Dispatchers.IO) { downloadOnIo(release, target, onProgress) }

    private suspend fun downloadOnIo(release: AppRelease, target: File, onProgress: (downloaded: Long, total: Long) -> Unit): UpdateError? {
        try {
            val expected = release.sha256Url?.let { fetchText(it).trim().substringBefore(' ').lowercase() }
            val digest = MessageDigest.getInstance("SHA-256")
            target.parentFile?.mkdirs()
            client().newCall(Request.Builder().url(release.apkUrl).build()).await().use { response ->
                if (!response.isSuccessful) return if (response.code == 404) UpdateError.NOT_PUBLISHED else UpdateError.SERVER
                val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && expected.length == 64 && expected != actual) {
                logger.warn(TAG, "Empreinte de la mise à jour incorrecte")
                target.delete()
                return UpdateError.CORRUPTED
            }
            return null
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            logger.warn(TAG, "Téléchargement de la mise à jour interrompu", e)
            target.delete()
            return UpdateError.NETWORK
        }
    }

    private suspend fun fetchText(url: String): String =
        client().newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }

    private fun client() = clients.client(RequestPurpose.METADATA)

    companion object {
        private const val TAG = "Update"
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * APK publié pour un processeur : « NexTV-v0.2.103.arm64-v8a.apk ». Ce nom se range après celui de l'APK
         * universel (« NexTV-v0.2.103.apk ») dans la liste de GitHub, triée par nom : les versions 0.2.102 et avant,
         * qui prennent le premier APK de la liste, continuent de télécharger l'APK universel.
         */
        private val ABI_APK = Regex("""^.+\.(arm64-v8a|armeabi-v7a|x86_64|x86)\.apk$""")

        /** Processeur d'un APK publié pour un seul processeur, `null` pour l'APK universel. */
        fun abiOf(name: String): String? = ABI_APK.matchEntire(name)?.groupValues?.get(1)

        /**
         * Versions publiées avec un APK (brouillons exclus) : celui du premier processeur de [supportedAbis] qui en a
         * un, sinon l'APK universel ; son empreinte est celle du même nom suivi de « .sha256 ».
         */
        fun parseReleases(json: String, supportedAbis: List<String> = emptyList()): List<AppRelease> {
            val releases = JSONArray(json)
            return (0 until releases.length()).mapNotNull { i ->
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) return@mapNotNull null
                val version = release.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val files = (0 until assets.length()).map { assets.getJSONObject(it) }
                val apks = files.filter { it.optString("name").endsWith(".apk") }
                val universal = apks.firstOrNull { abiOf(it.optString("name")) == null }
                val forDevice = supportedAbis.firstNotNullOfOrNull { abi -> apks.firstOrNull { abiOf(it.optString("name")) == abi } }
                val apk = forDevice ?: universal ?: apks.firstOrNull() ?: return@mapNotNull null
                val apkName = apk.optString("name")
                val sha = files.firstOrNull { it.optString("name") == "$apkName.sha256" }
                    // Versions plus anciennes : la première empreinte d'un APK universel.
                    ?: files.firstOrNull { it.optString("name").let { name -> name.endsWith(".apk.sha256") && abiOf(name.removeSuffix(".sha256")) == null } }
                        ?.takeIf { abiOf(apkName) == null }
                AppRelease(
                    version = version,
                    notes = release.optString("body").takeIf { it.isNotBlank() },
                    apkUrl = apk.getString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                    sha256Url = sha?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                )
            }
        }

        /**
         * Nouveautés des versions plus récentes que [installed], la plus récente d'abord, chacune sous son
         * numéro quand il y en a plusieurs. Elles viennent de la section « Nouveautés » des notes (tirée de
         * CHANGELOG.md à la publication) ; les versions qui n'en ont pas (0.2.35 et avant) sont passées.
         */
        fun changesSince(releases: List<AppRelease>, installed: String): String? {
            val sections = releases
                .filter { compareVersions(it.version, installed) > 0 }
                .sortedWith { a, b -> compareVersions(b.version, a.version) }
                .distinctBy { it.version }
                .mapNotNull { release -> release.notes?.let(::changesOf)?.let { release.version to it } }
            return when (sections.size) {
                0 -> null
                1 -> sections.single().second
                else -> sections.joinToString("\n\n") { (version, changes) -> "Version $version\n$changes" }
            }
        }

        /** Section « Nouveautés » de notes de version : ses lignes, jusqu'à la section suivante (« **Installation** »). */
        fun changesOf(notes: String): String? {
            val lines = notes.lines()
            val start = lines.indexOfFirst { it.trim().trimStart('*', '#', ' ').startsWith("Nouveautés") }
            if (start < 0) return null
            return lines.drop(start + 1)
                .takeWhile { line -> line.trim().let { !it.startsWith("**") && !it.startsWith("#") } }
                .joinToString("\n")
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Compare deux versions « 1.2.3 » numériquement (« 0.2.10 » > « 0.2.9 »). À numéros égaux,
         * une pré-version (« 1.0.0-beta ») précède la version finale.
         */
        fun compareVersions(a: String, b: String): Int {
            fun numbers(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val left = numbers(a)
            val right = numbers(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            val leftPre = a.contains('-')
            val rightPre = b.contains('-')
            return when {
                leftPre == rightPre -> 0
                leftPre -> -1
                else -> 1
            }
        }
    }
}
