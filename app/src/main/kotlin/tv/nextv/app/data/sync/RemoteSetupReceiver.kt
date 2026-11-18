package tv.nextv.app.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.nextv.core.log.Logger
import tv.nextv.core.sync.RemoteConfig
import tv.nextv.core.sync.RemoteSetupLink
import tv.nextv.core.sync.RemoteSetupOffer
import java.io.IOException

/** Code de configuration à distance affiché sur cette télé. */
sealed interface RemoteReceiveState {
    /** Aucun écran ne montre le code. */
    data object Idle : RemoteReceiveState

    /** Tirage du code. */
    data object Opening : RemoteReceiveState

    /** Code affiché, en attente de la configuration. [refused] : la précédente n'a pas pu s'installer (adresse refusée). */
    data class Waiting(val offer: RemoteSetupOffer, val refused: Boolean = false) : RemoteReceiveState

    /** Configuration reçue de [from], en cours d'installation. */
    data class Installing(val from: String) : RemoteReceiveState

    /** Installée : la playlist se charge. */
    data class Installed(val from: String) : RemoteReceiveState

    /** Pas d'Internet (ou filebin injoignable) : nouvel essai dans quelques secondes. */
    data object Offline : RemoteReceiveState
}

/**
 * Télé configurée à distance : tant qu'un écran affiche le code ([acquire] / [release] : écran de bienvenue, première
 * étape de la configuration rapide, page des réglages), un code est tiré, renouvelé quand il expire, et la
 * configuration reçue est installée ([installed] : identifiant de la playlist, à charger).
 *
 * @param enabled faux pendant les tests de l'appli (Robolectric) : rien n'est déposé sur filebin.
 */
class RemoteSetupReceiver(
    private val link: RemoteSetupLink,
    private val install: suspend (RemoteConfig) -> Long,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val enabled: Boolean = true,
    private val retryMillis: Long = RETRY_MILLIS,
    private val cleanupMillis: Long = RemoteSetupLink.CLEANUP_MILLIS,
) {
    private val mutableState = MutableStateFlow<RemoteReceiveState>(RemoteReceiveState.Idle)
    val state: StateFlow<RemoteReceiveState> = mutableState.asStateFlow()

    private val mutableInstalled = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val installed: SharedFlow<Long> = mutableInstalled.asSharedFlow()

    private var users = 0
    private var job: Job? = null

    @Synchronized
    fun acquire() {
        users++
        if (users == 1 && enabled && job?.isActive != true) job = scope.launch { run() }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        // Installation en cours : elle va jusqu'au bout (l'écran qui montrait le code se ferme justement pour elle).
        if (mutableState.value is RemoteReceiveState.Installing || mutableState.value is RemoteReceiveState.Installed) return
        job?.cancel()
        job = null
        mutableState.value = RemoteReceiveState.Idle
    }

    private suspend fun run() {
        var refused = false
        while (true) {
            mutableState.value = RemoteReceiveState.Opening
            val offer = try {
                link.open()
            } catch (e: IOException) {
                mutableState.value = RemoteReceiveState.Offline
                delay(retryMillis)
                continue
            }
            mutableState.value = RemoteReceiveState.Waiting(offer, refused)
            var keepPage = false
            try {
                // Code expiré sans rien recevoir : un autre.
                val config = link.awaitConfig(offer) ?: continue
                mutableState.value = RemoteReceiveState.Installing(config.from)
                val installedId = try {
                    install(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger?.warn(TAG, "Configuration à distance refusée", e)
                    null
                }
                runCatching { link.reply(offer, installedId != null) }
                // La page attend que l'autre télé lise la réponse (elle la supprime alors) ; supprimée ensuite sinon.
                keepPage = true
                scope.launch {
                    delay(cleanupMillis)
                    link.close(offer)
                }
                if (installedId == null) {
                    // Nouveau code pour un nouvel essai.
                    refused = true
                    continue
                }
                logger?.info(TAG, "Configuration à distance installée")
                mutableState.value = RemoteReceiveState.Installed(config.from)
                mutableInstalled.tryEmit(installedId)
                return
            } finally {
                if (!keepPage) link.close(offer)
            }
        }
    }

    private companion object {
        const val TAG = "NixTV/RemoteSetup"
        const val RETRY_MILLIS = 15_000L
    }
}
