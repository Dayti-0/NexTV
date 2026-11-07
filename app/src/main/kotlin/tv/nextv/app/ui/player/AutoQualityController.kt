package tv.nextv.app.ui.player

import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.stream.QualityOption
import tv.nextv.core.stream.QualitySwitcher
import tv.nextv.core.stream.QualityTarget

/**
 * Qualité automatique d'une chaîne proposée en plusieurs versions (SD, HD, FHD, 4K) : version de
 * départ (réglages, dernière qui a fonctionné), passage à une version inférieure sur erreur,
 * blocage ou images perdues, retour à une meilleure quand le débit le permet.
 */
internal class AutoQualityController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val session: PlaybackSession,
    private val resources: Resources,
    private val host: Host,
) {
    /** Ce que la qualité automatique demande à l'écran du lecteur. */
    interface Host {
        /** Lit cette autre version de la chaîne, avec [notice] (« Qualité réduite : HD »…). */
        suspend fun playVariant(variant: ChannelEntity, notice: String)
    }

    private var switcher: QualitySwitcher? = null
    private var autoQuality = true

    /** Versions 4K écartées (réglage « Masquer la 4K »). */
    private var hideUhd = false

    /** Qualité que l'appareil n'a pas su décoder (images perdues), retenue d'une chaîne à l'autre. */
    private var decoderCeilingRank: Int? = null

    private var workingQualityJob: Job? = null

    /** Versions de la chaîne en cours (vide : une seule version). */
    private var options: List<QualityOption> = emptyList()

    /** Prochaine ouverture sans la qualité retenue : la qualité visée vient d'être changée. */
    private var skipRemembered = false

    init {
        scope.launch { container.settings.autoQuality.collect { autoQuality = it } }
        scope.launch { container.settings.hideUhd.collect { hideUhd = it } }
    }

    /** Version de départ choisie pour la chaîne en cours (`null` : une seule version). */
    val currentChannelId: Long? get() = switcher?.current?.channelId

    /**
     * Versions de la chaîne [primary] et version de départ : la dernière qualité qui a fonctionné,
     * dans la limite de la qualité visée (réglages).
     *
     * @return version par laquelle commencer.
     */
    suspend fun prepare(requested: ChannelEntity, primary: ChannelEntity, isLive: Boolean): Long {
        val dao = container.channelDao
        val variants = if (isLive && primary.variantKey.isNotEmpty()) dao.variants(primary.sourceId, primary.variantKey) else emptyList()
        // 4K écartée quand l'écran ou le décodeur ne la suivent pas (image saccadée), ou masquée dans les réglages.
        val uhdAllowed = !hideUhd && container.playerFactory.supportsUhd
        val options = variants.filter { it.streamSupport != StreamSupport.UNSUPPORTED.name }
            .map { QualityOption(it.id, StreamQuality.entries.firstOrNull { q -> q.name == it.quality }) }
            .let { all -> if (uhdAllowed) all else all.filter { it.quality != StreamQuality.UHD }.ifEmpty { all } }
        // Chaîne ouverte sur sa meilleure version : départ par la qualité qui a fonctionné la dernière fois.
        val remembered = if (autoQuality && requested.isPrimary && options.size > 1 && !skipRemembered) {
            container.settings.workingQuality(primary.sourceId, primary.variantKey)?.let { name -> StreamQuality.entries.firstOrNull { it.name == name } }
        } else {
            null
        }
        val rememberedStart = remembered?.let { quality ->
            val rank = StreamQuality.rankOf(quality)
            options.sortedByDescending { StreamQuality.rankOf(it.quality) }.firstOrNull { StreamQuality.rankOf(it.quality) <= rank }?.channelId
        }
        val preferredId = rememberedStart
            ?: if (options.any { it.channelId == requested.id }) requested.id else options.firstOrNull()?.channelId ?: requested.id
        // Qualité visée (réglages) : au plus HD, ou SD pour économiser la connexion.
        skipRemembered = false
        this.options = if (options.size > 1) options else emptyList()
        val qualityTarget = container.settings.qualityTarget.first()
        val startId = if (options.size > 1) qualityTarget.start(options, preferredId) else preferredId
        switcher = if (options.size > 1) {
            QualitySwitcher(
                options,
                startId,
                enabled = autoQuality,
                clock = System::currentTimeMillis,
                decoderCeilingRank = decoderCeilingRank,
                maxRank = qualityTarget.maxRank(options),
            )
        } else {
            null
        }
        return startId
    }

    /** Lecture sans choix de version (replay) : pas de qualité automatique. */
    fun reset() {
        switcher = null
        options = emptyList()
    }

    /**
     * Qualité visée changée pendant la lecture (menu Options) : vrai si la chaîne en cours a une
     * version qui y correspond mieux que [playingId]. Sa prochaine ouverture part alors de la
     * qualité visée, sans la dernière qualité qui a fonctionné.
     */
    fun changesVersion(target: QualityTarget, playingId: Long?): Boolean {
        if (options.size <= 1) return false
        val best = options.maxBy { StreamQuality.rankOf(it.quality) }.channelId
        if (target.start(options, best) == playingId) return false
        skipRemembered = true
        return true
    }

    /** Version qui joue sans erreur une minute durant : la chaîne commencera par elle la prochaine fois. */
    fun rememberWhenStable(variant: ChannelEntity) {
        workingQualityJob?.cancel()
        val quality = variant.quality
        if (switcher == null || quality == null || variant.kind != ContentKind.LIVE.name || variant.variantKey.isEmpty()) return
        workingQualityJob = scope.launch {
            delay(WORKING_QUALITY_MILLIS)
            if (session.playing?.id == variant.id && state.value.error == null) {
                container.settings.rememberWorkingQuality(variant.sourceId, variant.variantKey, quality)
            }
        }
    }

    fun onBufferingStarted() {
        switcher?.onBufferingStarted()
    }

    fun onReady() {
        switcher?.onReady()
    }

    /**
     * Surveillance de la lecture : [dropped] images perdues depuis le dernier passage (l'appareil ne
     * suit pas cette qualité), puis débit mesuré (blocage, ou retour à une meilleure qualité).
     */
    fun check(dropped: Int) {
        // L'appareil ne suit pas cette qualité (4K sur une petite box…) : version inférieure si elle existe.
        if (dropped > 0) {
            val decision = switcher?.onDroppedFrames(dropped)
            if (decision is QualitySwitcher.Decision.Switch) {
                decoderCeilingRank = switcher?.decoderCeilingRank
                switchTo(decision)
                return
            }
        }
        val estimate = state.value.bandwidthMbps?.let { (it * 1_000_000).toLong() }
        val decision = switcher?.evaluate(estimate)
        if (decision is QualitySwitcher.Decision.Switch) switchTo(decision)
    }

    /** Lecture impossible : autre version de la même chaîne, de qualité inférieure, si elle existe. @return vrai si elle est lancée. */
    fun onError(): Boolean {
        val decision = switcher?.onError()
        if (decision !is QualitySwitcher.Decision.Switch) return false
        switchTo(decision)
        return true
    }

    private fun switchTo(decision: QualitySwitcher.Decision.Switch) {
        val notice = resources.getString(
            when (decision.reason) {
                QualitySwitcher.Reason.UPGRADE -> R.string.player_quality_up
                QualitySwitcher.Reason.DECODER -> R.string.player_quality_decoder
                QualitySwitcher.Reason.ERROR, QualitySwitcher.Reason.BUFFERING -> R.string.player_quality_down
            },
            decision.option.quality?.label ?: resources.getString(R.string.player_quality_unknown),
        )
        container.logger.info(TAG, "Qualité ${decision.reason} → ${decision.option.quality}")
        scope.launch {
            val variant = container.channelDao.get(decision.option.channelId) ?: return@launch
            host.playVariant(variant, notice)
        }
    }

    private companion object {
        const val TAG = "Player"
        const val WORKING_QUALITY_MILLIS = 60_000L
    }
}
