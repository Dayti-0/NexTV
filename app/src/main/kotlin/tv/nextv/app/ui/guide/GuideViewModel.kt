package tv.nextv.app.ui.guide

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.db.GuideVersionRow
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.epg.EveningPicker
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
) : ViewModel() {

    val sourceName: StateFlow<String> = container.playlistStore.observeSource(sourceId)
        .mapLatest { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val groups: StateFlow<List<GroupRow>> = container.channelDao.observeGroups(sourceId, ContentKind.LIVE.name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * `null` : toutes les chaînes ; `""` : chaînes sans groupe ; [FAVORITES] : chaînes favorites ;
     * [ESSENTIAL] : une ligne par chaîne du guide ; [TONIGHT] : programme du soir.
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
                ?.takeIf { it == FAVORITES || it == ESSENTIAL || it == TONIGHT }
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

    val state: StateFlow<GuideUiState> = combine(
        combine(selectedGroup, slot, hiddenGroups) { group, slot, _ -> group to slot }
            .mapLatest { (group, slot) -> if (group == TONIGHT) GuideUiState(loading = false) else load(group, slot) },
        clock,
    ) { loaded, now -> loaded.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    /** Programme du soir, lu seulement quand « Ce soir » est affiché. */
    val tonight: StateFlow<TonightUiState> = combine(
        combine(selectedGroup, tonightDay, slot, hiddenGroups) { group, day, slot, _ -> Triple(group, day, slot) }
            .mapLatest { (group, day, slot) -> if (group == TONIGHT) loadTonight(day, slot) else TonightUiState() },
        clock,
    ) { loaded, now -> loaded.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightUiState())

    /**
     * Une ligne par chaîne du guide (sa meilleure version visible, sans les doublons SD, HD, FHD ni
     * ceux des autres catégories), les chaînes de la TNT d'abord, dans l'ordre de leurs numéros.
     */
    private suspend fun essentialChannels(): List<Pair<GuideVersionRow, Int?>> =
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
    )

    private suspend fun programmesFor(epgIds: List<String>, from: Long, to: Long): Map<String, List<GuideProgramme>> =
        epgIds.distinct()
            .chunked(SQL_VARIABLES_PER_QUERY)
            .flatMap { ids -> container.programmeDao.inWindow(sourceId, ids, from, to) }
            .groupBy({ it.epgChannelId }) { GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year) }

    private suspend fun loadTonight(day: Int, now: Long): TonightUiState {
        val evenings = (0 until EVENINGS).map { offset ->
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, offset)
                set(Calendar.HOUR_OF_DAY, PRIME_TIME_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val evening = evenings[day.coerceIn(evenings.indices)]
        val channels = essentialChannels().take(MAX_TONIGHT_CHANNELS)
        val programmes = programmesFor(channels.map { it.first.epgChannelId }, evening - EVENING_BEFORE, evening + EVENING_AFTER)
        val rows = channels.mapNotNull { (row, tnt) ->
            val list = programmes[row.epgChannelId].orEmpty()
            val picked = EveningPicker.pick(list, evening, { it.startMillis }, { it.stopMillis })
            // Chaîne sans programme ce soir-là : absente (le programme papier ne l'aurait pas non plus).
            picked.first ?: return@mapNotNull null
            TonightRow(row.toGuideChannel(tnt, list), picked.first, picked.second)
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
        if (group == ESSENTIAL) {
            val channels = essentialChannels().take(MAX_CHANNELS)
            val programmes = programmesFor(channels.map { it.first.epgChannelId }, windowStart, windowEnd)
            return GuideUiState(
                loading = false,
                windowStart = windowStart,
                windowEnd = windowEnd,
                openAt = openAt,
                now = slot,
                channels = channels.map { (row, tnt) -> row.toGuideChannel(tnt, programmes[row.epgChannelId].orEmpty()) },
            )
        }
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
        val programmes = rows.map { it.epgChannelId }.distinct()
            .chunked(SQL_VARIABLES_PER_QUERY)
            .flatMap { ids -> container.programmeDao.inWindow(sourceId, ids, windowStart, windowEnd) }
            .groupBy { it.epgChannelId }
        val channels = rows.map { row ->
            GuideChannel(
                id = row.id,
                name = row.title.ifBlank { row.name },
                number = row.channelNumber,
                logoUrl = row.logoUrl,
                programmes = programmes[row.epgChannelId].orEmpty().map {
                    GuideProgramme(it.title, it.startMillis, it.stopMillis, it.subtitle, it.description, it.category, it.year)
                },
                catchupDays = row.catchupDays,
            )
        }
        return GuideUiState(loading = false, windowStart = windowStart, windowEnd = windowEnd, openAt = openAt, now = slot, channels = channels)
    }

    companion object {
        /** Catégorie « Favoris » (valeur réservée, jamais un nom de groupe). */
        const val FAVORITES = "\u0000favorites"

        /** Catégorie « L'essentiel » : une ligne par chaîne du guide, TNT d'abord. */
        const val ESSENTIAL = "\u0000essential"

        /** Programme du soir. */
        const val TONIGHT = "\u0000tonight"
        private const val PRIME_TIME_HOUR = 21
        private const val EVENINGS = 7
        private const val EVENING_BEFORE = 90 * 60_000L
        private const val EVENING_AFTER = 5 * 60 * 60_000L
        private const val MAX_TONIGHT_CHANNELS = 400
        private const val HALF_HOUR = 30 * 60_000L

        /** Fin de la grille : 24 heures après la demi-heure écoulée. */
        private const val WINDOW_MILLIS = 24 * 60 * 60_000L + HALF_HOUR
        private const val REFRESH_MILLIS = 60_000L
        private const val MAX_CHANNELS = 300
        private const val SQL_VARIABLES_PER_QUERY = 500
    }
}
