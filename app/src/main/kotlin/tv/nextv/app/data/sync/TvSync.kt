package tv.nextv.app.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.nextv.core.log.Logger
import tv.nextv.core.sync.JoinResult
import tv.nextv.core.sync.SyncApplied
import tv.nextv.core.sync.SyncInvite
import tv.nextv.core.sync.SyncStatus
import tv.nextv.core.sync.TvSyncEngine

/**
 * Télés reliées (Réglages › Télés reliées) : quand synchroniser. Quelques secondes après un changement sur la télé
 * (favori, « Déjà vu », réglage…), à la fermeture du lecteur (reprise de lecture), au retour dans l'appli, et toutes
 * les [PERIOD_MILLIS] pour voir arriver ce que les autres ont changé. Jamais pendant une lecture : rien ne doit gêner
 * le lecteur, surtout sur une petite Fire TV.
 *
 * @param changes émet à chaque changement d'un élément partagé sur la télé.
 * @param onPlaylistActivated playlist venue d'une autre télé, choisie parce que celle-ci n'en avait aucune.
 */
class TvSync(
    private val engine: TvSyncEngine,
    private val scope: CoroutineScope,
    private val playing: StateFlow<Boolean>,
    private val changes: Flow<Unit>,
    private val logger: Logger,
    private val onPlaylistActivated: (Long) -> Unit = {},
) {
    val status: StateFlow<SyncStatus> = engine.status

    /** Délai avant la prochaine synchronisation demandée (les demandes rapprochées n'en font qu'une). */
    private val requests = Channel<Long>(Channel.CONFLATED)

    fun start() {
        scope.launch(Dispatchers.IO) {
            engine.load()
            launch { changes.collect { request(CHANGE_DELAY_MILLIS) } }
            launch { playing.drop(1).filter { !it }.collect { request(AFTER_PLAYBACK_DELAY_MILLIS) } }
            request(START_DELAY_MILLIS)
            while (isActive) {
                val wait = withTimeoutOrNull(PERIOD_MILLIS) { requests.receive() } ?: 0L
                delay(wait)
                requests.tryReceive()
                if (!engine.isLinked) continue
                playing.first { !it }
                runSync()
                // Demandes nées des écritures de la synchronisation elle-même.
                delay(SETTLE_MILLIS)
                requests.tryReceive()
            }
        }
    }

    /** Synchronisation dans [delayMillis] (retour dans l'appli, changement). */
    fun request(delayMillis: Long = CHANGE_DELAY_MILLIS) {
        requests.trySend(delayMillis)
    }

    private suspend fun runSync(): SyncApplied? = try {
        engine.sync()?.also { applied -> applied.activatedPlaylist?.let(onPlaylistActivated) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Base ou réglages illisibles : réessayé au prochain passage, sans faire planter l'appli.
        logger.warn(TAG, "Synchronisation des télés reliées interrompue", e)
        null
    }

    /** « Synchroniser maintenant ». */
    suspend fun syncNow(): SyncApplied? = runSync()

    suspend fun createInvite(): SyncInvite = engine.createInvite()

    suspend fun awaitAnswer(invite: SyncInvite): String? = engine.awaitAnswer(invite).also { if (it != null) request(ANSWER_DELAY_MILLIS) }

    suspend fun join(code: String): JoinResult = engine.join(code)

    suspend fun leave() = engine.leave()

    companion object {
        private const val TAG = "NixTV/Sync"
        const val START_DELAY_MILLIS = 20_000L
        const val CHANGE_DELAY_MILLIS = 5_000L
        const val AFTER_PLAYBACK_DELAY_MILLIS = 3_000L
        const val RESUME_DELAY_MILLIS = 2_000L

        /** La télé qui vient de saisir le code dépose son document juste après sa réponse. */
        const val ANSWER_DELAY_MILLIS = 8_000L
        const val SETTLE_MILLIS = 2_000L
        const val PERIOD_MILLIS = 5 * 60_000L
    }
}
