package tv.nextv.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.nextv.app.ui.home.toPoster
import tv.nextv.app.ui.home.TrailerSettings
import tv.nextv.app.ui.home.PosterItem
import tv.nextv.app.ui.home.HomeLayout
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.db.EpisodeRow
import tv.nextv.app.data.db.MovieVersionRow
import tv.nextv.app.ui.home.qualityLabel
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.meta.CastMember
import tv.nextv.core.meta.MediaType
import tv.nextv.app.data.db.ItemKeys
import tv.nextv.core.meta.MediaDetails
import tv.nextv.core.meta.TmdbError
import tv.nextv.core.model.ContentKind

/** Ce que la playlist elle-même dit du film ou de la série. */
data class PlaylistItem(
    val sourceId: Long,
    val firstId: Long,
    val title: String,
    val year: Int?,
    val logoUrl: String?,
    val itemKey: String,
    /** Qualité annoncée par la playlist ([tv.nextv.core.model.StreamQuality]), si elle l'indique. */
    val quality: String? = null,
)

data class Season(val number: Int?, val episodes: List<EpisodeRow>)

/**
 * Version d'un film : une de ses copies dans la playlist (« Truefrench », « FR », « VOSTFR », 4K…).
 *
 * @property label ce qui la distingue des autres (« TRUEFRENCH », « VOSTFR · 4K », sinon sa catégorie) ; vide
 *   si rien ne la distingue (la fiche affiche alors son rang, « Version 2/3 »).
 */
data class MovieVersion(val id: Long, val label: String, val quality: String?)

/**
 * Versions d'un film à partir de ses copies ([rows], dans l'ordre de la playlist) : celle qui est ouverte
 * ([openedId]) en premier, une seule par flux (la même copie rangée dans deux catégories ne compte qu'une fois).
 * Avec [avoidUhd] (réglage « Masquer les chaînes 4K »), les versions 4K passent après les autres : la fiche
 * propose d'abord une version HD ou Full HD, la 4K reste au choix.
 */
internal fun movieVersions(rows: List<MovieVersionRow>, openedId: Long, avoidUhd: Boolean = false): List<MovieVersion> {
    val opened = rows.sortedByDescending { it.id == openedId }.distinctBy { it.streamUrl }
    val copies = if (avoidUhd) opened.sortedBy { it.quality == StreamQuality.UHD.name } else opened
    val base = copies.map { row ->
        listOfNotNull(ContentClassifier.versionOf(row.name).tag, qualityLabel(row.quality)).joinToString(" · ")
    }
    return copies.mapIndexed { index, row ->
        val unique = base[index].isNotEmpty() && base.count { it == base[index] } == 1
        // Même langue et même qualité (ou rien d'écrit) : la catégorie les distingue.
        val label = if (unique) base[index] else listOfNotNull(base[index].ifEmpty { null }, row.groupTitle?.trim()?.ifEmpty { null }).joinToString(" · ")
        MovieVersion(row.id, label, row.quality)
    }
}

data class DetailUiState(
    val item: PlaylistItem? = null,
    val metadata: MetadataState = MetadataState.Loading,
    val isFavorite: Boolean = false,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int = 0,
    val missing: Boolean = false,
    /** Lecture en cours (film commencé, épisode en cours de la série). */
    val progress: ProgressEntity? = null,
    /** Films et épisodes déjà vus (clés, voir [tv.nextv.app.data.progress.WatchedRepository]). */
    val watched: Set<String> = emptySet(),
    /** Titres de la même catégorie (fiche Streaming). */
    val similar: List<PosterItem> = emptyList(),
    /** Distribution (TMDB), par ordre d'importance. */
    val cast: List<CastMember> = emptyList(),
    /** Bandes-annonces des saisons lues sur TMDB (numéro de saison → identifiant YouTube, langue ; `null` : aucune). */
    val seasonTrailers: Map<Int, Pair<String, String>?> = emptyMap(),
    /** Versions du film quand la playlist en a plusieurs (vide sinon) ; [PlaylistItem.firstId] est celle choisie. */
    val versions: List<MovieVersion> = emptyList(),
    val selectedVersion: Int = 0,
) {
    /** Bande-annonce du bouton : celle de la saison choisie si TMDB en a une, sinon celle du film ou de la série. */
    val trailer: Pair<String, String?>?
        get() {
            val season = seasons.getOrNull(selectedSeason)?.number
            seasonTrailers[season]?.let { return it }
            val details = (metadata as? MetadataState.Available)?.details ?: return null
            return details.trailerYoutubeKey?.let { it to details.trailerLanguage }
        }
}

/** Fiche d'un film ou d'une série : données de la playlist, enrichies par TMDB si possible. */
class DetailViewModel(
    private val container: AppContainer,
    private val channelId: Long,
    private val kind: ContentKind,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

    /** Vrai dès qu'une version a été choisie sur la fiche : la reprise en cours ne la change plus. */
    private var versionChosen = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val entity = container.channelDao.get(channelId)
        if (entity == null) {
            mutableState.update { it.copy(missing = true, metadata = MetadataState.NotFound) }
            return
        }
        val rawTitle = if (kind == ContentKind.SERIES) entity.seriesName ?: entity.title else entity.title
        // Nettoyé à nouveau : les titres importés avant une amélioration du nettoyage (« … (MULTI ») en profitent.
        val title = ContentClassifier.cleanTitle(rawTitle).ifBlank { rawTitle }
        val item = PlaylistItem(entity.sourceId, entity.id, title, entity.year, entity.logoUrl, entity.itemKey, entity.quality)
        mutableState.update { it.copy(item = item) }

        if (kind == ContentKind.SERIES) {
            val seasons = container.channelDao.episodes(entity.sourceId, entity.itemKey)
                .groupBy { it.season }
                .map { (number, episodes) -> Season(number, episodes) }
                .sortedBy { it.number ?: 0 }
            mutableState.update { it.copy(seasons = seasons) }
        }

        if (kind == ContentKind.MOVIE) {
            val avoidUhd = container.settings.hideUhd.first()
            val versions = movieVersions(container.channelDao.movieVersions(entity.sourceId, entity.itemKey, entity.id), entity.id, avoidUhd)
            // La première version (hors 4K si elle est masquée) est celle que « Regarder » lit.
            if (versions.size > 1) mutableState.update { it.copy(versions = versions).withVersion(0) }
        }

        viewModelScope.launch {
            container.progress.observe(entity.sourceId, entity.itemKey).collect { progress ->
                mutableState.update { state ->
                    // Film commencé dans une autre version : c'est elle que la fiche propose, tant qu'aucune n'a été choisie.
                    val watching = state.versions.indexOfFirst { it.id == progress?.channelId }
                    val current = state.copy(progress = progress)
                    if (watching >= 0 && !versionChosen) current.withVersion(watching) else current
                }
            }
        }
        viewModelScope.launch {
            container.watched.observeKeys(entity.sourceId).collect { keys ->
                mutableState.update { it.copy(watched = keys) }
            }
        }
        viewModelScope.launch {
            container.favorites.observeKeys(entity.sourceId).collect { keys ->
                mutableState.update { it.copy(isFavorite = entity.itemKey in keys) }
            }
        }

        val metadata = if (!container.hasTmdbKey()) {
            MetadataState.Unavailable(TmdbError.MISSING_KEY)
        } else {
            container.metadata.details(
                // Version de la recherche : les « introuvables » enregistrés avant son amélioration sont ignorés.
                cacheKey = metadataCacheKey(entity.itemKey),
                type = if (kind == ContentKind.SERIES) MediaType.SERIES else MediaType.MOVIE,
                title = title,
                year = entity.year,
            )
        }
        mutableState.update { it.copy(metadata = metadata) }
        // Distribution et titres similaires : lus après la fiche (ils ne retardent ni le titre ni le résumé).
        (metadata as? MetadataState.Available)?.details?.let { details ->
            viewModelScope.launch {
                val similar = runCatching { similarInPlaylist(entity.sourceId, entity.itemKey, details) }.getOrDefault(emptyList())
                mutableState.update { it.copy(similar = similar) }
            }
            loadSeasonTrailer(state.value.selectedSeason)
            val cast = runCatching { container.metadata.credits(details) }.getOrDefault(emptyList())
            mutableState.update { it.copy(cast = cast) }
        }
    }

    /** Série : bande-annonce de la saison [index] (une fois par saison). */
    private fun loadSeasonTrailer(index: Int) {
        if (kind != ContentKind.SERIES) return
        val details = (state.value.metadata as? MetadataState.Available)?.details ?: return
        val number = state.value.seasons.getOrNull(index)?.number ?: return
        if (number in state.value.seasonTrailers) return
        viewModelScope.launch {
            val trailer = runCatching { container.metadata.seasonTrailer(details, number) }.getOrNull()
            mutableState.update { it.copy(seasonTrailers = it.seasonTrailers + (number to trailer)) }
        }
    }

    /** Interface choisie (la fiche Streaming a sa propre présentation). */
    val layout: StateFlow<HomeLayout> = container.settings.homeLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.DEFAULT)

    /** Bandes-annonces (fiche Streaming) : lecture automatique, son, délai. */
    val trailers: StateFlow<TrailerSettings> = combine(
        container.settings.trailerAutoplay,
        container.settings.trailerSound,
        container.settings.trailerDelaySeconds,
    ) { autoplay, sound, delay -> TrailerSettings(autoplay, sound, delay * 1_000L) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrailerSettings())

    private suspend fun similarInPlaylist(sourceId: Long, itemKey: String, details: MediaDetails): List<PosterItem> =
        recommendedInPlaylist(container, sourceId, kind, itemKey, details, SIMILAR_COUNT)

    /** Autres bandes-annonces du titre (TMDB), essayées quand celle choisie ne se lit pas. */
    suspend fun trailerAlternatives(): List<Pair<String, String?>> {
        val details = (state.value.metadata as? MetadataState.Available)?.details ?: return emptyList()
        return runCatching { container.metadata.trailers(details) }.getOrDefault(emptyList())
    }

    /** Version suivante du film (le bouton « Version » fait le tour des versions). */
    fun nextVersion() {
        versionChosen = true
        mutableState.update { if (it.versions.size > 1) it.withVersion((it.selectedVersion + 1) % it.versions.size) else it }
    }

    private fun DetailUiState.withVersion(index: Int): DetailUiState {
        val version = versions.getOrNull(index) ?: return this
        return copy(selectedVersion = index, item = item?.copy(firstId = version.id, quality = version.quality))
    }

    fun selectSeason(index: Int) {
        mutableState.update { it.copy(selectedSeason = index) }
        loadSeasonTrailer(index)
    }



    fun toggleFavorite() {
        val item = state.value.item ?: return
        viewModelScope.launch {
            container.favorites.toggle(item.sourceId, kind, item.itemKey, item.title, item.logoUrl)
        }
    }
}

/**
 * Titres que TMDB recommande pour [details] (mêmes genres, même public), retrouvés dans la playlist par leur titre
 * (et leur année pour un film), dans l'ordre de TMDB, sans [itemKey] lui-même. Aucun trouvé : liste vide (pas de
 * remplissage avec la simple catégorie de la playlist). Titres similaires des fiches, « Parce que vous avez
 * regardé » de l'accueil.
 */
internal suspend fun recommendedInPlaylist(
    container: AppContainer,
    sourceId: Long,
    kind: ContentKind,
    itemKey: String,
    details: MediaDetails,
    limit: Int,
): List<PosterItem> {
    val titles = container.metadata.recommendations(details)
    if (titles.isEmpty()) return emptyList()
    val movie = kind == ContentKind.MOVIE
    fun keyOf(title: String, year: Int?) = if (movie) "movie:${ItemKeys.normalize(title)}:${year ?: ""}" else "series:${ItemKeys.normalize(title)}"
    val rank = HashMap<String, Int>()
    titles.forEachIndexed { index, title ->
        rank.putIfAbsent(keyOf(title.title, title.year), index)
        title.originalTitle?.let { rank.putIfAbsent(keyOf(it, title.year), index) }
    }
    val poster = { key: String -> rank[key]?.let { titles[it].posterUrl } }
    val keys = rank.keys.filter { it != itemKey }
    val found = if (movie) {
        keys.chunked(KEYS_PER_QUERY).flatMap { container.channelDao.moviesByKey(sourceId, it) }.map { it.toPoster() }
    } else {
        keys.chunked(KEYS_PER_QUERY).flatMap { container.channelDao.seriesByKey(sourceId, it) }.map { it.toPoster() }
    }
    // Affiche TMDB d'abord (celles des playlists sont souvent recadrées ou floues).
    return found.distinctBy { it.itemKey }.sortedBy { rank[it.itemKey] ?: Int.MAX_VALUE }.take(limit)
        .map { item -> poster(item.itemKey)?.let { item.copy(imageUrl = it) } ?: item }
}

/**
 * Clé de la fiche TMDB d'un film ou d'une série, partagée par la fiche et l'accueil. Version de la
 * recherche : les « introuvables » enregistrés avant son amélioration sont ignorés.
 */
internal fun metadataCacheKey(itemKey: String): String = METADATA_CACHE_PREFIX + itemKey

/** Clé du titre d'une fiche en cache ([metadataCacheKey] inverse) ; `null` pour une fiche d'une ancienne version. */
internal fun itemKeyOfCache(cacheKey: String): String? = cacheKey.takeIf { it.startsWith(METADATA_CACHE_PREFIX) }?.removePrefix(METADATA_CACHE_PREFIX)

private const val METADATA_CACHE_PREFIX = "v3|"

/** Titres similaires affichés sur la fiche Streaming. */
private const val SIMILAR_COUNT = 20

/** Clés de titres par requête SQL (limite de variables de SQLite). */
private const val KEYS_PER_QUERY = 400
