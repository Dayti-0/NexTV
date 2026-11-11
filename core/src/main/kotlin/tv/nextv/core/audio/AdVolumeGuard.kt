package tv.nextv.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Logo de la chaîne à l'image, d'après [LogoWatcher] : il disparaît pendant les publicités. */
enum class LogoState {
    /** Pas de logo connu pour cette chaîne (ou image non lisible) : seul le son décide. */
    UNKNOWN,
    VISIBLE,
    HIDDEN,
}

/**
 * Baisse le son pendant les pages de publicité.
 *
 * Les publicités ne sont pas plus fortes que le programme : les chaînes les diffusent au même niveau
 * moyen (mesuré sur des coupures de M6, France 2 et RTS de 2024 : −26 à −28 dB, comme le programme
 * autour). Ce qui les distingue, c'est le **silence complet entre deux spots** : 0,3 à 1,5 s, toutes
 * les 5 à 31 secondes. Un film ou une émission n'en a presque jamais (trois en douze minutes de film,
 * jamais deux à moins d'une minute d'écart).
 *
 * - Entrée : deux silences complets séparés de [minIntervalMillis] à [maxIntervalMillis] (un spot, ou
 *   le jingle « publicité », tient entre les deux). Un seul suffit quand le logo de la chaîne vient de
 *   disparaître de l'image.
 * - Maintien : tant que les silences se suivent à moins de [holdMillis] (durée d'un spot de trente
 *   secondes, avec une marge).
 * - Sortie : dès que le logo de la chaîne revient à l'image ; sans logo connu, quand le son dure plus
 *   longtemps qu'un spot sans silence.
 *
 * Un logo resté affiché alors que les silences s'enchaînent ne dit rien des publicités de cette
 * chaîne : il n'est plus écouté.
 *
 * Utilisé bloc par bloc par le lecteur (blocs d'environ 20 ms) : [process] reçoit l'énergie moyenne du
 * bloc (carré moyen des échantillons, entre 0 et 1) et sa durée, et rend le gain linéaire à appliquer.
 */
class AdVolumeGuard(
    private val attenuationDb: Double = -24.0,
    /** Silence complet le plus court et le plus long entre deux spots. */
    private val minGapMillis: Long = 200,
    private val maxGapMillis: Long = 4_000,
    /** Écart entre deux silences : plus court, c'est le même ; plus long, ce n'est pas un spot. */
    private val minIntervalMillis: Long = 2_500,
    private val maxIntervalMillis: Long = 50_000,
    /** Sans nouveau silence après ce délai, le programme a repris. */
    private val holdMillis: Long = 33_000,
    /** Logo disparu depuis moins longtemps que cela : le premier silence ouvre la page de pubs. */
    private val logoJustHiddenMillis: Long = 90_000,
) {
    private var elapsed = 0L
    private var heard = false
    private var silentMillis = 0L

    /** Fin du dernier silence complet. */
    private var lastGap: Long? = null

    private var logoSince = 0L
    private var logoTrusted = true

    /** Silences enchaînés alors que le logo restait affiché. */
    private var vetoed = 0

    private var gainDb = 0.0

    /** Son baissé (page de pubs détectée). */
    var attenuating: Boolean = false
        private set

    /** Logo de la chaîne à l'image, tenu à jour par le lecteur. */
    var logo: LogoState = LogoState.UNKNOWN
        set(value) {
            if (field == value) return
            field = value
            logoSince = elapsed
            // Un logo qui disparaît puis revient suit de nouveau les coupures : il est réécouté.
            if (value == LogoState.HIDDEN) logoTrusted = true
        }

    fun process(meanSquare: Double, durationMillis: Long): Double {
        val duration = durationMillis.coerceAtLeast(1)
        elapsed += duration
        if (decibels(meanSquare) < SILENCE_DB) {
            silentMillis += duration
        } else {
            // Le silence d'avant le premier son (ouverture du flux) ne sépare rien.
            if (heard && silentMillis in minGapMillis..maxGapMillis) onGap(elapsed - duration)
            silentMillis = 0
            heard = true
        }
        if (attenuating) {
            val since = lastGap ?: elapsed
            val logoBack = logoTrusted && logo == LogoState.VISIBLE
            if (logoBack || elapsed - since > holdMillis) attenuating = false
        }

        // Gain lissé : baisse en une demi-seconde, retour en une seconde (pas de claquement).
        val target = if (attenuating) attenuationDb else 0.0
        val step = duration * (if (target < gainDb) -attenuationDb / FALL_MILLIS else -attenuationDb / RISE_MILLIS)
        gainDb = if (target < gainDb) max(target, gainDb - step) else min(target, gainDb + step)
        return 10.0.pow(gainDb / 20)
    }

    /** Un silence complet vient de finir à [time]. */
    private fun onGap(time: Long) {
        val previous = lastGap
        lastGap = time
        if (attenuating) return
        val visible = logoTrusted && logo == LogoState.VISIBLE
        val spotBefore = previous != null && time - previous in minIntervalMillis..maxIntervalMillis
        val logoJustHidden = logoTrusted && logo == LogoState.HIDDEN && time - logoSince <= logoJustHiddenMillis
        when {
            // Deux silences trop rapprochés : le même, il ne compte qu'une fois.
            previous != null && time - previous < minIntervalMillis -> Unit
            !spotBefore && !logoJustHidden -> vetoed = 0
            !visible -> enter()
            // Logo affiché : pas une page de pubs… sauf si les silences continuent de s'enchaîner.
            ++vetoed >= VETOES_BEFORE_DISTRUST -> {
                logoTrusted = false
                enter()
            }
        }
    }

    private fun enter() {
        attenuating = true
        vetoed = 0
    }

    private fun decibels(energy: Double) = 10 * log10(energy + 1e-12)

    private companion object {
        /** Silence complet : bien en dessous des passages calmes d'un film (−50 dB environ). */
        const val SILENCE_DB = -60.0
        const val FALL_MILLIS = 500.0
        const val RISE_MILLIS = 1_000.0
        const val VETOES_BEFORE_DISTRUST = 3
    }
}
