package tv.nextv.app.ui.player

import tv.nextv.app.data.settings.AdSound
import tv.nextv.app.player.AdRadios
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import tv.nextv.app.player.AdTestedChannels
import tv.nextv.app.player.CodecFailure
import tv.nextv.app.player.HandoffMiss
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
import tv.nextv.app.data.db.ProgrammeEntity
import tv.nextv.app.data.db.displayTitle
import tv.nextv.app.data.settings.AspectMode
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.app.player.toStream
import tv.nextv.app.player.describePlaybackError
import tv.nextv.app.ui.common.describe
import tv.nextv.app.ui.home.Category
import tv.nextv.core.audio.NowPlaying
import tv.nextv.core.epg.Rebroadcast
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

    /**
     * Chaîne ouverte depuis les favoris (accueil, guide ou menu des chaînes) : « Retour » ouvre le menu sur les
     * favoris et haut/bas zappe dans les favoris. Déclaré avant `init` (utilisé par `load()`).
     */
    private var fromFavorites: Boolean = handle.get<Boolean>(ARG_FAVORITES) ?: false

    /**
     * Catégorie d'où la chaîne a été ouverte (accueil, guide ou menu des chaînes ; `""` : sans groupe) : haut/bas
     * zappe dans cette catégorie, en boucle. `null` : ouverte depuis « Toutes les chaînes », une recherche… : haut/bas
     * parcourt toutes les chaînes.
     */
    private var zapGroup: String? = handle.get<String>(ARG_GROUP)

    private val resources = context.resources

    /** Chaîne, version et flux en cours, partagés par les parties du lecteur. */
    private val session = PlaybackSession()

    private val mutableState = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val sourceId = MutableStateFlow<Long?>(null)

    /**
     * Match dont les chaînes se suivent au zapping (▲ ▼), choisi dans la section Foot ou dans le menu des chaînes ; à
     * défaut, le match en direct sur la chaîne regardée ([activeMatch]).
     */
    private var zapMatch: MenuMatch? = container.pendingMatch.value?.takeIf { channelId in it.channelIds }
        .also { container.pendingMatch.value = null }

    // Menu des chaînes (« Retour ») : catégories (dont les matchs en direct), liste, programme de la chaîne sélectionnée.
    private val channelMenu = ChannelMenuController(
        container, viewModelScope, sourceId,
        watchingMatch = mutableState.map { it.match != null }.distinctUntilChanged(),
    )

    init {
        zapMatch?.let(channelMenu::pin)
    }

    /** Matchs en direct (catégories en tête du menu des chaînes) et chaînes de celui affiché. */
    val menuMatches: StateFlow<List<MenuMatch>> = channelMenu.matches
    val menuMatchRows: StateFlow<MatchRows?> = channelMenu.matchRows
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

    // Tout ce dont se servent l'ouverture de la chaîne et les surveillances lancées à la création (bloc `init`,
    // plus bas) est déclaré ici, au-dessus : une propriété déclarée après lui serait encore vide à ce moment-là.

    /** Écran du lecteur affiché (ni veille, ni autre application au premier plan). */
    private val started = MutableStateFlow(false)

    /** L'écran a déjà été affiché : ensuite, « pas affiché » veut dire quitté (et non pas encore ouvert). */
    private var shownOnce = false

    /** Flux à ouvrir au retour sur l'écran (son ouverture est arrivée pendant qu'il était quitté) : repli HLS ou non. */
    private var startWhenShown: Boolean? = null

    /** Relances du direct interrompu : combien, et après quelle attente. */
    private val liveRetries = LiveRetries()
    private var liveRetryJob: Job? = null
    private var liveStableJob: Job? = null

    /** Direct figé sans erreur (plus d'images ni de position depuis 20 s) : rouvert comme une coupure. */
    private val liveStall = LiveStallWatch()

    /** Direct fermé en quittant l'écran (veille, autre application) : à rouvrir au retour. */
    private var reopenLiveOnStart = false


    /** Qualité visée (réglage commun à toutes les chaînes), modifiable depuis les options. */
    val qualityTarget: StateFlow<QualityTarget> = container.settings.qualityTarget
        .stateIn(viewModelScope, SharingStarted.Eagerly, QualityTarget.BEST)

    /** Animation des radios (son sans image), à la place de l'écran noir. */
    val radioVisual: StateFlow<tv.nextv.app.data.settings.RadioVisual> = container.settings.radioVisual
        .stateIn(viewModelScope, SharingStarted.Eagerly, tv.nextv.app.data.settings.RadioVisual.DEFAULT)

    /** Animation des radios choisie dans les options du lecteur (enregistrée pour toutes les radios). */
    fun setRadioVisual(visual: tv.nextv.app.data.settings.RadioVisual) {
        viewModelScope.launch { container.settings.setRadioVisual(visual) }
    }

    /** Spectre du son pour l'animation des radios (commun aux lecteurs de l'appli). */
    val spectrum: tv.nextv.core.audio.SpectrumAnalyzer get() = container.playerFactory.spectrum

    /** Format de l'image (réglage commun à toutes les lectures). */
    val aspectMode: StateFlow<AspectMode> = container.settings.aspectMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AspectMode.FIT)

    /** Dernier serveur qui a répondu (playlist, racine) : les chaînes suivantes commencent par lui. */
    private var workingServer: Pair<Long, String>? = null

    // Lecteur ExoPlayer : lecture, pistes, infos techniques (lu aussi par les tests).
    internal val engine: PlayerEngine = PlayerEngine(
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
                session.retryAfterPreview = false
                quality.onReady()
                rememberServer()
                liveIsPlaying()
            }
            override fun onEnded() = this@PlayerViewModel.onEnded()
            override fun onError(error: PlaybackException) = handleError(error)

            // Aperçu repris : le changement d'écran n'est pas une connexion qui faiblit.
            override fun onContinued() = quality.onUserRestart()
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

    /** Passages de la surveillance depuis la dernière sauvegarde de ce que « baisser le son des pubs » a appris. */
    private var adMarksChecks = 0

    /** Chaîne notée en dernier dans l'enregistrement d'analyse. */
    private var adRecordedChannel = ""
    private var adRecordedQuality: String? = null

    /** Musique pendant les pubs (réglage « Pendant les pubs ») : radio ou morceaux de l'appareil, à la place du son baissé. */
    private val adMusic = tv.nextv.app.player.AdMusicPlayer(context.applicationContext)
    private var adSoundMode = AdSound.LOWER
    private var adRadioKey: String? = null

    /** Écran pendant les pubs (réglage, activé d'office). */
    private var adScreenEnabled = true

    /** Morceau en cours de la radio des pubs (services des radios), relu tant qu'elle joue. */
    private val adRadioTitles = RadioTitleService({ container.httpClients.client(tv.nextv.core.net.RequestPurpose.METADATA) }, container.logger)
    private var adTrackJob: kotlinx.coroutines.Job? = null

    /** Écran pendant les pubs : programme, matchs, radio et météo autour de la pub réduite dans un coin. */
    private val adBoard = AdBoardController(
        scope = viewModelScope,
        container = container,
        state = mutableState,
        programmes = {
            val current = session.channel
            if (current == null || current.kind != ContentKind.LIVE.name) {
                emptyList()
            } else {
                container.programmeDao.upcoming(current.id, System.currentTimeMillis(), limit = 2)
            }
        },
    )

    /** Son de la radio des pubs, pour les barres de l'écran des pubs. */
    val adMusicSpectrum: tv.nextv.core.audio.SpectrumAnalyzer get() = adMusic.spectrum

    /** Enregistrement d'analyse en train d'être coupé (fichier suivant) : l'automatique continue. */
    private var adRecordingSplitting = false

    /** Enregistrement automatique (essais) : change de chaîne tout seul, toute la nuit s'il le faut. */
    private var autoRecording: kotlinx.coroutines.Job? = null

    /** Chaînes qui ne se lisaient pas pendant l'enregistrement automatique : passées en dernier. */
    private val autoFailed = HashSet<String>()

    /** Dernier instant compté dans le temps enregistré de la chaîne ([AdTestedChannels]). */
    private var adTestedAt = 0L

    init {
        // Attention : rien de ce qui est déclaré plus bas dans la classe n'est encore prêt ici.
        // Pendant les pubs : son baissé, radio ou musique de l'appareil (réglages, suivis pendant la lecture).
        viewModelScope.launch { container.settings.adSound.collect { adSoundMode = it } }
        viewModelScope.launch { container.settings.adRadio.collect { adRadioKey = it } }
        viewModelScope.launch { container.settings.adMusicLevel.collect { adMusic.level = it / 100f } }
        viewModelScope.launch { container.settings.adScreen.collect { adScreenEnabled = it } }
        viewModelScope.launch { channelMenu.matches.collect { updateMatchPosition() } }
        // Lecteur ouvert : les actualisations automatiques de la playlist et du guide attendent sa fermeture.
        container.playerOpened()
        // Chaîne ouverte depuis l'accueil : aucune catégorie choisie dans le menu des chaînes pour l'instant.
        container.lastLiveCategory.value = null
        // Chaîne ouverte depuis son aperçu : son image et son son continuent tout de suite, pendant le chargement.
        engine.showPreviewHandoff(channelId)
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
                if (vlc == null) engine.updateAdCues()
                checkLiveStall()
                val adMuted = engine.adVolume.enabled && engine.adVolume.attenuating
                updateAdMusic(adMuted)
                // Écran pendant les pubs : direct lu par le lecteur de l'appli, avec une image (pas une radio), sans erreur.
                val shown = state.value
                adBoard.update(
                    open = adMuted && adScreenEnabled && shown.isLive && delayed.replay == null && vlc == null && shown.error == null && !shown.audioOnly,
                    channelName = shown.channelName,
                    channelVersion = shown.channelVersion,
                    music = adBoardMusic(),
                    cueRemainingMillis = if (adMuted) engine.adCueRemainingMillis() else null,
                )
                val adWatch = if (vlc == null) engine.adVolume.watch() else null
                val adDetails = if (vlc == null) engine.adDetails() else null
                // Enregistrement d'analyse coupé avant 30 Mo (la limite d'un envoi), ou au bout de deux heures.
                if (engine.adRecordingBytes >= AD_RECORDING_SPLIT_BYTES || (engine.adRecordingMillis ?: 0) >= AD_RECORDING_SPLIT_MILLIS) splitAdRecording()
                val adRecording = engine.adRecordingMillis?.let { (it / 60_000).toInt() }
                // Temps enregistré sur la chaîne (lue, pas en pause) : l'enregistrement automatique va aux moins connues.
                val testedNow = SystemClock.elapsedRealtime()
                val testedName = state.value.channelName
                if (adRecording != null && adTestedAt > 0 && state.value.error == null && !state.value.paused && testedName.isNotBlank()) {
                    val credited = (testedNow - adTestedAt).coerceAtMost(AD_TESTED_MAX_STEP_MILLIS)
                    viewModelScope.launch(Dispatchers.IO) { container.adTested.add(testedName, credited) }
                }
                adTestedAt = testedNow
                // Enregistrement d'analyse : la chaîne regardée y est notée à chaque changement.
                if (adRecording != null && state.value.channelName != adRecordedChannel) {
                    adRecordedChannel = state.value.channelName
                    engine.noteAdRecordingChannel(adRecordedChannel)
                }
                // Qualité lue, notée aussi : les enregistrements comparent la détection selon la qualité (rotation de
                // l'enregistrement automatique, AppContainer.analysisQualityTurn).
                if (adRecording != null && state.value.quality != null && state.value.quality != adRecordedQuality) {
                    adRecordedQuality = state.value.quality
                    adRecordedQuality?.let(engine::noteAdRecordingQuality)
                }
                if (state.value.adMuted != adMuted || state.value.adWatch != adWatch || state.value.adDetails != adDetails ||
                    state.value.adRecordingMinutes != adRecording
                ) {
                    // Enregistrement d'analyse arrêté tout seul (plus de place sur l'appareil) : dit dans le bandeau.
                    val ended = state.value.adRecordingMinutes != null && adRecording == null
                    val notice = when {
                        !ended -> null
                        engine.adRecordingStopReason == "place" -> resources.getString(R.string.player_ad_recording_full)
                        engine.adRecordingStopReason == "erreur" -> resources.getString(R.string.player_ad_recording_write_error)
                        else -> resources.getString(R.string.player_ad_recording_stopped)
                    }
                    mutableState.update {
                        it.copy(
                            adMuted = adMuted, adWatch = adWatch, adDetails = adDetails, adRecordingMinutes = adRecording,
                            qualityNotice = notice ?: it.qualityNotice,
                        )
                    }
                }
                // Logo de la chaîne appris pendant cette lecture : gardé chaque minute, pas seulement à la sortie.
                if (++adMarksChecks >= AD_MARKS_SAVE_CHECKS) {
                    adMarksChecks = 0
                    engine.saveAdMarks()
                }
                val dropped = engine.newDroppedFrames()
                // Pause ou différé : changer de version ramènerait au direct. Moteur VLC : le lecteur de l'appli est arrêté.
                if (vlc != null || delayed.recording || state.value.paused || state.value.liveDelayMs > 0) continue
                quality.check(dropped)
            }
        }
        // Message du bandeau (« Connexion lente : passage en HD », serveur de secours…) : effacé au bout de quelques
        // secondes (il restait affiché jusqu'à la chaîne suivante).
        viewModelScope.launch {
            mutableState.map { it.qualityNotice }.distinctUntilChanged().collectLatest { notice ->
                if (notice == null) return@collectLatest
                delay(QUALITY_NOTICE_MILLIS)
                mutableState.update { if (it.qualityNotice == notice) it.copy(qualityNotice = null) else it }
            }
        }
        // Son illisible par le lecteur de l'appli (ou introuvable dans un film) : le moteur VLC prend la suite.
        viewModelScope.launch {
            mutableState.map { it.audioProblem to it.isLive }.distinctUntilChanged().collect { (problem, live) ->
                if (problem == AudioProblem.UNSUPPORTED || (problem == AudioProblem.NO_TRACK && !live)) switchToVlc()
            }
        }
        // Film ou épisode dont le nom n'annonce pas de qualité : la taille de l'image lue donne son badge.
        viewModelScope.launch {
            mutableState.map { it.info.width to it.info.height }.distinctUntilChanged().collect { (width, height) ->
                val playing = session.playing ?: return@collect
                if (width == null && height == null) return@collect
                runCatching {
                    if (container.measuredQualities.recordPlayback(playing, width, height)) {
                        session.playing = playing.copy(quality = StreamQuality.fromSize(width, height)?.name)
                    }
                }
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
    /** Vidéo du direct d'une radio affichée (avec son propre son) : la radio, cachée dessous, est en sourdine. */
    private var radioVideoOn = false

    /**
     * Vidéo du direct d'une radio affichée avec son propre son : celui de la radio est coupé le temps de la vidéo. La
     * radio reste au direct : mise en pause dessous (OK), elle gardait le bandeau affiché (« En pause », « Différé »).
     */
    fun radioVideoShown(shown: Boolean) {
        if (shown == radioVideoOn) return
        radioVideoOn = shown
        mutableState.update { it.copy(radioVideoOnScreen = shown, radioVideoQuality = it.radioVideoQuality.takeIf { shown }) }
        val player = engine.current
        player?.volume = if (shown) 0f else 1f
        if (state.value.paused || state.value.liveDelayMs > 0) {
            backToLive()
        } else if (!shown && player != null && !player.playWhenReady) {
            // Le son de la vidéo (page YouTube) prend la main sur le son : Android met alors la radio en pause
            // (icône de pause au milieu de la vidéo). Vidéo partie : la radio repart, au direct.
            player.seekToDefaultPosition()
            player.playWhenReady = true
        }
    }

    /** Qualité de la vidéo du direct de la radio, lue dans la page YouTube (« hd1080 »). */
    fun radioVideoQuality(quality: String) {
        val label = youtubeQualityLabel(quality) ?: return
        if (label != state.value.radioVideoQuality) mutableState.update { it.copy(radioVideoQuality = label) }
    }

        fun useVlc() {
        vlcTried = false
        switchToVlc()
    }

    /**
     * « Lire avec le lecteur de l'appli » (options, moteur VLC en cours) : retour au lecteur de base, au même
     * endroit (film) ou au direct. Choix de l'utilisateur : VLC n'est plus repris tout seul pour ce titre.
     */
    fun useAppPlayer() {
        val playback = vlc ?: return
        val live = state.value.isLive
        if (!live) session.startPositionMs = playback.player.currentPosition.coerceAtLeast(0)
        releaseVlc()
        vlcTried = true
        container.logger.info(TAG, "Moteur VLC quitté : lecteur de l'appli")
        mutableState.update { it.copy(error = null, audioProblem = null, unsupportedAudio = null, buffering = true) }
        start(session.playingAsHls)
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
        playback.play(urlWithCredentials(request), request.userAgent, position, film = !state.value.isLive)
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

    /**
     * Image affichée par le lecteur (réduite, en niveaux de gris), pour « baisser le son des pubs » : le logo de la
     * chaîne, et le changement de plan qui sépare deux pubs.
     */
    fun onVideoFrame(gray: ByteArray) = engine.adVolume.frame(gray)

    /** Images rapprochées demandées (un silence est en cours) au lieu d'une par seconde. */
    val adFramesWanted: Boolean get() = engine.adVolume.wantsFrames

    /** Options › « Enregistrer pour l'analyse des pubs » : commence ou arrête l'enregistrement (à partir de la chaîne en cours). */
    fun toggleAdRecording() {
        adRecordedChannel = state.value.channelName
        val recording = state.value.adRecordingMinutes != null
        // Arrêté trop tôt : pas gardé (pas assez de pages de pub pour servir), et l'appli le dit.
        val tooShort = recording && (engine.adRecordingMillis ?: 0L) < tv.nextv.app.player.AD_RECORDING_MIN_MILLIS
        val started = engine.toggleAdRecording(state.value.channelName.ifBlank { "chaine" })
        mutableState.update {
            it.copy(
                adRecordingMinutes = if (recording || !started) null else 0,
                qualityNotice = when {
                    recording && tooShort -> resources.getString(R.string.player_ad_recording_too_short)
                    recording -> resources.getString(R.string.player_ad_recording_stopped)
                    !started && engine.adRecordingRefusal == "usb" -> resources.getString(R.string.player_ad_recording_no_usb)
                    !started && engine.adRecordingRefusal == "autorisation" -> resources.getString(R.string.player_ad_recording_no_access)
                    !started && engine.adRecordingRefusal == "usb_illisible" -> resources.getString(R.string.player_ad_recording_usb_unwritable)
                    !started -> resources.getString(R.string.player_ad_recording_failed)
                    else -> it.qualityNotice
                },
            )
        }
        if (recording || started) followAnalysisQuality(started = !recording)
    }

    /**
     * Enregistrement d'analyse commencé ([started]) : la chaîne passe à sa moins bonne version (même chaîne : la détection
     * et l'enregistrement continuent) ; arrêté : elle revient à la qualité visée. Pas pendant une pause, un différé ou un replay.
     */
    private fun followAnalysisQuality(started: Boolean) {
        viewModelScope.launch {
            if (delayed.recording || delayed.replay != null || state.value.paused || state.value.liveDelayMs > 0) return@launch
            val primary = session.channel ?: return@launch
            val playingId = session.playing?.id
            val reload = if (started) quality.wantsLowest(playingId) else quality.changesVersion(qualityTarget.value, playingId)
            if (reload) load(primary.id)
        }
    }

    /** Fin d'une heure d'enregistrement d'analyse : ce fichier se ferme, le suivant commence aussitôt. */
    private fun splitAdRecording() {
        adRecordingSplitting = true
        engine.toggleAdRecording(state.value.channelName.ifBlank { "chaine" })
        engine.toggleAdRecording(state.value.channelName.ifBlank { "chaine" })
        adRecordedChannel = state.value.channelName
        adRecordingSplitting = false
    }

    /**
     * Options › « Enregistrement automatique » (pour les essais) : enregistre pour l'analyse des pubs et passe tout seul à
     * la chaîne suivante de la liste d'où la chaîne a été ouverte (favoris, catégorie…) toutes les [AUTO_STAY_MILLIS] —
     * sans quitter une page de pubs en cours (au plus [AUTO_EXTRA_MILLIS] de plus), et tout de suite après
     * [AUTO_ERROR_MILLIS] sur une chaîne qui ne se lit pas. S'arrête avec l'enregistrement (à la main, ou place pleine).
     */
    fun toggleAutoRecording() {
        if (autoRecording != null) {
            stopAutoRecording()
            if (state.value.adRecordingMinutes != null) toggleAdRecording()
            return
        }
        if (state.value.adRecordingMinutes == null) toggleAdRecording()
        if (state.value.adRecordingMinutes == null) return
        mutableState.update { it.copy(autoRecordingChannels = 1) }
        container.analysisQualityRotation = true
        autoFailed.clear()
        autoRecording = viewModelScope.launch {
            // Dès le départ, la chaîne la moins connue de la liste, si celle-ci l'est déjà nettement plus.
            autoStart()
            var since = SystemClock.elapsedRealtime()
            var errorSince = 0L
            while (true) {
                delay(AUTO_CHECK_MILLIS)
                // Enregistrement arrêté (à la main, place pleine, clé retirée) : l'automatique aussi.
                if (engine.adRecordingMillis == null && !adRecordingSplitting) break
                val now = SystemClock.elapsedRealtime()
                val stayed = now - since
                errorSince = if (state.value.error != null) errorSince.takeIf { it > 0 } ?: now else 0L
                val failing = errorSince > 0 && now - errorSince >= AUTO_ERROR_MILLIS
                val inBreak = engine.adVolume.attenuating
                if (failing || (stayed >= AUTO_STAY_MILLIS && !inBreak) || stayed >= AUTO_STAY_MILLIS + AUTO_EXTRA_MILLIS) {
                    session.listed?.let { if (failing) autoFailed += AdTestedChannels.key(it.title) }
                    withContext(Dispatchers.IO) { container.adTested.save() }
                    autoNext()
                    since = SystemClock.elapsedRealtime()
                    errorSince = 0L
                    mutableState.update { it.copy(autoRecordingChannels = (it.autoRecordingChannels ?: 0) + 1) }
                }
            }
            autoRecording = null
            container.analysisQualityRotation = false
            mutableState.update { it.copy(autoRecordingChannels = null) }
        }
    }

    /** Chaîne candidate de l'enregistrement automatique : son nom et de quoi l'ouvrir. */
    private class AutoCandidate(val title: String, val open: suspend () -> Long?)

    /**
     * Chaînes de l'enregistrement automatique, dans l'ordre de la liste : les favoris ou la catégorie d'où la chaîne a
     * été ouverte ; ouverte d'ailleurs (« Toutes les chaînes »…), les grandes chaînes à pub de la TNT
     * ([AUTO_TNT_NUMBERS]) présentes dans la playlist, plutôt que des centaines de chaînes dans l'ordre.
     */
    private suspend fun autoCandidates(current: ChannelEntity): List<AutoCandidate> {
        val dao = container.channelDao
        val now = System.currentTimeMillis()
        val rows = when {
            fromFavorites -> dao.observeFavoriteChannels(current.sourceId, now).first()
            zapGroup != null -> dao.channelRow(current.sourceId, zapGroup.orEmpty(), now, AUTO_GROUP_LIMIT)
            else -> null
        }
        if (rows != null) return rows.map { row -> AutoCandidate(row.title) { row.id } }
        val byNumber = dao.lineupRows(current.sourceId).sortedBy { it.position }
            .mapNotNull { row -> tv.nextv.core.epg.TntChannels.number(row.title)?.let { it to row } }
            .filter { (number, _) -> number in AUTO_TNT_NUMBERS }
            .distinctBy { it.first }
            .toMap()
        return AUTO_TNT_NUMBERS.mapNotNull { number ->
            byNumber[number]?.let { row -> AutoCandidate(row.title) { dao.primaryIdByKey(current.sourceId, row.itemKey) } }
        }
    }

    /**
     * Chaîne où aller : la moins enregistrée jusqu'ici ([AdTestedChannels], avec ce que Claude a déjà analysé), en
     * passant en dernier celles qui ne se lisaient pas ; à égalité, la première après la chaîne en cours dans la liste.
     * Jamais la chaîne en cours (ni une autre version d'elle).
     */
    private fun pickAutoChannel(candidates: List<AutoCandidate>, currentTitle: String): AutoCandidate? {
        val here = AdTestedChannels.key(currentTitle)
        val unique = candidates.distinctBy { AdTestedChannels.key(it.title) }
        val start = unique.indexOfFirst { AdTestedChannels.key(it.title) == here }
        val ordered = if (start < 0) unique else unique.drop(start + 1) + unique.take(start)
        val others = ordered.filter { AdTestedChannels.key(it.title) != here }
        val readable = others.filter { AdTestedChannels.key(it.title) !in autoFailed }.ifEmpty { others }
        return readable.minByOrNull { container.adTested.millis(it.title) }
    }

    /** Départ de l'enregistrement automatique : sur la chaîne la moins connue, si la chaîne en cours l'est nettement plus. */
    private suspend fun autoStart() {
        val current = session.listed ?: return
        val best = withContext(Dispatchers.IO) {
            val candidates = autoCandidates(current)
            pickAutoChannel(candidates, current.title)?.takeIf { best ->
                container.adTested.millis(best.title) + AUTO_START_MARGIN_MILLIS < container.adTested.millis(current.title)
            }
        } ?: return
        val id = best.open() ?: return
        if (id != current.id) {
            load(id)
            mutableState.update { it.copy(autoRecordingChannels = (it.autoRecordingChannels ?: 0) + 1) }
            loadJob?.join()
        }
    }

    /**
     * Chaîne suivante de l'enregistrement automatique ([pickAutoChannel]) ; à défaut, la suivante de la liste. Rend la
     * main une fois la chaîne ouverte : jusque-là, l'erreur affichée est encore celle de la chaîne quittée, et elle ne
     * doit pas compter contre la nouvelle (sur une machine lente, la nouvelle était marquée « ne se lit pas » et sautée).
     */
    private suspend fun autoNext() {
        val current = session.listed ?: return
        val next = withContext(Dispatchers.IO) { pickAutoChannel(autoCandidates(current), current.title) }
        // Chaîne suivante dans la qualité suivante (voir AppContainer.analysisQualityRotation).
        container.analysisQualityTurn++
        val id = next?.open()
        if (id != null && id != current.id) load(id) else zap(+1)
        loadJob?.join()
    }

    private fun stopAutoRecording() {
        autoRecording?.cancel()
        autoRecording = null
        container.analysisQualityRotation = false
        mutableState.update { it.copy(autoRecordingChannels = null) }
    }

    /**
     * Musique des pubs : elle monte quand la page de pubs commence (son de la chaîne coupé), s'efface quand le programme
     * reprend. Rien en pause, ni avec le moteur VLC (la détection n'y tourne pas).
     */
    private fun updateAdMusic(adMuted: Boolean) {
        val wanted = adMuted && adSoundMode != AdSound.LOWER && !state.value.paused && vlc == null
        if (wanted && !adMusic.playing) {
            val label: String?
            val source = when (adSoundMode) {
                AdSound.RADIO -> AdRadios.of(adRadioKey).let { radio ->
                    label = radio.name
                    tv.nextv.app.player.AdMusicPlayer.Source.Radio(radio)
                }
                // Morceaux relevés en arrière-plan : lire une clé USB ici pourrait figer l'image (fil principal).
                else -> container.adMusic.knownTracks.takeIf { it.isNotEmpty() }.let { files ->
                    label = files?.let { resources.getString(R.string.player_ad_music_own) }
                    files?.let { tv.nextv.app.player.AdMusicPlayer.Source.Tracks(it) }
                }
            }
            if (source != null) {
                adMusic.start(source)
                mutableState.update { it.copy(adMusic = label) }
                followAdMusicTrack(source)
            }
        } else if (!wanted && adMusic.playing) {
            adMusic.stop()
            followAdMusicTrack(null)
            mutableState.update { it.copy(adMusic = null) }
        }
        // Son de la chaîne coupé (et non baissé) seulement quand la musique joue vraiment.
        engine.adVolume.muteAds = adMusic.playing && !adMusic.failed
    }

    /**
     * Morceau en cours de la musique des pubs, avec ou sans l'écran des pubs : titre donné par le flux de la radio, sinon
     * par son service (Radio France, Nova…) ; nom du fichier pour « votre musique ». Relu toutes les 10 s.
     */
    private fun followAdMusicTrack(source: tv.nextv.app.player.AdMusicPlayer.Source?) {
        adTrackJob?.cancel()
        adTrackJob = null
        if (source == null) {
            if (state.value.adMusicTrack != null) mutableState.update { it.copy(adMusicTrack = null) }
            return
        }
        val service = (source as? tv.nextv.app.player.AdMusicPlayer.Source.Radio)?.let { tv.nextv.core.catalog.InternetRadios.titlesFor(it.radio.name) }
        adTrackJob = viewModelScope.launch {
            while (true) {
                val track = when (source) {
                    is tv.nextv.app.player.AdMusicPlayer.Source.Tracks -> adMusic.currentTrack?.let { NowPlaying(artist = null, title = it) }
                    is tv.nextv.app.player.AdMusicPlayer.Source.Radio -> adMusic.streamTitle?.let { NowPlaying.fromStreamTitle(it) ?: NowPlaying(null, it) }
                        ?: service?.let { adRadioTitles.cached(it) }
                }
                if (state.value.adMusicTrack != track) mutableState.update { it.copy(adMusicTrack = track) }
                delay(AD_TRACK_REFRESH_MILLIS)
            }
        }
    }

    /** Musique jouée pendant la pub et son morceau, pour l'écran des pubs. */
    private fun adBoardMusic(): AdBoardController.Music? {
        if (!adMusic.playing || adMusic.failed) return null
        val name = if (adSoundMode == AdSound.RADIO) AdRadios.of(adRadioKey).name else resources.getString(R.string.player_ad_music_own)
        val track = state.value.adMusicTrack
        return AdBoardController.Music(name, track?.title, track?.artist, track?.cover)
    }

    /**
     * Options › « Ce n'est pas une pub » (ou Retour sur l'écran des pubs) : le son de la chaîne revient tout de suite, la
     * musique s'arrête, l'image reprend tout l'écran.
     */
    fun dismissAdBreak() {
        adBoard.dismiss()
        engine.adVolume.dismissBreak()
        if (adMusic.playing) adMusic.stop()
        followAdMusicTrack(null)
        engine.adVolume.muteAds = false
        mutableState.update { it.copy(adMuted = false, adMusic = null) }
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

    /**
     * Version du son choisie dans les options (chaînes rassemblées : son normal, Dolby Digital, double audio) :
     * retenue pour cette chaîne, qui repart tout de suite sur une version de ce son (retour au direct après une
     * pause ou un différé). Pendant un replay, elle s'applique à la prochaine ouverture de la chaîne.
     */
    fun setSoundVersion(audio: Int) {
        viewModelScope.launch {
            val primary = session.channel ?: return@launch
            if (primary.kind != ContentKind.LIVE.name) return@launch
            container.settings.rememberChannelSound(primary.sourceId, primary.itemKey, audio)
            if (delayed.replay != null || session.playing?.audio == audio) return@launch
            // Même chaîne, ouverte de la même façon : seule sa version change.
            load(channelId)
        }
    }

    /**
     * Qualité choisie dans les options pour cette chaîne ([key] : nom de StreamQuality, "" version sans mention ;
     * `null` : automatique) : gardée jusqu'à la fermeture de l'application, la chaîne ne change plus de qualité d'elle-même.
     * Elle repart tout de suite sur la version choisie (retour au direct après une pause ou un différé) ; pendant un
     * replay, à la prochaine ouverture de la chaîne.
     */
    fun lockQuality(key: String?) {
        viewModelScope.launch {
            val primary = session.channel ?: return@launch
            if (primary.kind != ContentKind.LIVE.name || primary.variantKey.isEmpty()) return@launch
            val reopen = quality.lock(primary, key, session.playing)
            mutableState.update { it.copy(lockedQuality = key) }
            if (delayed.replay != null || !reopen) return@launch
            load(channelId)
        }
    }

    /**
     * Qualité choisie sur la ligne « Qualité » des options : une version de la chaîne ([key], gardée pour elle), ou
     * une qualité visée pour toutes les chaînes (la version gardée est alors abandonnée).
     */
    fun chooseQuality(target: QualityTarget, key: String?) {
        when {
            key != null -> lockQuality(key)
            state.value.lockedQuality != null -> viewModelScope.launch {
                container.settings.setQualityTarget(target)
                lockQuality(null)
            }
            else -> setQualityTarget(target)
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

    /**
     * Chaîne suivante (+1) ou précédente (-1), en boucle, dans la liste d'où la chaîne a été ouverte : les favoris, sa
     * catégorie (depuis la première chaîne, « bas » va à la dernière de la catégorie), sinon toutes les chaînes.
     */
    fun zap(direction: Int) {
        val current = session.listed ?: return
        if (current.kind != ContentKind.LIVE.name) return
        // Match regardé sur plusieurs chaînes : ▲ ▼ passent de l'une à l'autre (la première n'est pas toujours la bonne).
        activeMatch(current)?.takeIf { it.channelIds.size > 1 }?.let { match ->
            val index = match.channelIds.indexOf(current.id)
            val target = match.channelIds[(index + direction + match.channelIds.size) % match.channelIds.size]
            if (target != current.id) load(target)
            return
        }
        viewModelScope.launch {
            val dao = container.channelDao
            if (fromFavorites) {
                val favorites = dao.observeFavoriteChannels(current.sourceId, System.currentTimeMillis()).first()
                val target = FavoriteZapping.next(favorites, current.itemKey, direction)
                if (target != null) {
                    if (target.id != current.id) load(target.id)
                    return@launch
                }
                // Chaîne retirée des favoris entre-temps : zapping dans toute la playlist.
            }
            // Catégorie sans autre chaîne lisible (vide entre-temps) : zapping dans toute la playlist.
            val target = zapGroup?.let { neighbourChannel(current, filterGroup = 1, group = it, direction) }
                ?: neighbourChannel(current, filterGroup = 0, group = "", direction)
            if (target != null && target != current.id) load(target)
        }
    }

    /**
     * Chaîne voisine de [current] dans la liste de la catégorie [group] ([filterGroup] 1) ou de « Toutes » (0), dans son
     * ordre (rangé si les chaînes sont rassemblées), en boucle ; `null` si la liste n'a aucune chaîne lisible. Départ :
     * la place de la chaîne en cours dans cette liste (sa version que la liste affiche) ; une chaîne qui n'y est pas
     * (chaîne précédente d'une autre catégorie) part de son rang, la voisine restant dans la liste.
     */
    private suspend fun neighbourChannel(current: ChannelEntity, filterGroup: Int, group: String, direction: Int): Long? {
        val dao = container.channelDao
        val order = current.variantKey.takeIf { it.isNotEmpty() }
            ?.let { dao.listedOrder(current.sourceId, filterGroup, group, it) }
            ?: current.listOrder
        return if (direction > 0) {
            dao.nextChannelId(current.sourceId, filterGroup, group, order) ?: dao.nextChannelId(current.sourceId, filterGroup, group, Int.MIN_VALUE)
        } else {
            dao.previousChannelId(current.sourceId, filterGroup, group, order) ?: dao.previousChannelId(current.sourceId, filterGroup, group, Int.MAX_VALUE)
        }
    }

    fun openMenu() {
        val current = session.listed ?: return
        channelMenu.open(current, fromFavorites)
    }

    /**
     * Match dont les chaînes se suivent au zapping pour la chaîne [current] : celui choisi (section Foot, menu), sinon le
     * match en direct sur cette chaîne ; `null` : zapping habituel.
     */
    private fun activeMatch(current: ChannelEntity): MenuMatch? =
        zapMatch?.takeIf { current.id in it.channelIds } ?: channelMenu.matches.value.firstOrNull { current.id in it.channelIds }

    /** Place de la chaîne regardée parmi celles de son match, pour le bandeau (« Chaîne 2 sur 4 du match »). */
    private fun updateMatchPosition() {
        val current = session.listed
        val match = current?.let(::activeMatch)?.takeIf { it.channelIds.size > 1 }
        val position = match?.let { MatchPosition(it.channelIds.indexOf(current.id) + 1, it.channelIds.size) }
        if (state.value.matchPosition != position) mutableState.update { it.copy(matchPosition = position) }
    }

    fun closeMenu() = channelMenu.close()

    fun selectMenuCategory(category: Category) = channelMenu.selectCategory(category)

    /** Programme en cours (ou le prochain) d'une chaîne du menu, avec sa description. */
    fun showProgramme(row: ChannelListRow) = channelMenu.showProgramme(row)

    fun hideProgramme() = channelMenu.hideProgramme()

    fun playFromMenu(id: Long) {
        // Haut/bas zappe ensuite dans la catégorie du menu où la chaîne a été choisie : un match, les favoris, une
        // catégorie, ou toutes les chaînes.
        val category = channelMenu.menu.value.category
        if (category is Category.Match) {
            zapMatch = channelMenu.matches.value.firstOrNull { it.category.key == category.key }
            zapMatch?.let(channelMenu::pin)
            fromFavorites = false
            handle[ARG_FAVORITES] = false
            zapGroup = null
            handle[ARG_GROUP] = null
            closeMenu()
            if (id != session.listed?.id) load(id)
            return
        }
        zapMatch = null
        channelMenu.pin(null)
        val favorites = category == Category.Favorites
        // Au retour, l'accueil montre la catégorie où elle a été choisie (et la sélection se pose sur elle).
        container.lastLiveCategory.value = category
        fromFavorites = favorites
        handle[ARG_FAVORITES] = favorites
        zapGroup = (category as? Category.Group)?.title
        handle[ARG_GROUP] = zapGroup
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
        // Retour à l'accueil : il se place sur la chaîne regardée en dernier.
        if (isLive) container.lastLiveChannel.value = primary.itemKey
        val replayed = delayed.replay
        if (replayed != null && isLive) {
            // Programme encore en cours : « Depuis le début », avec le bandeau du direct. Pas de qualité automatique.
            quality.reset()
            mutableState.update { it.copy(soundVersions = emptyList(), qualityVersions = emptyList(), lockedQuality = null) }
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
                channelName = primary.displayTitle(),
                channelNumber = primary.channelNumber,
                logoUrl = primary.logoUrl,
                sourceName = source?.name.orEmpty(),
                isLive = isLive,
                now = null,
                next = null,
                match = null,
                error = null,
                qualityNotice = null,
                soundVersions = quality.soundVersions,
                qualityVersions = quality.qualityVersions,
                lockedQuality = quality.lockedQuality,
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
                soundVersion = variant.audio,
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
        val programmes = container.programmeDao.upcoming(current.id, now, limit = 2)
        // Autre chaîne ouverte pendant la lecture de la base : ce programme n'est plus le sien.
        if (session.channel?.id != current.id) return
        val upcoming = programmes.map { PlayerProgramme(it.title, it.startMillis, it.stopMillis) }
        val live = upcoming.firstOrNull()?.takeIf { it.startMillis <= now }?.let { it.copy(rebroadcast = isRebroadcast(programmes.first())) }
        if (session.channel?.id != current.id) return
        mutableState.update { it.copy(now = live, next = upcoming.firstOrNull { p -> p.startMillis != live?.startMillis }) }
        // Chaîne « événement » (LALIGA 2 LIVE MATCH…) sans programme du guide : le match qu'elle diffuse.
        val found = if (live != null) matchOnAir(programmes.first(), now) else null
        // Coup d'envoi pendant ce programme (scores en direct) : c'est le direct, jamais une rediffusion, même si le guide
        // a déjà eu ce titre plus tôt (avant-match sous le titre du match : PSG – Roma marqué « Rediffusion », sans score, en
        // pleine seconde période sur la box le 25/11/2026).
        // (Une rediffusion annoncée par le guide, « en différé », le reste.)
        val direct = live != null && found?.kickoffMillis?.let { it >= live.startMillis - LIVE_MATCH_EARLY_MILLIS && it < live.stopMillis } == true &&
            !Rebroadcast.announced(programmes.first().title, programmes.first().subtitle)
        val onScreen = if (direct) live?.copy(rebroadcast = false) else live
        val match = found
            // Rediffusion : jamais le score final (il gâcherait le match).
            ?.let { if (onScreen?.rebroadcast == true) it.copy(score = null, live = false, clock = null, halfTime = false, resumeAtMillis = null) else it }
            ?: eventMatchOnAir(container, current.sourceId, current.name, now)?.let(::playerMatchOf)
        if (session.channel?.id == current.id) {
            mutableState.update { if (it.now == live && onScreen != live) it.copy(match = match, now = onScreen) else it.copy(match = match) }
        }
        updateMatchPosition()
    }

    /** Réponse gardée pour le programme en cours (la base n'est relue qu'à chaque nouveau programme). */
    private var rebroadcastChecked: Pair<ProgrammeKey, Boolean>? = null

    private data class ProgrammeKey(val sourceId: Long, val title: String, val startMillis: Long)

    /** Rediffusion : annoncée par le guide, ou match dont le même titre est déjà passé plus tôt (`Rebroadcast`). */
    private suspend fun isRebroadcast(programme: ProgrammeEntity): Boolean {
        val key = ProgrammeKey(programme.sourceId, programme.title, programme.startMillis)
        rebroadcastChecked?.takeIf { it.first == key }?.let { return it.second }
        val window = Rebroadcast.earlierWindow(programme.startMillis)
        val answer = Rebroadcast.isRebroadcast(programme.title, programme.subtitle) {
            container.programmeDao.airedBetween(programme.sourceId, programme.title, window.first, window.last, programme.startMillis)
        }
        rebroadcastChecked = key to answer
        return answer
    }

    /**
     * Match que diffuse le programme en cours de la chaîne, avec son score : celui de football-data.org
     * (réponse partagée avec la section Foot), sinon l'affiche du guide (coupes d'Europe, titre sans
     * compétition) avec le score en direct.
     */
    private suspend fun matchOnAir(programme: ProgrammeEntity, now: Long): PlayerMatch? {
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
                resumeAtMillis = resumeAt(match.home.displayName, match.away.displayName, match.kickoffMillis, live, live && match.halfTime, match.clock),
                kickoffMillis = match.kickoffMillis,
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
            resumeAtMillis = score?.let { resumeAt(home, away, it.kickoffMillis, it.inPlay, it.halfTime, it.clock) },
            kickoffMillis = score?.kickoffMillis,
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
            resumeAtMillis = resumeAt(match.home.displayName, match.away.displayName, match.kickoffMillis, live, live && match.halfTime, match.clock),
            kickoffMillis = match.kickoffMillis,
        )
    }

    /** Mi-temps : heure estimée de la reprise (début de la pause noté pour toute l'application) ; `null` sinon. */
    private fun resumeAt(home: String, away: String, kickoffMillis: Long, inPlay: Boolean, halfTime: Boolean, minute: String?): Long? =
        container.halfTimes.resumeAt("$home|$away|$kickoffMillis", kickoffMillis, inPlay, halfTime, minute)

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
        // Avant l'ouverture : un aperçu repris joue déjà, il annonce aussitôt que le chargement est fini.
        mutableState.update { it.copy(buffering = true) }
        val fromPreview = engine.play(
            playbackRequest, forceHls, session.startPositionMs, record = live, film = !state.value.isLive, replay = delayed.replay != null,
            // Chaîne choisie (toutes versions confondues) : un changement de qualité ne remet pas à zéro la détection des pubs.
            channelKey = session.listed?.let { it.variantKey.ifEmpty { it.itemKey } },
            adFree = tv.nextv.core.audio.AdFreeChannels.isAdFree(session.listed?.baseTitle ?: state.value.channelName),
        )
        // Chaîne ouverte depuis l'aperçu : flux repris, ou rouvert (et pourquoi), affiché dans les infos de lecture.
        val previewStart = fromPreview ?: container.previewHandoff.takeMiss(channelId)?.let { miss ->
            when (miss) {
                HandoffMiss.NOT_SHOWN -> PreviewStart.NOT_SHOWN
                HandoffMiss.OTHER_CHANNEL -> PreviewStart.OTHER_CHANNEL
                HandoffMiss.PASSTHROUGH -> PreviewStart.PASSTHROUGH
                HandoffMiss.NOT_PLAYING -> PreviewStart.NOT_PLAYING
                HandoffMiss.OLD_ANDROID -> PreviewStart.OLD_ANDROID
                HandoffMiss.SETTING_OFF -> PreviewStart.SETTING_OFF
                HandoffMiss.NO_LAYER -> PreviewStart.NO_LAYER
            }
        }
        if (previewStart != null) {
            container.logger.info(TAG, "Ouverture depuis l'aperçu : $previewStart")
            engine.notePreviewStart(previewStart)
            session.retryAfterPreview = previewStart != PreviewStart.TAKEN && previewStart != PreviewStart.NOT_SHOWN
        }
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
        // Radio de l'appli illisible, même lue en HLS (Fun Radio sur la box : son passé par un serveur qui insère des pubs,
        // parfois dans un autre format) : le moteur VLC, plus tolérant, la lit à la place.
        if ((session.triedHlsFallback || format !in HLS_FALLBACK_FORMATS) && error.errorCode in UNREADABLE_STREAM_ERRORS &&
            tv.nextv.core.catalog.InternetRadios.byUrl(playbackRequest.stream.url) != null
        ) {
            container.logger.info(TAG, "Radio illisible (${error.errorCodeName}) : moteur VLC")
            if (switchToVlc()) return
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
        // Rouvert juste après la fermeture de l'aperçu : la connexion est souvent refusée un instant (une seule permise
        // par abonnement). Même version, une fois, avant de baisser la qualité (« Connexion lente »).
        if (failure == null && session.retryAfterPreview && error.errorCode in RETRIABLE_LIVE_ERRORS) {
            session.retryAfterPreview = false
            container.logger.info(TAG, "Ouverture refusée juste après l'aperçu (${error.errorCodeName}) : nouvel essai")
            mutableState.update { it.copy(buffering = true, error = null) }
            liveRetryJob?.cancel()
            liveRetryJob = viewModelScope.launch {
                delay(PREVIEW_RETRY_MILLIS)
                start(forceHls = session.playingAsHls)
            }
            return
        }
        if (failure != null && recoverFromCodecFailure(failure, player)) return
        // Direct qui jouait, coupé (réseau, serveur) : d'abord rouvert dans la même qualité (deux fois). Une coupure
        // passagère du fournisseur faisait descendre la qualité aussitôt, et elle y restait.
        if (failure == null && error.errorCode in RETRIABLE_LIVE_ERRORS && liveRetries.hasPlayed &&
            liveRetries.done < SAME_QUALITY_RETRIES && retryLive(error.errorCodeName)
        ) {
            return
        }
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
        liveStall.reset()
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

    /**
     * Retour à l'accueil depuis une chaîne en direct : l'aperçu de l'accueil continue sur ce flux (même lecteur,
     * même connexion) au lieu de le fermer puis de le rouvrir. Pas en différé, en pause ni avec le moteur VLC.
     */
    fun keepStreamForHome() {
        if (vlc != null || !watchingLive || delayed.recording || !started.value) return
        val shown = state.value
        if (shown.paused || shown.liveDelayMs > 0 || shown.error != null) return
        val ids = setOfNotNull(channelId, session.listed?.id, session.channel?.id, session.playing?.id)
        engine.handBack(ids, session.playing?.id)?.let(container.previewHandoff::offer)
    }

    /** Pause / lecture. En direct, la pause crée un différé ; le retard sur le direct est affiché. */
    fun togglePause() {
        // Vidéo du direct de la radio affichée : rien à mettre en pause dessous (la radio est en sourdine).
        if (radioVideoOn) return
        vlc?.let { playback ->
            val player = playback.player
            player.playWhenReady = !player.playWhenReady
            // Direct : « Pause » affichée dans le bandeau.
            if (state.value.isLive) mutableState.update { it.copy(paused = !player.playWhenReady) }
            return
        }
        quality.onUserRestart()
        // Lecture suspendue par le système (une autre application a pris le son, casque débranché) et non par
        // la télécommande : la touche la reprend, au lieu de mettre le direct « en pause » une seconde fois.
        val player = engine.current
        if (player != null && !player.playWhenReady && !state.value.paused && state.value.isLive) {
            player.play()
            return
        }
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

    private fun resetLiveRetries() {
        liveRetryJob?.cancel()
        liveStableJob?.cancel()
        liveRetries.newChannel()
        liveStall.reset()
    }

    /**
     * Direct qui se croit en lecture mais dont plus rien n'avance depuis 20 s ([LiveStallWatch]) : rouvert comme une
     * coupure ([retryLive]). Sans cela, l'écran restait noir ou figé toute la nuit, jusqu'au changement de chaîne.
     * Relances épuisées : le flux est arrêté et l'erreur s'affiche (bouton « Réessayer »). Une chaîne qui n'a encore
     * jamais joué n'est pas concernée (démarrage lent).
     */
    private fun checkLiveStall() {
        val watched = vlc == null && watchingLive && !delayed.playingTimeshift && liveRetryJob?.isActive != true &&
            state.value.error == null && !state.value.paused && state.value.liveDelayMs == 0L
        val progress = if (watched) engine.liveProgress() else null
        if (!liveStall.check(SystemClock.elapsedRealtime(), progress)) return
        if (!liveRetries.hasPlayed) return
        container.logger.warn(TAG, "Direct figé (rien de neuf depuis ${LiveStallWatch.DEFAULT_LIMIT_MILLIS / 1_000} s) : ${state.value.channelName}")
        if (retryLive("image figée")) return
        engine.stop()
        mutableState.update { it.copy(buffering = false, error = resources.getString(R.string.player_error_stream_frozen)) }
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
        adMusic.release()
        container.playerClosed()
        resume.saveBeforeClose()
        releaseVlc()
        delayed.stopTimeshift()
        engine.release()
        releaseMulticastLock()
    }


    /** Morceau en cours des radios affichées (service de Radio France, reconnaissance à l'écoute). */
    private val radioNowPlaying = RadioNowPlaying(
        scope = viewModelScope,
        state = mutableState,
        client = { container.httpClients.client(tv.nextv.core.net.RequestPurpose.METADATA) },
        snippet = container.playerFactory.snippet,
        // Clé saisie dans les réglages, sinon celle intégrée à l'appli (secret de publication).
        auddKey = { container.settings.auddApiKey.first() ?: tv.nextv.app.BuildConfig.AUDD_API_KEY.takeIf { it.isNotBlank() } },
        logger = container.logger,
    )

    init {
        // Bloc placé en dernier : tout ce qu'il utilise est déjà créé. Radio affichée avec son animation : son morceau en cours.
        viewModelScope.launch {
            combine(mutableState, radioVisual) { current, visual ->
                (current.audioOnly && visual != tv.nextv.app.data.settings.RadioVisual.OFF && current.error == null) to current.channelName
            }.distinctUntilChanged().collect { (active, name) -> radioNowPlaying.follow(active, name) }
        }
    }

    companion object {
        /** Enregistrement automatique : temps passé sur chaque chaîne, prolongé pendant une page de pubs. */
        const val AUTO_STAY_MILLIS = 30 * 60_000L
        const val AUTO_EXTRA_MILLIS = 10 * 60_000L

        /**
         * Enregistrement automatique ouvert depuis « Toutes les chaînes » : chaînes à pub de la TNT (numéros ; ni Arte,
         * ni LCP, ni franceinfo, sans pub).
         */
        val AUTO_TNT_NUMBERS = listOf(1, 2, 3, 6, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 23, 24, 25)

        /** Au départ de l'automatique, la chaîne en cours est quittée si une autre est moins connue d'au moins ce temps. */
        const val AUTO_START_MARGIN_MILLIS = 10 * 60_000L

        /** Chaînes lues d'une catégorie pour l'automatique. */
        const val AUTO_GROUP_LIMIT = 500

        /** Temps compté au plus d'un coup pour une chaîne (appareil en veille entre deux relevés). */
        const val AD_TESTED_MAX_STEP_MILLIS = 5_000L

        /** Enregistrement d'analyse coupé en fichiers de moins de 30 Mo (envoi), et au plus de cette durée. */
        const val AD_RECORDING_SPLIT_BYTES = 27L * 1024 * 1024
        const val AD_RECORDING_SPLIT_MILLIS = 2 * 60 * 60_000L

        /** Chaîne qui ne se lit pas : la suivante après ce délai. */
        const val AUTO_ERROR_MILLIS = 60_000L
        const val AUTO_CHECK_MILLIS = 10_000L

        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_RESUME = "resume"

        /** Replay : début et fin du programme passé (millisecondes), 0 pour une lecture normale. */
        const val ARG_REPLAY_START = "replayStart"
        const val ARG_REPLAY_STOP = "replayStop"

        /** Chaîne ouverte depuis les favoris : menu des chaînes et zapping restent dans les favoris. */
        const val ARG_FAVORITES = "favorites"

        /** Catégorie d'où la chaîne a été ouverte (`""` : sans groupe ; absente : toutes les chaînes) : le zapping y reste. */
        const val ARG_GROUP = "group"

        /** Demander s'il faut reprendre un film ou un épisode commencé. */
        const val RESUME_ASK = "ask"

        /** Reprendre sans demander (catégorie « Reprendre la lecture », bouton « Reprendre »). */
        const val RESUME_YES = "yes"

        /** Depuis le début, sans demander. */
        const val RESUME_NO = "no"

        private const val PROGRESS_SAVE_MILLIS = 10_000L

        /** Attente avant de rouvrir un flux refusé juste après la fermeture de l'aperçu. */
        private const val PREVIEW_RETRY_MILLIS = 1_500L
        private const val TAG = "Player"
        private const val PROGRAMME_REFRESH_MILLIS = 30_000L
        private const val MATCH_REFRESH_MILLIS = 15_000L

        /** Échecs de décodage : pistes son, décodeurs vidéo et copies du titre essayés au plus. */
        private const val MAX_AUDIO_TRIES = 3
        private const val MAX_DECODER_TRIES = 2
        private const val MAX_COPY_TRIES = 3

        /** Coupures d'un direct rouvert dans la même qualité avant d'essayer une version inférieure. */
        private const val SAME_QUALITY_RETRIES = 2

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

        /** Morceau de la radio des pubs relu toutes les 10 s (le service garde sa réponse 20 s). */
        private const val AD_TRACK_REFRESH_MILLIS = 10_000L

        /** Un programme de match commence souvent par l'avant-match : coup d'envoi jusqu'à 45 min avant lui compris. */
        private const val LIVE_MATCH_EARLY_MILLIS = 45 * 60_000L
        private const val QUALITY_CHECK_MILLIS = 500L

        /** Durée d'affichage d'un message de qualité dans le bandeau. */
        internal const val QUALITY_NOTICE_MILLIS = 8_000L

        /** Une sauvegarde par minute (surveillance deux fois par seconde). */
        private const val AD_MARKS_SAVE_CHECKS = 120
        private val HLS_FALLBACK_FORMATS = setOf(StreamFormat.UNKNOWN, StreamFormat.PROGRESSIVE)
        private val HLS_FALLBACK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        /** Flux qu'on ne sait pas lire (abîmé, ou dans un format inattendu). */
        private val UNREADABLE_STREAM_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
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

/** « hd1080 » → « 1080p » ; `null` pour une qualité encore inconnue. */
internal fun youtubeQualityLabel(quality: String): String? = when (quality) {
    "tiny" -> "144p"
    "small" -> "240p"
    "medium" -> "360p"
    "large" -> "480p"
    "hd720" -> "720p"
    "hd1080" -> "1080p"
    "hd1440" -> "1440p"
    "hd2160", "highres" -> "2160p"
    "", "unknown", "auto" -> null
    else -> quality
}
