package tv.nextv.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val apkUrl: String,
    val apkSize: Long,
    /** Empreinte SHA-256 publiée avec l'APK (fichier `.sha256`), si présente. */
    val sha256Url: String?,
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
 */
class UpdateChecker(
    private val clients: HttpClientProvider,
    private val repository: String,
    private val apiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val logger: Logger = Logger.None,
) {

    /** Version la plus récente, si elle est plus récente que [currentVersion]. */
    suspend fun check(currentVersion: String): UpdateCheck = try {
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
                    val latest = parseReleases(response.body.string()).maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                    when {
                        latest == null -> UpdateCheck.Failed(UpdateError.NOT_PUBLISHED)
                        compareVersions(latest.version, currentVersion) > 0 -> UpdateCheck.Available(latest)
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
    suspend fun download(release: AppRelease, target: File, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): UpdateError? {
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

        /** Versions publiées avec un APK (brouillons exclus). */
        fun parseReleases(json: String): List<AppRelease> {
            val releases = JSONArray(json)
            return (0 until releases.length()).mapNotNull { i ->
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) return@mapNotNull null
                val version = release.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val files = (0 until assets.length()).map { assets.getJSONObject(it) }
                val apk = files.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
                val sha = files.firstOrNull { it.optString("name").endsWith(".apk.sha256") }
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
