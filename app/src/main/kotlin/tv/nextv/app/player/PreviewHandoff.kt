package tv.nextv.app.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Branchements d'un flux direct ouvert : la mesure du débit ([listener]) et la mémoire du direct ([recording],
 * pour revenir en arrière) du lecteur plein écran s'y posent et s'en retirent sans rouvrir la connexion, quand le
 * flux passe de l'aperçu de l'accueil au plein écran, et inversement.
 */
@OptIn(UnstableApi::class)
class StreamTaps : TransferListener {
    @Volatile
    var listener: TransferListener? = null

    @Volatile
    var recording: LiveRecording? = null

    /**
     * Flux affiché en aperçu : peu de nouvelles tentatives après une coupure ([retryPolicy]). Un flux qui retombe
     * sans cesse repartait chaque fois de ses premières secondes, et l'aperçu tournait en boucle sur elles.
     */
    @Volatile
    var preview: Boolean = false

    private val retries = ArrayDeque<Long>()

    /** Nouvelles tentatives après une coupure du flux : celles d'ExoPlayer, limitées pendant l'aperçu. */
    val retryPolicy: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val delayMs = super.getRetryDelayMsFor(loadErrorInfo)
            if (!preview || delayMs == C.TIME_UNSET) return delayMs
            return if (allowPreviewRetry(SystemClock.elapsedRealtime())) delayMs else C.TIME_UNSET
        }
    }

    /** Aperçu : au plus [PREVIEW_MAX_RETRIES] reprises par [PREVIEW_RETRY_WINDOW_MILLIS] ; au-delà, l'aperçu s'efface. */
    internal fun allowPreviewRetry(now: Long): Boolean = synchronized(retries) {
        while (retries.isNotEmpty() && now - retries.first() > PREVIEW_RETRY_WINDOW_MILLIS) retries.removeFirst()
        if (retries.size >= PREVIEW_MAX_RETRIES) return false
        retries.addLast(now)
        true
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        listener?.onTransferInitializing(source, dataSpec, isNetwork)
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        listener?.onTransferStart(source, dataSpec, isNetwork)
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        listener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        listener?.onTransferEnd(source, dataSpec, isNetwork)
    }

    private companion object {
        const val PREVIEW_MAX_RETRIES = 2
        const val PREVIEW_RETRY_WINDOW_MILLIS = 60_000L
    }
}

/**
 * Direct en cours de lecture confié d'un écran à l'autre : de l'aperçu de l'accueil au plein écran quand on ouvre
 * sa chaîne ([toPreview] faux), du plein écran à l'aperçu quand on revient à l'accueil ([toPreview] vrai). Le même
 * lecteur continue sur la même connexion : ni réouverture du flux, ni nouvelle attente d'image.
 */
@OptIn(UnstableApi::class)
class PreviewHandoff(
    val player: ExoPlayer,
    val request: PlaybackRequest,
    val taps: StreamTaps,
    /** Mémoire tampon du lecteur, réglée pour l'écran qui le reprend. */
    val buffer: LiveLoadControl,
    /** Chaînes que ce direct montre (version ouverte, meilleure version, version qui joue). */
    val channelIds: Set<Long>,
    val toPreview: Boolean,
    /** Décodeurs en service, pour les infos du lecteur. */
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
) {
    /** Réglages de l'aperçu (petite mémoire tampon, peu de reprises) ou du plein écran. */
    fun setPreview(preview: Boolean) {
        taps.preview = preview
        buffer.preview = preview
    }
}

/**
 * Où un écran dépose le direct qu'il confie, et où l'autre le prend. Jamais pris (autre écran ouvert, lecture qui
 * ne démarre pas) : il est libéré au bout de [HANDOFF_MILLIS], sa connexion ne doit pas rester ouverte (une seule
 * par abonnement, souvent). Fil principal.
 */
class PreviewHandoffSlot(private val scope: CoroutineScope) {
    private val mutableWaiting = MutableStateFlow<PreviewHandoff?>(null)

    /** Direct déposé, pas encore pris. */
    val waiting: StateFlow<PreviewHandoff?> = mutableWaiting.asStateFlow()

    private var expiry: Job? = null

    fun offer(handoff: PreviewHandoff) {
        discard()
        mutableWaiting.value = handoff
        expiry = scope.launch(Dispatchers.Main) {
            delay(HANDOFF_MILLIS)
            if (mutableWaiting.value === handoff) discard()
        }
    }

    /** Le direct déposé, à reprendre ou à libérer par l'appelant ; `null` s'il n'y en a pas. */
    fun take(): PreviewHandoff? {
        expiry?.cancel()
        expiry = null
        return mutableWaiting.value.also { mutableWaiting.value = null }
    }

    /** Le direct déposé s'il va dans ce sens et montre la chaîne [channelId] ; sinon `null` (il reste déposé). */
    fun takeFor(channelId: Long, toPreview: Boolean): PreviewHandoff? {
        val handoff = mutableWaiting.value ?: return null
        if (handoff.toPreview != toPreview || channelId !in handoff.channelIds) return null
        return take()
    }

    /** Libère le direct déposé (personne ne le reprendra). */
    fun discard() {
        take()?.player?.release()
    }

    private companion object {
        const val HANDOFF_MILLIS = 4_000L
    }
}

/**
 * Lecteurs repris en pleine lecture par un nouvel écran : sa vidéo ne doit pas les faire repartir au direct (ce
 * qui rouvrirait la connexion), comme elle le fait pour un aperçu qui jouait sans image. Fil principal.
 */
object ContinuingPlayers {
    private val players: MutableSet<Player> = Collections.newSetFromMap(WeakHashMap())

    fun mark(player: Player) {
        players += player
    }

    /** Vrai (une seule fois) si [player] vient d'être repris. */
    fun consume(player: Player): Boolean = players.remove(player)
}
