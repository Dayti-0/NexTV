package tv.nextv.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.nextv.core.epg.EpgFeedProvider
import tv.nextv.core.epg.EpgLoader
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.ProgressListener
import tv.nextv.core.log.Logger
import tv.nextv.core.log.Redactor
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgStatus
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceStatus
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.net.RemoteUrlValidator
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.LoadedPlaylist
import tv.nextv.core.source.PlaylistLoaderRegistry
import java.util.concurrent.ConcurrentHashMap

sealed interface RefreshResult {
    data class Success(val channelCount: Int, val warnings: Set<SourceWarning>, val epg: EpgStatus) : RefreshResult
    data class Failure(val error: SourceError) : RefreshResult
    data object NotFound : RefreshResult
}

/**
 * Point d'entrée de la gestion des sources : ajout, modification, suppression, actualisation.
 *
 * - Un échec d'actualisation conserve les chaînes et le guide précédents ; seule l'erreur est enregistrée.
 * - L'échec du guide EPG n'invalide pas la playlist (statuts distincts).
 * - Les identifiants sont stockés à part, chiffrés, et déclarés au [Redactor] pour être masqués des journaux.
 * - Deux actualisations d'une même source ne s'exécutent jamais en parallèle.
 */
class SourceRepository(
    private val store: PlaylistStore,
    private val credentialStore: CredentialStore,
    private val loaders: PlaylistLoaderRegistry,
    private val epgLoader: EpgLoader,
    private val redactor: Redactor,
    private val logger: Logger = Logger.None,
    private val documentAccess: DocumentAccess = DocumentAccess.None,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Guide commun à toutes les playlists (téléchargé et mis en cache). S'il est fourni, il remplace
     * les guides configurés par playlist ou annoncés par leur en-tête.
     */
    private val epgFeed: EpgFeedProvider? = null,
    /** Jours de replay des chaînes, par identifiant de flux (comptes Xtream) ; `null` : inconnus. */
    private val catchupDays: suspend (PlaylistSource) -> Map<String, Int>? = { null },
    /**
     * Attentes avant chaque nouvel essai d'un téléchargement coupé en route (connexion fermée par le serveur
     * ou le réseau, délai dépassé) : une coupure passagère ne doit pas arrêter le chargement.
     */
    private val retryDelaysMillis: List<Long> = RETRY_DELAYS_MILLIS,
) {

    val sources: Flow<List<PlaylistSource>> = store.observeSources()

    private val refreshingIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Sources en cours d'actualisation. */
    val refreshing: StateFlow<Set<Long>> = refreshingIds.asStateFlow()

    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** Déclare au [Redactor] les identifiants déjà enregistrés (à appeler au démarrage). */
    suspend fun registerStoredSecrets() {
        for (source in store.getSources()) {
            if (source.hasCredentials) credentialStore.get(source.id)?.let(::registerSecrets)
        }
    }

    /** Au démarrage : noms enregistrés qui sont des adresses (avec identifiants) réduits au nom du serveur. */
    suspend fun cleanStoredNames() {
        for (source in store.getSources()) {
            val name = SourceInputValidator.sanitizeName(source.name)
            if (name.isNotEmpty() && name != source.name) {
                store.updateSourceDefinition(source.copy(name = name))
                logger.info(TAG, "Source ${source.id} : adresse utilisée comme nom remplacée par le nom du serveur")
            }
        }
    }

    suspend fun getSource(id: Long): PlaylistSource? = store.getSource(id)

    /** Identifiants d'une source, pour l'authentification des flux lors de la lecture. */
    suspend fun credentialsFor(id: Long): Credentials? {
        val source = store.getSource(id) ?: return null
        return if (source.hasCredentials) credentialStore.get(id)?.also(::registerSecrets) else null
    }

    /**
     * @return l'identifiant de la nouvelle source (non encore actualisée).
     * @throws InvalidSourceInputException
     */
    suspend fun add(input: SourceInput): Long {
        val valid = validate(input)
        val credentials = (valid.credentials as? CredentialsChange.Replace)?.credentials
        val now = clock()
        val id = store.insertSource(
            PlaylistSource(
                name = valid.name,
                type = valid.type,
                location = valid.location,
                epg = valid.epg,
                userAgent = valid.userAgent,
                hasCredentials = credentials != null,
                createdAtMillis = now,
                updatedAtMillis = now,
                mirrors = valid.mirrors,
            ),
        )
        if (credentials != null) {
            registerSecrets(credentials)
            credentialStore.put(id, credentials)
        }
        logger.info(TAG, "Source $id ajoutée (${valid.type.id}, ${describe(valid.location)})")
        return id
    }

    /**
     * @return vrai si le contenu doit être rechargé (emplacement, guide, User-Agent ou identifiants
     * modifiés, ou serveurs de secours changés alors que le dernier chargement a échoué).
     * @throws InvalidSourceInputException
     * @throws NoSuchElementException si la source n'existe pas.
     */
    suspend fun update(id: Long, input: SourceInput): Boolean {
        val existing = store.getSource(id) ?: throw NoSuchElementException("Source $id")
        val valid = validate(input)
        val change = valid.credentials
        val hasCredentials = when (change) {
            CredentialsChange.Keep -> existing.hasCredentials && valid.location is SourceLocation.Remote
            CredentialsChange.Remove -> false
            is CredentialsChange.Replace -> true
        }
        when {
            change is CredentialsChange.Replace -> {
                registerSecrets(change.credentials)
                credentialStore.put(id, change.credentials)
            }
            !hasCredentials && existing.hasCredentials -> credentialStore.delete(id)
        }
        val updated = existing.copy(
            name = valid.name,
            type = valid.type,
            location = valid.location,
            epg = valid.epg,
            userAgent = valid.userAgent,
            hasCredentials = hasCredentials,
            updatedAtMillis = clock(),
            mirrors = valid.mirrors,
        )
        store.updateSourceDefinition(updated)
        releaseUnusedDocuments(listOfNotNull(existing.location, existing.epg.location))
        logger.info(TAG, "Source $id modifiée")
        return existing.location != updated.location ||
            existing.epg != updated.epg ||
            existing.userAgent != updated.userAgent ||
            existing.type != updated.type ||
            change is CredentialsChange.Replace ||
            existing.hasCredentials != hasCredentials ||
            (existing.mirrors != updated.mirrors && existing.status.error != null)
    }

    suspend fun delete(id: Long) {
        lockFor(id).withLock {
            val existing = store.getSource(id) ?: return
            store.deleteSource(id)
            if (existing.hasCredentials) credentialStore.delete(id)
            releaseUnusedDocuments(listOfNotNull(existing.location, existing.epg.location))
            logger.info(TAG, "Source $id supprimée")
        }
        locks.remove(id)
    }

    /**
     * Recharge la playlist puis, si demandé, le guide EPG.
     * @param progress avancement réel de chaque étape (octets reçus, chaînes enregistrées…).
     */
    suspend fun refresh(id: Long, includeEpg: Boolean = true, progress: ProgressListener? = null): RefreshResult =
        withRefreshLock(id) { guarded(id) { refreshLocked(id, includeEpg, progress) } }

    /**
     * Une actualisation ne fait jamais planter l'application : une erreur inattendue (stockage plein, base
     * illisible…) devient un échec, les données précédentes restant en place.
     */
    private suspend fun guarded(id: Long, block: suspend () -> RefreshResult): RefreshResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val error = SourceError.of(e)
        logger.warn(TAG, "Actualisation de la source $id interrompue : ${error.code}", e)
        RefreshResult.Failure(error)
    }

    /** Enregistre l'échec dans le statut de la source ; sans effet si même cela est impossible (stockage plein). */
    private suspend fun recordFailure(id: Long, status: SourceStatus) {
        try {
            store.updateStatus(id, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Statut de la source $id non enregistré", e)
        }
    }

    private suspend fun refreshLocked(id: Long, includeEpg: Boolean, progress: ProgressListener?): RefreshResult {
        val source = store.getSource(id) ?: return RefreshResult.NotFound
        val credentials = if (source.hasCredentials) credentialStore.get(id)?.also(::registerSecrets) else null
        val attemptAt = clock()

        suspend fun failed(error: SourceError, cause: Throwable? = null): RefreshResult {
            logger.warn(TAG, "Échec de l'actualisation de la source $id (${describe(source.location)}) : ${error.code}", cause)
            recordFailure(id, source.status.copy(lastAttemptAtMillis = attemptAt, error = error))
            return RefreshResult.Failure(error)
        }

        val loaded = try {
            coroutineScope {
                // Chaînes avec replay : demandées au serveur pendant le téléchargement de la playlist.
                val archive = async { archiveDays(source) }
                val playlist = loadWithRetries(source, credentials, progress)
                archive.await()?.let { days -> playlist.copy(channels = Catchup.annotate(playlist.channels, days)) } ?: playlist
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed((e as? SourceException)?.error ?: SourceError.Unknown(e.javaClass.simpleName), e)
        } catch (e: OutOfMemoryError) {
            // Playlist trop grosse pour la mémoire de l'appareil : la précédente reste en place.
            return failed(SourceError.OutOfMemory, e)
        }

        // Réponse coupée en route sans erreur du réseau (certains serveurs ferment la connexion au milieu de la
        // liste) : une playlist qui perd d'un coup plus de la moitié de ses entrées n'est pas enregistrée, la
        // précédente reste. Si la suivante est aussi courte, c'est bien la nouvelle liste : elle est acceptée.
        // Pas de contrôle quand la playlist vient d'être modifiée (autre adresse, autre contenu attendu).
        val previousCount = source.status.channelCount
        val lastSuccess = source.status.lastSuccessAtMillis
        if (lastSuccess != null && source.updatedAtMillis <= lastSuccess && source.status.error !is SourceError.Incomplete &&
            previousCount >= DROP_CHECK_MIN_ENTRIES && loaded.channels.size < previousCount * DROP_CHECK_RATIO
        ) {
            return failed(SourceError.Incomplete(received = loaded.channels.size, expected = previousCount))
        }

        // Le serveur n'a accepté qu'un User-Agent de lecteur courant : il est retenu pour la source
        // (flux compris) et visible dans l'éditeur.
        val fallbackUserAgent = loaded.userAgent
        if (fallbackUserAgent != null && source.userAgent.isNullOrBlank()) {
            store.updateSourceDefinition(source.copy(userAgent = fallbackUserAgent))
            logger.info(TAG, "Source $id : User-Agent de repli retenu")
        }

        val total = loaded.channels.size.toLong()
        progress?.onProgress(LoadProgress(LoadStep.SAVING, 0, total))
        try {
            store.replaceChannels(id, loaded.channels) { saved -> progress?.onProgress(LoadProgress(LoadStep.SAVING, saved.toLong(), total)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Stockage plein, base illisible : l'écriture est annulée d'un bloc, les chaînes précédentes restent.
            return failed(SourceError.of(e), e)
        }
        progress?.onProgress(LoadProgress(LoadStep.SAVING, total, total))
        var status = source.status.copy(
            lastAttemptAtMillis = attemptAt,
            lastSuccessAtMillis = clock(),
            channelCount = loaded.channels.size,
            error = null,
            warnings = loaded.warnings,
            headerEpgUrls = loaded.epgUrls,
        )
        store.updateStatus(id, status)
        logger.info(TAG, "Source $id : ${loaded.channels.size} chaînes, avertissements ${loaded.warnings}")

        if (includeEpg) {
            val epg = importEpg(source, credentials, loaded.epgUrls, status.epg, channelsChanged = true, force = false, progress = progress)
            status = status.copy(epg = epg.status)
            store.updateStatus(id, status)
        }
        return RefreshResult.Success(loaded.channels.size, loaded.warnings, status.epg)
    }

    /**
     * Télécharge la playlist ; une coupure passagère (connexion fermée en route, délai dépassé) est retentée
     * après une courte attente, au lieu d'afficher « Chargement impossible » au premier incident.
     */
    private suspend fun loadWithRetries(source: PlaylistSource, credentials: Credentials?, progress: ProgressListener?): LoadedPlaylist {
        var attempt = 0
        while (true) {
            try {
                return loaders.get(source.type).load(source, credentials, progress)
            } catch (e: SourceException) {
                if (attempt >= retryDelaysMillis.size || !isTransient(e.error)) throw e
                logger.info(TAG, "Téléchargement de la source ${source.id} coupé (${e.error.code} ${e.error.detail.orEmpty()}) : nouvel essai ${attempt + 1}")
                delay(retryDelaysMillis[attempt])
                attempt++
            }
        }
    }

    /** Coupure qui peut ne pas se reproduire : connexion fermée ou réinitialisée, délai dépassé. */
    private fun isTransient(error: SourceError): Boolean = error is SourceError.Network || error == SourceError.Timeout

    private suspend fun archiveDays(source: PlaylistSource): Map<String, Int>? = try {
        catchupDays(source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(TAG, "Replay de la source ${source.id} inconnu", e)
        null
    }

    /**
     * Met à jour le guide EPG. Avec un guide commun ([epgFeed]), il n'est retéléchargé que s'il est
     * trop ancien (ou si [force]) et réimporté seulement s'il a changé depuis le dernier import.
     */
    suspend fun refreshEpg(id: Long, force: Boolean = false, progress: ProgressListener? = null): RefreshResult =
        withRefreshLock(id) { guarded(id) { refreshEpgLocked(id, force, progress) } }

    private suspend fun refreshEpgLocked(id: Long, force: Boolean, progress: ProgressListener?): RefreshResult {
        val source = store.getSource(id) ?: return RefreshResult.NotFound
        val credentials = if (source.hasCredentials) credentialStore.get(id)?.also(::registerSecrets) else null
        val outcome = importEpg(
            source,
            credentials,
            source.status.headerEpgUrls,
            source.status.epg,
            channelsChanged = false,
            force = force,
            progress = progress,
        )
        val status = source.status.copy(epg = outcome.status)
        // Guide déjà à jour : rien n'est réécrit. Chaque écriture de la playlist fait relire les listes de chaînes
        // affichées, alors que seule l'heure de cette vérification aurait changé.
        if (!outcome.unchanged) store.updateStatus(id, status)
        return outcome.failure?.let { RefreshResult.Failure(it) }
            ?: RefreshResult.Success(status.channelCount, status.warnings, outcome.status)
    }

    /** Actualise toutes les sources, l'une après l'autre. */
    suspend fun refreshAll(): Map<Long, RefreshResult> =
        store.getSources().associate { it.id to refresh(it.id) }

    /**
     * Importe le guide. Une erreur inattendue (stockage plein…) devient un échec du guide seul : le guide
     * précédent reste en place, et la playlist n'est pas remise en cause.
     */
    private suspend fun importEpg(
        source: PlaylistSource,
        credentials: Credentials?,
        headerUrls: List<String>,
        previous: EpgStatus,
        channelsChanged: Boolean,
        force: Boolean,
        progress: ProgressListener?,
    ): EpgOutcome = try {
        importEpgOrThrow(source, credentials, headerUrls, previous, channelsChanged, force, progress)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val error = SourceError.of(e)
        logger.warn(TAG, "Import du guide de la source ${source.id} interrompu : ${error.code}", e)
        EpgOutcome(previous.copy(lastAttemptAtMillis = clock(), error = error), failure = error)
    }

    /** Écarte les programmes déjà écrits d'un import interrompu ; un échec ici n'en cache pas la cause. */
    private suspend fun abortQuietly(session: EpgImportSession) {
        withContext(NonCancellable) {
            try {
                session.abort()
            } catch (e: Exception) {
                logger.warn(TAG, "Programmes d'un import interrompu non retirés", e)
            }
        }
    }

    private suspend fun importEpgOrThrow(
        source: PlaylistSource,
        credentials: Credentials?,
        headerUrls: List<String>,
        previous: EpgStatus,
        channelsChanged: Boolean,
        force: Boolean,
        progress: ProgressListener?,
    ): EpgOutcome {
        epgFeed?.let { return importFromFeed(it, source, previous, channelsChanged, force, progress) }
        val locations = buildList {
            source.epg.location?.let(::add)
            if (source.epg.useHeaderUrls) {
                headerUrls.filter { RemoteUrlValidator.parseOrNull(it) != null }.forEach { add(SourceLocation.Remote(it)) }
            }
        }.distinct()

        val session = store.beginEpgImport(source.id)
        if (locations.isEmpty()) {
            // Aucun guide configuré : les programmes d'un ancien guide sont retirés.
            session.commit(emptyMap())
            return EpgOutcome(EpgStatus(), failure = null)
        }

        val attemptAt = clock()
        val channels = store.getEpgChannelRefs(source.id)
        val playlistUrl = (source.location as? SourceLocation.Remote)?.url
        val request = OpenRequest(
            purpose = RequestPurpose.EPG,
            // Les identifiants de la playlist ne sont présentés qu'à son propre serveur (cf. AuthScope).
            credentials = credentials.takeIf { playlistUrl != null },
            credentialsOrigin = playlistUrl,
            userAgent = source.userAgent,
        )
        val mapping = HashMap<Long, String>()
        val guideOnly = HashMap<String, String>()
        val covered = HashSet<String>()
        var programmes = 0
        var firstError: SourceError? = null
        var succeeded = 0

        try {
            for (location in locations) {
                try {
                    val result = epgLoader.load(location, request, channels, session, excludedEpgIds = covered, progress = progress)
                    result.mapping.forEach { (channelId, epgId) -> mapping.putIfAbsent(channelId, epgId) }
                    result.guideOnlyChannels.forEach { (epgId, name) -> guideOnly.putIfAbsent(epgId, name) }
                    covered += result.coveredEpgIds
                    programmes += result.programmeCount
                    succeeded++
                    logger.info(
                        TAG,
                        "Guide ${describe(location)} : ${result.programmeCount} programmes, ${result.mapping.size} chaînes associées",
                    )
                } catch (e: SourceException) {
                    firstError = firstError ?: e.error
                    logger.warn(TAG, "Échec du guide ${describe(location)} : ${e.error.code}", e)
                }
            }
            if (succeeded == 0) {
                abortQuietly(session)
                return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = firstError), failure = firstError)
            }
            session.commit(mapping, guideOnly.filterKeys { it !in mapping.values })
        } catch (e: Throwable) {
            abortQuietly(session)
            throw e
        }
        val status = EpgStatus(
            lastAttemptAtMillis = attemptAt,
            lastSuccessAtMillis = clock(),
            programmeCount = programmes,
            matchedChannelCount = mapping.size,
            error = firstError,
        )
        return EpgOutcome(status, failure = null)
    }

    private suspend fun importFromFeed(
        feedProvider: EpgFeedProvider,
        source: PlaylistSource,
        previous: EpgStatus,
        channelsChanged: Boolean,
        force: Boolean,
        progress: ProgressListener?,
    ): EpgOutcome {
        val attemptAt = clock()
        val feed = try {
            feedProvider.obtain(force, progress)
        } catch (e: SourceException) {
            logger.warn(TAG, "Guide indisponible pour la source ${source.id} : ${e.error.code}", e)
            return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = e.error), failure = e.error)
        }
        val lastImport = previous.lastSuccessAtMillis
        val upToDate = !channelsChanged && !force && previous.error == null && lastImport != null &&
            lastImport >= feed.updatedAtMillis && attemptAt - lastImport < EPG_REIMPORT_MILLIS
        if (upToDate) {
            progress?.onProgress(LoadProgress(LoadStep.EPG_IMPORT, 1, 1))
            return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = feed.error), failure = null, unchanged = feed.error == previous.error)
        }

        val session = store.beginEpgImport(source.id)
        try {
            val result = epgLoader.importFile(feed.file, store.getEpgChannelRefs(source.id), session, progress = progress)
            session.commit(result.mapping, result.guideOnlyChannels)
            logger.info(TAG, "Guide : ${result.programmeCount} programmes, ${result.mapping.size} chaînes associées")
            val status = EpgStatus(
                lastAttemptAtMillis = attemptAt,
                lastSuccessAtMillis = clock(),
                programmeCount = result.programmeCount,
                matchedChannelCount = result.mapping.size,
                error = feed.error,
            )
            return EpgOutcome(status, failure = null)
        } catch (e: SourceException) {
            abortQuietly(session)
            logger.warn(TAG, "Import du guide impossible : ${e.error.code}", e)
            return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = e.error), failure = e.error)
        } catch (e: Throwable) {
            abortQuietly(session)
            throw e
        }
    }

    /**
     * @property failure erreur si aucun guide n'a pu être importé (le guide précédent est conservé).
     * @property unchanged le guide était à jour : rien n'a été importé, seul le moment de la vérification diffère.
     */
    private class EpgOutcome(val status: EpgStatus, val failure: SourceError?, val unchanged: Boolean = false)

    private fun validate(input: SourceInput): SourceInput =
        when (val result = SourceInputValidator.validate(input)) {
            is SourceInputValidator.Result.Invalid -> throw InvalidSourceInputException(result.problems)
            is SourceInputValidator.Result.Valid -> result.input
        }

    private suspend fun releaseUnusedDocuments(candidates: List<SourceLocation>) {
        val documents = candidates.filterIsInstance<SourceLocation.LocalDocument>()
        if (documents.isEmpty()) return
        val inUse = store.getSources()
            .flatMap { listOfNotNull(it.location, it.epg.location) }
            .filterIsInstance<SourceLocation.LocalDocument>()
            .map { it.uri }
            .toSet()
        documents.filter { it.uri !in inUse }.distinctBy { it.uri }.forEach { document ->
            runCatching { documentAccess.release(document) }
        }
    }

    private fun registerSecrets(credentials: Credentials) {
        redactor.registerSecret(credentials.username)
        redactor.registerSecret(credentials.password)
    }

    private fun describe(location: SourceLocation): String = when (location) {
        is SourceLocation.Remote -> redactor.redact(location.url)
        is SourceLocation.LocalDocument -> "fichier local ${location.displayName.orEmpty()}".trim()
    }

    private fun lockFor(id: Long): Mutex = locks.getOrPut(id) { Mutex() }

    private suspend fun <T> withRefreshLock(id: Long, block: suspend () -> T): T =
        lockFor(id).withLock {
            refreshingIds.update { it + id }
            try {
                block()
            } finally {
                refreshingIds.update { it - id }
            }
        }

    private companion object {
        const val TAG = "SourceRepository"

        /** Deux nouveaux essais après une coupure : 2 s puis 5 s plus tard. */
        val RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L)

        /** Contrôle des playlists reçues incomplètes : à partir de ce nombre d'entrées, et en dessous de cette part. */
        const val DROP_CHECK_MIN_ENTRIES = 200
        const val DROP_CHECK_RATIO = 0.5

        /** Le guide importé ne couvre qu'une fenêtre de quelques jours : il est réimporté chaque jour. */
        const val EPG_REIMPORT_MILLIS = 24 * 60 * 60_000L
    }
}
