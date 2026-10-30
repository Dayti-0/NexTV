package tv.nextv.core.stream

import tv.nextv.core.model.StreamQuality

/** Une version (qualité) d'une même chaîne. */
data class QualityOption(val channelId: Long, val quality: StreamQuality?)

/**
 * Choix automatique de la qualité parmi les versions d'une même chaîne (FHD → HD → SD → LOW).
 *
 * - Erreur de lecture, mise en mémoire tampon prolongée ou répétée : passage à la version
 *   inférieure disponible. Jamais vers une autre chaîne : seules les [options] fournies
 *   (versions de la chaîne regardée) sont envisagées.
 * - Lecture stable et débit mesuré suffisant : retour progressif à une meilleure version.
 * - Une version en échec n'est réessayée qu'après un délai, doublé à chaque nouvel échec.
 * - Désactivé ([enabled] faux) : aucune bascule, une erreur est simplement signalée.
 */
class QualitySwitcher(
    options: List<QualityOption>,
    initialChannelId: Long,
    private val enabled: Boolean = true,
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val maxContinuousBufferingMillis: Long = 12_000,
        val rebufferWindowMillis: Long = 60_000,
        val maxRebuffersInWindow: Int = 3,
        val stableMillisBeforeUpgrade: Long = 120_000,
        val upgradeHeadroom: Double = 1.4,
        val failureCooldownMillis: Long = 5 * 60_000,
        val maxFailureCooldownMillis: Long = 60 * 60_000,
    )

    enum class Reason { ERROR, BUFFERING, UPGRADE }

    sealed interface Decision {
        data object Stay : Decision
        data class Switch(val option: QualityOption, val reason: Reason) : Decision

        /** Plus aucune version disponible : l'erreur doit être affichée. */
        data object GiveUp : Decision
    }

    /** Versions de la meilleure à la moins bonne (ordre de la playlist à qualité égale). */
    val ladder: List<QualityOption> = options.withIndex()
        .sortedWith(compareByDescending<IndexedValue<QualityOption>> { StreamQuality.rankOf(it.value.quality) }.thenBy { it.index })
        .map { it.value }
        .distinctBy { it.channelId }

    init {
        require(ladder.isNotEmpty()) { "At least one quality option is required" }
    }

    private var index = ladder.indexOfFirst { it.channelId == initialChannelId }.coerceAtLeast(0)
    private val failures = HashMap<Long, Int>()
    private val cooldownUntil = HashMap<Long, Long>()
    private val rebuffers = ArrayDeque<Long>()
    private var bufferingSince: Long? = null
    private var stableSince: Long = clock()

    val current: QualityOption get() = ladder[index]

    /** Plusieurs versions existent et le basculement automatique est actif. */
    val isAdaptive: Boolean get() = enabled && ladder.size > 1

    fun onBufferingStarted() {
        val now = clock()
        if (bufferingSince == null) {
            bufferingSince = now
            rebuffers.addLast(now)
        }
    }

    fun onReady() {
        bufferingSince = null
    }

    /** La version courante ne peut pas être lue. */
    fun onError(): Decision {
        markFailed(current)
        if (!enabled) return Decision.GiveUp
        return lower()?.let { switchTo(it, Reason.ERROR) } ?: Decision.GiveUp
    }

    /** À appeler régulièrement pendant la lecture. @param bandwidthBps débit mesuré, en bits/s. */
    fun evaluate(bandwidthBps: Long?): Decision {
        if (!isAdaptive) return Decision.Stay
        val now = clock()
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > config.rebufferWindowMillis) rebuffers.removeFirst()

        val buffering = bufferingSince
        val stalled = buffering != null && now - buffering >= config.maxContinuousBufferingMillis
        val unstable = rebuffers.size >= config.maxRebuffersInWindow
        if (stalled || unstable) {
            val target = lower() ?: return Decision.Stay
            markFailed(current)
            return switchTo(target, Reason.BUFFERING)
        }

        if (buffering == null && rebuffers.isEmpty() && now - stableSince >= config.stableMillisBeforeUpgrade && bandwidthBps != null) {
            val target = higher() ?: return Decision.Stay
            if (bandwidthBps >= requiredBitrate(target.quality) * config.upgradeHeadroom) {
                return switchTo(target, Reason.UPGRADE)
            }
        }
        return Decision.Stay
    }

    private fun lower(): QualityOption? {
        val now = clock()
        return ladder.drop(index + 1).firstOrNull { (cooldownUntil[it.channelId] ?: 0) <= now }
    }

    private fun higher(): QualityOption? {
        val now = clock()
        return ladder.take(index).lastOrNull { (cooldownUntil[it.channelId] ?: 0) <= now }
    }

    private fun switchTo(option: QualityOption, reason: Reason): Decision {
        index = ladder.indexOf(option)
        bufferingSince = null
        rebuffers.clear()
        stableSince = clock()
        return Decision.Switch(option, reason)
    }

    private fun markFailed(option: QualityOption) {
        val count = (failures[option.channelId] ?: 0) + 1
        failures[option.channelId] = count
        val cooldown = (config.failureCooldownMillis shl (count - 1).coerceAtMost(10)).coerceAtMost(config.maxFailureCooldownMillis)
        cooldownUntil[option.channelId] = clock() + cooldown
    }

    companion object {
        /** Débit approximatif nécessaire à chaque qualité, en bits/s. */
        fun requiredBitrate(quality: StreamQuality?): Long = when (quality) {
            StreamQuality.UHD -> 25_000_000
            StreamQuality.FHD -> 8_000_000
            StreamQuality.HD -> 5_000_000
            null -> 4_000_000
            StreamQuality.SD -> 2_500_000
            StreamQuality.LOW -> 1_000_000
        }
    }
}
