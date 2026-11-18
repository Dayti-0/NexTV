package tv.nextv.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.sync.SyncBinClosedException
import tv.nextv.core.sync.SyncRelay
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pages filebin.net des télés reliées (le même service que l'envoi des enregistrements). Une page n'existe que par
 * son nom, tiré au hasard ; filebin la garde 7 jours après le dernier dépôt, et refuse d'écrire de nouveau dans une
 * page supprimée ou expirée (405).
 *
 * @param base adresse du service (changée pour les tests).
 */
class FilebinRelay(client: OkHttpClient, private val base: String = "https://filebin.net") : SyncRelay {
    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun list(bin: String): Map<String, String> = call(Request.Builder().url("$base/$bin").header("Accept", "application/json").build()) { code, body ->
        when (code) {
            404 -> emptyMap()
            in 200..299 -> files(body)
            else -> throw IOException("filebin $code")
        }
    }

    override suspend fun read(bin: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("$base/$bin/$name").build()).execute().use { response ->
            when (response.code) {
                404 -> null
                in 200..299 -> response.body.bytes()
                else -> throw IOException("filebin ${response.code}")
            }
        }
    }

    override suspend fun write(bin: String, name: String, bytes: ByteArray) {
        val request = Request.Builder().url("$base/$bin/$name").post(bytes.toRequestBody(OCTETS)).build()
        call(request) { code, _ ->
            when (code) {
                in 200..299 -> Unit
                // Page supprimée, expirée ou verrouillée.
                403, 405 -> throw SyncBinClosedException()
                else -> throw IOException("filebin $code")
            }
        }
    }

    override suspend fun deleteFile(bin: String, name: String) = delete("$base/$bin/$name")

    override suspend fun deleteBin(bin: String) = delete("$base/$bin")

    private suspend fun delete(url: String) {
        call(Request.Builder().url(url).delete().build()) { code, _ ->
            if (code !in 200..299 && code != 404) throw IOException("filebin $code")
        }
    }

    private suspend fun <T> call(request: Request, handle: (Int, String) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response -> handle(response.code, response.body.string()) }
    }

    internal companion object {
        val OCTETS = "application/octet-stream".toMediaType()

        /** Fichiers d'une page d'après la réponse JSON de filebin (`"files": [{"filename", "sha256"}]`, ou `null`). */
        fun files(body: String): Map<String, String> = try {
            val array = JSONObject(body).optJSONArray("files")
            buildMap {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val file = array.optJSONObject(i) ?: continue
                        val name = file.optString("filename").takeIf { it.isNotEmpty() } ?: continue
                        put(name, file.optString("sha256").ifEmpty { file.optString("updated_at") })
                    }
                }
            }
        } catch (e: JSONException) {
            throw IOException("Réponse de filebin illisible", e)
        }
    }
}
