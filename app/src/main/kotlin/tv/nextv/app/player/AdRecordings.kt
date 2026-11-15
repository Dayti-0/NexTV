package tv.nextv.app.player

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Enregistrements pour l'analyse des pubs ([AdRecorder]) : lancés depuis les options du lecteur, gardés sur l'appareil
 * (les [MAX_FILES] derniers), puis récupérés sur un téléphone ou un ordinateur du même réseau par une adresse que
 * Réglages affiche ([share]).
 */
class AdRecordings(private val directory: File, private val version: String) {

    /** Enregistrement terminé, gardé sur l'appareil. */
    data class Recording(val name: String, val bytes: Long)

    private val mutableFiles = MutableStateFlow(list())

    /** Enregistrements gardés, le plus récent en premier. */
    val files: StateFlow<List<Recording>> = mutableFiles.asStateFlow()

    /** Enregistrement en cours (un seul à la fois). */
    @Volatile
    var current: AdRecorder? = null
        private set

    /** Il reste assez de place sur l'appareil pour un enregistrement de 20 minutes. */
    fun hasRoom(): Boolean = (directory.parentFile ?: directory).usableSpace > MIN_FREE_BYTES

    /**
     * Commence un enregistrement de la chaîne [channel], branché sur [control] (son, images et décisions de la détection
     * des pubs). L'enregistrement en cours, s'il y en a un, est d'abord arrêté.
     */
    fun start(channel: String, control: AdVolumeControl): AdRecorder? {
        stop()
        if (!hasRoom()) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.FRANCE).format(Date())
        val name = "pubs_${safe(channel)}_$stamp"
        val info = linkedMapOf(
            "format" to "1",
            "chaine" to channel,
            "debut" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()),
            "appli" to version,
            "appareil" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            "son_enregistre" to "8000 Hz environ, mono, µ-law (fréquence exacte : fréquence du son / arrondi(fréquence / 8000))",
        )
        val recorder = AdRecorder(File(directory, name), info, MAX_MILLIS) { zip ->
            if (zip != null) trim()
            mutableFiles.value = list()
        }
        current = recorder
        control.record(recorder)
        return recorder
    }

    /** Arrête l'enregistrement en cours (le fichier est prêt quelques secondes après). */
    fun stop() {
        current?.finish()
        current = null
    }

    /** Supprime tous les enregistrements gardés. */
    fun deleteAll() {
        directory.listFiles { file -> file.name.endsWith(".zip") }?.forEach { it.delete() }
        mutableFiles.value = list()
    }

    fun file(name: String): File? = File(directory, name).takeIf { it.isFile && it.name.endsWith(".zip") && it.parentFile == directory }

    private fun list(): List<Recording> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Recording(it.name, it.length()) }
            .orEmpty()

    private fun trim() {
        val zips = directory.listFiles { file -> file.name.endsWith(".zip") } ?: return
        zips.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    /**
     * Partage les enregistrements sur le réseau de la maison tant que le résultat n'est pas fermé : un petit serveur
     * web, à une adresse qui contient un code tiré au hasard (les autres appareils du réseau ne la devinent pas).
     * `null` sans réseau local.
     */
    fun share(host: String? = localAddress()): Share? {
        if (host == null) return null
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                try {
                    bind(InetSocketAddress(PORT))
                } catch (e: java.io.IOException) {
                    bind(InetSocketAddress(0))
                }
            }
        } catch (e: java.io.IOException) {
            return null
        }
        val code = (1..6).map { CODE_LETTERS[Random.nextInt(CODE_LETTERS.length)] }.joinToString("")
        return Share(this, socket, code, "http://$host:${socket.localPort}/$code/")
    }

    /** Partage en cours ([address] à ouvrir sur le téléphone) ; [close] l'arrête. */
    class Share internal constructor(
        private val recordings: AdRecordings,
        private val socket: ServerSocket,
        private val code: String,
        val address: String,
    ) : AutoCloseable {

        init {
            Thread({ serve() }, "ad-recordings-share").apply { isDaemon = true }.start()
        }

        private fun serve() {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: java.io.IOException) {
                    return
                }
                Thread({ answer(client) }, "ad-recordings-client").apply { isDaemon = true }.start()
            }
        }

        private fun answer(client: Socket) {
            client.use {
                try {
                    client.soTimeout = 15_000
                    val request = client.getInputStream().bufferedReader().readLine() ?: return
                    val path = request.split(' ').getOrNull(1).orEmpty()
                    val out = client.getOutputStream()
                    val prefix = "/$code/"
                    when {
                        path == prefix || path == prefix.dropLast(1) -> {
                            val body = page().toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        path.startsWith(prefix) -> {
                            val file = recordings.file(java.net.URLDecoder.decode(path.removePrefix(prefix), "UTF-8"))
                            if (file == null) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${file.length()}\r\n" +
                                        "Content-Disposition: attachment; filename=\"${file.name}\"\r\nConnection: close\r\n\r\n").toByteArray(),
                                )
                                file.inputStream().use { input -> input.copyTo(out) }
                            }
                        }
                        else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.flush()
                } catch (e: java.io.IOException) {
                    // Téléphone parti en cours de route.
                }
            }
        }

        private fun page(): String {
            val rows = recordings.files.value.joinToString("") { file ->
                val link = URLEncoder.encode(file.name, "UTF-8")
                "<li><a href=\"$link\">${file.name}</a> (${String.format(Locale.FRANCE, "%.1f", file.bytes / 1_000_000.0)} Mo)</li>"
            }
            return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>NixTV : enregistrements</title><style>body{font-family:sans-serif;margin:24px;font-size:18px}li{margin:14px 0}</style></head>" +
                "<body><h1>Enregistrements pour l’analyse des pubs</h1>" +
                (if (rows.isEmpty()) "<p>Aucun enregistrement.</p>" else "<p>Touchez un fichier pour le télécharger.</p><ul>$rows</ul>") +
                "</body></html>"
        }

        override fun close() {
            try {
                socket.close()
            } catch (e: java.io.IOException) {
                // Déjà fermé.
            }
        }
    }

    private companion object {
        const val MAX_FILES = 6
        const val MAX_MILLIS = 20 * 60_000L
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
        const val PORT = 8090
        const val CODE_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789"

        fun safe(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(30).ifEmpty { "chaine" }

        /** Adresse de l'appareil sur le réseau de la maison (Wi-Fi ou câble). */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: java.net.SocketException) {
            null
        }
    }
}
