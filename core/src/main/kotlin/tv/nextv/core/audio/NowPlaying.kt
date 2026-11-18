package tv.nextv.core.audio

import org.json.JSONException
import org.json.JSONObject

/**
 * Morceau en cours d'une radio : [artist] et [title], pochette ([cover]) si elle est connue. [untilMillis] : heure de fin
 * prévue (Radio France), [remainingMillis] : temps restant du morceau (reconnaissance), pour savoir quand regarder à nouveau.
 */
data class NowPlaying(
    val artist: String?,
    val title: String,
    val cover: String? = null,
    val untilMillis: Long? = null,
    val remainingMillis: Long? = null,
) {
    /** « Artiste – Titre », ou le titre seul. */
    val label: String get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" – ")

    companion object {
        /**
         * Réponse du service « livemeta » de Radio France (`api.radiofrance.fr/livemeta/pull/<numéro>`) : l'élément en cours
         * de la grille. Un morceau donne artiste, titre et pochette ; une émission parlée, son titre seul. `null` si illisible.
         */
        fun fromRadioFrance(body: String): NowPlaying? = try {
            val json = JSONObject(body)
            val level = json.getJSONArray("levels").getJSONObject(0)
            val id = level.getJSONArray("items").getString(level.getInt("position"))
            val step = json.getJSONObject("steps").getJSONObject(id)
            val title = step.optString("title").trim().takeIf { it.isNotEmpty() }
            title?.let {
                NowPlaying(
                    artist = step.optString("authors").trim().takeIf { a -> a.isNotEmpty() && step.optString("embedType") == "song" },
                    title = it,
                    cover = step.optString("visual").takeIf { v -> v.startsWith("http") },
                    untilMillis = step.optLong("end").takeIf { end -> end > 0 }?.times(1_000),
                )
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse du service de reconnaissance AudD (`api.audd.io`, avec `return=apple_music`) : morceau reconnu, pochette et
         * temps restant (durée moins le moment reconnu). `null` : rien de reconnu ou erreur ([recognitionError]).
         */
        fun fromAudd(body: String): NowPlaying? = try {
            val result = JSONObject(body).optJSONObject("result")
            result?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
                val apple = result.optJSONObject("apple_music")
                val duration = apple?.optLong("durationInMillis")?.takeIf { it > 0 }
                val at = clockMillis(result.optString("timecode"))
                NowPlaying(
                    artist = result.optString("artist").trim().takeIf { it.isNotEmpty() },
                    title = title,
                    cover = apple?.optJSONObject("artwork")?.optString("url")?.takeIf { it.startsWith("http") }
                        ?.replace("{w}", "300")?.replace("{h}", "300"),
                    remainingMillis = if (duration != null && at != null) (duration - at).coerceAtLeast(0) else null,
                )
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse de Skyrock (`skyrock.fm/api/v3/player/onair/national`) : le morceau de la liste « schedule » qui passe à
         * [nowSeconds] (heure Unix), sinon le dernier commencé ; titre seul (l'artiste n'y est pas), pochette.
         */
        fun fromSkyrock(body: String, nowSeconds: Long): NowPlaying? = try {
            val schedule = JSONObject(body).optJSONArray("schedule")
            var playing: JSONObject? = null
            var latest: JSONObject? = null
            for (i in 0 until (schedule?.length() ?: 0)) {
                val entry = schedule!!.getJSONObject(i)
                if (entry.optString("type") != "record") continue
                val info = entry.optJSONObject("info") ?: continue
                val start = info.optString("start_ts").toLongOrNull() ?: continue
                val end = info.optString("end_ts").toLongOrNull() ?: continue
                if (start <= nowSeconds && (latest == null || start > (latest.optString("start_ts").toLongOrNull() ?: 0))) latest = info
                if (nowSeconds in start until end) playing = info
            }
            (playing ?: latest)?.let { info ->
                info.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
                    NowPlaying(
                        artist = info.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name") ?: a.optString(it) } }
                            ?.takeIf { it.isNotBlank() },
                        title = title,
                        cover = info.optString("cover_mid_uri").takeIf { it.startsWith("http") },
                        untilMillis = info.optString("end_ts").toLongOrNull()?.times(1_000)?.takeIf { playing === info },
                    )
                }
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse de Nova (`nova.fr/radios-data/www.nova.fr/all.json`) : morceau en cours de la radio lue à l'adresse
         * [stream] (Radio Nova ou une de ses webradios).
         */
        fun fromNova(body: String, stream: String): NowPlaying? = try {
            val radios = org.json.JSONArray(body)
            val wanted = streamKey(stream)
            (0 until radios.length()).map { radios.getJSONObject(it) }
                .firstOrNull { streamKey(it.optJSONObject("radio")?.optString("stream_url").orEmpty()) == wanted }
                ?.optJSONObject("currentTrack")
                ?.let { track ->
                    track.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
                        NowPlaying(
                            artist = track.optString("artist").trim().takeIf { it.isNotEmpty() },
                            title = title,
                            cover = track.optString("media_url").takeIf { it.startsWith("http") },
                        )
                    }
                }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse des Indés Radios (`https://<site>/lite/update_onair`, Jazz Radio, M Radio, Générations…) : morceau en
         * cours de la radio [radioId], avec sa pochette.
         */
        fun fromIndes(body: String, radioId: String): NowPlaying? = try {
            JSONObject(body).optJSONObject(radioId)?.let { radio ->
                radio.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
                    NowPlaying(
                        artist = radio.optString("artist").trim().takeIf { it.isNotEmpty() },
                        title = title,
                        cover = radio.optString("cover").takeIf { it.startsWith("http") && "pochette" !in it },
                    )
                }
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Réponse d'Europe 2 ou RFM (`/cetait-quoi-ce-titre/history`) : le morceau « playing », sa pochette, et sa fin
         * (heure de début + durée « 00:02:57 »).
         */
        fun fromLagardere(body: String): NowPlaying? = try {
            val list = JSONObject(body).optJSONArray("nowplaying")
            val entry = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.firstOrNull { it.optString("status") == "playing" }
            entry?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
                val start = runCatching { java.time.Instant.parse(entry.optString("time")).toEpochMilli() }.getOrNull()
                val length = clockMillis(entry.optJSONObject("raw")?.optString("duration").orEmpty())
                NowPlaying(
                    artist = entry.optString("artist").trim().takeIf { it.isNotEmpty() },
                    title = title,
                    cover = entry.optString("imageUrl").takeIf { it.startsWith("http") },
                    untilMillis = if (start != null && length != null) start + length else null,
                )
            }
        } catch (e: JSONException) {
            null
        }

        /** Réponse de Radioking (`/widgets/api/v1/radio/<id>/track/current`) : morceau, pochette et fin. */
        fun fromRadioking(body: String): NowPlaying? = try {
            val json = JSONObject(body)
            json.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
                NowPlaying(
                    artist = json.optString("artist").trim().takeIf { it.isNotEmpty() },
                    title = title,
                    cover = json.optString("cover").takeIf { it.startsWith("http") && !json.optBoolean("default_cover") },
                    untilMillis = runCatching {
                        java.time.OffsetDateTime.parse(json.optString("end_at"), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))
                            .toInstant().toEpochMilli()
                    }.getOrNull(),
                )
            }
        } catch (e: JSONException) {
            null
        }

        /**
         * Titre annoncé dans le flux d'une radio (métadonnées ICY, « Artiste - Titre ») séparé en artiste et titre ;
         * `null` sans séparateur (titre seul, nom d'émission).
         */
        fun fromStreamTitle(text: String): NowPlaying? {
            val match = STREAM_SEPARATOR.find(text) ?: return null
            val artist = text.substring(0, match.range.first).trim()
            val title = text.substring(match.range.last + 1).trim()
            return if (artist.isEmpty() || title.isEmpty()) null else NowPlaying(artist = artist, title = title)
        }

        private val STREAM_SEPARATOR = Regex("\\s+[-–—]\\s+")

        /** Réponse du groupe RTL (`/ws/live/live`) : l'émission en cours (« Ça peut vous arriver – Julien Courbet »), son image. */
        fun fromRtlProgram(body: String): NowPlaying? = try {
            val json = JSONObject(body)
            json.optString("title").trim().takeIf { it.isNotEmpty() }?.let { title ->
                NowPlaying(
                    artist = null,
                    title = listOfNotNull(title, json.optString("hosts").trim().takeIf { it.isNotEmpty() && it != "null" }).joinToString(" – "),
                    cover = json.optString("cover").takeIf { it.startsWith("http") },
                    untilMillis = json.optJSONObject("schedule")?.optLong("end")?.takeIf { it > 0 },
                )
            }
        } catch (e: JSONException) {
            null
        }

        /** Adresse de flux comparable (sans protocole ni paramètres). */
        private fun streamKey(url: String): String = url.substringAfter("://").substringBefore('?').trimEnd('/').lowercase()

        /** Code d'erreur d'une réponse AudD (`null` : pas d'erreur) : 900 et suivants pour la clé, la limite du jour… */
        fun recognitionError(body: String): Int? = try {
            JSONObject(body).takeIf { it.optString("status") == "error" }?.optJSONObject("error")?.optInt("error_code")
        } catch (e: JSONException) {
            null
        }

        /** « 01:23 » → 83 000 ms. */
        private fun clockMillis(text: String): Long? {
            val parts = text.split(':').mapNotNull { it.trim().toLongOrNull() }
            if (parts.isEmpty() || parts.size != text.split(':').size) return null
            return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
        }
    }
}
