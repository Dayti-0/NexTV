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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.settings.Reminder
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

    /** `null` : toutes les chaînes ; `""` : chaînes sans groupe ; [FAVORITES] : chaînes favorites. */
    val selectedGroup = MutableStateFlow<String?>(null)

    private val hiddenGroups = container.hiddenGroupDao.observeTitles(sourceId, ContentKind.LIVE.name)

    init {
        // Catégorie masquée entre-temps : retour à toutes les chaînes.
        viewModelScope.launch {
            hiddenGroups.collect { hidden -> selectedGroup.update { if (it != null && it in hidden) null else it } }
        }
    }

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MILLIS)
        }
    }

    /** Programmes avec un rappel ([Reminder.keyOf]). */
    val reminders: StateFlow<Set<String>> = container.settings.reminders
        .map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleReminder(channel: GuideChannel, programme: GuideProgramme) {
        viewModelScope.launch {
            container.settings.toggleReminder(
                Reminder(Reminder.keyOf(channel.id, programme.startMillis), programme.startMillis, programme.title, channel.id, channel.name),
            )
        }
    }

    /** Demi-heure en cours : la grille n'est rechargée qu'à son changement (ou à celui de la catégorie). */
    private val slot = clock.map { it - it % HALF_HOUR }.distinctUntilChanged()

    val state: StateFlow<GuideUiState> = combine(
        combine(selectedGroup, slot, hiddenGroups) { group, slot, _ -> group to slot }.mapLatest { (group, slot) -> load(group, slot) },
        clock,
    ) { loaded, now -> loaded.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

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
        private const val HALF_HOUR = 30 * 60_000L

        /** Fin de la grille : 24 heures après la demi-heure écoulée. */
        private const val WINDOW_MILLIS = 24 * 60 * 60_000L + HALF_HOUR
        private const val REFRESH_MILLIS = 60_000L
        private const val MAX_CHANNELS = 300
        private const val SQL_VARIABLES_PER_QUERY = 500
    }
}
