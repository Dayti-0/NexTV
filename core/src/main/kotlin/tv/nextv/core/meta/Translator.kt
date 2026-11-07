package tv.nextv.core.meta

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.net.await
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Traduction automatique des résumés que TMDB n'a qu'en anglais : Google Traduction (service gratuit,
 * sans clé), sinon MyMemory. `null` quand aucun ne répond : le texte d'origine reste affiché.
 */
class Translator(
    private val clients: HttpClientProvider,
    private val logger: Logger = Logger.None,
    private val googleUrl: HttpUrl = "https://translate.googleapis.com/translate_a/single".toHttpUrl(),
    private val myMemoryUrl: HttpUrl = "https://api.mymemory.translated.net/get".toHttpUrl(),
    private val callTimeoutMillis: Long = 8_000,
) {
    suspend fun translate(text: String, from: String, to: String): String? {
        val source = text.trim().take(MAX_LENGTH)
        if (source.isEmpty() || from == to) return null
        return google(source, from, to) ?: myMemory(source, from, to)
    }

    private suspend fun google(text: String, from: String, to: String): String? {
        val url = googleUrl.newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", from)
            .addQueryParameter("tl", to)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        return fetch(url)?.let(::parseGoogle)
    }

    /** MyMemory n'accepte que 500 octets par demande : le texte est envoyé phrase par phrase. */
    private suspend fun myMemory(text: String, from: String, to: String): String? {
        val parts = chunks(text, MY_MEMORY_BYTES)
        val translated = parts.map { part ->
            val url = myMemoryUrl.newBuilder()
                .addQueryParameter("q", part)
                .addQueryParameter("langpair", "$from|$to")
                .build()
            fetch(url)?.let(::parseMyMemory) ?: return null
        }
        return translated.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun fetch(url: HttpUrl): String? = try {
        val call = clients.client(RequestPurpose.METADATA).newCall(Request.Builder().url(url).build())
        call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        withContext(Dispatchers.IO) {
            call.await().use { response -> if (response.isSuccessful) response.body.string() else null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn(TAG, "Traduction injoignable", e)
        null
    }

    companion object {
        private const val TAG = "Translator"
        private const val MAX_LENGTH = 3_000
        private const val MY_MEMORY_BYTES = 450

        /** Réponse de Google Traduction : `[[["traduit","original",…],…],…]`. */
        fun parseGoogle(body: String): String? = try {
            val sentences = JSONArray(body).optJSONArray(0) ?: JSONArray()
            (0 until sentences.length()).mapNotNull { sentences.optJSONArray(it)?.optString(0) }
                .joinToString("")
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (e: JSONException) {
            null
        }

        /** Réponse de MyMemory : `{"responseData":{"translatedText":"…"},"responseStatus":200}`. */
        fun parseMyMemory(body: String): String? = try {
            val json = JSONObject(body)
            json.optJSONObject("responseData")?.optString("translatedText")
                ?.takeIf { json.optInt("responseStatus") == 200 && it.isNotBlank() && !json.optBoolean("quotaFinished") }
        } catch (e: JSONException) {
            null
        }

        /** Morceaux de moins de [maxBytes] octets, coupés entre deux phrases (ou deux mots). */
        fun chunks(text: String, maxBytes: Int): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in text.split(Regex("""(?<=[.!?])\s+"""))) {
                val pieces = if (sentence.toByteArray().size <= maxBytes) listOf(sentence) else sentence.split(' ')
                for (piece in pieces) {
                    val candidate = if (current.isEmpty()) piece else "$current $piece"
                    if (candidate.toByteArray().size > maxBytes && current.isNotEmpty()) {
                        out += current.toString()
                        current.clear().append(piece)
                    } else {
                        current.clear().append(candidate)
                    }
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
    }
}
