package tv.nextv.core.football

/**
 * Reprise estimée des matchs à la mi-temps ([HALF_TIME_MILLIS] après le début de la pause) : les sources des scores disent seulement « mi-temps »,
 * pas depuis quand. Début de la pause :
 * - vu passer (dernier relevé en jeu, en fin de première période, et premier relevé « mi-temps » à moins de
 *   [TRANSITION_MILLIS] l'un de l'autre) : entre les deux ;
 * - sinon (chaîne ouverte pendant la pause) : coup d'envoi prévu + 45 min + temps additionnel (dernière minute de la
 *   première période gardée par ESPN à la pause, « 45+2' »), plus [LATE_MILLIS].
 *
 * Une seule pour toute l'application : le début de la pause reste connu après un changement de chaîne.
 */
class HalfTimes(private val clock: () -> Long = System::currentTimeMillis) {
    private class Seen(var playingAt: Long? = null, var playingMinute: Minute? = null, var pauseFrom: Long? = null, var seenAt: Long = 0)

    private val matches = HashMap<String, Seen>()

    /**
     * Note l'état du match [key] (coup d'envoi prévu [kickoffMillis] ; en jeu [inPlay], à la pause [halfTime],
     * minute de jeu [minute] « 44' », « 45+2' » si elle est connue) et renvoie l'heure estimée de la reprise
     * pendant la mi-temps, `null` sinon.
     */
    @Synchronized
    fun resumeAt(key: String, kickoffMillis: Long, inPlay: Boolean, halfTime: Boolean, minute: String?): Long? {
        val now = clock()
        matches.entries.removeAll { now - it.value.seenAt > FORGET_MILLIS }
        val seen = matches.getOrPut(key) { Seen() }
        seen.seenAt = now
        if (!halfTime) {
            if (inPlay) {
                seen.playingAt = now
                seen.playingMinute = minute?.let(::parseMinute)
                seen.pauseFrom = null
            }
            return null
        }
        val pauseFrom = seen.pauseFrom ?: estimateStart(seen, kickoffMillis, minute?.let(::parseMinute), now).also { seen.pauseFrom = it }
        return pauseFrom + HALF_TIME_MILLIS
    }

    private fun estimateStart(seen: Seen, kickoffMillis: Long, pauseMinute: Minute?, now: Long): Long {
        val playingAt = seen.playingAt
        val last = seen.playingMinute
        // Fin de la première période vue : le coup de sifflet est entre les deux relevés.
        if (playingAt != null && now - playingAt <= TRANSITION_MILLIS && last != null && last.base in FIRST_HALF - 1..FIRST_HALF) {
            return (playingAt + now) / 2
        }
        // Dernière minute de la première période gardée par ESPN à la pause (« 45' », « 45+2' »), sinon au moins le
        // temps additionnel déjà vu en jeu.
        val added = pauseMinute?.takeIf { it.base == FIRST_HALF }?.added
            ?: maxOf(last?.takeIf { it.base == FIRST_HALF }?.added ?: 0, DEFAULT_ADDED_MINUTES)
        val estimate = kickoffMillis + LATE_MILLIS + (FIRST_HALF + added) * 60_000L
        return estimate.coerceAtMost(now)
    }

    /** Minute de jeu : « 45+2' » → 45 et 2 ; « 44' » → 44 et 0. */
    private data class Minute(val base: Int, val added: Int)

    private fun parseMinute(text: String): Minute? {
        val parts = text.replace("'", "").split('+').map { it.trim() }
        val base = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val added = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: 0
        return Minute(base, added)
    }

    companion object {
        /**
         * Durée de la pause telle qu'ESPN la voit : 15 min dans les Lois du jeu, mais 16 à 17 min entre l'annonce de la
         * mi-temps et celle de la seconde période sur 5 matchs de Ligue des champions le 25/11/2026.
         */
        const val HALF_TIME_MILLIS = 16 * 60_000L

        private const val FIRST_HALF = 45

        /** Écart au-delà duquel le passage à la mi-temps n'est pas considéré comme vu. */
        private const val TRANSITION_MILLIS = 2 * 60_000L

        /** Temps additionnel de la première période quand il est inconnu (le plus fréquent). */
        private const val DEFAULT_ADDED_MINUTES = 2

        /**
         * Coup d'envoi donné un peu après l'heure prévue, temps additionnel joué au-delà de la minute affichée : sur 7 matchs
         * de Ligue des champions le 25/11/2026, mi-temps annoncée par ESPN de 0 à 2 min 45 (1 min 40 en moyenne) après coup
         * d'envoi prévu + 45 min + temps additionnel (« 45+2' » → 2).
         */
        private const val LATE_MILLIS = 100_000L

        /** Matchs oubliés quand ils n'ont plus été vus depuis. */
        private const val FORGET_MILLIS = 4 * 60 * 60_000L
    }
}
