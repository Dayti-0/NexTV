package tv.nextv.app.ui.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.app.data.db.ProgrammeEntity
import tv.nextv.core.catalog.InternetRadios
import tv.nextv.core.football.BroadcastFinder
import tv.nextv.core.football.MatchStatus
import tv.nextv.core.football.ScheduledMatch

/**
 * Écran pendant les pubs ([AdBoard]) : ouvert quand une page de pub commence, refermé à sa fin. Le temps de la pub, il
 * relit le programme du guide, les matchs en direct (calendrier d'ESPN), la météo et le morceau de la radio jouée à la
 * place de la pub. À la fin d'une vraie page (ni « ce n'est pas une pub », ni changement de chaîne), sa durée compte pour
 * la durée habituelle des pubs de la chaîne (« Fin prévue dans… » des pages suivantes).
 */
internal class AdBoardController(
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val state: MutableStateFlow<PlayerUiState>,
    /** Programme en cours de la chaîne et le suivant (le guide). */
    private val programmes: suspend () -> List<ProgrammeEntity>,
    private val radioTitles: RadioTitleService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private var sinceMillis = 0L
    private var channel: String? = null
    private var version = -1
    private var dismissed = false

    /** Buts vus de chaque match depuis l'ouverture, et ligne allumée jusqu'à quand après un nouveau but. */
    private val goalsSeen = HashMap<Long, Int>()
    private val goalFlash = HashMap<Long, Long>()

    /** Musique jouée à la place de la pub : nom de la radio (ou « Ma musique ») et morceau quand il est connu. */
    data class Music(val name: String, val title: String? = null)

    /**
     * Deux fois par seconde : [open] (pub détectée, réglage activé, direct lu par le lecteur de l'appli), la chaîne et sa
     * version (changement de chaîne), la musique des pubs et le temps restant annoncé par le flux.
     */
    fun update(open: Boolean, channelName: String, channelVersion: Int, music: Music?, cueRemainingMillis: Long?) {
        val now = clock()
        if (!open) {
            close(now, record = true)
            return
        }
        // Autre chaîne pendant la pub : cette page-là n'est pas finie, rien n'est compté.
        if (job != null && channelVersion != version) close(now, record = false)
        if (job == null) start(now, channelName, channelVersion)
        val end = cueRemainingMillis?.let { now + it } ?: container.adBreakDurations.typical(channelName)?.let { sinceMillis + it }
        val rounded = end?.let { it / 1_000 * 1_000 }
        val announced = cueRemainingMillis != null
        state.update { current ->
            val board = current.adBoard ?: return@update current
            val radio = music?.let { wanted ->
                board.radio?.takeIf { it.name == wanted.name }?.let { known -> if (wanted.title != null) known.copy(title = wanted.title) else known }
                    ?: AdBoardRadio(wanted.name, title = wanted.title)
            }
            if (board.endMillis == rounded && board.announced == announced && board.radio == radio) {
                current
            } else {
                current.copy(adBoard = board.copy(endMillis = rounded, announced = announced, radio = radio))
            }
        }
    }

    /** « Ce n'est pas une pub » : l'écran se ferme, et cette page ne compte pas pour la durée habituelle. */
    fun dismiss() {
        dismissed = true
        close(clock(), record = false)
    }

    private fun start(now: Long, channelName: String, channelVersion: Int) {
        sinceMillis = now
        channel = channelName
        version = channelVersion
        dismissed = false
        goalsSeen.clear()
        goalFlash.clear()
        // Tout de suite ce que le bandeau sait déjà ; le détail du guide, les matchs et la météo suivent.
        state.update { current ->
            current.copy(
                adBoard = AdBoard(
                    sinceMillis = now,
                    programme = current.now?.let { AdBoardProgramme(it.title, it.startMillis, it.stopMillis) },
                    next = current.next?.let { AdBoardProgramme(it.title, it.startMillis, it.stopMillis) },
                ),
            )
        }
        job = scope.launch { refreshLoop() }
    }

    private fun close(now: Long, record: Boolean) {
        val running = job ?: return
        running.cancel()
        job = null
        val name = channel
        if (record && !dismissed && name != null) container.noteAdBreak(name, now - sinceMillis)
        state.update { it.copy(adBoard = null) }
    }

    private suspend fun refreshLoop() {
        var round = 0
        while (true) {
            if (round % PROGRAMME_ROUNDS == 0) safely { refreshProgrammes() }
            safely { refreshMatches() }
            safely { refreshRadio() }
            if (round % WEATHER_ROUNDS == 0) safely { refreshWeather() }
            round++
            delay(REFRESH_MILLIS)
        }
    }

    /** Une source muette ou en erreur n'empêche pas les autres de s'afficher. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            container.logger.warn(TAG, "Écran des pubs : source indisponible", e)
        }
    }

    private suspend fun refreshProgrammes() {
        val now = clock()
        val list = programmes()
        val current = list.firstOrNull { it.startMillis <= now && it.stopMillis > now }
        val next = list.firstOrNull { it.startMillis >= (current?.stopMillis ?: now) }
        edit { it.copy(programme = current?.toBoard() ?: it.programme, next = next?.toBoard() ?: it.next) }
    }

    private suspend fun refreshMatches() {
        if (!container.settings.footballSection.first()) {
            edit { it.copy(matches = emptyList()) }
            return
        }
        val now = clock()
        val schedule = container.liveScores.schedule(now - LIVE_WINDOW_MILLIS, now + EARLY_MILLIS) ?: return
        val live = schedule.filter { it.status == MatchStatus.LIVE }
        val teams = container.settings.favoriteTeams.first()
        val onAir = state.value.match
        val ordered = live.sortedWith(
            compareByDescending<ScheduledMatch> { onAir != null && sameMatch(it, onAir) }
                .thenByDescending { match -> teams.any { BroadcastFinder.sameClub(it, match.home.name) || BroadcastFinder.sameClub(it, match.away.name) } }
                .thenBy { it.kickoffMillis },
        ).take(MAX_MATCHES)
        val matches = ordered.map { match ->
            val goals = (match.homeScore ?: 0) + (match.awayScore ?: 0)
            val before = goalsSeen.put(match.id, goals)
            if (before != null && goals > before) goalFlash[match.id] = now + GOAL_FLASH_MILLIS
            AdBoardMatch(
                home = match.home.displayName,
                away = match.away.displayName,
                homeCrest = match.home.crestUrl,
                awayCrest = match.away.crestUrl,
                homeScore = match.homeScore,
                awayScore = match.awayScore,
                minute = match.clock,
                halfTime = match.halfTime,
                resumeAtMillis = container.halfTimes.resumeAt(
                    "${match.home.displayName}|${match.away.displayName}|${match.kickoffMillis}", match.kickoffMillis,
                    inPlay = true, halfTime = match.halfTime, minute = match.clock,
                ),
                goals = match.goals,
                goalUntilMillis = goalFlash[match.id]?.takeIf { it > now },
                onThisChannel = onAir != null && sameMatch(match, onAir),
            )
        }
        edit { it.copy(matches = matches) }
    }

    private fun sameMatch(match: ScheduledMatch, onAir: PlayerMatch): Boolean =
        (BroadcastFinder.sameClub(onAir.home, match.home.name) || BroadcastFinder.sameClub(onAir.home, match.home.displayName)) &&
            (BroadcastFinder.sameClub(onAir.away, match.away.name) || BroadcastFinder.sameClub(onAir.away, match.away.displayName))

    private suspend fun refreshRadio() {
        val radio = state.value.adBoard?.radio ?: return
        val source = InternetRadios.titlesFor(radio.name) ?: return
        val playing = radioTitles.cached(source) ?: return
        edit { board ->
            board.radio?.takeIf { it.name == radio.name }?.let { current ->
                board.copy(radio = current.copy(title = playing.title, artist = playing.artist, cover = playing.cover))
            } ?: board
        }
    }

    private suspend fun refreshWeather() {
        val place = container.settings.weatherPlace.first()
        if (place == null) {
            edit { it.copy(weather = null, weatherPlaceMissing = true) }
            return
        }
        val weather = container.weather.forecast(place) ?: return
        edit { it.copy(weather = weather, weatherPlaceMissing = false) }
    }

    /** Change l'écran affiché, s'il l'est encore. */
    private fun edit(change: (AdBoard) -> AdBoard) {
        state.update { current -> current.adBoard?.let { current.copy(adBoard = change(it)) } ?: current }
    }

    private fun ProgrammeEntity.toBoard() = AdBoardProgramme(
        title = title,
        startMillis = startMillis,
        stopMillis = stopMillis,
        subtitle = subtitle?.takeIf { it.isNotBlank() && it != title },
        description = description?.takeIf { it.isNotBlank() },
        category = category?.takeIf { it.isNotBlank() },
        year = year,
        imageUrl = imageUrl,
    )

    private companion object {
        const val TAG = "AdBoard"
        const val REFRESH_MILLIS = 15_000L

        /** Programme relu toutes les 30 s, météo toutes les 2 min (le service la garde 30 min). */
        const val PROGRAMME_ROUNDS = 2
        const val WEATHER_ROUNDS = 8

        /** Matchs en cours : coup d'envoi dans les 3 dernières heures. */
        const val LIVE_WINDOW_MILLIS = 3 * 60 * 60_000L
        const val EARLY_MILLIS = 15 * 60_000L
        const val MAX_MATCHES = 4

        /** Ligne d'un match allumée après un but marqué pendant la pub. */
        const val GOAL_FLASH_MILLIS = 60_000L
    }
}
