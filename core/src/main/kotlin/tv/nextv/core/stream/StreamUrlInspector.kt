package tv.nextv.core.stream

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.m3u.HttpHeaderValues
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport

/**
 * Analyse l'URL d'un flux déclarée dans une playlist : résolution des URL relatives,
 * extraction des en-têtes au format Kodi (`url|User-Agent=…`), détection du format
 * et évaluation de la compatibilité avec le lecteur (Media3).
 *
 * Aucune requête n'est faite ici : la compatibilité réelle n'est connue qu'à la lecture.
 */
object StreamUrlInspector {

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

    /** Schémas lus via le client HTTP (proxy, en-têtes et authentification appliqués). */
    private val HTTP_SCHEMES = setOf("http", "https")

    /** Schémas lus par Media3 sans passer par le client HTTP (ni proxy, ni en-têtes). */
    private val LIMITED_SCHEMES = setOf("rtsp", "rtsps", "udp")

    /**
     * @param rawUrl URL telle qu'écrite dans la playlist.
     * @param baseUrl URL effective de la playlist (après redirections) ; `null` pour un fichier local.
     */
    fun inspect(
        rawUrl: String,
        baseUrl: String?,
        declaredHeaders: Map<String, String> = emptyMap(),
        drmProtected: Boolean = false,
    ): StreamDescriptor {
        val (urlPart, kodiHeaders) = splitKodiHeaders(rawUrl.trim())
        // Cas le plus fréquent : aucun en-tête (pas de copie ni de validation).
        val headers = if (declaredHeaders.isEmpty() && kodiHeaders.isEmpty()) emptyMap() else HttpHeaderValues.sanitize(declaredHeaders + kodiHeaders)
        val scheme = SCHEME.find(urlPart)?.groupValues?.get(1)?.lowercase()
            ?.takeUnless { urlPart.length > it.length + 1 && urlPart[it.length + 1].isDigit() && "://" !in urlPart }

        if (scheme == null) {
            // URL relative : seule une playlist distante fournit une base de résolution.
            val base = baseUrl?.toHttpUrlOrNull()
            val resolved = base?.resolve(urlPart)
                ?: return unsupported(urlPart, headers, StreamIssue.UNRESOLVED_RELATIVE_URL)
            return http(resolved, headers, drmProtected)
        }

        return when (scheme) {
            in HTTP_SCHEMES -> {
                val url = urlPart.toHttpUrlOrNull() ?: return unsupported(urlPart, headers, StreamIssue.MALFORMED_URL)
                http(url, headers, drmProtected)
            }
            in LIMITED_SCHEMES -> {
                val issues = mutableSetOf(StreamIssue.PROXY_NOT_APPLICABLE)
                if (scheme == "udp") issues += StreamIssue.MULTICAST_NETWORK_REQUIRED
                if (drmProtected) issues += StreamIssue.DRM_PROTECTED
                StreamDescriptor(
                    url = urlPart,
                    headers = headers,
                    format = if (scheme == "udp") StreamFormat.UDP else StreamFormat.RTSP,
                    support = if (drmProtected) StreamSupport.UNSUPPORTED else StreamSupport.LIMITED,
                    issues = issues,
                )
            }
            else -> unsupported(urlPart, headers, StreamIssue.UNSUPPORTED_SCHEME)
        }
    }

    /** Format probable d'après le chemin et les paramètres usuels (`output=m3u8`, `type=hls`…). */
    fun guessFormat(url: HttpUrl): StreamFormat {
        val path = url.encodedPath.lowercase()
        val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
        val hints = if (url.querySize == 0) {
            emptyList()
        } else {
            HINT_PARAMETERS.mapNotNull { url.queryParameter(it)?.lowercase() }
        }
        return when {
            extension in HLS_EXTENSIONS || hints.any { it == "m3u8" || it == "hls" } -> StreamFormat.HLS
            extension == "mpd" || hints.any { it == "mpd" || it == "dash" } -> StreamFormat.DASH
            extension == "ism" || extension == "isml" || path.endsWith("/manifest") && ".ism" in path -> StreamFormat.SMOOTH_STREAMING
            extension in PROGRESSIVE_EXTENSIONS || hints.any { it == "ts" || it == "mpegts" } -> StreamFormat.PROGRESSIVE
            else -> StreamFormat.UNKNOWN
        }
    }

    /** Sépare `url|Nom=valeur&Nom2=valeur2` en URL et en-têtes. */
    fun splitKodiHeaders(raw: String): Pair<String, Map<String, String>> {
        val pipe = raw.indexOf('|')
        if (pipe < 0) return raw to emptyMap()
        val spec = raw.substring(pipe + 1)
        if ('=' !in spec) return raw.substring(0, pipe) to emptyMap()
        return raw.substring(0, pipe).trim() to HttpHeaderValues.parseKodi(spec)
    }

    private fun http(url: HttpUrl, headers: Map<String, String>, drmProtected: Boolean) = StreamDescriptor(
        url = url.toString(),
        headers = headers,
        format = guessFormat(url),
        support = if (drmProtected) StreamSupport.UNSUPPORTED else StreamSupport.SUPPORTED,
        issues = if (drmProtected) setOf(StreamIssue.DRM_PROTECTED) else emptySet(),
    )

    private fun unsupported(url: String, headers: Map<String, String>, issue: StreamIssue) = StreamDescriptor(
        url = url,
        headers = headers,
        format = StreamFormat.UNKNOWN,
        support = StreamSupport.UNSUPPORTED,
        issues = setOf(issue),
    )

    private val HINT_PARAMETERS = listOf("output", "type", "format", "extension")
    private val HLS_EXTENSIONS = setOf("m3u8", "m3u")

    private val PROGRESSIVE_EXTENSIONS = setOf(
        "ts", "mts", "m2ts", "mp4", "m4v", "mkv", "webm", "mov", "flv", "avi",
        "mp3", "aac", "m4a", "ogg", "opus", "flac", "wav",
    )
}
