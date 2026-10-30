package tv.nextv.app.ui.startup

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.ui.common.describe
import tv.nextv.core.io.LoadProgress
import tv.nextv.core.io.LoadStep
import tv.nextv.core.io.ProgressListener
import tv.nextv.core.model.ContentKind
import tv.nextv.core.repository.RefreshResult

enum class StartupStep { PREPARING, PLAYLIST, SAVING, EPG_DOWNLOAD, EPG_IMPORT, READY }

data class StartupError(val message: String, val canContinue: Boolean)

data class StartupUiState(
    val percent: Int = 0,
    val step: StartupStep = StartupStep.PREPARING,
    /** Octets reçus quand la taille totale est inconnue, ou résumé du contenu. */
    val bytesReceived: Long? = null,
    val playlistName: String? = null,
    val summary: ContentSummary? = null,
    val error: StartupError? = null,
)

data class ContentSummary(val channels: Int, val movies: Int, val series: Int)

enum class StartupDestination { HOME, WELCOME }

/**
 * Démarrage : charge la dernière playlist utilisée (actualisée si nécessaire) et le guide,
 * en publiant l'avancement réel de chaque étape.
 *
 * @param forceRefresh recharger la playlist même si elle est récente (adresse modifiée…).
 */
class StartupViewModel(
    private val container: AppContainer,
    private val resources: Resources,
    private val forceRefresh: Boolean = false,
) : ViewModel() {

    private val mutableState = MutableStateFlow(StartupUiState())
    val state: StateFlow<StartupUiState> = mutableState.asStateFlow()

    private val mutableDestination = MutableStateFlow<StartupDestination?>(null)
    val destination: StateFlow<StartupDestination?> = mutableDestination.asStateFlow()

    private var job: Job? = null
    private var activeId: Long? = null

    init {
        start()
    }

    fun retry() {
        mutableState.value = StartupUiState(playlistName = mutableState.value.playlistName)
        start()
    }

    /** Ouvre l'accueil avec les données déjà enregistrées malgré l'échec de l'actualisation. */
    fun continueWithCache() {
        val id = activeId ?: return
        mutableState.update { it.copy(error = null) }
        job = viewModelScope.launch { finish(id) }
    }

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch { run() }
    }

    private suspend fun run() {
        report(StartupStep.PREPARING, 0f)
        val sources = container.playlistStore.getSources()
        if (sources.isEmpty()) {
            mutableDestination.value = StartupDestination.WELCOME
            return
        }
        val savedId = container.settings.activePlaylistId.first()
        val source = sources.firstOrNull { it.id == savedId } ?: sources.first()
        if (source.id != savedId) container.settings.setActivePlaylist(source.id)
        activeId = source.id
        mutableState.update { it.copy(playlistName = source.name) }

        val listener = ProgressListener { onProgress(it) }
        val lastSuccess = source.status.lastSuccessAtMillis
        val stale = forceRefresh || lastSuccess == null || source.status.channelCount == 0 ||
            System.currentTimeMillis() - lastSuccess > PLAYLIST_MAX_AGE_MILLIS
        if (stale) {
            // Playlist puis guide (réassocié aux nouvelles chaînes), en une seule actualisation.
            val result = container.sourceRepository.refresh(source.id, includeEpg = true, progress = listener)
            if (result is RefreshResult.Failure) {
                val message = resources.describe(result.error)
                mutableState.update { it.copy(error = StartupError(message, canContinue = source.status.channelCount > 0)) }
                return
            }
        } else {
            report(StartupStep.EPG_DOWNLOAD, 0f)
            // Le guide n'est retéléchargé que s'il a plus de 12 h ; une erreur n'empêche pas l'accès.
            container.sourceRepository.refreshEpg(source.id, progress = listener)
        }
        finish(source.id)
    }

    private suspend fun finish(sourceId: Long) {
        val dao = container.channelDao
        val summary = ContentSummary(
            channels = dao.count(sourceId, ContentKind.LIVE.name),
            movies = dao.count(sourceId, ContentKind.MOVIE.name),
            series = dao.seriesCount(sourceId),
        )
        mutableState.update { it.copy(summary = summary) }
        report(StartupStep.READY, 1f)
        mutableDestination.value = StartupDestination.HOME
    }

    private fun onProgress(progress: LoadProgress) {
        val step = when (progress.step) {
            LoadStep.PLAYLIST -> StartupStep.PLAYLIST
            LoadStep.SAVING -> StartupStep.SAVING
            LoadStep.EPG_DOWNLOAD -> StartupStep.EPG_DOWNLOAD
            LoadStep.EPG_IMPORT -> StartupStep.EPG_IMPORT
        }
        val fraction = progress.fraction
        mutableState.update { it.copy(bytesReceived = if (fraction == null) progress.done else null) }
        report(step, fraction ?: 0f)
    }

    /** Pourcentage global = début de l'étape + part réalisée de l'étape ; jamais en recul. */
    private fun report(step: StartupStep, fraction: Float) {
        val (start, end) = RANGES.getValue(step)
        val percent = (start + (end - start) * fraction.coerceIn(0f, 1f)).toInt()
        mutableState.update { state ->
            state.copy(
                step = if (step.ordinal >= state.step.ordinal) step else state.step,
                percent = maxOf(state.percent, percent),
            )
        }
    }

    companion object {
        const val ARG_RELOAD = "reload"

        private const val PLAYLIST_MAX_AGE_MILLIS = 12 * 60 * 60_000L

        private val RANGES = mapOf(
            StartupStep.PREPARING to (0 to 2),
            StartupStep.PLAYLIST to (2 to 50),
            StartupStep.SAVING to (50 to 60),
            StartupStep.EPG_DOWNLOAD to (60 to 85),
            StartupStep.EPG_IMPORT to (85 to 99),
            StartupStep.READY to (100 to 100),
        )
    }
}
