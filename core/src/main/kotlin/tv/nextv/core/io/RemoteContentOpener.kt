package tv.nextv.core.io

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tv.nextv.core.error.SourceException
import tv.nextv.core.log.Logger
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.net.AuthScope
import tv.nextv.core.net.HttpClientProvider
import tv.nextv.core.net.NetworkErrorMapper
import tv.nextv.core.net.RemoteUrlValidator
import tv.nextv.core.net.await
import java.io.IOException

/** Ouvre une ressource HTTP(S) via le client adapté à [OpenRequest.purpose]. */
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
            .header("User-Agent", request.userAgent?.takeIf { it.isNotBlank() } ?: clients.networkSettings.defaultUserAgent)
            .header("Accept", "*/*")
        if (credentials != null) {
            val origin = request.credentialsOrigin?.toHttpUrlOrNull() ?: url
            builder.tag(AuthScope::class.java, AuthScope.forUrl(origin, credentials))
        }

        val viaProxy = clients.usesProxy(request.purpose)
        val started = System.nanoTime()
        val response = try {
            clients.client(request.purpose).newCall(builder.build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val error = NetworkErrorMapper.map(e, url.host, viaProxy)
            logger.warn(TAG, "Échec ${request.purpose} $url : ${error.code}", e)
            throw SourceException(error, e)
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
            onClose = { response.close() },
        )
    }

    private companion object {
        const val TAG = "RemoteContentOpener"
    }
}
