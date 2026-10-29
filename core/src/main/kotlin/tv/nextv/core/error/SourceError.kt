package tv.nextv.core.error

/**
 * Erreur typée rencontrée lors de la validation, du téléchargement ou de l'analyse d'une source.
 *
 * Les erreurs sont persistées sous la forme ([code], [detail]) via [encode] / [decode] ;
 * le détail ne contient jamais d'URL complète ni d'identifiant.
 */
sealed class SourceError(val code: String) {

    open val detail: String? get() = null

    // --- Saisie / configuration -------------------------------------------------------------

    data class InvalidUrl(val reason: InvalidUrlReason) : SourceError("invalid_url") {
        override val detail get() = reason.name
    }

    data class UnsupportedScheme(val scheme: String) : SourceError("unsupported_scheme") {
        override val detail get() = scheme
    }

    data class UnsupportedSourceType(val type: String) : SourceError("unsupported_source_type") {
        override val detail get() = type
    }

    // --- Réseau ----------------------------------------------------------------------------

    data object Timeout : SourceError("timeout")

    data class UnknownHost(val host: String?) : SourceError("unknown_host") {
        override val detail get() = host
    }

    data object ConnectionFailed : SourceError("connection_failed")

    data object TlsFailure : SourceError("tls_failure")

    data object TooManyRedirects : SourceError("too_many_redirects")

    /** Redirection vers un schéma qui n'est pas suivi (ex. `rtsp://`, `ftp://`). */
    data class UnsupportedRedirect(val status: Int) : SourceError("unsupported_redirect") {
        override val detail get() = status.toString()
    }

    data object CleartextNotPermitted : SourceError("cleartext_not_permitted")

    data class HttpStatus(val status: Int) : SourceError("http_status") {
        override val detail get() = status.toString()
    }

    /** Le serveur exige une authentification et aucun identifiant n'est configuré. */
    data object AuthenticationRequired : SourceError("auth_required")

    /** Les identifiants ont été envoyés mais refusés. */
    data object AuthenticationFailed : SourceError("auth_failed")

    /** Le serveur ne propose qu'un schéma d'authentification non pris en charge (Digest, NTLM…). */
    data class UnsupportedAuthScheme(val scheme: String) : SourceError("auth_scheme_unsupported") {
        override val detail get() = scheme
    }

    data object ProxyUnreachable : SourceError("proxy_unreachable")

    data object ProxyAuthRequired : SourceError("proxy_auth_required")

    data class ProxyFailure(val status: Int?) : SourceError("proxy_failure") {
        override val detail get() = status?.toString()
    }

    /** Erreur d'entrée/sortie réseau non classée. [type] est le nom de la classe d'exception. */
    data class Network(val type: String?) : SourceError("network") {
        override val detail get() = type
    }

    // --- Fichiers locaux -------------------------------------------------------------------

    data object FileNotFound : SourceError("file_not_found")

    /** L'autorisation d'accès au document a été perdue (fichier déplacé, permission révoquée…). */
    data object PermissionDenied : SourceError("permission_denied")

    // --- Contenu ---------------------------------------------------------------------------

    data object EmptyContent : SourceError("empty_content")

    /** Le serveur a renvoyé une page HTML (portail captif, abonnement expiré, page d'erreur…). */
    data object HtmlInsteadOfContent : SourceError("html_content")

    /** Le contenu n'est pas une playlist M3U exploitable. */
    data object NotAPlaylist : SourceError("not_a_playlist")

    /** La playlist est lisible mais ne contient aucune entrée. */
    data object NoChannels : SourceError("no_channels")

    /** Le contenu n'est pas un guide XMLTV valide. */
    data object InvalidXmltv : SourceError("invalid_xmltv")

    data class TooLarge(val limitBytes: Long) : SourceError("too_large") {
        override val detail get() = limitBytes.toString()
    }

    data class Unknown(val type: String?) : SourceError("unknown") {
        override val detail get() = type
    }

    fun encode(): Pair<String, String?> = code to detail

    companion object {
        fun decode(code: String, detail: String?): SourceError = when (code) {
            "invalid_url" -> InvalidUrl(
                InvalidUrlReason.entries.firstOrNull { it.name == detail } ?: InvalidUrlReason.MALFORMED,
            )
            "unsupported_scheme" -> UnsupportedScheme(detail.orEmpty())
            "unsupported_source_type" -> UnsupportedSourceType(detail.orEmpty())
            "timeout" -> Timeout
            "unknown_host" -> UnknownHost(detail)
            "connection_failed" -> ConnectionFailed
            "tls_failure" -> TlsFailure
            "too_many_redirects" -> TooManyRedirects
            "unsupported_redirect" -> UnsupportedRedirect(detail?.toIntOrNull() ?: 0)
            "cleartext_not_permitted" -> CleartextNotPermitted
            "http_status" -> HttpStatus(detail?.toIntOrNull() ?: 0)
            "auth_required" -> AuthenticationRequired
            "auth_failed" -> AuthenticationFailed
            "auth_scheme_unsupported" -> UnsupportedAuthScheme(detail.orEmpty())
            "proxy_unreachable" -> ProxyUnreachable
            "proxy_auth_required" -> ProxyAuthRequired
            "proxy_failure" -> ProxyFailure(detail?.toIntOrNull())
            "network" -> Network(detail)
            "file_not_found" -> FileNotFound
            "permission_denied" -> PermissionDenied
            "empty_content" -> EmptyContent
            "html_content" -> HtmlInsteadOfContent
            "not_a_playlist" -> NotAPlaylist
            "no_channels" -> NoChannels
            "invalid_xmltv" -> InvalidXmltv
            "too_large" -> TooLarge(detail?.toLongOrNull() ?: 0)
            else -> Unknown(detail)
        }
    }
}

enum class InvalidUrlReason {
    EMPTY,
    MISSING_SCHEME,
    MISSING_HOST,
    MALFORMED,
    INVALID_CHARACTERS,
}

/** Exception porteuse d'une [SourceError]. Son message ne contient aucune donnée sensible. */
class SourceException(
    val error: SourceError,
    cause: Throwable? = null,
) : Exception("Source error: ${error.code}${error.detail?.let { " ($it)" }.orEmpty()}", cause)
