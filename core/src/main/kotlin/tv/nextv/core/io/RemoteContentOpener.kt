package tv.nextv.core.io

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import tv.nextv.core.error.SourceException
import tv.nextv.core.log.Logger
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.AuthScope
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkErrorMapper
import tv.nextv.core.net.RemoteUrlValidator
import tv.nextv.core.net.await
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Ouvre une ressource HTTP(S) via le client adapté à [OpenRequest.purpose].
 *
 * Sans User-Agent choisi pour la source, un refus (HTTP 403, ou connexion fermée sans réponse) est
 * retenté avec ceux de lecteurs courants : beaucoup de serveurs IPTV n'acceptent que les
 * applications qu'ils connaissent.
 */
class RemoteContentOpener(
    private val clients: HttpClientProvider,
    private val logger: Logger = Logger.None,
) : ContentOpener {

    override fun supports(location: SourceLocation) = location is SourceLocation.Remote

    override suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent {
        val remote = location as SourceLocation.Remote
        val validated = when (val result = RemoteUrlValidator.validate(remote.url)) {
            is RemoteUrlValidator.Result.Invalid -> throw SourceException(result.error)
            is RemoteUrlValidator.Result.Valid -> result
        }
        val url = validated.url
        // Identifiants intégrés à l'URL (ex. guide annoncé par `url-tvg`) faute d'identifiants configurés.
        val credentials = request.credentials ?: validated.embeddedCredentials
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
        if (credentials != null) {
            val origin = request.credentialsOrigin?.toHttpUrlOrNull() ?: url
            builder.tag(AuthScope::class.java, AuthScope.forUrl(origin, credentials))
        }
        val custom = request.userAgent?.takeIf { it.isNotBlank() }
        val userAgents = if (custom != null) listOf(custom) else listOf(clients.networkSettings.defaultUserAgent) + FALLBACK_USER_AGENTS

        val viaProxy = clients.usesProxy(request.purpose)
        val started = System.nanoTime()
        var attempt = 0
        var response: Response
        while (true) {
            val canRetry = attempt < userAgents.lastIndex
            builder.header("User-Agent", userAgents[attempt])
            val result = try {
                clients.client(request.purpose).newCall(builder.build()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (canRetry && closedWithoutResponse(e)) {
                    attempt++
                    logger.info(TAG, "Connexion fermée sans réponse pour ${request.purpose} $url : nouvel essai en tant que lecteur courant ($attempt)")
                    continue
                }
                val error = NetworkErrorMapper.map(e, url.host, viaProxy)
                logger.warn(TAG, "Échec ${request.purpose} $url : ${error.code}", e)
                throw SourceException(error, e)
            }
            if (result.code == 403 && canRetry) {
                result.close()
                attempt++
                logger.info(TAG, "HTTP 403 pour ${request.purpose} $url : nouvel essai en tant que lecteur courant ($attempt)")
                continue
            }
            response = result
            break
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (!response.isSuccessful) {
            val error = NetworkErrorMapper.map(response, credentialsProvided = credentials != null)
            logger.warn(TAG, "HTTP ${response.code} pour ${request.purpose} $url (${elapsedMs} ms) : ${error.code}")
            response.close()
            throw SourceException(error)
        }

        val finalUrl = response.request.url
        if (finalUrl != url) logger.debug(TAG, "Redirigé vers $finalUrl")
        logger.info(TAG, "HTTP ${response.code} pour ${request.purpose} $finalUrl (${elapsedMs} ms)")

        val body = response.body
        return OpenedContent(
            stream = body.byteStream(),
            effectiveUrl = finalUrl.toString(),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength().takeIf { it >= 0 },
            displayName = finalUrl.pathSegments.lastOrNull { it.isNotEmpty() },
            userAgent = userAgents[attempt].takeIf { attempt > 0 },
            onClose = { response.close() },
        )
    }

    /** Le serveur a accepté la connexion puis l'a fermée sans répondre (refus silencieux). */
    private fun closedWithoutResponse(error: IOException): Boolean = when (error) {
        is SocketTimeoutException, is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> false
        is SocketException, is EOFException -> true
        else -> error.cause is EOFException || error.message.orEmpty().contains("unexpected end of stream", ignoreCase = true)
    }

    companion object {
        private const val TAG = "RemoteContentOpener"

        /** Essayés dans l'ordre après un refus : lecteur VLC, puis navigateur. */
        val FALLBACK_USER_AGENTS = listOf(
            "VLC/3.0.20 LibVLC/3.0.20",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        )
    }
}
