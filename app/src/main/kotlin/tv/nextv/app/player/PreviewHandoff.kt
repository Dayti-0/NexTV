package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branchements d'un flux déjà ouvert : l'aperçu de l'accueil ouvre le sien sans rien y brancher ; le lecteur
 * plein écran qui le reprend y pose ensuite sa mesure du débit ([listener]) et la mémoire du direct
 * ([recording], pour revenir en arrière), sans rouvrir la connexion.
 */
@OptIn(UnstableApi::class)
class StreamTaps : TransferListener {
    @Volatile
    var listener: TransferListener? = null

    @Volatile
    var recording: LiveRecording? = null

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
}

/**
 * Aperçu d'une chaîne en cours de lecture, confié au lecteur plein écran quand on ouvre cette chaîne : il
 * continue sur le même lecteur et la même connexion, sans le temps de réouverture du flux.
 */
class PreviewHandoff(
    val player: ExoPlayer,
    val request: PlaybackRequest,
    val taps: StreamTaps,
    /** Décodeurs en service (relevés pendant l'aperçu), pour les infos du lecteur. */
    val videoDecoder: String?,
    val audioDecoder: String?,
)

/**
 * Où l'accueil dépose l'aperçu au moment d'ouvrir sa chaîne, et où le lecteur plein écran le prend. Jamais
 * pris (autre écran ouvert, lecture qui ne démarre pas) : il est libéré au bout de [HANDOFF_MILLIS], sa
 * connexion ne doit pas rester ouverte (une seule par abonnement, souvent). Fil principal.
 */
class PreviewHandoffSlot(private val scope: CoroutineScope) {
    private var waiting: PreviewHandoff? = null
    private var expiry: Job? = null

    fun offer(handoff: PreviewHandoff) {
        discard()
        waiting = handoff
        expiry = scope.launch(Dispatchers.Main) {
            delay(HANDOFF_MILLIS)
            if (waiting === handoff) discard()
        }
    }

    /** L'aperçu déposé, à reprendre ou à libérer par l'appelant ; `null` s'il n'y en a pas. */
    fun take(): PreviewHandoff? {
        expiry?.cancel()
        expiry = null
        return waiting.also { waiting = null }
    }

    /** Libère l'aperçu déposé (personne ne le reprendra). */
    fun discard() {
        take()?.player?.release()
    }

    private companion object {
        const val HANDOFF_MILLIS = 4_000L
    }
}
