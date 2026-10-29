package tv.nextv.core.m3u

/**
 * Normalisation des en-têtes HTTP déclarés dans une playlist.
 *
 * Les valeurs proviennent d'un fichier tiers : on écarte tout ce qu'OkHttp refuserait
 * (caractères de contrôle, non-ASCII) et les en-têtes qui casseraient la requête.
 */
object HttpHeaderValues {

    private val TOKEN = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")
    private val FORBIDDEN = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer")

    private val CANONICAL = mapOf(
        "user-agent" to "User-Agent",
        "referer" to "Referer",
        "referrer" to "Referer",
        "origin" to "Origin",
        "cookie" to "Cookie",
        "authorization" to "Authorization",
        "accept" to "Accept",
    )

    fun canonicalName(name: String): String {
        val trimmed = name.trim()
        return CANONICAL[trimmed.lowercase()] ?: trimmed
    }

    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in headers) {
            val name = canonicalName(rawName)
            if (!TOKEN.matches(name) || name.lowercase() in FORBIDDEN) continue
            val value = rawValue.trim()
            if (value.isEmpty() || value.any { it.code !in 0x20..0x7e && it != '\t' }) continue
            result[name] = value
        }
        return result
    }

    /**
     * En-têtes au format Kodi : `User-Agent=xxx&Referer=yyy`, valeurs encodées en URL.
     */
    fun parseKodi(spec: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in spec.split('&')) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val name = percentDecode(part.substring(0, separator))
            val value = percentDecode(part.substring(separator + 1))
            result[canonicalName(name)] = value
        }
        return result
    }

    /** Décodage `%XX` sans transformer `+` (les User-Agent en contiennent souvent). */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
