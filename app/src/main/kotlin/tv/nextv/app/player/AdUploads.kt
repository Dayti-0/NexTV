package tv.nextv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Envoi des enregistrements d'analyse des pubs sur filebin.net (réglage) : chaque enregistrement terminé part tout seul
 * sur la page de l'appareil (`https://filebin.net/<bin>`), où Claude va le chercher — plus rien à récupérer à la main.
 * Une fois filebin d'accord sur la taille reçue, l'enregistrement est supprimé de l'appareil (place libérée) ; sans
 * cette confirmation, il est seulement marqué envoyé (« .envoye » dans son dossier) et gardé. Un envoi raté (réseau
 * coupé) est refait au suivant, à l'ouverture des réglages ou au prochain lancement. Les fichiers restent une semaine
 * sur filebin.
 *
 * @param bin page de l'appareil, `null` quand l'envoi est coupé.
 * @param base adresse du service (changée pour les tests).
 */
class AdUploads(
    private val recordings: AdRecordings,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val bin: () -> String?,
    private val base: String = "https://filebin.net",
) {
    /** État des envois, pour les réglages. */
    data class Status(val sent: Int = 0, val pending: Int = 0, val sending: String? = null, val failed: Boolean = false)

    private val client = client.newBuilder()
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    /** Enregistrements envoyés puis supprimés de l'appareil depuis le lancement. */
    @Volatile
    private var removed = 0

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** Envoie les enregistrements pas encore envoyés (l'un après l'autre, en arrière-plan). */
    fun sendPending() {
        scope.launch(worker) { sendAll() }
    }

    /** Pour les tests : envoie tout de suite, sur le fil appelant. */
    internal fun sendAll() {
        val page = bin() ?: return
        var folders = recordings.folders()
        var failed = false
        for (folder in folders.filter { !File(it, SENT).exists() }) {
            update(folders, sending = folder.name, failed = failed)
            failed = !send(page, folder) || failed
            folders = recordings.folders()
        }
        update(folders, sending = null, failed = failed)
        recordings.refresh()
    }

    private fun update(folders: List<File>, sending: String?, failed: Boolean) {
        val kept = folders.count { File(it, SENT).exists() }
        mutableStatus.value = Status(sent = removed + kept, pending = folders.size - kept, sending = sending, failed = failed)
    }

    private fun send(page: String, folder: File): Boolean = try {
        val zip = StoredZip(folder)
        val body = object : RequestBody() {
            override fun contentType() = "application/zip".toMediaType()
            override fun contentLength() = zip.length
            override fun writeTo(sink: BufferedSink) = zip.writeTo(sink.outputStream())
        }
        val request = Request.Builder().url("$base/$page/${folder.name}.zip").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            when (receivedBytes(response.body.string())) {
                // Bien reçu en entier : plus besoin de le garder sur l'appareil.
                zip.length -> {
                    folder.deleteRecursively()
                    removed++
                    true
                }
                // Pas de confirmation de la taille : gardé, mais pas renvoyé.
                null -> {
                    File(folder, SENT).writeText(System.currentTimeMillis().toString())
                    true
                }
                // Taille différente : envoi incomplet, refait plus tard.
                else -> false
            }
        }
    } catch (e: IOException) {
        false
    }

    companion object {
        /** Taille du fichier reçu d'après la réponse de filebin (`"file": { … "bytes": N … }`), `null` si absente. */
        internal fun receivedBytes(answer: String): Long? =
            Regex("\"file\"\\s*:\\s*\\{[^}]*?\"bytes\"\\s*:\\s*(\\d+)").find(answer)?.groupValues?.get(1)?.toLongOrNull()

        /** Marque d'un enregistrement envoyé (fichier caché : pas dans les zips). */
        const val SENT = ".envoye"

        /** Page filebin propre à l'appareil : « nixtv- » et 12 lettres ou chiffres tirés au hasard. */
        fun newBin(): String = "nixtv-" + (1..12).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }.joinToString("")
    }
}
