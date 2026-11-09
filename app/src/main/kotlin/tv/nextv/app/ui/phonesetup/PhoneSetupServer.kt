package tv.nextv.app.ui.phonesetup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.nextv.app.PlaylistImport
import tv.nextv.core.source.XtreamAccount
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Petite page web servie par la télé pendant que l'écran « Configurer depuis le téléphone » est ouvert :
 * le téléphone (même Wi-Fi) l'ouvre avec le QR code, saisit un compte Xtream ou un lien M3U, et la playlist
 * arrive sur la télé ([onPlaylist]), qui ouvre l'ajout prérempli. Rien ne passe par Internet.
 *
 * Seule l'adresse avec le code secret ([token], tiré au hasard) répond, et une seule playlist est acceptée.
 */
class PhoneSetupServer(private val onPlaylist: (PlaylistImport) -> Unit) {
    val token: String = randomToken()
    private var socket: ServerSocket? = null
    private var job: Job? = null

    @Volatile
    private var used = false

    /** Démarre l'écoute sur un port libre ; rend le port. */
    fun start(scope: CoroutineScope): Int {
        val server = ServerSocket(0)
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { runCatching { client.use { handle(it) } } }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MILLIS
        val request = readRequest(client.getInputStream()) ?: return
        val out = client.getOutputStream()
        fun respond(status: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.write(body)
            out.flush()
        }
        val path = request.path.trimEnd('/')
        if (path != "/$token") {
            respond("404 Not Found", PhoneSetupPage.message("Page introuvable", "Scannez de nouveau le QR code affiché sur la télé."))
            return
        }
        if (used) {
            respond("200 OK", PhoneSetupPage.message("Déjà envoyé", "La playlist est arrivée sur la télé. Pour en envoyer une autre, rouvrez l'écran du QR code."))
            return
        }
        when (request.method) {
            "GET" -> respond("200 OK", PhoneSetupPage.form(error = null))
            "POST" -> when (val result = phoneImport(parseForm(request.body))) {
                is PhoneImportResult.Ok -> {
                    used = true
                    onPlaylist(result.import)
                    respond("200 OK", PhoneSetupPage.message("C'est envoyé !", "Regardez la télé : il ne reste qu'à confirmer l'ajout de la playlist avec OK."))
                }
                is PhoneImportResult.Error -> respond("200 OK", PhoneSetupPage.form(error = result.message))
            }
            else -> respond("405 Method Not Allowed", PhoneSetupPage.message("Requête refusée", ""))
        }
    }

    private class Request(val method: String, val path: String, val body: String)

    /** Requête HTTP simple : ligne de requête, en-têtes, corps (formulaire, au plus [MAX_BODY] octets). */
    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == END_OF_HEADERS) break
            if (head.size() > MAX_HEADERS) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val length = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0], parts[1].substringBefore('?'), String(body, 0, read, Charsets.UTF_8))
    }

    companion object {
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_HEADERS = 16 * 1024
        private const val MAX_BODY = 16 * 1024
        private const val END_OF_HEADERS = 0x0D0A0D0A

        private fun randomToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** Adresse de la télé sur le réseau local (Wi-Fi ou Ethernet), `null` sans réseau. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif to it } }
                .filter { (_, address) -> address is Inet4Address && address.isSiteLocalAddress }
                // Wi-Fi ou Ethernet d'abord (pas une interface de VPN).
                .sortedBy { (nif, _) -> if (nif.name.startsWith("wlan") || nif.name.startsWith("eth")) 0 else 1 }
                .firstOrNull()?.second?.hostAddress
        }.getOrNull()
    }
}

/** Résultat du formulaire du téléphone. */
sealed interface PhoneImportResult {
    data class Ok(val import: PlaylistImport) : PhoneImportResult
    data class Error(val message: String) : PhoneImportResult
}

/** Formulaire du téléphone → playlist à ajouter : compte Xtream (serveur, identifiant, mot de passe) ou lien M3U. */
fun phoneImport(form: Map<String, String>): PhoneImportResult {
    val name = form["name"]?.trim()?.takeIf { it.isNotEmpty() }
    return when (form["mode"]) {
        "m3u" -> {
            val url = form["url"]?.trim().orEmpty()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                PhoneImportResult.Error("Le lien doit commencer par http:// ou https://")
            } else {
                PhoneImportResult.Ok(PlaylistImport(url, name, null, null))
            }
        }
        else -> {
            val server = form["server"]?.trim().orEmpty()
            val username = form["username"]?.trim().orEmpty()
            val password = form["password"].orEmpty()
            when {
                server.isEmpty() || username.isEmpty() || password.isEmpty() ->
                    PhoneImportResult.Error("Remplissez le serveur, l'identifiant et le mot de passe.")
                else -> XtreamAccount(server, username, password).playlistUrl()
                    ?.let { PhoneImportResult.Ok(PlaylistImport(it, name, null, null)) }
                    ?: PhoneImportResult.Error("Adresse du serveur invalide (exemple : http://serveur.com:8080).")
            }
        }
    }
}

/** Corps d'un formulaire HTML (`application/x-www-form-urlencoded`). */
fun parseForm(body: String): Map<String, String> = body.split('&')
    .filter { it.isNotEmpty() }
    .associate { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        decode(key) to decode(value)
    }

private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
