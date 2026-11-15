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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Enregistrements pour l'analyse des pubs ([AdRecorder]) : lancés et arrêtés depuis les options du lecteur, gardés
 * dans la mémoire de l'appareil (les [MAX_FILES] derniers) ou sur une clé USB ([Location], réglage
 * « Emplacement des enregistrements »), un dossier chacun, puis récupérés sur un téléphone ou un ordinateur du même
 * réseau par une adresse que Réglages affiche ([share]) : chaque dossier y est donné en `.zip`, fabriqué à la volée
 * (aucune copie, même pour des heures d'enregistrement).
 *
 * @param internal dossier dans la mémoire de l'appareil.
 * @param removable emplacements amovibles branchés en ce moment (clés USB, cartes), relus à chaque appel.
 */
class AdRecordings(
    private val internal: File,
    private val version: String,
    private val removable: () -> List<Location> = { emptyList() },
) {

    /** Enregistrement terminé ([name] : celui du `.zip` téléchargé), et l'emplacement où il est gardé. */
    data class Recording(val name: String, val bytes: Long, val location: String? = null)

    /**
     * Emplacement possible des enregistrements.
     * @property key identifiant gardé dans les réglages ; `null` : la mémoire de l'appareil.
     * @property label nom donné par le système (« Clé USB SanDisk ») ; `null` : la mémoire de l'appareil.
     * @property directory dossier des enregistrements à cet emplacement.
     */
    data class Location(val key: String?, val label: String?, val directory: File, val access: Access = Access.READY) {
        val freeBytes: Long get() = (directory.parentFile ?: directory).usableSpace
    }

    /** Peut-on écrire à cet emplacement ? */
    enum class Access {
        READY,

        /** Clé branchée, mais il faut l'autorisation « Accès à tous les fichiers » (Android 11 et plus). */
        NEEDS_PERMISSION,

        /** Clé branchée, mais le système n'y laisse pas écrire (lecture seule, format non pris en charge…). */
        READ_ONLY,

        /** Clé vue par le système, mais pas prête (en cours de vérification, à formater…). */
        NOT_MOUNTED,
    }

    /** Emplacement choisi dans les réglages (`null` : mémoire de l'appareil), tenu à jour par l'application. */
    @Volatile
    var selected: String? = null

    /** Mémoire de l'appareil, puis les clés USB branchées. */
    fun locations(): List<Location> = listOf(Location(null, null, internal)) + removable()

    /** Emplacement choisi, s'il est disponible (une clé USB choisie mais débranchée : `null`). */
    fun target(): Location? = locations().firstOrNull { it.key == selected }

    private fun directories(): List<File> = locations().filter { it.access == Access.READY }.map { it.directory }

    init {
        // Enregistrement interrompu (application fermée par le système) : ce qui a été écrit est gardé.
        for (directory in directories()) {
            directory.listFiles { file -> file.isDirectory && file.name.endsWith(PART) }?.forEach { part ->
                part.renameTo(File(directory, part.name.removeSuffix(PART)))
            }
        }
    }

    private val mutableFiles = MutableStateFlow(list())

    /** Enregistrements gardés, le plus récent en premier. */
    val files: StateFlow<List<Recording>> = mutableFiles.asStateFlow()

    /** Relit les enregistrements (clé USB branchée ou retirée depuis). */
    fun refresh() {
        mutableFiles.value = list()
    }

    /** Enregistrement en cours (un seul à la fois). */
    @Volatile
    var current: AdRecorder? = null
        private set

    /** Pourquoi le dernier enregistrement n'a pas pu commencer : « usb » (clé débranchée) ou « place ». */
    @Volatile
    var refusal: String? = null
        private set

    /**
     * Commence un enregistrement, à partir de la chaîne [channel], branché sur [control] (son, images et décisions de la
     * détection des pubs), à l'emplacement choisi. Il dure jusqu'à [stop], même si l'on change de chaîne.
     * L'enregistrement en cours, s'il y en a un, est d'abord arrêté. `null` s'il ne peut pas commencer ([refusal]).
     * @param onStopped appelé quand l'enregistrement s'arrête, avec la raison s'il s'est arrêté tout seul (place).
     */
    fun start(channel: String, control: AdVolumeControl, onStopped: (reason: String?) -> Unit = {}): AdRecorder? {
        stop()
        val location = target()
        refusal = when {
            location == null -> "usb"
            location.access == Access.NEEDS_PERMISSION -> "autorisation"
            location.access != Access.READY -> "usb_illisible"
            location.freeBytes <= MIN_FREE_BYTES -> "place"
            else -> null
        }
        if (location == null || refusal != null) return null
        val directory = location.directory
        // À la seconde près : un fichier coupé au bout d'une heure et le suivant ne portent jamais le même nom.
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(Date())
        val name = "pubs_${safe(channel)}_$stamp"
        val info = linkedMapOf(
            "format" to "2",
            "chaine" to channel,
            "debut" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()),
            "appli" to version,
            "appareil" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        )
        val part = File(directory, name + PART)
        val recorder = AdRecorder(part, info) { reason ->
            val done = File(directory, name)
            if (!part.renameTo(done)) part.deleteRecursively()
            // Moins d'une minute de son : rien d'utile.
            if (File(done, "audio.ulaw").length() < MIN_AUDIO_BYTES) done.deleteRecursively()
            // Mémoire de l'appareil : les derniers seulement. Sur une clé USB, tout est gardé (jusqu'à ce qu'elle soit pleine).
            if (location.key == null) trim(directory)
            mutableFiles.value = list()
            onStopped(reason)
        }
        current = recorder
        control.record(recorder, channel)
        return recorder
    }

    /** Arrête l'enregistrement en cours (il apparaît dans la liste une seconde après). */
    fun stop() {
        current?.finish()
        current = null
    }

    /** Supprime tous les enregistrements gardés, à tous les emplacements branchés (pas celui en cours). */
    fun deleteAll() {
        for (directory in directories()) {
            directory.listFiles { file -> file.isDirectory && !file.name.endsWith(PART) }?.forEach { it.deleteRecursively() }
        }
        mutableFiles.value = list()
    }

    /** Dossier de l'enregistrement téléchargé sous le nom [zipName], quel que soit son emplacement. */
    fun folder(zipName: String): File? = directories().firstNotNullOfOrNull { directory ->
        File(directory, zipName.removeSuffix(".zip")).takeIf { it.isDirectory && it.parentFile == directory && !it.name.endsWith(PART) }
    }

    private fun list(): List<Recording> = locations().filter { it.access == Access.READY }.flatMap { location ->
        location.directory.listFiles { file -> file.isDirectory && !file.name.endsWith(PART) }.orEmpty().map { folder ->
            folder to Recording(folder.name + ".zip", folder.listFiles()?.sumOf { it.length() } ?: 0L, location.label)
        }
    }.sortedByDescending { it.first.lastModified() }.map { it.second }

    private fun trim(directory: File) {
        val folders = directory.listFiles { file -> file.isDirectory && !file.name.endsWith(PART) } ?: return
        folders.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.deleteRecursively() }
    }

    /**
     * Partage les enregistrements sur le réseau de la maison tant que le résultat n'est pas fermé : un petit serveur
     * web, à une adresse qui contient un code tiré au hasard (les autres appareils du réseau ne la devinent pas).
     * `null` sans réseau local.
     */
    fun share(host: String? = localAddress(), music: AdMusicLibrary? = null): Share? {
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
        return Share(this, socket, code, "http://$host:${socket.localPort}/$code/", music)
    }

    /**
     * Partage en cours ([address] à ouvrir sur le téléphone) ; [close] l'arrête. Avec [music], la page permet aussi
     * d'envoyer des morceaux de musique du téléphone vers l'appareil (musique jouée pendant les pubs).
     */
    class Share internal constructor(
        private val recordings: AdRecordings,
        private val socket: ServerSocket,
        private val code: String,
        val address: String,
        private val music: AdMusicLibrary? = null,
    ) : AutoCloseable {

        /** Morceaux reçus depuis l'ouverture du partage. */
        @Volatile
        var received: Int = 0
            private set

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
                    val input = client.getInputStream()
                    // Requête lue à la main (une ligne par en-tête) : le corps d'un envoi suit, octet pour octet.
                    val request = line(input) ?: return
                    val headers = HashMap<String, String>()
                    while (true) {
                        val header = line(input) ?: return
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':', "").trim()
                    }
                    val method = request.substringBefore(' ')
                    val path = request.split(' ').getOrNull(1).orEmpty()
                    val out = client.getOutputStream()
                    val prefix = "/$code/"
                    when {
                        method == "PUT" && path.startsWith(prefix + MUSIC_PATH) && music != null -> {
                            val name = java.net.URLDecoder.decode(path.removePrefix(prefix + MUSIC_PATH), "UTF-8")
                            val length = headers["content-length"]?.toLongOrNull() ?: -1L
                            val target = music.target(name)
                            if (target == null || !music.hasRoom(length)) {
                                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                // Reçu dans un fichier à part, renommé une fois complet (envoi coupé : rien de gardé).
                                val partial = java.io.File(target.parentFile, ".${target.name}.part")
                                var left = length
                                partial.outputStream().use { file ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (left > 0) {
                                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                        if (read < 0) break
                                        file.write(buffer, 0, read)
                                        left -= read
                                    }
                                }
                                if (left == 0L && partial.renameTo(target)) {
                                    received++
                                    music.refresh()
                                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                } else {
                                    partial.delete()
                                    out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                }
                            }
                        }
                        path == prefix || path == prefix.dropLast(1) -> {
                            val body = page().toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        path.startsWith(prefix) -> {
                            val name = java.net.URLDecoder.decode(path.removePrefix(prefix), "UTF-8")
                            val folder = recordings.folder(name)
                            if (folder == null) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                // Zip fabriqué à la volée (taille inconnue d'avance : la connexion se ferme à la fin).
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
                                        "Content-Disposition: attachment; filename=\"${folder.name}.zip\"\r\nConnection: close\r\n\r\n").toByteArray(),
                                )
                                val zip = ZipOutputStream(out.buffered())
                                folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                                    zip.putNextEntry(ZipEntry(file.name))
                                    file.inputStream().use { input -> input.copyTo(zip) }
                                    zip.closeEntry()
                                }
                                zip.finish()
                                zip.flush()
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

        /** Ligne d'en-tête HTTP (jusqu'au retour à la ligne), lue octet par octet ; `null` en fin de flux. */
        private fun line(input: java.io.InputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                if (b == '\n'.code) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(b)
                if (bytes.size() > MAX_HEADER) return null
            }
        }

        private fun page(): String {
            val rows = recordings.files.value.joinToString("") { file ->
                val link = URLEncoder.encode(file.name, "UTF-8")
                "<li><a href=\"$link\">${file.name}</a> (${String.format(Locale.FRANCE, "%.1f", file.bytes / 1_000_000.0)} Mo)</li>"
            }
            val musicPart = music?.let { library ->
                val tracks = library.tracks()
                "<h1>Musique pendant les pubs</h1><p>${tracks.size} morceau(x) sur l’appareil.</p>" +
                    "<p><input type=\"file\" id=\"files\" multiple accept=\"audio/*\"> <button onclick=\"send()\">Envoyer vers la télé</button></p>" +
                    "<p id=\"state\"></p>" +
                    "<script>async function send(){const f=document.getElementById('files').files;const s=document.getElementById('state');" +
                    "let ok=0;for(let i=0;i<f.length;i++){s.textContent='Envoi '+(i+1)+' sur '+f.length+' : '+f[i].name;" +
                    "try{const r=await fetch('$MUSIC_PATH'+encodeURIComponent(f[i].name),{method:'PUT',body:f[i]});if(r.ok)ok++;}catch(e){}}" +
                    "s.textContent=ok+' morceau(x) envoyé(s) sur '+f.length+'.';}</script>"
            }.orEmpty()
            return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>NixTV</title><style>body{font-family:sans-serif;margin:24px;font-size:18px}li{margin:14px 0}button,input{font-size:18px}</style></head>" +
                "<body>" + musicPart + "<h1>Enregistrements pour l’analyse des pubs</h1>" +
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
        const val MAX_FILES = 24
        const val PART = ".part"
        const val MIN_FREE_BYTES = 400L * 1024 * 1024

        /** Une minute de son enregistré (8 000 octets par seconde). */
        const val MIN_AUDIO_BYTES = 60L * 8_000
        const val PORT = 8090
        const val MUSIC_PATH = "musique/"
        const val MAX_HEADER = 8 * 1024
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
