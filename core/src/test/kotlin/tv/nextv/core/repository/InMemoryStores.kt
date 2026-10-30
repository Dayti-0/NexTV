package tv.nextv.core.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tv.nextv.core.epg.EpgChannelRef
import tv.nextv.core.model.Channel
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.Programme
import tv.nextv.core.model.SourceStatus

class InMemoryPlaylistStore : PlaylistStore {

    private val state = MutableStateFlow<Map<Long, PlaylistSource>>(emptyMap())
    private var nextId = 1L
    val channels = mutableMapOf<Long, List<Pair<Long, Channel>>>()
    val programmes = mutableMapOf<Long, List<Programme>>()
    val epgMapping = mutableMapOf<Long, Map<Long, String>>()
    private var nextChannelId = 1L

    override fun observeSources(): Flow<List<PlaylistSource>> = state.map { it.values.sortedBy(PlaylistSource::id) }

    override suspend fun getSources() = state.value.values.sortedBy(PlaylistSource::id)

    override suspend fun getSource(id: Long) = state.value[id]

    override suspend fun insertSource(source: PlaylistSource): Long {
        val id = nextId++
        state.update { it + (id to source.copy(id = id)) }
        return id
    }

    override suspend fun updateSourceDefinition(source: PlaylistSource) {
        state.update { current ->
            val existing = current.getValue(source.id)
            current + (source.id to source.copy(status = existing.status))
        }
    }

    override suspend fun deleteSource(id: Long) {
        state.update { it - id }
        channels.remove(id)
        programmes.remove(id)
        epgMapping.remove(id)
    }

    override suspend fun replaceChannels(sourceId: Long, channels: List<Channel>, onSaved: (Int) -> Unit) {
        this.channels[sourceId] = channels.map { nextChannelId++ to it }
    }

    override suspend fun updateStatus(sourceId: Long, status: SourceStatus) {
        state.update { current -> current + (sourceId to current.getValue(sourceId).copy(status = status)) }
    }

    override suspend fun getEpgChannelRefs(sourceId: Long): List<EpgChannelRef> =
        channels[sourceId].orEmpty().map { (id, channel) -> EpgChannelRef(id, channel.tvgId, channel.tvgName, channel.name) }

    override suspend fun beginEpgImport(sourceId: Long): EpgImportSession = object : EpgImportSession {
        private val pending = mutableListOf<Programme>()

        override suspend fun write(batch: List<Programme>) {
            pending += batch
        }

        override suspend fun commit(mapping: Map<Long, String>) {
            programmes[sourceId] = pending.toList()
            epgMapping[sourceId] = mapping
        }

        override suspend fun abort() = pending.clear()
    }

    fun source(id: Long) = state.value.getValue(id)
}

class InMemoryCredentialStore : CredentialStore {
    val values = mutableMapOf<Long, Credentials>()

    override suspend fun get(sourceId: Long) = values[sourceId]

    override suspend fun put(sourceId: Long, credentials: Credentials) {
        values[sourceId] = credentials
    }

    override suspend fun delete(sourceId: Long) {
        values.remove(sourceId)
    }
}
