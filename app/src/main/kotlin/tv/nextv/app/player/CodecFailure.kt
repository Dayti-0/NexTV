package tv.nextv.app.player

import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import tv.nextv.app.R

/**
 * Échec de décodage : la piste en cause (vidéo, son, sous-titres), son format et le décodeur qui a échoué.
 * Sert à reprendre la lecture autrement (autre piste son, autre décodeur, autre copie du film) et à dire
 * précisément ce que l'appareil ne sait pas lire.
 */
@OptIn(UnstableApi::class)
data class CodecFailure(
    /** [C.TRACK_TYPE_VIDEO], [C.TRACK_TYPE_AUDIO], [C.TRACK_TYPE_TEXT] ou [C.TRACK_TYPE_UNKNOWN]. */
    val trackType: Int,
    val format: Format?,
    /** Décodeur qui a échoué (« c2.amlogic.hevc.decoder »), s'il est connu. */
    val decoderName: String?,
    /** Le décodeur n'a pas pu démarrer (le lecteur a déjà essayé les autres décodeurs). */
    val initFailed: Boolean,
) {
    companion object {
        /** Erreurs de décodage. */
        private val CODEC_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

        /** `null` : ce n'est pas une erreur de décodage. */
        fun of(error: PlaybackException): CodecFailure? {
            if (error.errorCode !in CODEC_ERRORS) return null
            val format = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
            val type = format?.let { trackTypeOf(it) } ?: C.TRACK_TYPE_UNKNOWN
            val decoder = when (val cause = error.cause) {
                is MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
                is MediaCodecDecoderException -> cause.codecInfo?.name
                else -> null
            }
            return CodecFailure(type, format, decoder, error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        }

        fun trackTypeOf(format: Format): Int {
            val mimeType = format.sampleMimeType ?: format.containerMimeType
            return when {
                MimeTypes.isVideo(mimeType) -> C.TRACK_TYPE_VIDEO
                MimeTypes.isAudio(mimeType) -> C.TRACK_TYPE_AUDIO
                MimeTypes.isText(mimeType) || MimeTypes.isImage(mimeType) -> C.TRACK_TYPE_TEXT
                else -> MimeTypes.getTrackType(mimeType)
            }
        }
    }
}

/** Nom courant d'un codec (« HEVC (H.265) », « DTS-HD »), sinon son type brut. */
@OptIn(UnstableApi::class)
fun codecName(mimeType: String?): String? = when (mimeType) {
    null -> null
    MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_VP8 -> "VP8"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_MP4V -> "MPEG-4 (DivX, Xvid)"
    MimeTypes.VIDEO_VC1 -> "VC-1"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC3)"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC3)"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC3)"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    else -> mimeType.substringAfter('/').uppercase()
}

/**
 * Description d'une piste vidéo pour le message d'erreur : « HEVC (H.265) 10 bits HDR · 3840×2160 ».
 * 10 bits : profil Main 10 annoncé par le flux, ou profondeur des couleurs.
 */
@OptIn(UnstableApi::class)
fun describeVideoFormat(format: Format): String {
    val tenBit = format.colorInfo?.lumaBitdepth?.let { it >= 10 } == true ||
        format.codecs?.let { TEN_BIT_CODECS.containsMatchIn(it) } == true
    val hdr = format.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }
    val name = listOfNotNull(codecName(format.sampleMimeType), "10 bits".takeIf { tenBit }, "HDR".takeIf { hdr }).joinToString(" ")
    val size = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null
    return listOfNotNull(name.ifEmpty { null }, size).joinToString(" · ")
}

/** HEVC Main 10 (« hvc1.2… », « hev1.2… »), H.264 High 10 (« avc1.6E… »), AV1 10 bits (« av01.0.08M.10 »). */
private val TEN_BIT_CODECS = Regex("""^(hvc1|hev1)\.2\.|^avc1\.6e|^av01\.\d\.\d+\w\.10""", RegexOption.IGNORE_CASE)

/** Message d'un échec de décodage dont on connaît la piste : ce que l'appareil ne sait pas lire. */
fun Resources.describeCodecFailure(failure: CodecFailure): String? {
    val format = failure.format ?: return null
    return when (failure.trackType) {
        C.TRACK_TYPE_VIDEO -> getString(R.string.player_error_video_codec, describeVideoFormat(format))
        C.TRACK_TYPE_AUDIO -> codecName(format.sampleMimeType)?.let { getString(R.string.player_error_audio_codec, it) }
        else -> null
    }
}
