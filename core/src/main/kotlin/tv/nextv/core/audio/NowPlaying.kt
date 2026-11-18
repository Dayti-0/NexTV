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
