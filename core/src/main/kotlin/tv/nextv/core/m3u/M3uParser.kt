package tv.nextv.core.m3u

import java.io.Reader

/**
 * Analyseur M3U / M3U8 tolérant, en flux (la playlist n'est jamais chargée en entier).
 *
 * Pris en charge : en-tête `#EXTM3U` et ses attributs (`url-tvg`, `x-tvg-url`…), `#EXTINF`
 * avec attributs entre guillemets doubles, simples ou sans guillemets, `#EXTGRP`,
 * `#EXTVLCOPT` (User-Agent, Referer…), `#EXTHTTP` (JSON), `#KODIPROP` (en-têtes, DRM),
 * listes d'URL sans `#EXTINF`, fins de ligne Unix/Windows/Mac.
 *
 * Un manifeste HLS (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`…) est reconnu et signalé par
 * [M3uPlaylist.isHlsMedia] : c'est un flux vidéo, pas une liste de chaînes.
 */
class M3uParser(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH,
) {

    /** @throws LineTooLongException si une ligne dépasse [maxLineLength] (contenu non textuel). */
    fun parse(reader: Reader, checkCancelled: () -> Unit = {}): M3uPlaylist {
        val entries = ArrayList<M3uEntry>()
        val playlist = parse(reader, checkCancelled) { entry, _ -> entries += entry }
        return playlist.copy(entries = entries)
    }

    /**
     * Lecture en flux : chaque entrée est transmise à [onEntry] dès qu'elle est lue, sans être
     * conservée (les grosses playlists ne sont jamais entièrement en mémoire). La playlist
     * renvoyée n'a donc pas d'entrées.
     *
     * @param onEntry reçoit l'entrée et vrai si l'en-tête `#EXTM3U` a déjà été lu.
     * @throws LineTooLongException si une ligne dépasse [maxLineLength] (contenu non textuel).
     */
    fun parse(reader: Reader, checkCancelled: () -> Unit = {}, onEntry: (M3uEntry, Boolean) -> Unit): M3uPlaylist {
        val lines = LineReader(reader, maxLineLength)
        var entryCount = 0
        var header: Map<String, String> = emptyMap()
        var headerSeen = false
        var pending: ExtInf? = null
        var pendingGroup: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()
        var pendingDrm = false
        var truncated = false
        var lineCount = 0

        while (true) {
            val raw = lines.readLine() ?: break
            if (++lineCount % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
            val line = (if (lineCount == 1) raw.removePrefix("﻿") else raw).trim()
            if (line.isEmpty()) continue

            if (line.startsWith('#')) {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> if (!headerSeen) {
                        headerSeen = true
                        header = AttributeParser.parse(line.substring("#EXTM3U".length)).attributes
                    }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> pending = ExtInf.parse(line.substring("#EXTINF:".length))
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        pendingGroup = line.substring("#EXTGRP:".length).trim().ifEmpty { null }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) ->
                        parseVlcOption(line.substring("#EXTVLCOPT:".length))?.let { (k, v) -> pendingHeaders[k] = v }
                    line.startsWith("#EXTHTTP:", ignoreCase = true) ->
                        pendingHeaders.putAll(parseJsonHeaders(line.substring("#EXTHTTP:".length)))
                    line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                        val (key, value) = splitKeyValue(line.substring("#KODIPROP:".length)) ?: continue
                        when (key.lowercase()) {
                            "inputstream.adaptive.license_type", "inputstream.adaptive.license_key",
                            "inputstream.adaptive.drm", "inputstream.adaptive.drm_legacy" -> pendingDrm = true
                            "inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers" ->
                                pendingHeaders.putAll(HttpHeaderValues.parseKodi(value))
                        }
                    }
                    entryCount == 0 && HLS_TAGS.any { line.startsWith(it, ignoreCase = true) } ->
                        return M3uPlaylist(header, emptyList(), hasHeader = headerSeen, isHlsMedia = true)
                    // Autres commentaires (#EXTREM, #PLAYLIST, #EXT-X-VERSION…) : ignorés.
                }
                continue
            }

            if (entryCount >= maxEntries) {
                truncated = true
                break
            }
            val info = pending
            entryCount++
            val entry = M3uEntry(
                title = info?.title,
                url = line,
                duration = info?.duration,
                attributes = info?.attributes.orEmpty(),
                group = info?.attributes?.get("group-title")?.trim()?.ifEmpty { null } ?: pendingGroup,
                headers = HttpHeaderValues.sanitize(pendingHeaders),
                drmProtected = pendingDrm,
                hasExtInf = info != null,
            )
            onEntry(entry, headerSeen)
            pending = null
            pendingGroup = null
            pendingHeaders.clear()
            pendingDrm = false
        }
        return M3uPlaylist(header, emptyList(), hasHeader = headerSeen, truncated = truncated)
    }

    private fun parseVlcOption(option: String): Pair<String, String>? {
        val (key, value) = splitKeyValue(option) ?: return null
        val header = when (key.lowercase()) {
            "http-user-agent" -> "User-Agent"
            "http-referrer", "http-referer" -> "Referer"
            "http-origin" -> "Origin"
            "http-cookie" -> "Cookie"
            else -> return null
        }
        return header to value.trim().trim('"')
    }

    private fun splitKeyValue(text: String): Pair<String, String>? {
        val separator = text.indexOf('=')
        if (separator <= 0) return null
        return text.substring(0, separator).trim() to text.substring(separator + 1).trim()
    }

    private fun parseJsonHeaders(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (match in JSON_STRING_PAIR.findAll(json)) {
            val name = unescapeJson(match.groupValues[1])
            result[HttpHeaderValues.canonicalName(name)] = unescapeJson(match.groupValues[2])
        }
        return result
    }

    private fun unescapeJson(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'u' -> {
                    val code = value.substring(i + 2, minOf(i + 6, value.length)).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(next)
                }
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 250_000
        const val DEFAULT_MAX_LINE_LENGTH = 64 * 1024
        private const val CANCELLATION_CHECK_INTERVAL = 512

        private val HLS_TAGS = listOf(
            "#EXT-X-TARGETDURATION",
            "#EXT-X-STREAM-INF",
            "#EXT-X-MEDIA-SEQUENCE",
            "#EXT-X-MEDIA:",
            "#EXT-X-PLAYLIST-TYPE",
            "#EXT-X-I-FRAME-STREAM-INF",
            "#EXT-X-KEY",
            "#EXT-X-MAP",
            "#EXT-X-ENDLIST",
        )

        private val JSON_STRING_PAIR = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    }
}

/** Contenu d'une ligne `#EXTINF:<durée> <attributs>,<titre>`. */
internal data class ExtInf(
    val duration: Double?,
    val attributes: Map<String, String>,
    val title: String?,
) {
    companion object {
        fun parse(content: String): ExtInf {
            var i = 0
            while (i < content.length && content[i].isWhitespace()) i++
            val durationStart = i
            while (i < content.length && !content[i].isWhitespace() && content[i] != ',') i++
            val duration = content.substring(durationStart, i).toDoubleOrNull()
            val parsed = AttributeParser.parse(content, i)
            return ExtInf(duration, parsed.attributes, parsed.title?.trim()?.ifEmpty { null })
        }
    }
}

/**
 * Attributs `clé="valeur"`, `clé='valeur'` ou `clé=valeur`, éventuellement suivis
 * de `,titre`. Une virgule placée entre guillemets ne termine pas les attributs.
 */
internal object AttributeParser {

    data class Parsed(val attributes: Map<String, String>, val title: String?)

    fun parse(text: String, start: Int = 0): Parsed {
        val attributes = LinkedHashMap<String, String>()
        var i = start
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            if (text[i] == ',') return Parsed(attributes, text.substring(i + 1))

            val keyStart = i
            while (i < n && text[i] != '=' && text[i] != ',' && !text[i].isWhitespace()) i++
            val key = text.substring(keyStart, i)
            if (i >= n || text[i] != '=') continue // jeton isolé : ignoré

            i++ // '='
            val value: String
            if (i < n && (text[i] == '"' || text[i] == '\'')) {
                val quote = text[i]
                val close = text.indexOf(quote, i + 1)
                if (close < 0) {
                    value = text.substring(i + 1)
                    i = n
                } else {
                    value = text.substring(i + 1, close)
                    i = close + 1
                }
            } else {
                val valueStart = i
                while (i < n && !text[i].isWhitespace() && text[i] != ',') i++
                value = text.substring(valueStart, i)
            }
            if (key.isNotEmpty()) attributes[key.lowercase()] = value.trim()
        }
        return Parsed(attributes, null)
    }
}
