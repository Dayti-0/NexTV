package tv.nextv.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.nextv.core.log.Logger
import java.io.IOException
import java.security.SecureRandom

/**
 * Page de dépôt commune (filebin) : fichiers lus et écrits par nom. Toutes les méthodes lèvent [IOException] sans
 * réseau, et [write] lève [SyncBinClosedException] sur une page fermée.
 */
interface SyncRelay {
    /** Fichiers de la page, nom → empreinte du contenu ; vide si la page n'existe pas (ou plus). */
    suspend fun list(bin: String): Map<String, String>

    /** Contenu d'un fichier ; `null` s'il n'existe pas. */
    suspend fun read(bin: String, name: String): ByteArray?

    suspend fun write(bin: String, name: String, bytes: ByteArray)

    suspend fun deleteFile(bin: String, name: String)

    suspend fun deleteBin(bin: String)
}

/** Page supprimée ou expirée : filebin refuse d'y écrire de nouveau (réponse 405). */
class SyncBinClosedException : IOException("Page fermée par filebin")

/** Éléments de la télé (favoris, réglages…) lus et modifiés sous les clés de [SyncKeys]. */
interface SyncLocal {
    suspend fun snapshot(): SyncSnapshot

    /**
     * Rend la télé conforme à [target] (valeurs en vigueur du document partagé) : seuls les éléments qui diffèrent de
     * [current] sont écrits. Une playlist d'une portée de [ignoredScopes] (retirée sur cette télé) n'est pas ajoutée.
     */
    suspend fun apply(target: Map<String, String>, current: SyncSnapshot, ignoredScopes: Set<String>): SyncApplied
}

/** Ce qu'une synchronisation a changé sur la télé. */
data class SyncApplied(val changes: Int = 0, val playlistsAdded: Int = 0, val activatedPlaylist: Long? = null) {
    operator fun plus(other: SyncApplied) =
        SyncApplied(changes + other.changes, playlistsAdded + other.playlistsAdded, activatedPlaylist ?: other.activatedPlaylist)
}

/** État gardé sur la télé entre deux synchronisations (et deux lancements). */
data class SyncState(
    val deviceId: String,
    val group: SyncGroup? = null,
    /** Numéro de la page du groupe en usage ([pageOf]) : on passe à la suivante quand filebin a fermé la page. */
    val generation: Int = 0,
    val document: SyncDocument = SyncDocument(),
    /** État de la télé laissé par la synchronisation précédente : ce qui a changé depuis vient d'ici. */
    val base: SyncSnapshot = SyncSnapshot(),
    /** Portées des playlists retirées de cette télé : jamais rajoutées par les autres. */
    val ignoredScopes: Set<String> = emptySet(),
    /** Télé qui vient de rejoindre le groupe, pas encore synchronisée. */
    val joining: Boolean = false,
    val uploadedDigest: String? = null,
    val uploadedAt: Long = 0,
    /** Autres télés du groupe, d'après leurs derniers dépôts. */
    val peers: List<SyncDevice> = emptyList(),
    val lastSyncAt: Long = 0,
    /** Dernier dépôt de l'invitation du code du groupe par cette télé (0 : à refaire) et sa page ([SyncPairing.inviteBin]). */
    val inviteAt: Long = 0,
    val inviteGeneration: Int = 0,
)

/** Lecture et écriture de [SyncState] sur l'appareil (appels bloquants, faits hors du fil principal). */
interface SyncStateStore {
    fun load(): SyncState?

    fun save(state: SyncState)
}

/** État affiché dans les réglages. */
data class SyncStatus(
    val linked: Boolean = false,
    val peers: List<SyncDevice> = emptyList(),
    val lastSyncAt: Long = 0,
    /** Dernier essai raté (pas d'Internet, filebin injoignable), 0 si le dernier a réussi. */
    val failedAt: Long = 0,
    val running: Boolean = false,
)

/** Code du groupe affiché sur la télé : permanent, le même sur toutes les télés reliées. */
data class SyncInvite(val code: String)

sealed interface JoinResult {
    data class Joined(val from: String, val applied: SyncApplied, val synced: Boolean) : JoinResult

    /** Aucune invitation sous ce code (faute de frappe, ou groupe dont aucune télé n'a déposé l'invitation). */
    data object UnknownCode : JoinResult

    data object AlreadyLinked : JoinResult

    data object Offline : JoinResult
}

/**
 * Synchronisation des télés reliées. Chaque télé dépose son document complet (chiffré) sur la page commune du
 * groupe, sous `tv-<identifiant>.bin`, et fusionne ceux des autres : les favoris, « Déjà vu », reprises, catégories
 * masquées et réglages changés sur l'une arrivent sur les autres à leur prochain passage, même si elles étaient
 * éteintes au moment du changement. Filebin garde une page 7 jours après son dernier dépôt ; chaque télé redépose le
 * sien au moins tous les [REUPLOAD_MILLIS], et une page perdue est refaite au dépôt suivant (chaque télé a tout).
 */
class TvSyncEngine(
    private val relay: SyncRelay,
    private val local: SyncLocal,
    private val store: SyncStateStore,
    /** Nom de la télé et version de l'appli, pour les autres télés. */
    private val deviceName: () -> String,
    private val appVersion: String,
    private val logger: Logger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val mutex = Mutex()
    private var loaded: SyncState? = null

    /** Empreinte des fichiers des autres télés déjà fusionnés (depuis le lancement). */
    private val merged = HashMap<String, String>()

    private val mutableStatus = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    private fun state(): SyncState {
        loaded?.let { return it }
        val state = runCatching { store.load() }.getOrNull() ?: SyncState(deviceId = randomText(random, 10))
        loaded = state
        publish(state)
        return state
    }

    private fun save(state: SyncState) {
        loaded = state
        store.save(state)
        publish(state)
    }

    private fun publish(state: SyncState, failedAt: Long = mutableStatus.value.failedAt, running: Boolean = mutableStatus.value.running) {
        mutableStatus.value = SyncStatus(state.group != null, state.peers, state.lastSyncAt, failedAt, running)
    }

    /** Lit l'état gardé (au lancement, hors du fil principal) pour que les réglages l'affichent. */
    suspend fun load() = mutex.withLock { state() }

    val isLinked: Boolean get() = loaded?.group != null

    /** Synchronise maintenant. @return ce qui a changé sur la télé, `null` si elle n'est reliée à aucune autre. */
    suspend fun sync(): SyncApplied? = mutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncApplied? {
        val start = state()
        val group = start.group ?: return null
        publish(start, running = true)
        try {
            return runSync(start, group)
        } catch (e: CancellationException) {
            publish(state(), running = false)
            throw e
        } catch (e: IOException) {
            logger?.warn(TAG, "Synchronisation impossible : ${e.message}")
            publish(state(), failedAt = clock(), running = false)
            return SyncApplied()
        }
    }

    private suspend fun runSync(start: SyncState, group: SyncGroup): SyncApplied {
        val now = clock()
        val current = local.snapshot()
        // Playlist retirée de cette télé depuis le passage précédent : les autres ne la lui rendront pas.
        val ignored = (start.ignoredScopes + (start.base.scopes - current.scopes)) - current.scopes
        var document = start.document.withLocalChanges(start.base, current, now, joining = start.joining)
        // Changements de la télé notés avant tout appel réseau : rien n'est perdu si la suite échoue.
        if (document != start.document || current != start.base || ignored != start.ignoredScopes) {
            save(start.copy(document = document, base = current, ignoredScopes = ignored))
        }

        val own = fileName(start.deviceId)
        var generation = start.generation
        val files = relay.list(pageOf(group, generation))
        val peers = start.peers.associateBy { it.id }.toMutableMap()
        val present = HashSet<String>()
        for ((name, digest) in files) {
            if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX) || name == own) continue
            val id = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
            present += id
            if (merged[name] == digest && digest.isNotEmpty()) continue
            val bytes = relay.read(pageOf(group, generation), name) ?: continue
            val file = try {
                SyncCodec.decodeFile(bytes, group.key)
            } catch (e: SyncFormatException) {
                logger?.warn(TAG, "Document d'une autre télé illisible ($name)")
                continue
            }
            document = document.merge(file.document)
            peers[id] = file.device.copy(id = id)
            merged[name] = digest
        }
        peers.keys.retainAll(present)

        val applied = local.apply(document.values(), current, ignored)
        val after = if (applied.changes > 0 || applied.playlistsAdded > 0) local.snapshot() else current
        document = document.pruned(now)

        val digest = digest(document)
        var uploadedDigest = start.uploadedDigest
        var uploadedAt = start.uploadedAt
        if (digest != uploadedDigest || own !in files || now - uploadedAt >= REUPLOAD_MILLIS) {
            val device = SyncDevice(start.deviceId, deviceName(), appVersion, now)
            val bytes = SyncCodec.encodeFile(SyncFile(device, document), group.key)
            // Page fermée (restée 7 jours sans dépôt, ou supprimée) : page suivante, que les autres télés trouveront
            // de la même façon en voulant y déposer le leur.
            var attempts = 0
            while (true) {
                try {
                    relay.write(pageOf(group, generation), own, bytes)
                    break
                } catch (e: SyncBinClosedException) {
                    if (++attempts > MAX_PAGE_SKIPS) throw e
                    generation++
                    logger?.info(TAG, "Page du groupe fermée : passage à la page $generation")
                }
            }
            uploadedDigest = digest
            uploadedAt = now
        }
        var inviteAt = start.inviteAt
        var inviteGeneration = start.inviteGeneration
        if (inviteAt == 0L || now - inviteAt >= REUPLOAD_MILLIS || generation != start.generation) {
            try {
                inviteGeneration = publishInvite(group, generation, inviteGeneration)
                inviteAt = now
            } catch (e: IOException) {
                // Refait au passage suivant ; les documents, eux, sont à jour.
                logger?.warn(TAG, "Invitation du groupe non déposée : ${e.message}")
            }
        }
        val before = state()
        val finished = before.copy(
            document = document,
            base = after,
            ignoredScopes = ignored,
            joining = false,
            generation = generation,
            uploadedDigest = uploadedDigest,
            uploadedAt = uploadedAt,
            peers = peers.values.sortedBy { it.name },
            lastSyncAt = now,
            inviteAt = inviteAt,
            inviteGeneration = inviteGeneration,
        )
        // Rien de neuf (le cas de presque tous les passages) : pas de réécriture du fichier, seulement l'heure en mémoire.
        if (finished.copy(lastSyncAt = before.lastSyncAt, peers = before.peers) == before && finished.peers.map { it.id } == before.peers.map { it.id }) {
            loaded = finished
        } else {
            save(finished)
        }
        publish(finished, failedAt = 0, running = false)
        if (applied.changes > 0 || applied.playlistsAdded > 0) {
            logger?.info(TAG, "Synchronisation : ${applied.changes} changement(s), ${applied.playlistsAdded} playlist(s) ajoutée(s)")
        }
        return applied
    }

    /**
     * Dépose l'invitation du groupe sous son code, sur la page [inviteGeneration] ou la suivante si filebin l'a fermée.
     * @return la page utilisée.
     */
    private suspend fun publishInvite(group: SyncGroup, generation: Int, inviteGeneration: Int): Int {
        val code = SyncPairing.codeOf(group)
        val bytes = SyncPairing.encodeInvite(group, deviceName(), code, generation)
        var page = inviteGeneration
        while (true) {
            try {
                relay.write(SyncPairing.inviteBin(code, page), SyncPairing.INVITE_FILE, bytes)
                return page
            } catch (e: SyncBinClosedException) {
                if (page - inviteGeneration >= MAX_PAGE_SKIPS) throw e
                page++
            }
        }
    }

    /**
     * Code pour relier une autre télé (crée le groupe si cette télé n'en a pas encore). Le code ne change pas et
     * n'expire pas : l'invitation est déposée tout de suite, puis redéposée par chaque télé du groupe.
     * @throws IOException sans réseau.
     */
    suspend fun createInvite(): SyncInvite = mutex.withLock {
        var state = state()
        val group = state.group ?: SyncGroup.create(random).also {
            // Première télé du groupe : tout ce qu'elle a part au premier dépôt.
            state = state.copy(group = it, document = SyncDocument(), base = SyncSnapshot(), joining = false, uploadedDigest = null, peers = emptyList())
        }
        // Invitation redéposée à coup sûr (page perdue entre-temps) avec le document de cette télé : l'autre télé
        // trouve tout dès qu'elle a saisi le code, même si celle-ci est éteinte entre-temps.
        save(state.copy(inviteAt = 0))
        syncLocked()
        if (mutableStatus.value.failedAt != 0L || state().inviteAt == 0L) throw IOException("Synchronisation impossible")
        SyncInvite(SyncPairing.codeOf(group))
    }

    /**
     * Attend qu'une nouvelle télé rejoigne le groupe (tant que le code est affiché) : son document apparaît sur la page
     * commune. Synchronise alors (par [syncNow]) et @return son nom.
     */
    suspend fun awaitNewTv(syncNow: suspend () -> Unit = { this.sync() }): String {
        val known = mutex.withLock { state().peers.map { it.id }.toHashSet() }
        val started = clock()
        while (true) {
            // Code laissé longtemps à l'écran : la page est relue moins souvent.
            delay(if (clock() - started < FAST_WAIT_MILLIS) pollMillis else SLOW_POLL_MILLIS)
            val state = loaded ?: continue
            val group = state.group ?: continue
            val files = try {
                relay.list(pageOf(group, state.generation))
            } catch (e: IOException) {
                continue
            }
            val arrived = files.keys
                .filter { it.startsWith(FILE_PREFIX) && it.endsWith(FILE_SUFFIX) }
                .map { it.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX) }
                .filter { it != state.deviceId && it !in known }
            if (arrived.isEmpty()) continue
            syncNow()
            state().peers.firstOrNull { it.id !in known }?.let { return it.name.ifBlank { "?" } }
            // Document illisible (autre groupe, fichier abîmé) : plus attendu.
            if (mutableStatus.value.failedAt == 0L) known += arrived
        }
    }

    /** Rejoint le groupe avec le code affiché sur une de ses télés (même éteinte depuis), puis synchronise. */
    suspend fun join(code: String): JoinResult = mutex.withLock {
        var found: Pair<SyncPairing.Invite, Int>? = null
        try {
            for (page in 0..MAX_PAGE_SKIPS) {
                val bytes = relay.read(SyncPairing.inviteBin(code, page), SyncPairing.INVITE_FILE) ?: continue
                val decoded = SyncPairing.decodeInvite(bytes, code) ?: continue
                found = decoded to page
                break
            }
        } catch (e: IOException) {
            return@withLock JoinResult.Offline
        }
        val (decoded, invitePage) = found ?: return@withLock JoinResult.UnknownCode
        val group = decoded.group
        val previous = state()
        if (previous.group == group) return@withLock JoinResult.AlreadyLinked
        previous.group?.let { old -> runCatching { relay.deleteFile(pageOf(old, previous.generation), fileName(previous.deviceId)) } }
        // Invitation qui vient d'être lue : déjà en place, pas à redéposer tout de suite.
        save(
            SyncState(
                deviceId = previous.deviceId, group = group, generation = decoded.generation, joining = true,
                inviteAt = clock(), inviteGeneration = invitePage,
            ),
        )
        merged.clear()
        val applied = syncLocked()
        JoinResult.Joined(decoded.from, applied ?: SyncApplied(), synced = mutableStatus.value.failedAt == 0L)
    }

    /** Détache cette télé du groupe : elle garde tout ce qu'elle a, les autres ne la voient plus. */
    suspend fun leave() = mutex.withLock {
        val state = state()
        state.group?.let { group -> runCatching { relay.deleteFile(pageOf(group, state.generation), fileName(state.deviceId)) } }
        merged.clear()
        save(SyncState(deviceId = state.deviceId))
        publish(state(), failedAt = 0, running = false)
    }

    companion object {
        private const val TAG = "NixTV/Sync"
        const val FILE_PREFIX = "tv-"
        const val FILE_SUFFIX = ".bin"
        const val POLL_MILLIS = 3_000L

        /** Attente d'une nouvelle télé : page relue toutes les [POLL_MILLIS] pendant 10 min, puis chaque minute. */
        const val FAST_WAIT_MILLIS = 10 * 60_000L
        const val SLOW_POLL_MILLIS = 60_000L

        /** Dépôt (document, invitation) refait au moins tous les 3 jours : filebin garde la page 7 jours après le dernier. */
        const val REUPLOAD_MILLIS = 3L * 24 * 60 * 60_000L

        fun fileName(deviceId: String) = "$FILE_PREFIX$deviceId$FILE_SUFFIX"

        /** Pages essayées à la suite quand filebin les a fermées. */
        const val MAX_PAGE_SKIPS = 5

        /** Page filebin du groupe pour un numéro donné (la première porte le nom tiré au hasard). */
        fun pageOf(group: SyncGroup, generation: Int): String = if (generation == 0) group.bin else "${group.bin}-$generation"

        /** Empreinte stable d'un document (clés triées). */
        internal fun digest(document: SyncDocument): String {
            val text = buildString {
                for (key in document.entries.keys.sorted()) {
                    val entry = document.entries.getValue(key)
                    append(key).append('\u0000').append(entry.time).append('\u0000').append(entry.value ?: "\u0001").append('\u0000')
                }
            }
            return text.encodeUtf8().sha256().hex()
        }
    }
}

/** État de [TvSyncEngine] en JSON (gardé chiffré sur l'appareil : il contient les identifiants des playlists). */
object SyncStateCodec {
    fun encode(state: SyncState): String {
        val base = JSONObject()
        state.base.values.forEach { (key, value) -> base.put(key, value) }
        return JSONObject()
            .put("v", 1)
            .put("device", state.deviceId)
            .put("group", state.group?.encode() ?: JSONObject.NULL)
            .put("generation", state.generation)
            .put("doc", SyncCodec.documentJson(SyncFile(SyncDevice(state.deviceId, ""), state.document)).getJSONObject("e"))
            .put("base", base)
            .put("scopes", JSONArray(state.base.scopes.sorted()))
            .put("ignored", JSONArray(state.ignoredScopes.sorted()))
            .put("joining", state.joining)
            .put("uploadedDigest", state.uploadedDigest ?: JSONObject.NULL)
            .put("uploadedAt", state.uploadedAt)
            .put("lastSyncAt", state.lastSyncAt)
            .put("inviteAt", state.inviteAt)
            .put("inviteGeneration", state.inviteGeneration)
            .put(
                "peers",
                JSONArray(state.peers.map { JSONObject().put("id", it.id).put("name", it.name).put("app", it.app).put("at", it.at) }),
            )
            .toString()
    }

    /** @return `null` si le texte est illisible. */
    fun decode(text: String): SyncState? = try {
        val root = JSONObject(text)
        val deviceId = root.getString("device")
        val doc = SyncCodec.parseFile(JSONObject().put("device", JSONObject().put("id", deviceId)).put("e", root.optJSONObject("doc") ?: JSONObject()).toString())
        val baseJson = root.optJSONObject("base") ?: JSONObject()
        val base = buildMap { for (key in baseJson.keys()) put(key, baseJson.getString(key)) }
        SyncState(
            deviceId = deviceId,
            group = if (root.isNull("group")) null else SyncGroup.decode(root.optString("group")),
            generation = root.optInt("generation"),
            document = doc.document,
            base = SyncSnapshot(base, root.optJSONArray("scopes").strings()),
            ignoredScopes = root.optJSONArray("ignored").strings(),
            joining = root.optBoolean("joining"),
            uploadedDigest = if (root.isNull("uploadedDigest")) null else root.optString("uploadedDigest"),
            uploadedAt = root.optLong("uploadedAt"),
            lastSyncAt = root.optLong("lastSyncAt"),
            inviteAt = root.optLong("inviteAt"),
            inviteGeneration = root.optInt("inviteGeneration"),
            peers = root.optJSONArray("peers")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { SyncDevice(it.optString("id"), it.optString("name"), it.optString("app"), it.optLong("at")) }
                }
            }.orEmpty(),
        )
    } catch (e: JSONException) {
        null
    } catch (e: SyncFormatException) {
        null
    }

    private fun JSONArray?.strings(): Set<String> = this?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
}
