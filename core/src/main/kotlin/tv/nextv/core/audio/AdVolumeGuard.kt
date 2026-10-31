package tv.nextv.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Baisse le son pendant les publicités. Les flux IPTV ne signalent pas les pubs : elles sont
 * repérées à leur niveau sonore, nettement plus fort que celui du programme. Le niveau de
 * référence du programme suit lentement le son ; quand le son reste au-dessus de cette référence
 * de [excessDb] pendant [triggerMillis], il est baissé de [attenuationDb], puis rétabli dès qu'il
 * revient au niveau du programme pendant [releaseMillis].
 *
 * Utilisé bloc par bloc par le lecteur : [process] reçoit l'énergie moyenne du bloc (carré moyen des
 * échantillons, entre 0 et 1) et sa durée, et rend le gain linéaire à appliquer.
 */
class AdVolumeGuard(
    private val excessDb: Double = 4.0,
    private val triggerMillis: Long = 3_000,
    private val releaseMillis: Long = 2_000,
    private val attenuationDb: Double = -24.0,
    /** Début de lecture : le niveau du programme s'apprend avant toute atténuation. */
    private val warmupMillis: Long = 15_000,
    /** Au-delà, ce n'était pas une pub mais un programme plus fort : il devient la référence. */
    private val maxAttenuationMillis: Long = 6 * 60_000,
) {
    /** Énergie moyenne sur environ une seconde. */
    private var shortTerm = 0.0

    /** Énergie de référence du programme (environ une minute et demie). */
    private var reference = 0.0

    private var elapsed = 0L
    private var aboveMillis = 0L
    private var belowMillis = 0L
    private var attenuatedMillis = 0L
    private var gainDb = 0.0

    /** Son baissé (pub détectée). */
    var attenuating: Boolean = false
        private set

    fun process(meanSquare: Double, durationMillis: Long): Double {
        val dt = durationMillis.coerceAtLeast(1).toDouble()
        elapsed += durationMillis
        shortTerm += min(1.0, dt / SHORT_TERM_MILLIS) * (meanSquare - shortTerm)
        val level = decibels(shortTerm)
        val silent = level < SILENCE_DB

        if (!attenuating && !silent) {
            val window = if (elapsed < warmupMillis) WARMUP_REFERENCE_MILLIS else REFERENCE_MILLIS
            reference += min(1.0, dt / window) * (shortTerm - reference)
        }
        val excess = level - decibels(reference)

        if (elapsed >= warmupMillis && !silent) {
            if (!attenuating) {
                aboveMillis = if (excess > excessDb) aboveMillis + durationMillis else 0
                if (aboveMillis >= triggerMillis) {
                    attenuating = true
                    attenuatedMillis = 0
                    belowMillis = 0
                }
            } else {
                attenuatedMillis += durationMillis
                belowMillis = if (excess < excessDb / 2) belowMillis + durationMillis else 0
                when {
                    belowMillis >= releaseMillis -> release()
                    attenuatedMillis >= maxAttenuationMillis -> {
                        reference = shortTerm
                        release()
                    }
                }
            }
        }

        // Gain lissé : baisse en une demi-seconde, retour en une seconde (pas de claquement).
        val target = if (attenuating) attenuationDb else 0.0
        val step = dt * (if (target < gainDb) -attenuationDb / FALL_MILLIS else -attenuationDb / RISE_MILLIS)
        gainDb = if (target < gainDb) max(target, gainDb - step) else min(target, gainDb + step)
        return 10.0.pow(gainDb / 20)
    }

    private fun release() {
        attenuating = false
        aboveMillis = 0
        belowMillis = 0
    }

    private fun decibels(energy: Double) = 10 * log10(energy + 1e-12)

    private companion object {
        const val SHORT_TERM_MILLIS = 1_000.0
        const val REFERENCE_MILLIS = 90_000.0
        const val WARMUP_REFERENCE_MILLIS = 4_000.0
        const val SILENCE_DB = -55.0
        const val FALL_MILLIS = 500.0
        const val RISE_MILLIS = 1_000.0
    }
}
