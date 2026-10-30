package tv.nextv.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
     * @return vrai si le contenu doit être rechargé (emplacement, guide, User-Agent ou identifiants modifiés).
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
        )
        store.updateSourceDefinition(updated)
        releaseUnusedDocuments(listOfNotNull(existing.location, existing.epg.location))
        logger.info(TAG, "Source $id modifiée")
        return existing.location != updated.location ||
            existing.epg != updated.epg ||
            existing.userAgent != updated.userAgent ||
            existing.type != updated.type ||
            change is CredentialsChange.Replace ||
            existing.hasCredentials != hasCredentials
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
    suspend fun refresh(id: Long, includeEpg: Boolean = true, progress: ProgressListener? = null): RefreshResult = withRefreshLock(id) {
        val source = store.getSource(id) ?: return@withRefreshLock RefreshResult.NotFound
        val credentials = if (source.hasCredentials) credentialStore.get(id)?.also(::registerSecrets) else null
        val attemptAt = clock()

        val loaded = try {
            loaders.get(source.type).load(source, credentials, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = (e as? SourceException)?.error ?: SourceError.Unknown(e.javaClass.simpleName)
            logger.warn(TAG, "Échec de l'actualisation de la source $id (${describe(source.location)}) : ${error.code}", e)
            store.updateStatus(id, source.status.copy(lastAttemptAtMillis = attemptAt, error = error))
            return@withRefreshLock RefreshResult.Failure(error)
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
        store.replaceChannels(id, loaded.channels) { saved -> progress?.onProgress(LoadProgress(LoadStep.SAVING, saved.toLong(), total)) }
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
        RefreshResult.Success(loaded.channels.size, loaded.warnings, status.epg)
    }

    /**
     * Met à jour le guide EPG. Avec un guide commun ([epgFeed]), il n'est retéléchargé que s'il est
     * trop ancien (ou si [force]) et réimporté seulement s'il a changé depuis le dernier import.
     */
    suspend fun refreshEpg(id: Long, force: Boolean = false, progress: ProgressListener? = null): RefreshResult = withRefreshLock(id) {
        val source = store.getSource(id) ?: return@withRefreshLock RefreshResult.NotFound
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
        store.updateStatus(id, status)
        outcome.failure?.let { RefreshResult.Failure(it) }
            ?: RefreshResult.Success(status.channelCount, status.warnings, outcome.status)
    }

    /** Actualise toutes les sources, l'une après l'autre. */
    suspend fun refreshAll(): Map<Long, RefreshResult> =
        store.getSources().associate { it.id to refresh(it.id) }

    private suspend fun importEpg(
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
        val covered = HashSet<String>()
        var programmes = 0
        var firstError: SourceError? = null
        var succeeded = 0

        try {
            for (location in locations) {
                try {
                    val result = epgLoader.load(location, request, channels, session, excludedEpgIds = covered, progress = progress)
                    result.mapping.forEach { (channelId, epgId) -> mapping.putIfAbsent(channelId, epgId) }
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
                session.abort()
                return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = firstError), failure = firstError)
            }
            session.commit(mapping)
        } catch (e: Throwable) {
            withContext(NonCancellable) { session.abort() }
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
            return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = feed.error), failure = null)
        }

        val session = store.beginEpgImport(source.id)
        try {
            val result = epgLoader.importFile(feed.file, store.getEpgChannelRefs(source.id), session, progress = progress)
            session.commit(result.mapping)
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
            withContext(NonCancellable) { session.abort() }
            logger.warn(TAG, "Import du guide impossible : ${e.error.code}", e)
            return EpgOutcome(previous.copy(lastAttemptAtMillis = attemptAt, error = e.error), failure = e.error)
        } catch (e: Throwable) {
            withContext(NonCancellable) { session.abort() }
            throw e
        }
    }

    /** @property failure erreur si aucun guide n'a pu être importé (le guide précédent est conservé). */
    private class EpgOutcome(val status: EpgStatus, val failure: SourceError?)

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

        /** Le guide importé ne couvre qu'une fenêtre de quelques jours : il est réimporté chaque jour. */
        const val EPG_REIMPORT_MILLIS = 24 * 60 * 60_000L
    }
}
