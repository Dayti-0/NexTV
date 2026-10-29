package tv.nextv.app.player

import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import tv.nextv.app.R

/** Message lisible pour une erreur de lecture Media3. */
@OptIn(UnstableApi::class)
fun Resources.describePlaybackError(error: PlaybackException, viaProxy: Boolean): String {
    val proxyHint = if (viaProxy) "\n" + getString(R.string.player_error_proxy_hint) else ""
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            getString(R.string.player_error_connection) + proxyHint
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            getString(R.string.player_error_timeout) + proxyHint
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            when (status) {
                401 -> getString(R.string.player_error_auth)
                403 -> getString(R.string.player_error_forbidden)
                404, 410 -> getString(R.string.player_error_not_found)
                407 -> getString(R.string.error_proxy_auth_required)
                null -> getString(R.string.player_error_http_unknown)
                else -> getString(R.string.player_error_http, status)
            }
        }
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> getString(R.string.player_error_content_type)
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> getString(R.string.error_cleartext)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> getString(R.string.player_error_not_found)
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> getString(R.string.player_error_forbidden)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> getString(R.string.player_error_format_unsupported)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> getString(R.string.player_error_malformed)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> getString(R.string.player_error_codec)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> getString(R.string.player_error_audio)
        in DRM_ERROR_CODES -> getString(R.string.stream_issue_drm)
        else -> getString(R.string.player_error_generic, error.errorCodeName)
    }
}

private val DRM_ERROR_CODES = 6000..6999
