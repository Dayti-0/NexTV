package tv.nextv.core.repository

import kotlinx.coroutines.flow.Flow
import tv.nextv.core.epg.EpgChannelRef
import tv.nextv.core.epg.ProgrammeSink
import tv.nextv.core.model.Channel
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceStatus

/** Persistance des sources et de leur contenu (Room côté Android). */
interface PlaylistStore {
    fun observeSources(): Flow<List<PlaylistSource>>

    suspend fun getSources(): List<PlaylistSource>

    suspend fun getSource(id: Long): PlaylistSource?

    /** @return l'identifiant attribué. */
    suspend fun insertSource(source: PlaylistSource): Long

    /** Met à jour la définition (nom, emplacement, EPG, User-Agent, identifiants) sans toucher au statut. */
    suspend fun updateSourceDefinition(source: PlaylistSource)

    /** Supprime la source, ses chaînes et ses programmes. */
    suspend fun deleteSource(id: Long)

    /**
     * Remplace atomiquement les chaînes de la source. L'association EPG des chaînes
     * inchangées (même `tvg-id` et même nom) est conservée jusqu'au prochain import du guide.
     *
     * @param onSaved nombre de chaînes déjà enregistrées, appelé au fil de l'écriture (progression).
     */
    suspend fun replaceChannels(sourceId: Long, channels: List<Channel>, onSaved: (Int) -> Unit = {})

    suspend fun updateStatus(sourceId: Long, status: SourceStatus)

    suspend fun getEpgChannelRefs(sourceId: Long): List<EpgChannelRef>

    /**
     * Ouvre un import de guide : les programmes écrits ne deviennent visibles qu'au [EpgImportSession.commit],
     * qui remplace alors les précédents ; [EpgImportSession.abort] les écarte.
     */
    suspend fun beginEpgImport(sourceId: Long): EpgImportSession
}

interface EpgImportSession : ProgrammeSink {
    /**
     * @param mapping chaîne de la playlist (identifiant en base) → identifiant de chaîne XMLTV.
     * @param guideOnlyChannels chaînes du guide sans chaîne de la playlist mais dont des matchs ont
     * été importés (identifiant XMLTV → nom affiché).
     */
    suspend fun commit(mapping: Map<Long, String>, guideOnlyChannels: Map<String, String> = emptyMap())

    suspend fun abort()
}

/** Stockage chiffré des identifiants des sources (Android Keystore côté application). */
interface CredentialStore {
    suspend fun get(sourceId: Long): Credentials?

    suspend fun put(sourceId: Long, credentials: Credentials)

    suspend fun delete(sourceId: Long)
}

/** Gestion des autorisations d'accès aux documents locaux (permissions persistantes Android). */
fun interface DocumentAccess {
    /** Le document n'est plus référencé par aucune source : son autorisation peut être rendue. */
    fun release(location: SourceLocation.LocalDocument)

    companion object {
        val None = DocumentAccess { }
    }
}
