package tv.nextv.app.ui.guide

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.GuideVersionRow
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.epg.EveningPicker
import tv.nextv.core.epg.SameProgramme
import tv.nextv.core.epg.TntChannels
import tv.nextv.core.model.ContentKind
import java.util.Calendar

@Immutable
data class GuideProgramme(
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    /** Année de production, si le guide l'indique. */
    val year: Int? = null,
    /** Image du programme donnée par le guide. */
    val imageUrl: String? = null,
    /**
     * Numéro du programme dans la base, pour ceux de la grille : leur [description] n'est pas chargée avec eux
     * mais lue à la demande ([GuideViewModel.describe]). 0 : programme complet (ou sans description à lire).
     */
    val id: Long = 0,
)

@Immutable
data class GuideChannel(
    val id: Long,
    val name: String,
    val number: String?,
    val logoUrl: String?,
    val programmes: List<GuideProgramme>,
    /** Jours de programmes passés gardés par le serveur (replay), 0 sans replay. */
    val catchupDays: Int = 0,
    /** Clé stable de la chaîne (toutes ses versions) : favoris, « L'essentiel ». */
    val itemKey: String = "",
)

/** Soirée d'une chaîne (« Ce soir ») : programme principal vers 21 h et deuxième partie de soirée. */
@Immutable
data class TonightRow(val channel: GuideChannel, val first: GuideProgramme?, val second: GuideProgramme?)

/**
 * Programme du soir, comme un magazine télé : une ligne par chaîne (TNT d'abord, dans l'ordre des
 * numéros), pour le soir du jour [day] (0 : ce soir).
 */
data class TonightUiState(
    val loading: Boolean = true,
    val day: Int = 0,
    /** 21 h de chacun des soirs proposés. */
    val evenings: List<Long> = emptyList(),
    val rows: List<TonightRow> = emptyList(),
    val now: Long = 0,
)

data class GuideUiState(
    val loading: Boolean = true,
    /** Début de la grille : la veille à minuit (programmes passés, replay). */
    val windowStart: Long = 0,
    val windowEnd: Long = 0,
    /** Position affichée à l'ouverture : la demi-heure écoulée. */
    val openAt: Long = 0,
    val now: Long = 0,
    val channels: List<GuideChannel> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    private val container: AppContainer,
    val sourceId: Long,
    private val resources: Resources,
) : ViewModel() {

    val sourceName: StateFlow<String> = container.playlistStore.observeSource(sourceId)
        .mapLatest { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val groups: StateFlow<List<GroupRow>> = container.channelDao.observeGroups(sourceId, ContentKind.LIVE.name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * `null` : toutes les chaînes ; `""` : chaînes sans groupe ; [FAVORITES] : chaînes favorites ;
     * [FAVORITES_TONIGHT] : programme du soir des favorites ; [TONIGHT] : programme du soir.
     */
    val selectedGroup = MutableStateFlow<String?>(null)

    /** Soir affiché dans « Ce soir » (0 : ce soir, 1 : demain…). */
    val tonightDay = MutableStateFlow(0)

    private val hiddenGroups = container.hiddenGroupDao.observeTitles(sourceId, ContentKind.LIVE.name)

    init {
        // Dernière catégorie spéciale choisie (« Ce soir » pour qui ne regarde que la soirée) ; un
        // groupe n'est pas repris : il peut ne pas exister dans une autre playlist.
        viewModelScope.launch {
            container.settings.guideCategory.first()
                ?.takeIf { it == FAVORITES || it == FAVORITES_TONIGHT || it == TONIGHT }
                ?.let { saved -> selectedGroup.compareAndSet(null, saved) }
            selectedGroup.drop(1).collect { container.settings.setGuideCategory(it) }
        }
        // Catégorie masquée entre-temps : retour à toutes les chaînes.
        viewModelScope.launch {
            hiddenGroups.collect { hidden -> selectedGroup.update { if (it != null && it in hidden) null else it } }
        }
    }

    fun selectGroup(group: String?) {
        selectedGroup.value = group
    }

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MILLIS)
        }
    }

    /** Demi-heure en cours : la grille n'est rechargée qu'à son changement (ou à celui de la catégorie). */
    private val slot = clock.map { it - it % HALF_HOUR }.distinctUntilChanged()

    /** Dernier import du guide : la grille est relue quand il change (ses programmes ont été remplacés). */
    private val guideImport = container.playlistStore.observeSource(sourceId)
        .map { it?.status?.epg?.lastSuccessAtMillis }
        .distinctUntilChanged()

    val state: StateFlow<GuideUiState> = combine(
        combine(selectedGroup, slot, hiddenGroups, guideImport) { group, slot, _, _ -> group to slot }
            .mapLatest { (group, slot) -> if (group.isTonight()) GuideUiState(loading = false) else load(group, slot) },
        clock,
    ) { loaded, now -> loaded.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    /**
     * Programme de la grille avec sa description, lue à la demande (programme sélectionné, fiche ouverte). Rendu
     * tel quel s'il est déjà complet, ou si la description ne peut pas être lue.
     */
    suspend fun describe(programme: GuideProgramme): GuideProgramme {
        if (programme.id == 0L || programme.description != null) return programme
        val description = runCatching { container.programmeDao.description(programme.id) }.getOrNull() ?: return programme
        return programme.copy(description = description)
    }

    /**
     * Programme du soir, lu seulement quand « Ce soir » est affiché. Soirée changée depuis le dernier affichage
     * (retour sur l'écran après minuit…) : écran de chargement d'abord, jamais l'ancienne soirée sous « Ce soir ».
     */
    val tonight: StateFlow<TonightUiState> = combine(
        combine(selectedGroup, tonightDay, slot, hiddenGroups) { group, day, slot, _ -> Triple(group, day, slot) }
            .transformLatest { (group, day, slot) ->
                if (!group.isTonight()) {
                    emit(TonightUiState())
                    return@transformLatest
                }
                val evenings = evenings(slot)
                val shown = group to evenings[day.coerceIn(evenings.indices)]
                if (shown != tonightShown) emit(TonightUiState(loading = true, day = day, evenings = evenings))
                emit(loadTonight(day, slot, favoritesOnly = group == FAVORITES_TONIGHT))
                tonightShown = shown
            },
        clock,
    ) { loaded, now -> loaded.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightUiState())

    /** Catégorie et soirée affichées en dernier (« Ce soir ») : rechargement de la même soirée sans écran de chargement. */
    private var tonightShown: Pair<String?, Long>? = null

    private fun String?.isTonight() = this == TONIGHT || this == FAVORITES_TONIGHT

    /**
     * Toutes les chaînes du guide, une ligne par chaîne (sa meilleure version visible, sans les
     * doublons SD, HD, FHD ni ceux des autres catégories), la TNT d'abord dans l'ordre des numéros.
     */
    private suspend fun allGuideChannels(): List<Pair<GuideVersionRow, Int?>> =
        container.channelDao.guideVersions(sourceId)
            .distinctBy { it.epgChannelId }
            .map { it to TntChannels.number(it.title.ifBlank { it.name }, it.epgChannelId) }
            .distinctBy { (row, tnt) -> tnt?.toString() ?: row.epgChannelId }
            .sortedWith(compareBy({ it.second == null }, { it.second ?: 0 }, { it.first.position }))

    private fun GuideVersionRow.toGuideChannel(tnt: Int?, programmes: List<GuideProgramme>) = GuideChannel(
        id = id,
        name = ContentClassifier.cleanTitle(title.ifBlank { name }).ifBlank { name },
        number = tnt?.toString(),
        logoUrl = logoUrl,
        programmes = programmes,
        catchupDays = catchupDays,
        itemKey = itemKey,
    )

    private suspend fun programmesFor(epgIds: List<String>, from: Long, to: Long): Map<String, List<GuideProgramme>> =
        epgIds.distinct()
            .chunked(SQL_VARIABLES_PER_QUERY)
            .flatMap { ids -> container.programmeDao.inWindow(sourceId, ids, from, to) }
            .groupBy({ it.epgChannelId }) { GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year, it.imageUrl) }

    /**
     * 21 h des soirs proposés. Le premier est celui d'aujourd'hui tant que sa soirée n'est pas finie ;
     * ensuite (vers 23 h 30, et bien sûr après minuit) c'est celui du lendemain : à 1 h du matin, plus
     * de programme de la veille.
     */
    private fun evenings(now: Long): List<Long> {
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, PRIME_TIME_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (now >= today.timeInMillis + EVENING_OVER) today.add(Calendar.DAY_OF_MONTH, 1)
        return (0 until EVENINGS).map { offset ->
            (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }.timeInMillis
        }
    }

    private suspend fun loadTonight(day: Int, now: Long, favoritesOnly: Boolean): TonightUiState {
        val evenings = evenings(now)
        val evening = evenings[day.coerceIn(evenings.indices)]
        // Tout le catalogue associé au guide, une ligne par chaîne (sa meilleure version : « Regarder »
        // l'ouvre dans la qualité des réglages), la TNT d'abord ; Favoris : seulement les chaînes favorites.
        val all = allGuideChannels()
        val channels = if (favoritesOnly) {
            val favorites = container.channelDao.guideFavoriteChannels(sourceId).mapTo(HashSet()) { it.epgChannelId }
            all.filter { it.first.epgChannelId in favorites }
        } else {
            all.take(MAX_TONIGHT_CHANNELS)
        }
        val programmes = programmesFor(channels.map { it.first.epgChannelId }, evening - EVENING_BEFORE, evening + EVENING_AFTER)
        // Même programme à la même heure sur plusieurs chaînes (versions régionales, doublons) : une seule fois.
        val shown = HashSet<String>()
        val rows = channels.mapNotNull { (row, tnt) ->
            val list = programmes[row.epgChannelId].orEmpty()
            val picked = EveningPicker.pick(list, evening, { it.startMillis }, { it.stopMillis })
            // Chaîne sans programme ce soir-là : absente (le programme papier ne l'aurait pas non plus).
            val first = picked.first ?: return@mapNotNull null
            if (!shown.add(SameProgramme.key(first.title, first.startMillis))) return@mapNotNull null
            TonightRow(row.toGuideChannel(tnt, list), first, picked.second)
        }
        return TonightUiState(loading = false, day = day, evenings = evenings, rows = rows, now = now)
    }
    private suspend fun load(group: String?, slot: Long): GuideUiState {
        val openAt = slot - HALF_HOUR
        val windowStart = Calendar.getInstance().apply {
            timeInMillis = slot
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -1)
        }.timeInMillis
        val windowEnd = openAt + WINDOW_MILLIS
        val rows = if (group == FAVORITES) {
            container.channelDao.guideFavoriteChannels(sourceId)
        } else {
            container.channelDao.guideChannels(
                sourceId = sourceId,
                filterGroup = if (group == null) 0 else 1,
                group = group.orEmpty(),
                limit = MAX_CHANNELS,
            )
        }
        // Sans les descriptions (lues à la demande, [describe]) : deux jours de programmes de centaines de chaînes.
        val programmes = rows.map { it.epgChannelId }.distinct()
            .chunked(SQL_VARIABLES_PER_QUERY)
            .flatMap { ids -> container.programmeDao.gridWindow(sourceId, ids, windowStart, windowEnd) }
            .groupBy { it.epgChannelId }
        val channels = rows.map { row ->
            GuideChannel(
                id = row.id,
                name = row.title.ifBlank { row.name },
                number = row.channelNumber,
                logoUrl = row.logoUrl,
                programmes = programmes[row.epgChannelId].orEmpty().map {
                    GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, description = null, it.category, it.year, it.imageUrl, id = it.id)
                },
                catchupDays = row.catchupDays,
                itemKey = row.itemKey,
            )
        }
        return GuideUiState(loading = false, windowStart = windowStart, windowEnd = windowEnd, openAt = openAt, now = slot, channels = channels)
    }

    companion object {
        /** Catégorie « Favoris » (valeur réservée, jamais un nom de groupe). */
        const val FAVORITES = "\u0000favorites"

        /** Favoris en mode « Ce soir » : le programme du soir des chaînes favorites. */
        const val FAVORITES_TONIGHT = "\u0000favorites_tonight"

        /** Programme du soir. */
        const val TONIGHT = "\u0000tonight"
        private const val PRIME_TIME_HOUR = 21
        private const val EVENINGS = 7
        private const val EVENING_BEFORE = 90 * 60_000L
        private const val EVENING_AFTER = 5 * 60 * 60_000L

        /** Soirée finie, après 21 h : le premier soir proposé devient celui du lendemain. */
        private const val EVENING_OVER = 150 * 60_000L
        private const val MAX_TONIGHT_CHANNELS = 2_000
        private const val HALF_HOUR = 30 * 60_000L

        /** Fin de la grille : 24 heures après la demi-heure écoulée. */
        private const val WINDOW_MILLIS = 24 * 60 * 60_000L + HALF_HOUR
        private const val REFRESH_MILLIS = 60_000L
        private const val MAX_CHANNELS = 300
        private const val SQL_VARIABLES_PER_QUERY = 500
    }
}
