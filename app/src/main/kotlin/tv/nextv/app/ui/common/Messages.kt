package tv.nextv.app.ui.common

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import tv.nextv.app.R
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.net.ProxyProblem

/** Message destiné à l'utilisateur pour une erreur de source. */
fun Resources.describe(error: SourceError): String = when (error) {
    is SourceError.InvalidUrl -> getString(
        when (error.reason) {
            InvalidUrlReason.EMPTY -> R.string.error_url_empty
            InvalidUrlReason.MISSING_SCHEME -> R.string.error_url_missing_scheme
            InvalidUrlReason.MISSING_HOST, InvalidUrlReason.MALFORMED -> R.string.error_url_malformed
            InvalidUrlReason.INVALID_CHARACTERS -> R.string.error_invalid_characters
        },
    )
    is SourceError.UnsupportedScheme -> getString(R.string.error_unsupported_scheme, error.scheme)
    is SourceError.UnsupportedSourceType -> getString(R.string.error_unsupported_source_type)
    SourceError.Timeout -> getString(R.string.error_timeout)
    is SourceError.UnknownHost -> getString(R.string.error_unknown_host, error.host ?: "?")
    SourceError.ConnectionFailed -> getString(R.string.error_connection_failed)
    SourceError.TlsFailure -> getString(R.string.error_tls)
    SourceError.TooManyRedirects -> getString(R.string.error_too_many_redirects)
    is SourceError.UnsupportedRedirect -> getString(R.string.error_unsupported_redirect)
    SourceError.CleartextNotPermitted -> getString(R.string.error_cleartext)
    is SourceError.HttpStatus -> when (error.status) {
        403 -> getString(R.string.error_http_forbidden)
        404, 410 -> getString(R.string.error_http_not_found)
        in 500..599 -> getString(R.string.error_http_server, error.status)
        else -> getString(R.string.error_http, error.status)
    }
    SourceError.AuthenticationRequired -> getString(R.string.error_auth_required)
    SourceError.AuthenticationFailed -> getString(R.string.error_auth_failed)
    is SourceError.UnsupportedAuthScheme -> getString(R.string.error_auth_scheme, error.scheme)
    SourceError.ProxyUnreachable -> getString(R.string.error_proxy_unreachable)
    SourceError.ProxyAuthRequired -> getString(R.string.error_proxy_auth_required)
    is SourceError.ProxyFailure -> getString(R.string.error_proxy_failure)
    is SourceError.Network -> getString(R.string.error_network)
    SourceError.FileNotFound -> getString(R.string.error_file_not_found)
    SourceError.PermissionDenied -> getString(R.string.error_permission_denied)
    SourceError.EmptyContent -> getString(R.string.error_empty)
    SourceError.HtmlInsteadOfContent -> getString(R.string.error_html)
    SourceError.NotAPlaylist -> getString(R.string.error_not_a_playlist)
    SourceError.NoChannels -> getString(R.string.error_no_channels)
    SourceError.InvalidXmltv -> getString(R.string.error_invalid_xmltv)
    is SourceError.TooLarge -> getString(R.string.error_too_large, error.limitBytes / (1024 * 1024))
    is SourceError.Unknown -> getString(R.string.error_unknown)
}

fun Resources.describe(issue: StreamIssue): String = getString(
    when (issue) {
        StreamIssue.UNSUPPORTED_SCHEME -> R.string.stream_issue_scheme
        StreamIssue.UNRESOLVED_RELATIVE_URL -> R.string.stream_issue_relative
        StreamIssue.MALFORMED_URL -> R.string.stream_issue_malformed
        StreamIssue.DRM_PROTECTED -> R.string.stream_issue_drm
        StreamIssue.PROXY_NOT_APPLICABLE -> R.string.stream_issue_no_proxy
        StreamIssue.MULTICAST_NETWORK_REQUIRED -> R.string.stream_issue_multicast
    },
)

fun Resources.describe(problem: ProxyProblem): String = getString(
    when (problem) {
        ProxyProblem.MISSING_HOST -> R.string.proxy_problem_missing_host
        ProxyProblem.INVALID_HOST -> R.string.proxy_problem_invalid_host
        ProxyProblem.INVALID_PORT -> R.string.proxy_problem_invalid_port
        ProxyProblem.SOCKS_AUTH_UNSUPPORTED -> R.string.proxy_problem_socks_auth
        ProxyProblem.INVALID_USERNAME -> R.string.proxy_problem_username
        ProxyProblem.NO_SCOPE -> R.string.proxy_problem_no_scope
    },
)

/** Description d'un emplacement sans identifiant visible. */
fun Resources.describe(location: SourceLocation, redactor: Redactor): String = when (location) {
    is SourceLocation.Remote -> redactor.redact(location.url)
    is SourceLocation.LocalDocument -> location.displayName ?: getString(R.string.location_local_file)
}

fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** « 1 chaîne », « 42 chaînes ». */
@Composable
fun channelCount(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.channels_count, count, count)
