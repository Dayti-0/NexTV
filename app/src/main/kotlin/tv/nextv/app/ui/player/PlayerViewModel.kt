package tv.nextv.app.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.player.CodecFailure
import tv.nextv.app.player.VlcPlayback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.app.player.describeCodecFailure
import androidx.media3.common.C
import androidx.media3.common.Player
import tv.nextv.app.AppContainer
import tv.nextv.app.ui.football.eventMatchOnAir
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.GroupRow
import tv.nextv.app.data.settings.AspectMode
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.toStream
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.home.Category
import tv.nextv.core.football.Broadcast
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.FootballDataResult
import tv.nextv.core.football.FootballMatches
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.net.RequestPurpose
import tv.nextv.core.source.Mirrors
import tv.nextv.core.stream.QualityTarget

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    context: Context,
) : ViewModel() {

    /** Contexte de l'application : ce modèle survit à l'activité, qu'il ne doit pas retenir. */
    private val context: Context = context.applicationContext

    private var channelId: Long = checkNotNull(handle.get<Long>(ARG_CHANNEL_ID))

    private val resources = context.resources

    /** Chaîne, version et flux en cours, partagés par les parties du lecteur. */
    private val session = PlaybackSession()

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val sourceId = MutableStateFlow<Long?>(null)

    // Menu des chaînes (« Retour ») : catégories, liste, programme de la chaîne sélectionnée.
    private val channelMenu = ChannelMenuController(container, viewModelScope, sourceId)
    val menu: StateFlow<ChannelMenuState> = channelMenu.menu
    val programmeSheet: StateFlow<ProgrammeSheetState?> = channelMenu.programmeSheet
    val menuProgramme: StateFlow<MenuProgramme?> = channelMenu.menuProgramme
    val menuGroups: StateFlow<List<GroupRow>> = channelMenu.groups
    val menuChannels: Flow<PagingData<MenuChannel>> = channelMenu.channels
    val menuFavorites: StateFlow<List<ChannelListRow>> = channelMenu.favorites

    /** Chaîne sélectionnée dans le menu : son programme s'affiche à côté. */
    fun focusMenuChannel(row: ChannelListRow) = channelMenu.focus(row)

    private var resumeOnStart = true

    /** Moteur VLC (son ou image que le lecteur de l'appli ne sait pas lire), pour le titre en cours. */
    private var vlc: VlcPlayback? = null
    private val mutableVlc = MutableStateFlow<VlcPlayback?>(null)
    val vlcPlayback: StateFlow<VlcPlayback?> = mutableVlc.asStateFlow()

    /** VLC déjà essayé pour ce titre (pas de nouvel essai s'il échoue lui aussi). */
    private var vlcTried = false

    /** Lecteur qui lit : celui de l'appli, ou le moteur VLC. */
    private fun currentPlayer(): Player? = vlc?.player ?: engine.current
    private var multicastLock: WifiManager.MulticastLock? = null
    private var loadJob: Job? = null

    /** Qualité visée (réglage commun à toutes les chaînes), modifiable depuis les options. */
    val qualityTarget: StateFlow<QualityTarget> = container.settings.qualityTarget
        .stateIn(viewModelScope, SharingStarted.Eagerly, QualityTarget.BEST)

    /** Format de l'image (réglage commun à toutes les lectures). */
    val aspectMode: StateFlow<AspectMode> = container.settings.aspectMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AspectMode.FIT)

    /** Dernier serveur qui a répondu (playlist, racine) : les chaînes suivantes commencent par lui. */
    private var workingServer: Pair<Long, String>? = null

    // Lecteur ExoPlayer : lecture, pistes, infos techniques.
    private val engine: PlayerEngine = PlayerEngine(
        container = container,
        scope = viewModelScope,
        state = mutableState,
        resources = resources,
        events = object : PlayerEngine.Events {
            override fun onBuffering() {
                // Pause, différé, retour en arrière : chargements voulus, pas une connexion qui faiblit.
                if (delayed.recording || state.value.paused || state.value.liveDelayMs > 0) return
                quality.onBufferingStarted()
            }
            override fun onReady() {
                quality.onReady()
                rememberServer()
                liveIsPlaying()
            }
            override fun onEnded() = this@PlayerViewModel.onEnded()
            override fun onError(error: PlaybackException) = handleError(error)
        },
    )
    val player: StateFlow<ExoPlayer?> = engine.player

    // Pause, différé, replay, sauts et avance rapide.
    private val delayed: DelayedPlaybackController = DelayedPlaybackController(
        container = container,
        context = context,
        scope = viewModelScope,
        state = mutableState,
        session = session,
        engine = engine,
        handle = handle,
        host = object : DelayedPlaybackController.Host {
            override fun start(forceHls: Boolean) = this@PlayerViewModel.start(forceHls)
            override fun reload() = load(channelId)
            override suspend fun refreshProgrammes() = this@PlayerViewModel.refreshProgrammes()
            override fun showPlaylist(sourceId: Long) {
                this@PlayerViewModel.sourceId.value = sourceId
            }

            override fun restartedByUser() {
                quality.onUserRestart()
            }
        },
    )

    // Qualité automatique (versions SD, HD, FHD, 4K d'une même chaîne).
    private val quality: AutoQualityController = AutoQualityController(
        container = container,
        scope = viewModelScope,
        state = mutableState,
        session = session,
        resources = resources,
        host = object : AutoQualityController.Host {
            override suspend fun playVariant(variant: ChannelEntity, notice: String) = this@PlayerViewModel.playVariant(variant, notice)
        },
    )

    // Reprise de lecture des films et épisodes.
    private val resume = ResumeController(
        container = container,
        scope = viewModelScope,
        state = mutableState,
        session = session,
        initialMode = handle.get<String>(ARG_RESUME) ?: RESUME_ASK,
        player = { currentPlayer() },
        host = object : ResumeController.Host {
            override suspend fun playVariant(variant: ChannelEntity) = this@PlayerViewModel.playVariant(variant, notice = null)
            override fun load(id: Long) = this@PlayerViewModel.load(id)
        },
    )

    init {
        load(channelId)
        // Ces surveillances s'arrêtent quand l'écran n'est plus affiché (veille, autre application).
        viewModelScope.launch {
            while (true) {
                awaitStarted()
                refreshProgrammes()
                // Match en cours dans le bandeau : score relu plus souvent (un but s'affiche vite).
                delay(if (state.value.match?.live == true) MATCH_REFRESH_MILLIS else PROGRAMME_REFRESH_MILLIS)
            }
        }
        // Surveillance de la lecture (2 fois par seconde) : blocage, images perdues, retour à une meilleure qualité.
        viewModelScope.launch {
            while (true) {
                delay(QUALITY_CHECK_MILLIS)
                awaitStarted()
                delayed.updateWatchedAt()
                // Épisode : le suivant est proposé 40 s avant la fin.
                resume.checkUpNext()
                val adMuted = engine.adVolume.enabled && engine.adVolume.attenuating
                if (state.value.adMuted != adMuted) mutableState.update { it.copy(adMuted = adMuted) }
                val dropped = engine.newDroppedFrames()
                // Pause ou différé : changer de version ramènerait au direct. Moteur VLC : le lecteur de l'appli est arrêté.
                if (vlc != null || delayed.recording || state.value.paused || state.value.liveDelayMs > 0) continue
                quality.check(dropped)
            }
        }
        // Son illisible par le lecteur de l'appli (ou introuvable dans un film) : le moteur VLC prend la suite.
        viewModelScope.launch {
            mutableState.map { it.audioProblem to it.isLive }.distinctUntilChanged().collect { (problem, live) ->
                if (problem == AudioProblem.UNSUPPORTED || (problem == AudioProblem.NO_TRACK && !live)) switchToVlc()
            }
        }
        // Position des films et épisodes, pour la reprise de lecture.
        viewModelScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_MILLIS)
                awaitStarted()
                resume.save()
            }
        }
    }

    /** Écran du lecteur affiché (ni veille, ni autre application au premier plan). */
    private val started = MutableStateFlow(false)

    /** L'écran a déjà été affiché : ensuite, « pas affiché » veut dire quitté (et non pas encore ouvert). */
    private var shownOnce = false

    /** Flux à ouvrir au retour sur l'écran (son ouverture est arrivée pendant qu'il était quitté) : repli HLS ou non. */
    private var startWhenShown: Boolean? = null

    private suspend fun awaitStarted() {
        if (!started.value) started.first { it }
    }

    fun selectAudio(choice: TrackChoice) {
        val playback = vlc ?: return engine.selectAudio(choice)
        playback.selectAudio(choice.trackIndex)
        mutableState.update { it.copy(tracks = vlcTracks(playback)) }
    }

    /** [choice] `null` : sous-titres désactivés. */
    fun selectSubtitles(choice: TrackChoice?) {
        val playback = vlc ?: return engine.selectSubtitles(choice)
        playback.selectSubtitles(choice?.trackIndex ?: -1)
        mutableState.update { it.copy(tracks = vlcTracks(playback)) }
    }

    // --- Moteur VLC ---------------------------------------------------------------------------------

    /** « Lire avec le moteur VLC » (options du lecteur). */
    fun useVlc() {
        vlcTried = false
        switchToVlc()
    }

    /**
     * Le moteur VLC lit le titre en cours, au même endroit, dans le même écran : le lecteur de l'appli s'arrête
     * (une seule connexion au fournisseur). @return faux s'il lit déjà, ou s'il a déjà échoué sur ce titre.
     */
    private fun switchToVlc(): Boolean {
        if (vlc != null || vlcTried) return false
        val request = session.request ?: return false
        vlcTried = true
        val live = state.value.isLive
        val position = if (live) 0L else (engine.current?.currentPosition?.takeIf { it > 0 } ?: session.startPositionMs).coerceAtLeast(0)
        container.logger.info(TAG, "Moteur VLC : son ${state.value.unsupportedAudio ?: "?"}, problème ${state.value.audioProblem}")
        val playback = try {
            VlcPlayback(context, vlcListener)
        } catch (e: Throwable) {
            // Bibliothèque absente pour ce processeur : le lecteur de l'appli continue, avec le message habituel.
            container.logger.warn(TAG, "Moteur VLC indisponible", e)
            return false
        }
        engine.stop()
        delayed.stopTimeshift()
        vlc = playback
        mutableVlc.value = playback
        mutableState.update {
            it.copy(engineVlc = true, audioProblem = null, unsupportedAudio = null, error = null, buffering = true, paused = false, tracks = PlayerTracks(emptyList(), emptyList()))
        }
        playback.play(urlWithCredentials(request), request.userAgent, position)
        return true
    }

    private fun releaseVlc() {
        val playback = vlc ?: return
        vlc = null
        mutableVlc.value = null
        playback.release()
        mutableState.update { it.copy(engineVlc = false) }
    }

    private val vlcListener = object : VlcPlayback.Listener {
        override fun onPlaying() {
            mutableState.update { it.copy(buffering = false, error = null) }
        }

        override fun onBuffering(buffering: Boolean) {
            if (state.value.buffering != buffering) mutableState.update { it.copy(buffering = buffering) }
        }

        override fun onEnded() = this@PlayerViewModel.onEnded()

        override fun onError() {
            container.logger.warn(TAG, "Moteur VLC : lecture impossible")
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_error_vlc)) }
        }

        override fun onTracksChanged() {
            val playback = vlc ?: return
            mutableState.update { it.copy(tracks = vlcTracks(playback)) }
        }
    }

    /** Pistes son et sous-titres lues par VLC, pour le menu des options. */
    private fun vlcTracks(playback: VlcPlayback): PlayerTracks {
        val audio = playback.audioTracks().map { (id, name) -> TrackChoice(-1, id, name, null, id == playback.audioTrack) }
        val subtitles = playback.subtitleTracks().map { (id, name) -> TrackChoice(-1, id, name, null, id == playback.subtitleTrack) }
        return PlayerTracks(audio, subtitles)
    }

    /** Adresse du flux, identifiants compris s'il en faut (le moteur VLC n'a pas l'authentification du lecteur). */
    private fun urlWithCredentials(request: PlaybackRequest): String {
        val credentials = request.credentials ?: return request.stream.url
        return request.stream.url.toHttpUrlOrNull()?.newBuilder()?.username(credentials.username)?.password(credentials.password)?.build()?.toString()
            ?: request.stream.url
    }

    fun setLowerAdVolume(enabled: Boolean) {
        viewModelScope.launch { container.settings.setLowerAdVolume(enabled) }
    }

    fun setAspectMode(mode: AspectMode) {
        viewModelScope.launch { container.settings.setAspectMode(mode) }
    }

    /**
     * Qualité visée choisie dans les options : enregistrée pour toutes les chaînes, et appliquée tout
     * de suite à celle-ci quand elle existe dans une autre version (sauf en pause, différé ou replay).
     */
    fun setQualityTarget(target: QualityTarget) {
        viewModelScope.launch {
            container.settings.setQualityTarget(target)
            if (delayed.recording || delayed.replay != null || state.value.paused || state.value.liveDelayMs > 0) return@launch
            val primary = session.channel ?: return@launch
            if (quality.changesVersion(target, session.playing?.id)) load(primary.id)
        }
    }

    /** Chaîne regardée juste avant celle-ci (touche « chaîne précédente », ⏪). */
    fun previousChannel() {
        val current = session.listed ?: return
        if (current.kind != ContentKind.LIVE.name) return
        viewModelScope.launch {
            val key = container.settings.recentChannels(current.sourceId).first().firstOrNull { it != current.itemKey } ?: return@launch
            val id = container.channelDao.primaryIdByKey(current.sourceId, key) ?: return@launch
            load(id)
        }
    }

    /** Chaîne suivante (+1) ou précédente (-1) de la playlist, en boucle. */
    fun zap(direction: Int) {
        val current = session.listed ?: return
        if (current.kind != ContentKind.LIVE.name) return
        viewModelScope.launch {
            val dao = container.channelDao
            // Ordre de la liste « Toutes » : depuis la place de la chaîne en cours dans cette liste.
            val position = current.variantKey.takeIf { it.isNotEmpty() }?.let { dao.listedPosition(current.sourceId, it) } ?: current.position
            val target = if (direction > 0) {
                dao.nextChannelId(current.sourceId, position) ?: dao.nextChannelId(current.sourceId, -1)
            } else {
                dao.previousChannelId(current.sourceId, position) ?: dao.previousChannelId(current.sourceId, Int.MAX_VALUE)
            }
            if (target != null && target != current.id) load(target)
        }
    }

    fun openMenu() {
        val current = session.listed ?: return
        channelMenu.open(current)
    }

    fun closeMenu() = channelMenu.close()

    fun selectMenuCategory(category: Category) = channelMenu.selectCategory(category)

    /** Programme en cours (ou le prochain) d'une chaîne du menu, avec sa description. */
    fun showProgramme(row: ChannelListRow) = channelMenu.showProgramme(row)

    fun hideProgramme() = channelMenu.hideProgramme()

    fun playFromMenu(id: Long) {
        closeMenu()
        if (id != session.listed?.id) load(id)
    }

    private fun load(id: Long) {
        // Autre titre (ou nouvel essai) : de nouveau le lecteur de l'appli, VLC seulement s'il le faut.
        releaseVlc()
        vlcTried = false
        resetLiveRetries()
        reopenLiveOnStart = false
        startWhenShown = null
        delayed.beforeLoad(otherChannel = id != channelId)
        // Nouveau titre : les essais après un échec de décodage repartent de zéro.
        codecTries = CodecTries()
        engine.resetExcludedDecoders()
        engine.startZapTimer()
        channelId = id
        handle[ARG_CHANNEL_ID] = id
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadChannel(id) }
    }

    private suspend fun loadChannel(id: Long) {
        val dao = container.channelDao
        val requested = dao.get(id)
        if (requested == null) {
            engine.stop()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_channel_missing)) }
            return
        }
        val isLive = requested.kind == ContentKind.LIVE.name
        // Pour l'affichage et le zapping : la meilleure version de la chaîne.
        val primary = if (isLive && requested.variantKey.isNotEmpty()) dao.primaryOf(requested.sourceId, requested.variantKey) ?: requested else requested
        session.channel = primary
        session.listed = requested
        val replayed = delayed.replay
        if (replayed != null && isLive) {
            // Programme encore en cours : « Depuis le début », avec le bandeau du direct. Pas de qualité automatique.
            quality.reset()
            delayed.playReplay(requested, primary, replayed)
            return
        }
        delayed.playingLive()
        // Chaînes récentes de l'accueil.
        if (isLive) container.settings.addRecentChannel(primary.sourceId, primary.itemKey)
        sourceId.value = primary.sourceId
        val source = container.sourceRepository.getSource(primary.sourceId)

        val startId = quality.prepare(requested, primary, isLive)

        mutableState.update {
            it.copy(
                channelName = primary.title.ifBlank { primary.name },
                channelNumber = primary.channelNumber,
                logoUrl = primary.logoUrl,
                sourceName = source?.name.orEmpty(),
                isLive = isLive,
                now = null,
                next = null,
                match = null,
                error = null,
                qualityNotice = null,
                buffering = true,
                channelVersion = it.channelVersion + 1,
            )
        }
        refreshProgrammes()
        if (!isLive) mutableState.update { it.copy(vod = resume.vodInfo(requested), upNext = null, upNextSeconds = null) }
        if (isLive) session.startPositionMs = 0
        if (!isLive && resume.handle(requested)) {
            // Question « reprendre / recommencer » : pas d'image attendue avant la réponse.
            if (state.value.resumePrompt != null) engine.cancelZapTimer()
            return
        }
        val firstId = quality.currentChannelId ?: startId
        val variant = if (firstId == requested.id) requested else dao.get(firstId) ?: requested
        playVariant(variant, notice = null)
    }

    /** Lance la lecture d'une version précise de la chaîne. */
    private suspend fun playVariant(variant: ChannelEntity, notice: String?) {
        delayed.stopTimeshift()
        session.playing = variant
        quality.rememberWhenStable(variant)
        val stream = variant.toStream()
        mutableState.update {
            it.copy(
                notices = stream.issues.map { issue -> resources.describe(issue) },
                quality = StreamQuality.entries.firstOrNull { q -> q.name == variant.quality }?.label,
                qualityNotice = notice,
                error = null,
            )
        }
        if (stream.support == StreamSupport.UNSUPPORTED) {
            engine.stop()
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.stream_unsupported)) }
            return
        }
        val source = container.sourceRepository.getSource(variant.sourceId)
        session.serverRoots = source?.let(::serverRootsOf).orEmpty()
        val url = workingServer?.takeIf { it.first == variant.sourceId }
            ?.let { (_, root) -> stream.url.takeIf { Mirrors.alternatives(it, session.serverRoots).isNotEmpty() }?.let { Mirrors.rewrite(it, root) } }
            ?: stream.url
        session.backupUrls = Mirrors.alternatives(url, session.serverRoots)
        session.request = PlaybackRequest(
            stream = stream.copy(url = url),
            userAgent = source?.userAgent,
            credentials = container.sourceRepository.credentialsFor(variant.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        if (stream.format == StreamFormat.UDP) acquireMulticastLock() else releaseMulticastLock()
        session.triedHlsFallback = false
        start(forceHls = false)
    }

    private suspend fun refreshProgrammes() {
        val current = session.channel ?: return
        if (current.kind != ContentKind.LIVE.name || delayed.replayingPast) return
        // En différé : le programme à l'écran, pas celui du direct.
        val shown = state.value
        val now = System.currentTimeMillis() - shown.liveDelayMs - (shown.pausedAtMillis?.let { System.currentTimeMillis() - it } ?: 0)
        val upcoming = container.programmeDao.upcoming(current.id, now, limit = 2)
            .map { PlayerProgramme(it.title, it.startMillis, it.stopMillis) }
        val live = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }
        mutableState.update { it.copy(now = live, next = upcoming.firstOrNull { p -> p != live }) }
        // Chaîne « événement » (LALIGA 2 LIVE MATCH…) sans programme du guide : le match qu'elle diffuse.
        val match = (if (live != null) matchOnAir(current.id, now) else null)
            ?: eventMatchOnAir(container, current.sourceId, current.name, now)?.let(::playerMatchOf)
        if (session.channel?.id == current.id) mutableState.update { it.copy(match = match) }
    }

    /**
     * Match que diffuse le programme en cours de la chaîne, avec son score : celui de football-data.org
     * (réponse partagée avec la section Foot), sinon l'affiche du guide (coupes d'Europe, titre sans
     * compétition) avec le score en direct.
     */
    private suspend fun matchOnAir(channelId: Long, now: Long): PlayerMatch? {
        val programme = container.programmeDao.upcoming(channelId, now, limit = 1).firstOrNull()?.takeIf { it.startMillis <= now } ?: return null
        val result = container.footballData.matches(now, now + FOOTBALL_WINDOW_MILLIS) as? FootballDataResult.Success
        val broadcast = Broadcast(Unit, programme.title, programme.subtitle, programme.category, programme.description, programme.startMillis, programme.stopMillis)
        val match = result?.let { BroadcastFinder.assign(it.matches, listOf(broadcast)).keys.firstOrNull() }
        if (match != null) {
            val live = match.status == MatchStatus.LIVE
            return PlayerMatch(
                home = match.home.displayName,
                away = match.away.displayName,
                homeCrest = match.home.crestUrl,
                awayCrest = match.away.crestUrl,
                score = if (match.homeScore != null && match.awayScore != null && (live || match.status == MatchStatus.FINISHED)) {
                    "${match.homeScore} – ${match.awayScore}"
                } else {
                    null
                },
                live = live,
                clock = match.clock.takeIf { live },
                halfTime = live && match.halfTime,
            )
        }
        val fixture = FootballMatches.fixtureOf(programme.title, programme.subtitle, programme.category, programme.description)
        val (home, away) = fixture?.let { it.home to it.away }
            ?: FootballMatches.teamsOf(programme.title, programme.subtitle, programme.category)
            ?: return null
        val score = container.liveScores.find(fixture?.competition, home, away, programme.startMillis, programme.stopMillis)
            ?.takeIf { it.inPlay || it.finished }
        val crest = result?.let { BroadcastFinder.crests(it.matches.flatMap { scheduled -> listOf(scheduled.home, scheduled.away) }) }
        return PlayerMatch(
            home = home,
            away = away,
            homeCrest = crest?.invoke(home),
            awayCrest = crest?.invoke(away),
            score = score?.let { if (it.homeScore != null && it.awayScore != null) "${it.homeScore} – ${it.awayScore}" else null },
            live = score?.inPlay == true,
            clock = score?.clock,
            halfTime = score?.halfTime == true,
        )
    }

    /** Match du calendrier (ESPN) pour le bandeau : équipes, écussons et score en direct. */
    private fun playerMatchOf(match: ScheduledMatch): PlayerMatch {
        val live = match.status == MatchStatus.LIVE
        return PlayerMatch(
            home = match.home.displayName,
            away = match.away.displayName,
            homeCrest = match.home.crestUrl,
            awayCrest = match.away.crestUrl,
            score = if (match.homeScore != null && match.awayScore != null && (live || match.status == MatchStatus.FINISHED)) {
                "${match.homeScore} – ${match.awayScore}"
            } else {
                null
            },
            live = live,
            clock = match.clock.takeIf { live },
            halfTime = live && match.halfTime,
        )
    }

    private fun start(forceHls: Boolean) {
        val playbackRequest = session.request ?: return
        // Écran quitté pendant l'ouverture (veille, autre application) : rien ne joue en arrière-plan, le flux
        // sera ouvert au retour.
        if (shownOnce && !started.value) {
            startWhenShown = forceHls
            return
        }
        session.playingAsHls = forceHls || playbackRequest.stream.format == StreamFormat.HLS
        // Position de départ seulement pour une reprise : un direct démarre au plus près du direct.
        // Direct : les dernières secondes sont gardées pour revenir en arrière (←).
        val live = state.value.isLive && delayed.replay == null && !delayed.restarting
        engine.play(playbackRequest, forceHls, session.startPositionMs, record = live)
        mutableState.update { it.copy(buffering = true) }
    }

    private fun handleError(error: PlaybackException) {
        val player = engine.current ?: return
        val playbackRequest = session.request ?: return
        // Différé interrompu, replay HLS refusé, direct sorti de la fenêtre du serveur.
        if (delayed.handleError(error, player)) return
        val format = playbackRequest.stream.format
        if (!session.triedHlsFallback && format in HLS_FALLBACK_FORMATS && error.errorCode in HLS_FALLBACK_ERRORS) {
            // Beaucoup d'URL IPTV sans extension servent en réalité du HLS.
            session.triedHlsFallback = true
            container.logger.info(TAG, "Format non reconnu, nouvelle tentative en HLS")
            start(forceHls = true)
            return
        }
        if (error.errorCode in SERVER_ERRORS && session.backupUrls.isNotEmpty()) {
            // Serveur injoignable : même flux sur un serveur de secours de la playlist.
            container.logger.info(TAG, "Serveur injoignable (${error.errorCodeName}), essai d'un serveur de secours")
            session.request = playbackRequest.copy(stream = playbackRequest.stream.copy(url = session.backupUrls.first()))
            session.backupUrls = session.backupUrls.drop(1)
            mutableState.update { it.copy(qualityNotice = resources.getString(R.string.player_backup_server)) }
            start(forceHls = session.playingAsHls)
            return
        }
        container.logger.warn(TAG, "Lecture impossible (${error.errorCodeName}) : ${playbackRequest.stream.url}", error)
        // Décodage impossible : autre piste son, sans sous-titres, autre décodeur ou autre copie du titre.
        val failure = CodecFailure.of(error)
        if (failure != null && recoverFromCodecFailure(failure, player)) return
        // Autre version de la même chaîne, de qualité inférieure, si elle existe.
        if (quality.onError()) return
        // Direct qui jouait, coupé (réseau, serveur) : rouvert quelques fois avant d'afficher l'erreur.
        if (failure == null && error.errorCode in RETRIABLE_LIVE_ERRORS && retryLive(error.errorCodeName)) return
        val viaProxy = container.httpClients.usesProxy(RequestPurpose.STREAM) &&
            format != StreamFormat.RTSP && format != StreamFormat.UDP
        val message = failure?.let { resources.describeCodecFailure(it) } ?: resources.describePlaybackError(error, viaProxy)
        mutableState.update { it.copy(buffering = false, error = message) }
    }

    /** Essais faits sur le titre en cours après des échecs de décodage. */
    private class CodecTries {
        var audio = 0
        var subtitles = false
        var decoders = 0
        val copies = mutableSetOf<Long>()
    }

    private var codecTries = CodecTries()

    /**
     * Reprise après un échec de décodage, sans quitter le lecteur :
     * - son illisible : autre piste son, sinon l'image sans le son ;
     * - sous-titres illisibles : coupés ;
     * - vidéo illisible : autre décodeur de l'appareil (logiciel compris), puis autre copie du même titre
     *   dans la playlist (version HD d'un film 4K…).
     * @return vrai si une reprise est lancée (sinon le message d'erreur s'affiche).
     */
    private fun recoverFromCodecFailure(failure: CodecFailure, player: ExoPlayer): Boolean {
        val format = failure.format
        container.logger.warn(TAG, "Décodage impossible : ${failure.trackType} ${format?.sampleMimeType} ${format?.codecs} ${format?.width}x${format?.height} (${failure.decoderName})")
        when (failure.trackType) {
            C.TRACK_TYPE_AUDIO -> {
                // D'abord le son décodé par l'appli (MP2, FFmpeg) au même endroit, puis une autre piste son.
                if (engine.preferSoftwareAudio()) {
                    session.startPositionMs = if (state.value.isLive) 0L else player.currentPosition.coerceAtLeast(0)
                    start(forceHls = session.playingAsHls)
                    return true
                }
                if (codecTries.audio < MAX_AUDIO_TRIES) {
                    codecTries.audio++
                    engine.skipAudioTrack(format)
                    player.prepare()
                    return true
                }
            }
            C.TRACK_TYPE_TEXT -> if (!codecTries.subtitles) {
                codecTries.subtitles = true
                engine.disableSubtitles()
                player.prepare()
                return true
            }
            else -> {
                val restartAt = if (state.value.isLive) 0L else player.currentPosition.coerceAtLeast(0)
                // Autre décodeur (pas quand aucun n'a pu démarrer : le lecteur les a déjà tous essayés).
                if (!failure.initFailed && codecTries.decoders < MAX_DECODER_TRIES && engine.excludeDecoder(format?.sampleMimeType, failure.decoderName) != null) {
                    codecTries.decoders++
                    session.startPositionMs = restartAt
                    mutableState.update { it.copy(qualityNotice = resources.getString(R.string.player_other_decoder)) }
                    start(forceHls = session.playingAsHls)
                    return true
                }
                // Autre copie du titre (film en double dans la playlist, autre qualité).
                val playing = session.playing ?: return false
                if (playing.kind == ContentKind.LIVE.name || codecTries.copies.size >= MAX_COPY_TRIES) return switchToVlc()
                codecTries.copies += playing.id
                viewModelScope.launch {
                    val copy = container.channelDao.otherCopies(
                        playing.sourceId, playing.kind, playing.itemKey, playing.season, playing.episode, playing.id, playing.streamUrl, playing.qualityRank,
                    ).firstOrNull { it.id !in codecTries.copies }
                    if (copy == null) {
                        // Aucune autre version : le moteur VLC essaie avant le message.
                        if (switchToVlc()) return@launch
                        val message = resources.describeCodecFailure(failure) ?: resources.getString(R.string.player_error_codec)
                        mutableState.update { it.copy(buffering = false, error = message) }
                        return@launch
                    }
                    container.logger.info(TAG, "Autre copie du titre essayée : ${copy.id}")
                    engine.resetExcludedDecoders()
                    session.startPositionMs = restartAt
                    playVariant(copy, resources.getString(R.string.player_other_copy))
                }
                return true
            }
        }
        return false
    }

    /** Le flux joue : son serveur sert de départ aux chaînes suivantes de la playlist. */
    private fun rememberServer() {
        val variant = session.playing ?: return
        if (session.serverRoots.isEmpty() || delayed.playingTimeshift) return
        val url = session.request?.stream?.url ?: return
        if (Mirrors.alternatives(url, session.serverRoots).isEmpty()) return
        Mirrors.normalize(url)?.let { workingServer = variant.sourceId to it }
    }

    fun retry() {
        session.triedHlsFallback = false
        val variant = session.playing
        if (session.request == null || variant == null) {
            load(channelId)
        } else {
            // Nouvel essai complet : toutes les versions redeviennent candidates.
            load(session.listed?.id ?: variant.id)
        }
    }

    fun onStart() {
        started.value = true
        shownOnce = true
        if (reopenLiveOnStart) {
            // Direct fermé à la sortie (veille…) : la chaîne est rouverte, au direct.
            reopenLiveOnStart = false
            load(session.listed?.id ?: channelId)
            return
        }
        startWhenShown?.let { forceHls ->
            startWhenShown = null
            start(forceHls)
            return
        }
        vlc?.let { playback ->
            if (resumeOnStart) playback.player.play()
            return
        }
        val player = engine.current ?: return
        if (resumeOnStart) {
            // Flux en direct : on reprend au direct plutôt qu'à la position mise en pause.
            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
            player.play()
        }
    }

    fun onStop() {
        started.value = false
        val player = currentPlayer() ?: return
        if (vlc == null && watchingLive && state.value.error == null) {
            // Direct : le flux est fermé plutôt que mis en pause. La connexion au fournisseur (souvent une seule
            // permise) et le décodeur sont rendus pendant la veille ; mis en pause, le lecteur rejouait au retour
            // d'anciennes images avant de tomber en erreur. Un différé en cours s'arrête aussi (plus d'enregistrement
            // en arrière-plan). La chaîne est rouverte au retour.
            reopenLiveOnStart = true
            liveRetryJob?.cancel()
            liveStableJob?.cancel()
            delayed.stopTimeshift()
            engine.stop()
            return
        }
        resumeOnStart = player.playWhenReady
        player.pause()
        viewModelScope.launch { resume.save() }
    }

    /** Pause / lecture. En direct, la pause crée un différé ; le retard sur le direct est affiché. */
    fun togglePause() {
        vlc?.let { playback ->
            val player = playback.player
            player.playWhenReady = !player.playWhenReady
            // Direct : « Pause » affichée dans le bandeau.
            if (state.value.isLive) mutableState.update { it.copy(paused = !player.playWhenReady) }
            return
        }
        quality.onUserRestart()
        delayed.togglePause()
    }

    /** Retour au direct : fin du différé, de « Depuis le début » ou du replay. */
    fun backToLive() = delayed.backToLive()

    /** ← en direct : recule de [seconds]. @return faux si ce n'est pas possible sur cette chaîne. */
    fun rewindLive(seconds: Int): Boolean {
        quality.onUserRestart()
        return delayed.rewindLive(seconds)
    }

    /** Replay ou « Depuis le début » : recule ou avance de [steps] pas (voir [DelayedPlaybackController.jump]). */
    fun jump(steps: Int) {
        quality.onUserRestart()
        delayed.jump(steps)
    }

    /** ← → maintenu : avance ([direction] 1) ou retour (−1) rapide x2, à rappeler tant que la touche reste enfoncée. */
    fun fastSeek(direction: Int) {
        quality.onUserRestart()
        delayed.fastSeek(direction)
    }

    /** Touche relâchée : lecture normale à l'endroit atteint. */
    fun stopFastSeek() {
        quality.onUserRestart()
        delayed.stopFastSeek()
    }

    private fun onEnded() {
        if (delayed.onEnded()) return
        if (!state.value.isLive) {
            resume.onVodEnded()
            return
        }
        // Direct fermé par le serveur (flux continu qui se termine) : rouvert, sinon l'image resterait figée
        // sans un mot. Moteur VLC : il se reconnecte lui-même.
        if (vlc != null || !watchingLive) return
        if (!retryLive("flux terminé")) {
            mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_error_stream_ended)) }
        }
    }

    // --- Relance du direct et veille ----------------------------------------------------------------

    /** Direct proprement dit : ni replay ni « Depuis le début » (enregistrements du serveur, qui ont une fin). */
    private val watchingLive: Boolean get() = state.value.isLive && delayed.replay == null && !delayed.restarting

    /** Relances du direct interrompu : combien, et après quelle attente. */
    private val liveRetries = LiveRetries()
    private var liveRetryJob: Job? = null
    private var liveStableJob: Job? = null

    /** Direct fermé en quittant l'écran (veille, autre application) : à rouvrir au retour. */
    private var reopenLiveOnStart = false

    private fun resetLiveRetries() {
        liveRetryJob?.cancel()
        liveStableJob?.cancel()
        liveRetries.newChannel()
    }

    /** Le flux joue : après [LIVE_STABLE_MILLIS] sans coupure, les relances repartent de zéro. */
    private fun liveIsPlaying() {
        if (!watchingLive || delayed.playingTimeshift) return
        liveRetries.playing()
        if (liveStableJob?.isActive == true) return
        liveStableJob = viewModelScope.launch {
            delay(LIVE_STABLE_MILLIS)
            liveRetries.stable()
        }
    }

    /**
     * Le direct qui jouait s'est arrêté (coupure du réseau, flux fermé par le serveur) : il est rouvert après une
     * courte attente, quelques fois au plus tant qu'il n'a pas rejoué sans coupure ([LiveRetries]). Une chaîne qui
     * ne démarre pas du tout n'est pas concernée : son erreur s'affiche tout de suite.
     *
     * @return faux quand ce n'est pas le cas, ou que les essais sont épuisés (le message d'erreur s'affiche).
     */
    private fun retryLive(reason: String): Boolean {
        if (!watchingLive || vlc != null || session.request == null) return false
        val wait = liveRetries.next() ?: return false
        liveStableJob?.cancel()
        container.logger.info(TAG, "Direct interrompu ($reason) : relance ${liveRetries.done}")
        mutableState.update { it.copy(buffering = true, error = null) }
        liveRetryJob?.cancel()
        liveRetryJob = viewModelScope.launch {
            delay(wait)
            // Écran quitté entre-temps : la chaîne sera rouverte au retour.
            if (!started.value) {
                reopenLiveOnStart = true
                return@launch
            }
            // Le chargement qui suit n'est pas une connexion qui faiblit (qualité automatique).
            quality.onUserRestart()
            start(forceHls = session.playingAsHls)
        }
        return true
    }

    /** Choix fait dans la question « reprendre / recommencer ». */
    fun answerResume(resume: Boolean) = this.resume.answer(resume)

    /** Film ou épisode : avance ou recul de [deltaMs] dans la vidéo. */
    fun seekBy(deltaMs: Long) {
        val player = currentPlayer() ?: return
        if (state.value.isLive) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        quality.onUserRestart()
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Film ou épisode : position choisie sur la barre de progression. */
    fun seekTo(positionMs: Long) {
        val player = currentPlayer() ?: return
        if (state.value.isLive) return
        quality.onUserRestart()
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Épisode suivant de la série, depuis son début. */
    fun playNextEpisode() = resume.playNextEpisode()

    fun dismissUpNext() = resume.dismissUpNext()

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("nextv-udp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    override fun onCleared() {
        resume.saveBeforeClose()
        releaseVlc()
        delayed.stopTimeshift()
        engine.release()
        releaseMulticastLock()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_RESUME = "resume"

        /** Replay : début et fin du programme passé (millisecondes), 0 pour une lecture normale. */
        const val ARG_REPLAY_START = "replayStart"
        const val ARG_REPLAY_STOP = "replayStop"

        /** Demander s'il faut reprendre un film ou un épisode commencé. */
        const val RESUME_ASK = "ask"

        /** Reprendre sans demander (catégorie « Reprendre la lecture », bouton « Reprendre »). */
        const val RESUME_YES = "yes"

        /** Depuis le début, sans demander. */
        const val RESUME_NO = "no"

        private const val PROGRESS_SAVE_MILLIS = 10_000L
        private const val TAG = "Player"
        private const val PROGRAMME_REFRESH_MILLIS = 30_000L
        private const val MATCH_REFRESH_MILLIS = 15_000L

        /** Échecs de décodage : pistes son, décodeurs vidéo et copies du titre essayés au plus. */
        private const val MAX_AUDIO_TRIES = 3
        private const val MAX_DECODER_TRIES = 2
        private const val MAX_COPY_TRIES = 3

        /** Lecture sans coupure pendant ce temps : les relances repartent de zéro. */
        private const val LIVE_STABLE_MILLIS = 30_000L

        /** Erreurs d'un direct qu'une réouverture peut régler : réseau, serveur, flux abîmé. Pas le décodage. */
        private val RETRIABLE_LIVE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )

        /** Même période que la section Foot : la réponse de football-data.org est partagée. */
        private const val FOOTBALL_WINDOW_MILLIS = 8 * 24 * 60 * 60_000L
        private const val QUALITY_CHECK_MILLIS = 500L
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        /** Erreurs qui peuvent venir du serveur (injoignable, en panne, bloqué) : un serveur de secours peut répondre. */
        private val SERVER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}
