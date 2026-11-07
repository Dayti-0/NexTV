package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** Décodeur vidéo proposé par le système. */
data class DecoderOption(val name: String, val hardware: Boolean)

/** Décodeurs vidéo de l'appareil, et choix de l'utilisateur appliqué au lecteur. */
@OptIn(UnstableApi::class)
object VideoDecoders {

    const val HEVC = "video/hevc"
    const val AVC = "video/avc"

    /** Codecs dont le décodeur peut être choisi dans les réglages (4K : HEVC ; la plupart des chaînes : H.264). */
    val CHOOSABLE: List<String> = listOf(HEVC, AVC)

    /** Décodeurs disponibles pour [mimeType], dans l'ordre de préférence du système. */
    fun list(mimeType: String): List<DecoderOption> = try {
        MediaCodecUtil.getDecoderInfos(mimeType, false, false).map { DecoderOption(it.name, it.hardwareAccelerated) }
    } catch (e: MediaCodecUtil.DecoderQueryException) {
        emptyList()
    }

    /** Vrai : décodeur matériel ; faux : logiciel ; `null` : inconnu. */
    fun isHardware(mimeType: String?, decoderName: String): Boolean? =
        mimeType?.let { list(it) }?.firstOrNull { it.name == decoderName }?.hardware

    /** Place le décodeur choisi en tête : le lecteur l'essaie d'abord, puis les autres en repli. */
    fun selector(preferred: Map<String, String>): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            val name = preferred[mimeType] ?: return@MediaCodecSelector infos
            val (chosen, others) = infos.partition { it.name == name }
            chosen + others
        }
}
