package tv.nextv.core.stream

import tv.nextv.core.model.StreamQuality

/** Une version (qualité) d'une même chaîne. */
data class QualityOption(val channelId: Long, val quality: StreamQuality?)

/** Qualité visée (réglages) : la meilleure, au plus HD, ou l'économie de la connexion. */
enum class QualityTarget {
    /** Meilleure version disponible. */
    BEST,

    /** Au plus HD : jamais de FHD ni de 4K s'il existe une version HD ou inférieure. */
    HD,

    /** SD (ou LOW) si la chaîne en a une version, sinon au plus HD. */
    ECO,
    ;

    /** Rang le plus élevé visé parmi les versions [options], `null` : aucune limite. */
    fun maxRank(options: List<QualityOption>): Int? = when (this) {
        BEST -> null
        HD -> StreamQuality.HD.rank
        ECO -> if (options.any { StreamQuality.rankOf(it.quality) <= StreamQuality.SD.rank }) StreamQuality.SD.rank else StreamQuality.HD.rank
    }

    /**
     * Version de départ : [preferred] (version ouverte, ou qualité qui a fonctionné la dernière fois)
     * si elle respecte la qualité visée, sinon la meilleure version qui la respecte, et à défaut la
     * moins bonne disponible (une chaîne seulement en FHD reste lisible).
     */
    fun start(options: List<QualityOption>, preferred: Long): Long {
        val limit = maxRank(options) ?: return preferred
        val preferredOption = options.firstOrNull { it.channelId == preferred }
        if (preferredOption != null && StreamQuality.rankOf(preferredOption.quality) <= limit) return preferred
        val allowed = options.filter { StreamQuality.rankOf(it.quality) <= limit }
        return (allowed.maxByOrNull { StreamQuality.rankOf(it.quality) } ?: options.minByOrNull { StreamQuality.rankOf(it.quality) })?.channelId
            ?: preferred
    }
}

/**
 * Choix automatique de la qualité parmi les versions d'une même chaîne (FHD → HD → SD → LOW).
 *
 * - Erreur de lecture, mise en mémoire tampon prolongée ou répétée : passage à la version
 *   inférieure disponible, en quelques secondes. Le démarrage d'une chaîne a droit à plus de
 *   temps qu'un blocage en cours de lecture. Jamais vers une autre chaîne : seules les [options] fournies
 *   (versions de la chaîne regardée) sont envisagées.
 * - Trop d'images perdues par le décodeur (l'appareil ne suit pas, typiquement en 4K) : passage
 *   à une qualité inférieure, et plus de retour à cette qualité pour la chaîne en cours.
 * - Lecture stable : retour progressif à une meilleure version, dès que le débit mesuré le
 *   justifie, sinon à l'essai. Le débit d'un direct en MPEG-TS ne dépasse jamais celui de la vidéo
 *   (le serveur l'envoie au rythme de la lecture) : il ne peut pas prouver qu'une meilleure version
 *   passerait. Un essai qui ne démarre pas vite revient aussitôt à la version qui fonctionnait.
 * - Une version en échec n'est réessayée qu'après un délai, doublé à chaque nouvel échec.
 * - Désactivé ([enabled] faux) : aucune bascule, une erreur est simplement signalée.
 *
 * @param decoderCeilingRank limite déjà constatée sur cet appareil (autre chaîne) : la lecture
 * commence directement sous cette qualité si une version le permet.
 * @param maxRank qualité visée (réglages) : jamais de remontée au-dessus de ce rang.
 */
class QualitySwitcher(
    options: List<QualityOption>,
    initialChannelId: Long,
    private val enabled: Boolean = true,
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    decoderCeilingRank: Int? = null,
    private val maxRank: Int? = null,
) {
    data class Config(
        /** Blocage en cours de lecture toléré avant de changer de version. */
        val maxContinuousBufferingMillis: Long = 3_000,
        /** Démarrage de la version (chargement initial) toléré avant de changer de version. */
        val maxInitialBufferingMillis: Long = 8_000,
        val rebufferWindowMillis: Long = 30_000,
        val maxRebuffersInWindow: Int = 2,
        /** Lecture stable avant de remonter quand le débit mesuré le justifie. */
        val stableMillisBeforeUpgrade: Long = 60_000,
        val upgradeHeadroom: Double = 1.4,
        /** Lecture stable avant d'essayer une meilleure version sans preuve de débit. */
        val stableMillisBeforeTrial: Long = 90_000,
        /** Démarrage toléré pour une version à l'essai avant de revenir à la précédente. */
        val maxTrialStartMillis: Long = 5_000,
        val failureCooldownMillis: Long = 5 * 60_000,
        val maxFailureCooldownMillis: Long = 60 * 60_000,
        /** Images perdues tolérées sur [droppedFramesWindowMillis] avant de juger que l'appareil ne suit pas. */
        val maxDroppedFramesInWindow: Int = 30,
        val droppedFramesWindowMillis: Long = 5_000,
    )

    enum class Reason { ERROR, BUFFERING, DECODER, UPGRADE }

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

    /** La version courante a commencé à jouer : les mises en mémoire tampon suivantes sont des blocages. */
    private var played = false

    /** Meilleure version à l'essai, pas encore démarrée : rang de la version qui fonctionnait. */
    private var trialFrom: Int? = null
    private val droppedFrames = ArrayDeque<Pair<Long, Int>>()

    /** Rang de qualité que l'appareil n'a pas su décoder : aucune remontée à ce rang ou au-dessus. */
    var decoderCeilingRank: Int? = decoderCeilingRank.takeIf { enabled }
        private set

    init {
        val ceiling = this.decoderCeilingRank
        if (ceiling != null && StreamQuality.rankOf(ladder[index].quality) >= ceiling) {
            val below = ladder.indexOfFirst { StreamQuality.rankOf(it.quality) < ceiling }
            if (below >= 0) index = below
        }
    }
    private var stableSince: Long = clock()

    val current: QualityOption get() = ladder[index]

    /** Plusieurs versions existent et le basculement automatique est actif. */
    val isAdaptive: Boolean get() = enabled && ladder.size > 1

    fun onBufferingStarted() {
        val now = clock()
        if (bufferingSince == null) {
            bufferingSince = now
            if (played) rebuffers.addLast(now)
        }
    }

    fun onReady() {
        bufferingSince = null
        played = true
        trialFrom = null
    }

    /** La version courante ne peut pas être lue. */
    fun onError(): Decision {
        markFailed(current)
        if (!enabled) return Decision.GiveUp
        // Essai d'une meilleure version : retour à celle qui fonctionnait.
        trialFrom?.let { return switchTo(ladder[it], Reason.ERROR) }
        return lower()?.let { switchTo(it, Reason.ERROR) } ?: Decision.GiveUp
    }

    /**
     * Images perdues par le décodeur vidéo (signalées par lots par le lecteur). Au-delà du seuil,
     * l'appareil ne suit pas cette qualité : passage à une qualité strictement inférieure.
     */
    fun onDroppedFrames(count: Int): Decision {
        if (count <= 0 || !isAdaptive) return Decision.Stay
        val now = clock()
        droppedFrames.addLast(now to count)
        while (droppedFrames.isNotEmpty() && now - droppedFrames.first().first > config.droppedFramesWindowMillis) droppedFrames.removeFirst()
        if (droppedFrames.sumOf { it.second } < config.maxDroppedFramesInWindow) return Decision.Stay

        val currentRank = StreamQuality.rankOf(current.quality)
        // Une autre version de même qualité poserait le même problème au décodeur.
        val target = lower { StreamQuality.rankOf(it.quality) < currentRank } ?: return Decision.Stay
        decoderCeilingRank = minOf(decoderCeilingRank ?: currentRank, currentRank)
        return switchTo(target, Reason.DECODER)
    }

    /** À appeler régulièrement pendant la lecture. @param bandwidthBps débit mesuré, en bits/s. */
    fun evaluate(bandwidthBps: Long?): Decision {
        if (!isAdaptive) return Decision.Stay
        val now = clock()
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > config.rebufferWindowMillis) rebuffers.removeFirst()

        val buffering = bufferingSince
        val trial = trialFrom
        // Meilleure version à l'essai qui tarde à démarrer : retour à la version qui fonctionnait.
        if (trial != null && now - stableSince >= config.maxTrialStartMillis) {
            markFailed(current)
            return switchTo(ladder[trial], Reason.BUFFERING)
        }
        val tolerated = if (played) config.maxContinuousBufferingMillis else config.maxInitialBufferingMillis
        val stalled = buffering != null && now - buffering >= tolerated
        val unstable = rebuffers.size >= config.maxRebuffersInWindow
        if (stalled || unstable) {
            val target = lower() ?: return Decision.Stay
            markFailed(current)
            return switchTo(target, Reason.BUFFERING)
        }

        if (buffering == null && rebuffers.isEmpty()) {
            val target = higher() ?: return Decision.Stay
            val stableFor = now - stableSince
            val proven = bandwidthBps != null && bandwidthBps >= requiredBitrate(target.quality) * config.upgradeHeadroom
            if ((proven && stableFor >= config.stableMillisBeforeUpgrade) || (played && stableFor >= config.stableMillisBeforeTrial)) {
                val from = index
                return switchTo(target, Reason.UPGRADE).also { trialFrom = from }
            }
        }
        return Decision.Stay
    }

    private fun lower(accept: (QualityOption) -> Boolean = { true }): QualityOption? {
        val now = clock()
        return ladder.drop(index + 1).firstOrNull { (cooldownUntil[it.channelId] ?: 0) <= now && accept(it) }
    }

    /**
     * Qualité juste au-dessus de la version courante (une de ses versions disponibles) ; jamais
     * plus haut tant que celle-ci est en échec : ce qui ne passe pas en HD ne passera pas en FHD.
     */
    private fun higher(): QualityOption? {
        val now = clock()
        val ceiling = decoderCeilingRank
        val currentRank = StreamQuality.rankOf(current.quality)
        val above = ladder.take(index).filter {
            val rank = StreamQuality.rankOf(it.quality)
            rank > currentRank && (ceiling == null || rank < ceiling) && (maxRank == null || rank <= maxRank)
        }
        val nextRank = above.lastOrNull()?.let { StreamQuality.rankOf(it.quality) } ?: return null
        return above.lastOrNull { StreamQuality.rankOf(it.quality) == nextRank && (cooldownUntil[it.channelId] ?: 0) <= now }
    }

    private fun switchTo(option: QualityOption, reason: Reason): Decision {
        index = ladder.indexOf(option)
        bufferingSince = null
        played = false
        trialFrom = null
        rebuffers.clear()
        droppedFrames.clear()
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
