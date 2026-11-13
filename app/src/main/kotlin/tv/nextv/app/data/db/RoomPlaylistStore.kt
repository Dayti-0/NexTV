package tv.nextv.app.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.catalog.LiveVariants
import tv.nextv.core.epg.EpgChannelRef
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport
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

/**
 * Implémentation Room de [PlaylistStore].
 *
 * @param mergeChannels réglage « Rassembler les chaînes », lu à chaque enregistrement d'une playlist : les chaînes
 *   sont enregistrées rassemblées (versions de son réunies, ordre rangé) ou telles que la playlist les présente.
 */
class RoomPlaylistStore(
    private val database: NexTvDatabase,
    private val mergeChannels: suspend () -> Boolean = { true },
) : PlaylistStore {

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
                    mirrors = definition.mirrors,
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
            // Films et épisodes sans qualité dans leur nom : celle déjà mesurée (lecture, fiche du serveur).
            val measured = channelDao.measuredQualities(sourceId)
                .mapNotNull { row -> runCatching { StreamQuality.valueOf(row.quality) }.getOrNull()?.let { row.streamUrl to it } }
                .toMap()
            val known = if (measured.isEmpty()) channels else channels.map { channel ->
                if (channel.quality != null || channel.kind == ContentKind.LIVE) channel
                else measured[channel.stream.url]?.let { channel.copy(quality = it) } ?: channel
            }
            // Même film sous son titre français et son titre original (« Le Virtuose », « The Virtuoso ») : une affiche.
            val aligned = MovieCopies.mergeTitles(MovieCopies.alignYears(known), movieTitles())
            val merge = mergeChannels()
            val variants = VariantInfo.compute(aligned) { groupedKey(it, merge) }
            var saved = 0
            aligned.chunked(BATCH_SIZE).forEach { batch ->
                channelDao.insertAll(
                    batch.map {
                        toEntity(sourceId, it, previous[assignmentKey(it.tvgId, it.name)], variants.getValue(it.position), merge)
                            .copy(id = previousIds[urlKey(it.stream.url)]?.removeFirstOrNull() ?: 0)
                    },
                )
                saved += batch.size
                onSaved(saved)
            }
        }
    }

    /** Fiches TMDB des films déjà trouvées, par clé de titre (vide si le cache est illisible : rien n'est regroupé). */
    private suspend fun movieTitles(): Map<String, CachedMovieTitle> =
        runCatching { database.tmdbDao().movieTitles() }.getOrDefault(emptyList())
            .associateBy { it.cacheKey.removePrefix(METADATA_CACHE_PREFIX) }

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
                        year = it.year,
                        imageUrl = it.imageUrl,
                    )
                },
            )
        }

        override suspend fun commit(mapping: Map<Long, String>, guideOnlyChannels: Map<String, String>) {
            database.withTransaction {
                if (sourceDao.get(sourceId) == null) return@withTransaction
                channelDao.clearEpg(sourceId)
                for ((channelId, epgChannelId) in mapping) channelDao.setEpg(channelId, epgChannelId)
                val guideOnly = database.guideOnlyChannelDao()
                guideOnly.deleteForSource(sourceId)
                guideOnly.insertAll(guideOnlyChannels.map { (id, name) -> GuideOnlyChannelEntity(sourceId, id, name) })
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
                mirrors = entity.mirrors.split('\n').filter { it.isNotBlank() },
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
                mirrors = source.mirrors.joinToString("\n"),
            )
        }

        /**
         * Clé de version enregistrée : chaînes rassemblées, celle de la chaîne ; sinon, une version double audio ou
         * Dolby a la sienne (même calcul que ChannelDao.applyGrouping au changement du réglage).
         */
        internal fun groupedKey(channel: Channel, merge: Boolean): String =
            if (merge || channel.kind != ContentKind.LIVE) channel.variantKey else LiveVariants.splitKey(channel.variantKey, channel.audio)

        private fun toEntity(sourceId: Long, channel: Channel, epgChannelId: String?, variant: VariantInfo, merge: Boolean): ChannelEntity {
            val live = channel.kind == ContentKind.LIVE
            // Ordre rangé des chaînes (TNT, Canal+…) ; les films et les épisodes gardent celui de la playlist.
            val lineup = if (live) channel.lineup ?: channel.position else channel.position
            return ChannelEntity(
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
                variantKey = groupedKey(channel, merge),
                quality = channel.quality?.name,
                year = channel.year,
                seriesName = channel.episode?.seriesName,
                season = channel.episode?.season,
                episode = channel.episode?.episode,
                qualityRank = StreamQuality.rankOf(channel.quality),
                isPrimary = variant.primary,
                variantCount = variant.count,
                itemKey = ItemKeys.of(channel),
                catchupDays = channel.catchupDays,
                audio = channel.audio,
                lineup = lineup,
                listOrder = if (live && merge) lineup else channel.position,
            )
        }

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

/**
 * Version affichée (la meilleure qualité, puis la première dans la playlist) et nombre de versions.
 * Les épisodes d'une série sont les « versions » de la série : le premier la représente dans les
 * listes, avec le nombre d'épisodes (pas de regroupement à chaque page affichée).
 * Les copies d'un film (« À la dérive (Truefrench) », « À la dérive (FR) », la même en 4K…) sont ses versions :
 * une seule est affichée, les autres se choisissent sur sa fiche.
 */
internal data class VariantInfo(val primary: Boolean, val count: Int) {
    companion object {
        /**
         * @param liveKey clé de version enregistrée d'une chaîne en direct (versions de son à part ou non).
         * @return informations par position de chaîne dans la playlist.
         */
        fun compute(channels: List<Channel>, liveKey: (Channel) -> String = { it.variantKey }): Map<Int, VariantInfo> {
            val result = HashMap<Int, VariantInfo>(channels.size)
            val groups = channels.groupBy { channel ->
                when {
                    channel.kind == ContentKind.LIVE && channel.variantKey.isNotEmpty() -> liveKey(channel)
                    channel.kind == ContentKind.SERIES -> "\u0001" + ItemKeys.of(channel)
                    channel.kind == ContentKind.MOVIE -> MovieCopies.groupKey(channel) ?: "\u0000${channel.position}"
                    else -> "\u0000${channel.position}"
                }
            }
            for (group in groups.values) {
                val best = when {
                    // Série : son premier épisode la représente, quelle que soit la qualité annoncée par chacun.
                    group.first().kind == ContentKind.SERIES -> group.minBy { it.position }
                    group.size > 1 && group.first().kind == ContentKind.MOVIE -> MovieCopies.best(group)
                    else -> group.maxWith(compareBy<Channel> { StreamQuality.rankOf(it.quality) }.thenByDescending { it.position })
                }
                group.forEach { result[it.position] = VariantInfo(primary = it === best, count = group.size) }
            }
            return result
        }
    }
}

/** Copies d'un même film dans la playlist (autre langue, autre qualité, autre catégorie). */
internal object MovieCopies {

    /** Clé commune aux copies d'un film ; `null` pour un titre sans lettre ni chiffre (jamais regroupé). */
    fun groupKey(channel: Channel): String? =
        ItemKeys.of(channel).takeUnless { it.startsWith(UNTITLED) }?.let { "\u0002$it" }

    /** Début de la clé d'un film dont le titre n'a ni lettre ni chiffre (« movie: », titre vide, « : », année). */
    private const val UNTITLED = "movie::"

    /**
     * Copie affichée dans les listes : lisible d'abord, puis en version française (ou sans mention de langue),
     * de la meilleure qualité, la première de la playlist.
     */
    fun best(copies: List<Channel>): Channel {
        var best = copies.first()
        var bestScore = score(best)
        for (index in 1 until copies.size) {
            val copy = copies[index]
            val score = score(copy)
            if (score > bestScore || (score == bestScore && copy.position < best.position)) {
                best = copy
                bestScore = score
            }
        }
        return best
    }

    /** Préférence d'une copie, calculée une fois par copie (des milliers de films en ont plusieurs). */
    private fun score(copy: Channel): Int {
        // Copie rangée sous le titre d'une autre ([mergeTitles]) : sa mention de version se lit dans son propre nom.
        val own = ItemKeys.normalize(copy.name).contains(ItemKeys.normalize(copy.title))
        val version = if (own) ContentClassifier.versionOf(copy.name, copy.title) else ContentClassifier.versionOf(copy.name)
        return (if (copy.stream.support != StreamSupport.UNSUPPORTED) 1_000 else 0) +
            version.rank * 10 +
            StreamQuality.rankOf(copy.quality)
    }

    /**
     * Même film sous deux titres, français et original (« Le Virtuose » et « The Virtuoso », « Star Trek : Sans limites »
     * et « Star Trek Beyond ») : rien dans les noms ne les relie, mais la recherche TMDB de chacun a trouvé la même fiche.
     * Ses copies prennent alors le titre français (et l'année) : elles ont la même clé, une seule affiche s'affiche, les
     * autres se choisissent sur la fiche (bouton « Version »).
     *
     * Seule une fiche déjà trouvée compte ([found], par clé de titre : celles des affiches et des fiches affichées), et
     * seulement si chaque titre est l'un des siens et l'année la sienne ([sameFilm]) : TMDB rend son premier résultat
     * quand aucun ne porte le titre cherché, deux films différents peuvent ainsi avoir la même fiche.
     */
    fun mergeTitles(channels: List<Channel>, found: Map<String, CachedMovieTitle>): List<Channel> {
        if (found.isEmpty()) return channels
        // Titres reconnus de chaque film TMDB : clé → première copie de ce titre.
        val keys = arrayOfNulls<String>(channels.size)
        val films = HashMap<Long, LinkedHashMap<String, Channel>>()
        channels.forEachIndexed { index, channel ->
            if (channel.kind != ContentKind.MOVIE) return@forEachIndexed
            val key = ItemKeys.of(channel)
            keys[index] = key
            val film = found[key] ?: return@forEachIndexed
            if (!sameFilm(ItemKeys.normalize(channel.title), channel.year, film)) return@forEachIndexed
            films.getOrPut(film.tmdbId) { LinkedHashMap(2) }.putIfAbsent(key, channel)
        }
        // Clé → titre et année du film (ceux du titre français, sinon du premier titre de la playlist).
        val renamed = HashMap<String, Pair<String, Int?>>()
        for (titles in films.values) {
            if (titles.size < 2) continue
            val target = titles.entries.maxWith(
                compareBy<Map.Entry<String, Channel>> { (key, _) -> isLocalTitle(key, found.getValue(key)) }
                    .thenBy { it.value.year != null }
                    .thenByDescending { it.value.position },
            ).value
            val year = target.year ?: titles.values.firstNotNullOfOrNull { it.year }
            for (key in titles.keys) renamed[key] = target.title to year
        }
        if (renamed.isEmpty()) return channels
        return channels.mapIndexed { index, channel ->
            val (title, year) = keys[index]?.let(renamed::get) ?: return@mapIndexed channel
            // Copies déjà sous ce titre : inchangées (même clé, seule l'écriture pourrait différer).
            if (channel.year == year && ItemKeys.normalize(channel.title) == ItemKeys.normalize(title)) channel
            else channel.copy(title = title, year = year)
        }
    }

    /**
     * [title] (mots en minuscules, voir [ItemKeys.normalize]) et [year] désignent le film de la fiche [film] : son titre
     * français ou original, à un an près (sortie en fin d'année, date d'une autre copie). Sans année, le titre suffit.
     */
    fun sameFilm(title: String, year: Int?, film: CachedMovieTitle): Boolean {
        if (title.isEmpty()) return false
        val named = title == ItemKeys.normalize(film.title) || film.originalTitle?.let { title == ItemKeys.normalize(it) } == true
        return named && (year == null || film.year == null || kotlin.math.abs(year - film.year) <= 1)
    }

    /** Clé d'un titre qui est le titre de la fiche (en français : la langue des fiches). */
    private fun isLocalTitle(key: String, film: CachedMovieTitle): Boolean =
        key.removePrefix(MOVIE_KEY_PREFIX).substringBeforeLast(':') == ItemKeys.normalize(film.title)

    private const val MOVIE_KEY_PREFIX = "movie:"

    /**
     * Film écrit sans année alors qu'une autre copie la donne (« À la dérive (FR) » et « À la dérive (2018) ») :
     * il prend cette année, et les deux copies ont la même clé. Rien n'est supposé quand le titre existe à
     * plusieurs années (« Dune » de 1984 et de 2021).
     */
    fun alignYears(channels: List<Channel>): List<Channel> {
        val years = HashMap<String, Int>()
        var undated = false
        for (channel in channels) {
            if (channel.kind != ContentKind.MOVIE) continue
            val year = channel.year
            if (year == null) {
                undated = true
                continue
            }
            val title = ItemKeys.normalize(channel.title)
            val known = years[title]
            if (known == null) years[title] = year else if (known != year) years[title] = SEVERAL_YEARS
        }
        if (!undated || years.isEmpty()) return channels
        return channels.map { channel ->
            if (channel.kind != ContentKind.MOVIE || channel.year != null) return@map channel
            val title = ItemKeys.normalize(channel.title)
            val year = years[title]?.takeIf { it != SEVERAL_YEARS && title.isNotEmpty() }
            if (year != null) channel.copy(year = year) else channel
        }
    }

    private const val SEVERAL_YEARS = -1
}

/** Clés stables des favoris et du cache TMDB. */
object ItemKeys {
    fun of(channel: Channel): String = when (channel.kind) {
        ContentKind.LIVE -> "live:" + channel.variantKey.ifEmpty { normalize(channel.name) }
        ContentKind.MOVIE -> "movie:" + normalize(channel.title) + ":" + (channel.year ?: "")
        ContentKind.SERIES -> "series:" + normalize(channel.episode?.seriesName ?: channel.title)
    }

    /**
     * Minuscules sans accents, mots séparés par une espace (`[^a-z0-9]+` → « »). Appelé pour chaque
     * entrée de la playlist : un seul passage, sans expression régulière, et sans décomposition
     * Unicode pour un texte ASCII (le cas courant). Les lettres des autres écritures (arabe, cyrillique,
     * grec…) sont gardées : sans elles, tous ces titres avaient la même clé.
     */
    internal fun normalize(text: String): String {
        val lower = text.lowercase()
        val folded = if (lower.all { it.code < 0x80 }) {
            lower
        } else {
            MARKS.replace(java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD), "")
        }
        val out = StringBuilder(folded.length)
        var separator = false
        for (c in folded) {
            if (c in 'a'..'z' || c in '0'..'9' || (c.code in OTHER_SCRIPTS && c.isLetterOrDigit())) {
                if (separator && out.isNotEmpty()) out.append(' ')
                separator = false
                out.append(c)
            } else {
                separator = true
            }
        }
        return out.toString()
    }

    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Écritures non latines, du grec jusqu'avant les formes « pleine largeur » : en dehors, seules les lettres de
     * « a » à « z » comptent, comme avant (les clés des titres en lettres latines ne changent pas).
     */
    private val OTHER_SCRIPTS = 0x370 until 0xFF00
}
