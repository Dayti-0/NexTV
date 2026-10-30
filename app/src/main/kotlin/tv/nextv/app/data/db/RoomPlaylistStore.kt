package tv.nextv.app.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.nextv.core.epg.EpgChannelRef
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.EpgStatus
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.Programme
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceStatus
import tv.nextv.core.model.SourceType
import tv.nextv.core.model.SourceWarning
import tv.nextv.core.repository.EpgImportSession
import tv.nextv.core.repository.PlaylistStore

/** Implémentation Room de [PlaylistStore]. */
class RoomPlaylistStore(private val database: NexTvDatabase) : PlaylistStore {

    private val sourceDao = database.sourceDao()
    private val channelDao = database.channelDao()
    private val programmeDao = database.programmeDao()

    override fun observeSources(): Flow<List<PlaylistSource>> = sourceDao.observeAll().map { list -> list.mapNotNull(::toModel) }

    fun observeSource(id: Long): Flow<PlaylistSource?> = sourceDao.observe(id).map { it?.let(::toModel) }

    override suspend fun getSources(): List<PlaylistSource> = sourceDao.getAll().mapNotNull(::toModel)

    override suspend fun getSource(id: Long): PlaylistSource? = sourceDao.get(id)?.let(::toModel)

    override suspend fun insertSource(source: PlaylistSource): Long = sourceDao.insert(toEntity(source))

    override suspend fun updateSourceDefinition(source: PlaylistSource) {
        database.withTransaction {
            val current = sourceDao.get(source.id) ?: return@withTransaction
            val definition = toEntity(source)
            sourceDao.update(
                current.copy(
                    name = definition.name,
                    type = definition.type,
                    locationKind = definition.locationKind,
                    location = definition.location,
                    locationName = definition.locationName,
                    epgLocationKind = definition.epgLocationKind,
                    epgLocation = definition.epgLocation,
                    epgLocationName = definition.epgLocationName,
                    epgUseHeaderUrls = definition.epgUseHeaderUrls,
                    userAgent = definition.userAgent,
                    hasCredentials = definition.hasCredentials,
                    updatedAt = definition.updatedAt,
                ),
            )
        }
    }

    override suspend fun deleteSource(id: Long) {
        database.withTransaction {
            programmeDao.deleteForSource(id)
            channelDao.deleteForSource(id)
            database.credentialDao().delete(id)
            sourceDao.delete(id)
        }
    }

    override suspend fun replaceChannels(sourceId: Long, channels: List<Channel>, onSaved: (Int) -> Unit) {
        database.withTransaction {
            // L'association EPG des chaînes inchangées survit au rechargement de la playlist.
            val previous = channelDao.epgAssignments(sourceId).associate { assignmentKey(it.tvgId, it.name) to it.epgChannelId }
            // Une chaîne inchangée (même URL de flux) garde son identifiant : le lecteur, les fiches
            // ouvertes et le focus de l'accueil restent valables après une actualisation.
            val previousIds = existingIds(sourceId)
            channelDao.deleteForSource(sourceId)
            val variants = VariantInfo.compute(channels)
            var saved = 0
            channels.chunked(BATCH_SIZE).forEach { batch ->
                channelDao.insertAll(
                    batch.map {
                        toEntity(sourceId, it, previous[assignmentKey(it.tvgId, it.name)], variants.getValue(it.position))
                            .copy(id = previousIds[urlKey(it.stream.url)]?.removeFirstOrNull() ?: 0)
                    },
                )
                saved += batch.size
                onSaved(saved)
            }
        }
    }

    /** Identifiants actuels par URL de flux (empreinte 64 bits : pas de copie des URL en mémoire). */
    private suspend fun existingIds(sourceId: Long): HashMap<Long, ArrayDeque<Long>> {
        val ids = HashMap<Long, ArrayDeque<Long>>()
        var after = 0L
        while (true) {
            val rows = channelDao.idsAndUrls(sourceId, after, ID_PAGE_SIZE)
            if (rows.isEmpty()) break
            rows.forEach { ids.getOrPut(urlKey(it.streamUrl)) { ArrayDeque(1) }.addLast(it.id) }
            after = rows.last().id
        }
        return ids
    }

    override suspend fun updateStatus(sourceId: Long, status: SourceStatus) {
        val (errorCode, errorDetail) = status.error?.encode() ?: (null to null)
        val (epgErrorCode, epgErrorDetail) = status.epg.error?.encode() ?: (null to null)
        sourceDao.updateStatus(
            id = sourceId,
            lastAttemptAt = status.lastAttemptAtMillis,
            lastSuccessAt = status.lastSuccessAtMillis,
            channelCount = status.channelCount,
            errorCode = errorCode,
            errorDetail = errorDetail,
            warnings = status.warnings.joinToString(",") { it.name },
            headerEpgUrls = status.headerEpgUrls.joinToString("\n"),
            epgLastAttemptAt = status.epg.lastAttemptAtMillis,
            epgLastSuccessAt = status.epg.lastSuccessAtMillis,
            epgProgrammeCount = status.epg.programmeCount,
            epgMatchedCount = status.epg.matchedChannelCount,
            epgErrorCode = epgErrorCode,
            epgErrorDetail = epgErrorDetail,
        )
    }

    override suspend fun getEpgChannelRefs(sourceId: Long): List<EpgChannelRef> =
        channelDao.epgRefs(sourceId).map { EpgChannelRef(it.id, it.tvgId, it.tvgName, it.name) }

    override suspend fun beginEpgImport(sourceId: Long): EpgImportSession {
        val committed = sourceDao.get(sourceId)?.epgGeneration ?: 0L
        val generation = maxOf(committed, programmeDao.maxGeneration(sourceId) ?: 0L) + 1
        return RoomEpgImportSession(sourceId, generation)
    }

    private inner class RoomEpgImportSession(
        private val sourceId: Long,
        private val generation: Long,
    ) : EpgImportSession {

        override suspend fun write(batch: List<Programme>) {
            programmeDao.insertAll(
                batch.map {
                    ProgrammeEntity(
                        sourceId = sourceId,
                        generation = generation,
                        epgChannelId = it.epgChannelId,
                        startMillis = it.startMillis,
                        stopMillis = it.stopMillis,
                        title = it.title,
                        subtitle = it.subtitle,
                        description = it.description,
                        category = it.category,
                    )
                },
            )
        }

        override suspend fun commit(mapping: Map<Long, String>) {
            database.withTransaction {
                if (sourceDao.get(sourceId) == null) return@withTransaction
                channelDao.clearEpg(sourceId)
                for ((channelId, epgChannelId) in mapping) channelDao.setEpg(channelId, epgChannelId)
                sourceDao.setEpgGeneration(sourceId, generation)
                programmeDao.deleteOtherGenerations(sourceId, generation)
            }
        }

        override suspend fun abort() {
            programmeDao.deleteGeneration(sourceId, generation)
        }
    }

    private fun assignmentKey(tvgId: String?, name: String) = "${tvgId.orEmpty()}\u0000$name"

    companion object {
        private const val BATCH_SIZE = 500
        private const val ID_PAGE_SIZE = 5_000

        /** Empreinte FNV-1a 64 bits d'une URL. */
        fun urlKey(url: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (c in url) {
                hash = (hash xor c.code.toLong()) * 0x100000001b3L
            }
            return hash
        }
        private const val KIND_REMOTE = "remote"
        private const val KIND_DOCUMENT = "document"

        fun toModel(entity: SourceEntity): PlaylistSource? {
            val type = SourceType.fromId(entity.type) ?: return null
            val location = location(entity.locationKind, entity.location, entity.locationName) ?: return null
            return PlaylistSource(
                id = entity.id,
                name = entity.name,
                type = type,
                location = location,
                epg = EpgSettings(
                    location = location(entity.epgLocationKind, entity.epgLocation, entity.epgLocationName),
                    useHeaderUrls = entity.epgUseHeaderUrls,
                ),
                userAgent = entity.userAgent,
                hasCredentials = entity.hasCredentials,
                createdAtMillis = entity.createdAt,
                updatedAtMillis = entity.updatedAt,
                status = SourceStatus(
                    lastAttemptAtMillis = entity.lastAttemptAt,
                    lastSuccessAtMillis = entity.lastSuccessAt,
                    channelCount = entity.channelCount,
                    error = entity.errorCode?.let { SourceError.decode(it, entity.errorDetail) },
                    warnings = entity.warnings.split(',')
                        .mapNotNull { name -> SourceWarning.entries.firstOrNull { it.name == name } }
                        .toSet(),
                    headerEpgUrls = entity.headerEpgUrls.split('\n').filter { it.isNotBlank() },
                    epg = EpgStatus(
                        lastAttemptAtMillis = entity.epgLastAttemptAt,
                        lastSuccessAtMillis = entity.epgLastSuccessAt,
                        programmeCount = entity.epgProgrammeCount,
                        matchedChannelCount = entity.epgMatchedCount,
                        error = entity.epgErrorCode?.let { SourceError.decode(it, entity.epgErrorDetail) },
                    ),
                ),
            )
        }

        fun toEntity(source: PlaylistSource): SourceEntity {
            val (kind, value, name) = encode(source.location)
            val epg = source.epg.location?.let(::encode)
            return SourceEntity(
                id = source.id,
                name = source.name,
                type = source.type.id,
                locationKind = kind,
                location = value,
                locationName = name,
                epgLocationKind = epg?.first,
                epgLocation = epg?.second,
                epgLocationName = epg?.third,
                epgUseHeaderUrls = source.epg.useHeaderUrls,
                userAgent = source.userAgent,
                hasCredentials = source.hasCredentials,
                createdAt = source.createdAtMillis,
                updatedAt = source.updatedAtMillis,
            )
        }

        private fun toEntity(sourceId: Long, channel: Channel, epgChannelId: String?, variant: VariantInfo) = ChannelEntity(
            sourceId = sourceId,
            position = channel.position,
            name = channel.name,
            groupTitle = channel.groupTitle,
            logoUrl = channel.logoUrl,
            tvgId = channel.tvgId,
            tvgName = channel.tvgName,
            channelNumber = channel.channelNumber,
            streamUrl = channel.stream.url,
            streamHeaders = HeaderCodec.encode(channel.stream.headers),
            streamFormat = channel.stream.format.name,
            streamSupport = channel.stream.support.name,
            streamIssues = channel.stream.issues.joinToString(",") { it.name },
            epgChannelId = epgChannelId,
            kind = channel.kind.name,
            title = channel.title,
            variantKey = channel.variantKey,
            quality = channel.quality?.name,
            year = channel.year,
            seriesName = channel.episode?.seriesName,
            season = channel.episode?.season,
            episode = channel.episode?.episode,
            qualityRank = StreamQuality.rankOf(channel.quality),
            isPrimary = variant.primary,
            variantCount = variant.count,
            itemKey = ItemKeys.of(channel),
        )

        private fun encode(location: SourceLocation): Triple<String, String, String?> = when (location) {
            is SourceLocation.Remote -> Triple(KIND_REMOTE, location.url, null)
            is SourceLocation.LocalDocument -> Triple(KIND_DOCUMENT, location.uri, location.displayName)
        }

        private fun location(kind: String?, value: String?, name: String?): SourceLocation? = when {
            kind == null || value == null -> null
            kind == KIND_REMOTE -> SourceLocation.Remote(value)
            kind == KIND_DOCUMENT -> SourceLocation.LocalDocument(value, name)
            else -> null
        }
    }
}

/** Version affichée (la meilleure qualité, puis la première dans la playlist) et nombre de versions. */
internal data class VariantInfo(val primary: Boolean, val count: Int) {
    companion object {
        /** @return informations par position de chaîne dans la playlist. */
        fun compute(channels: List<Channel>): Map<Int, VariantInfo> {
            val result = HashMap<Int, VariantInfo>(channels.size)
            val groups = channels.groupBy { channel ->
                if (channel.kind == ContentKind.LIVE && channel.variantKey.isNotEmpty()) channel.variantKey else "\u0000${channel.position}"
            }
            for (group in groups.values) {
                val best = group.maxWith(compareBy<Channel> { StreamQuality.rankOf(it.quality) }.thenByDescending { it.position })
                group.forEach { result[it.position] = VariantInfo(primary = it === best, count = group.size) }
            }
            return result
        }
    }
}

/** Clés stables des favoris et du cache TMDB. */
object ItemKeys {
    fun of(channel: Channel): String = when (channel.kind) {
        ContentKind.LIVE -> "live:" + channel.variantKey.ifEmpty { normalize(channel.name) }
        ContentKind.MOVIE -> "movie:" + normalize(channel.title) + ":" + (channel.year ?: "")
        ContentKind.SERIES -> "series:" + normalize(channel.episode?.seriesName ?: channel.title)
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
