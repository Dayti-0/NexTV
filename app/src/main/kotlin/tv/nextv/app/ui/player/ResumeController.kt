package tv.nextv.app.ui.player

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.progress.ProgressEntity
import tv.nextv.app.data.progress.ProgressRepository
import tv.nextv.core.model.ContentKind

/**
 * Reprise de lecture des films et des épisodes : question « reprendre / recommencer », position
 * enregistrée au fil de la lecture, titre et épisode affichés, épisode suivant d'une série.
 *
 * @param initialMode [PlayerViewModel.RESUME_ASK], [PlayerViewModel.RESUME_YES] ou [PlayerViewModel.RESUME_NO].
 * @param player lecteur en cours (position et durée).
 */
internal class ResumeController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val session: PlaybackSession,
    initialMode: String,
    private val player: () -> ExoPlayer?,
    private val host: Host,
) {
    /** Ce que la reprise demande à l'écran du lecteur. */
    interface Host {
        /** Lit cette version du film ou de l'épisode (à la position de [PlaybackSession.startPositionMs]). */
        suspend fun playVariant(variant: ChannelEntity)

        /** Ouvre un autre film ou épisode. */
        fun load(id: Long)
    }

    private var resumeMode: String = initialMode
    private var pendingResume: Pair<ChannelEntity, ProgressEntity>? = null

    /** @return vrai si la lecture attend le choix « reprendre / recommencer » (ou reprend un autre épisode). */
    suspend fun handle(requested: ChannelEntity): Boolean {
        val mode = resumeMode
        resumeMode = PlayerViewModel.RESUME_ASK
        session.startPositionMs = 0
        if (mode == PlayerViewModel.RESUME_NO) return false
        val progress = container.progress.get(requested.sourceId, requested.itemKey) ?: return false
        val sameItem = progress.channelId == requested.id
        if (mode == PlayerViewModel.RESUME_YES) {
            if (sameItem) {
                session.startPositionMs = progress.positionMs
                return false
            }
            resumeOther(progress)
            return true
        }
        if (sameItem && progress.positionMs <= 0) return false
        pendingResume = requested to progress
        state.update {
            it.copy(
                buffering = false,
                resumePrompt = ResumePrompt(
                    title = progress.title,
                    resumeLabel = progress.episodeLabel,
                    positionMs = progress.positionMs,
                    startLabel = if (sameItem) null else episodeLabel(requested),
                ),
            )
        }
        return true
    }

    /** Choix fait dans la question « reprendre / recommencer ». */
    fun answer(resume: Boolean) {
        val (requested, progress) = pendingResume ?: return
        pendingResume = null
        state.update { it.copy(resumePrompt = null, buffering = true) }
        scope.launch {
            when {
                !resume -> {
                    session.startPositionMs = 0
                    host.playVariant(requested)
                }
                progress.channelId == requested.id -> {
                    session.startPositionMs = progress.positionMs
                    host.playVariant(requested)
                }
                else -> resumeOther(progress)
            }
        }
    }

    /** Reprend un autre épisode (celui en cours dans la série). */
    private fun resumeOther(progress: ProgressEntity) {
        resumeMode = PlayerViewModel.RESUME_YES
        host.load(progress.channelId)
    }

    /** Enregistre la position du film ou de l'épisode en cours. */
    suspend fun save(positionMs: Long? = null, durationMs: Long? = null) {
        val item = session.playing ?: return
        if (item.kind == ContentKind.LIVE.name || state.value.resumePrompt != null) return
        val player = player()
        val position = positionMs ?: player?.currentPosition ?: return
        val duration = (durationMs ?: player?.duration ?: 0).takeIf { it > 0 } ?: 0
        val entity = ProgressEntity(
            sourceId = item.sourceId,
            itemKey = item.itemKey,
            kind = item.kind,
            channelId = item.id,
            title = if (item.kind == ContentKind.SERIES.name) item.seriesName ?: item.title else item.title,
            episodeLabel = if (item.kind == ContentKind.SERIES.name) episodeLabel(item) else null,
            logoUrl = item.logoUrl,
            positionMs = position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
        )
        val finished = duration > 0 && (duration - position < ProgressRepository.END_MARGIN_MS || position >= duration * 95 / 100)
        if (finished && item.kind == ContentKind.SERIES.name) {
            // Épisode terminé : la série reprendra à l'épisode suivant.
            val episodes = container.channelDao.episodes(item.sourceId, item.itemKey)
            val next = episodes.getOrNull(episodes.indexOfFirst { it.id == item.id } + 1)
            if (next != null) {
                val nextEntity = container.channelDao.get(next.id)
                container.progress.saveNext(entity.copy(channelId = next.id, episodeLabel = nextEntity?.let(::episodeLabel)))
            } else {
                container.progress.clear(item.sourceId, item.itemKey)
            }
            return
        }
        container.progress.save(entity)
    }

    /** Film ou épisode arrivé à la fin : position finale enregistrée, épisode suivant proposé. */
    fun onVodEnded() {
        val player = player() ?: return
        scope.launch { save(positionMs = player.duration, durationMs = player.duration) }
        // Épisode terminé : le suivant est proposé.
        state.value.vod?.next?.let { next -> state.update { it.copy(upNext = next) } }
    }

    /** Écran du lecteur fermé : dernière position enregistrée dans la portée de l'application. */
    fun saveBeforeClose() {
        val player = player()
        val item = session.playing
        if (player != null && item != null && item.kind != ContentKind.LIVE.name && state.value.resumePrompt == null) {
            val position = player.currentPosition
            val duration = player.duration
            container.applicationScope.launch { save(position, duration) }
        }
    }

    /** Titre et épisode d'un film ou d'un épisode, avec l'épisode suivant de la série. */
    suspend fun vodInfo(item: ChannelEntity): VodInfo {
        if (item.kind != ContentKind.SERIES.name) {
            return VodInfo(item.title.ifBlank { item.name }, null, item.year, item.logoUrl, next = null)
        }
        val episodes = container.channelDao.episodes(item.sourceId, item.itemKey)
        val next = episodes.getOrNull(episodes.indexOfFirst { it.id == item.id } + 1)?.let { row ->
            val label = when {
                row.season != null && row.episode != null -> "S%02dE%02d".format(row.season, row.episode)
                row.episode != null -> "E%02d".format(row.episode)
                else -> null
            }
            NextEpisode(row.id, label, row.name, row.logoUrl)
        }
        val series = item.seriesName ?: item.title.ifBlank { item.name }
        val label = episodeLabel(item)
        val episodeTitle = item.name.takeIf { it.isNotBlank() && !it.equals(series, ignoreCase = true) }
        return VodInfo(series, listOfNotNull(label, episodeTitle).joinToString("  ·  ").ifEmpty { null }, item.year, item.logoUrl, next)
    }

    /** Épisode suivant de la série, depuis son début. */
    fun playNextEpisode() {
        val next = state.value.vod?.next ?: state.value.upNext ?: return
        state.update { it.copy(upNext = null) }
        scope.launch { save() }
        resumeMode = PlayerViewModel.RESUME_NO
        host.load(next.id)
    }

    fun dismissUpNext() {
        state.update { it.copy(upNext = null) }
    }

    private fun episodeLabel(item: ChannelEntity): String? {
        val season = item.season
        val episode = item.episode
        return when {
            season != null && episode != null -> "S%02dE%02d".format(season, episode)
            episode != null -> "E%02d".format(episode)
            else -> null
        }
    }
}
