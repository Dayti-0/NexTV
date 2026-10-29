package tv.nextv.core.net

import okhttp3.Response
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Traduction des exceptions et réponses HTTP en [SourceError]. */
object NetworkErrorMapper {

    /**
     * @param host hôte visé, pour le message « hôte introuvable ».
     * @param viaProxy vrai si la requête passait par le proxy configuré.
     */
    fun map(error: Throwable, host: String? = null, viaProxy: Boolean = false): SourceError {
        if (error is SourceException) return error.error
        val message = error.message.orEmpty()
        return when (error) {
            is TooManyRedirectsException -> SourceError.TooManyRedirects
            is SocketTimeoutException -> SourceError.Timeout
            is UnknownHostException -> if (viaProxy) SourceError.ProxyUnreachable else SourceError.UnknownHost(host)
            is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                if (viaProxy) SourceError.ProxyUnreachable else SourceError.ConnectionFailed
            is SSLException -> SourceError.TlsFailure
            is UnknownServiceException ->
                if (message.contains("CLEARTEXT", ignoreCase = true)) SourceError.CleartextNotPermitted
                else SourceError.Network(error.javaClass.simpleName)
            is ProtocolException ->
                if (message.contains("follow-up", ignoreCase = true)) SourceError.TooManyRedirects
                else SourceError.Network(error.javaClass.simpleName)
            is FileNotFoundException -> SourceError.FileNotFound
            is SecurityException -> SourceError.PermissionDenied
            is InterruptedIOException ->
                if (message.contains("timeout", ignoreCase = true)) SourceError.Timeout
                else SourceError.Network(error.javaClass.simpleName)
            is IOException -> mapGenericIo(error, message)
            else -> {
                val cause = error.cause
                if (cause is CertificateException) SourceError.TlsFailure else SourceError.Unknown(error.javaClass.simpleName)
            }
        }
    }

    private fun mapGenericIo(error: IOException, message: String): SourceError = when {
        // Messages produits par OkHttp lors de l'ouverture d'un tunnel CONNECT.
        message.contains("Failed to authenticate with proxy", ignoreCase = true) -> SourceError.ProxyAuthRequired
        message.contains("Unexpected response code for CONNECT", ignoreCase = true) ->
            SourceError.ProxyFailure(message.substringAfterLast(':').trim().toIntOrNull())
        error.cause is CertificateException -> SourceError.TlsFailure
        else -> SourceError.Network(error.javaClass.simpleName)
    }

    /**
     * Erreur correspondant à une réponse HTTP non réussie.
     *
     * @param credentialsProvided vrai si des identifiants étaient configurés pour cette requête.
     */
    fun map(response: Response, credentialsProvided: Boolean): SourceError = when (response.code) {
        401 -> when {
            response.request.header(ScopedCredentialsAuthenticator.HEADER_AUTHORIZATION) != null ->
                SourceError.AuthenticationFailed
            !credentialsProvided -> SourceError.AuthenticationRequired
            else -> {
                val schemes = response.challenges().map { it.scheme }
                if (schemes.none { it.equals("Basic", ignoreCase = true) } && schemes.isNotEmpty()) {
                    SourceError.UnsupportedAuthScheme(schemes.first())
                } else {
                    SourceError.AuthenticationFailed
                }
            }
        }
        407 -> SourceError.ProxyAuthRequired
        in 300..399 -> SourceError.UnsupportedRedirect(response.code)
        else -> SourceError.HttpStatus(response.code)
    }
}
