package tv.nextv.core.io

/** Nature probable d'un contenu, déduite de ses premiers octets. */
enum class ContentKind {
    EMPTY,
    TEXT,
    HTML,
    XML,
    JSON,

    /** Flux vidéo binaire (MPEG-TS, MP4…) : l'URL désigne un flux, pas une liste. */
    MEDIA,
    BINARY,
}

/**
 * Identification sommaire du contenu reçu, pour ne jamais supposer qu'une URL renvoie
 * bien ce qu'on attend : page HTML d'un portail captif, réponse JSON d'une API, flux vidéo…
 */
object ContentSniffer {

    const val SNIFF_SIZE = 4096

    private const val TS_PACKET_SIZE = 188
    private const val TS_SYNC_BYTE = 0x47

    fun sniff(head: ByteArray, charset: DetectedCharset): ContentKind {
        if (head.size <= charset.bomLength) return ContentKind.EMPTY
        if (isMpegTs(head) || isMp4(head)) return ContentKind.MEDIA
        if (charset.bomLength == 0 && looksBinary(head)) return ContentKind.BINARY

        val text = decode(head, charset).trimStart()
        if (text.isEmpty()) return ContentKind.EMPTY
        val lower = text.take(1024).lowercase()
        return when {
            lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
                (lower.startsWith("<") && ("<html" in lower || "<body" in lower)) -> ContentKind.HTML
            lower.startsWith("<") -> ContentKind.XML
            lower.startsWith("{") || lower.startsWith("[{") || lower.startsWith("[]") -> ContentKind.JSON
            else -> ContentKind.TEXT
        }
    }

    /** Types MIME qui désignent sans ambiguïté un flux audio/vidéo. */
    fun isMediaContentType(contentType: String?): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (mime in PLAYLIST_MIME_TYPES) return false
        return mime.startsWith("video/") || mime.startsWith("audio/") || mime in STREAM_MANIFEST_MIME_TYPES
    }

    private val PLAYLIST_MIME_TYPES = setOf(
        "audio/x-mpegurl",
        "audio/mpegurl",
        "audio/x-scpls",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
    )

    private val STREAM_MANIFEST_MIME_TYPES = setOf(
        "application/dash+xml",
        "application/vnd.ms-sstr+xml",
    )

    private fun isMpegTs(head: ByteArray): Boolean =
        head.size > TS_PACKET_SIZE * 2 &&
            (head[0].toInt() and 0xff) == TS_SYNC_BYTE &&
            (head[TS_PACKET_SIZE].toInt() and 0xff) == TS_SYNC_BYTE &&
            (head[TS_PACKET_SIZE * 2].toInt() and 0xff) == TS_SYNC_BYTE

    private fun isMp4(head: ByteArray): Boolean =
        head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    private fun looksBinary(head: ByteArray): Boolean {
        var control = 0
        for (byte in head) {
            val b = byte.toInt() and 0xff
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) control++
        }
        return control > head.size / 10
    }

    private fun decode(head: ByteArray, charset: DetectedCharset): String =
        String(head, charset.bomLength, head.size - charset.bomLength, charset.charset)
}
