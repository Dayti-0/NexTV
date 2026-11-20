package tv.nextv.app.ui.player

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.nextv.core.audio.AudioSnippet
import tv.nextv.core.audio.NowPlaying
import tv.nextv.core.catalog.InternetRadios
import tv.nextv.core.log.Logger
import tv.nextv.core.net.await
import java.io.IOException

/**
 * Morceau en cours d'une radio affichée (animation des radios), quand elle ne l'annonce pas dans son flux :
 * 1. titre du flux (métadonnées ICY, `PlayerUiState.radioTitle`) : rien d'autre à faire ;
 * 2. radios qui publient le morceau en cours sur leur site ([InternetRadios.TitleSource] : Radio France, Skyrock, Nova et
 *    ses webradios, Les Indés Radios, Europe 2, RFM, Radioking ; émission en cours de RTL) : ce service, relu à la fin du
 *    morceau ou toutes les 25 s ;
 * 3. sinon, reconnaissance à l'écoute (comme Shazam) : 8 s du son envoyées à AudD, relancée à la fin du morceau reconnu
 *    (sa durée est connue), plus espacée quand rien n'est reconnu (émission parlée), arrêtée un moment à la limite du jour.
 * Le résultat va dans `PlayerUiState.nowPlaying` / `nowPlayingCover`.
 */
internal class RadioNowPlaying(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val client: () -> OkHttpClient,
    private val snippet: AudioSnippet,
    private val auddKey: suspend () -> String?,
    private val logger: Logger,
    /** Service « livemeta » de Radio France pour la radio de ce numéro (autre adresse : tests). */
    private val radioFranceUrl: (Int) -> String = { "https://api.radiofrance.fr/livemeta/pull/$it" },
    /** Morceau en cours de Skyrock, de Nova et de ses webradios, et des Indés Radios (autres adresses : tests). */
    private val skyrockUrl: String = "https://skyrock.fm/api/v3/player/onair/national/",
    private val novaUrl: String = "https://www.nova.fr/radios-data/www.nova.fr/all.json",
    private val indesUrl: (String) -> String = { site -> "https://$site/lite/update_onair" },
    private val lagardereUrl: (String) -> String = { site -> "https://$site/cetait-quoi-ce-titre/history?idplayer=1&limit=1" },
    private val radiokingUrl: (Int) -> String = { id -> "https://api.radioking.io/widgets/api/v1/radio/$id/track/current" },
    private val rtlUrl: (String) -> String = { site -> "https://$site/ws/live/live" },
    /** Service de reconnaissance (autre adresse : tests). */
    private val recognitionUrl: String = "https://api.audd.io/",
) {
    private val titles = RadioTitleService(client, logger, radioFranceUrl, skyrockUrl, novaUrl, indesUrl, lagardereUrl, radiokingUrl, rtlUrl)
    private var job: Job? = null
    private var videoJob: Job? = null
    private var following: Pair<Boolean, String>? = null

    /** Reconnaissance refusée (limite du jour sans clé, clé invalide) : plus d'essai avant ce moment. */
    private var recognitionPausedUntil = 0L

    /** Radio affichée ([active] : son sans image, animation affichée) : suit son morceau en cours, ou s'arrête. */
    fun follow(active: Boolean, channelName: String) {
        val target = active to channelName
        if (target == following) return
        following = target
        job?.cancel()
        videoJob?.cancel()
        state.update { it.copy(nowPlaying = null, nowPlayingCover = null, nowPlayingArtist = null, nowPlayingSong = null, radioVideo = null) }
        if (!active) return
        snippet.clear()
        val source = InternetRadios.titlesFor(channelName)
        job = scope.launch { loop(source) }
        // Skyrock : vidéo du direct quand l'émission est filmée (relu toutes les minutes, même si le flux donne le titre).
        if (source == InternetRadios.TitleSource.Skyrock) videoJob = scope.launch { followLiveVideo() }
    }

    private suspend fun loop(source: InternetRadios.TitleSource?) {
        var nextSource = 0L
        var sourceFailures = 0
        var nextRecognition = SystemClock.elapsedRealtime() + FIRST_RECOGNITION_MILLIS
        var misses = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            // Service de la radio qui ne répond plus (3 fois de suite) : reconnaissance à l'écoute à la place.
            val useSource = source != null && sourceFailures < SOURCE_FAILURES
            when {
                // La radio annonce le morceau : c'est lui qui est affiché.
                !state.value.radioTitle.isNullOrBlank() -> {
                    if (state.value.nowPlaying != null) show(null)
                }
                useSource -> if (now >= nextSource) {
                    val playing = fromSource(source!!)
                    if (playing != null) {
                        sourceFailures = 0
                        show(playing)
                    } else {
                        sourceFailures++
                    }
                    val untilEnd = playing?.untilMillis?.let { it - System.currentTimeMillis() + 3_000 } ?: SOURCE_MILLIS
                    nextSource = now + untilEnd.coerceIn(10_000, 120_000)
                }
                now >= nextRecognition && now >= recognitionPausedUntil -> {
                    val wav = snippet.wav(CLIP_MILLIS)
                    if (wav == null) {
                        nextRecognition = now + 2_000
                    } else {
                        val playing = recognize(wav)
                        if (playing != null) {
                            misses = 0
                            show(playing)
                            nextRecognition = now + ((playing.remainingMillis ?: UNKNOWN_LENGTH_MILLIS) + 8_000).coerceIn(30_000, 300_000)
                        } else {
                            // Rien de reconnu (émission parlée, pub) : de plus en plus espacé.
                            misses++
                            if (misses >= 2) show(null)
                            nextRecognition = now + if (misses < 3) 60_000L else 300_000L
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    private suspend fun followLiveVideo() {
        while (true) {
            val body = titles.call(Request.Builder().url(skyrockUrl).build())
            // Réponse manquée (réseau) : la vidéo en cours reste.
            if (body != null) {
                val video = NowPlaying.skyrockLiveVideo(body)
                if (video != state.value.radioVideo) state.update { it.copy(radioVideo = video) }
            }
            delay(LIVE_VIDEO_MILLIS)
        }
    }

    /** Morceau en cours lu sur le service de la radio, `null` s'il ne répond pas ou ne le donne pas. */
    private suspend fun fromSource(source: InternetRadios.TitleSource): NowPlaying? = titles.current(source)

    private fun show(playing: NowPlaying?) {
        state.update {
            it.copy(
                nowPlaying = playing?.label,
                nowPlayingCover = playing?.cover,
                nowPlayingArtist = playing?.artist?.takeIf { artist -> artist.isNotBlank() },
                nowPlayingSong = playing?.title,
            )
        }
    }

    private suspend fun recognize(wav: ByteArray): NowPlaying? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "radio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("return", "apple_music")
            .apply { auddKey()?.let { addFormDataPart("api_token", it) } }
            .build()
        val answer = call(Request.Builder().url(recognitionUrl).post(body).build()) ?: return null
        NowPlaying.recognitionError(answer)?.let { code ->
            logger.warn(TAG, "Reconnaissance du morceau refusée (code $code)")
            recognitionPausedUntil = SystemClock.elapsedRealtime() + PAUSE_AFTER_REFUSAL_MILLIS
            return null
        }
        return NowPlaying.fromAudd(answer)
    }

    private suspend fun call(request: Request): String? = titles.call(request)

    private companion object {
        const val TAG = "RadioNowPlaying"

        /** Son envoyé à la reconnaissance. */
        const val CLIP_MILLIS = 8_000L

        /** Première reconnaissance : le temps d'avoir assez de son. */
        const val FIRST_RECOGNITION_MILLIS = 9_000L

        /** Morceau reconnu sans durée connue : nouvel essai après ce temps. */
        const val UNKNOWN_LENGTH_MILLIS = 150_000L

        /** Service de la radio sans heure de fin : relu après ce temps. */
        const val SOURCE_MILLIS = 25_000L

        /** Réponses vides de suite du service de la radio avant de passer à la reconnaissance. */
        const val SOURCE_FAILURES = 3

        /** Vidéo du direct de Skyrock : relue toutes les minutes. */
        const val LIVE_VIDEO_MILLIS = 60_000L

        /** Reconnaissance refusée (limite du jour) : pause. */
        const val PAUSE_AFTER_REFUSAL_MILLIS = 30 * 60_000L
    }
}

/**
 * Services des radios qui publient leur morceau (ou émission) en cours : partagé par le lecteur ([RadioNowPlaying]) et
 * par l'accueil, qui l'affiche à la place du programme pour la radio sélectionnée ([current] garde chaque réponse 20 s :
 * passer d'une radio à l'autre ne relit pas un service à chaque fois).
 */
class RadioTitleService(
    private val client: () -> OkHttpClient,
    private val logger: Logger,
    private val radioFranceUrl: (Int) -> String = { "https://api.radiofrance.fr/livemeta/pull/$it" },
    private val skyrockUrl: String = "https://skyrock.fm/api/v3/player/onair/national/",
    private val novaUrl: String = "https://www.nova.fr/radios-data/www.nova.fr/all.json",
    private val indesUrl: (String) -> String = { site -> "https://$site/lite/update_onair" },
    private val lagardereUrl: (String) -> String = { site -> "https://$site/cetait-quoi-ce-titre/history?idplayer=1&limit=1" },
    private val radiokingUrl: (Int) -> String = { id -> "https://api.radioking.io/widgets/api/v1/radio/$id/track/current" },
    private val rtlUrl: (String) -> String = { site -> "https://$site/ws/live/live" },
) {
    private class Cached(val atMillis: Long, val playing: NowPlaying?)

    private val cache = java.util.concurrent.ConcurrentHashMap<InternetRadios.TitleSource, Cached>()

    /** Morceau en cours, relu au plus toutes les 20 s par service (accueil). */
    suspend fun cached(source: InternetRadios.TitleSource): NowPlaying? {
        val now = SystemClock.elapsedRealtime()
        cache[source]?.takeIf { now - it.atMillis < CACHE_MILLIS }?.let { return it.playing }
        val playing = current(source)
        cache[source] = Cached(now, playing)
        return playing
    }

    /** Morceau en cours lu sur le service de la radio, `null` s'il ne répond pas ou ne le donne pas. */
    suspend fun current(source: InternetRadios.TitleSource): NowPlaying? = when (source) {
        is InternetRadios.TitleSource.RadioFrance -> call(Request.Builder().url(radioFranceUrl(source.station)).build())
            ?.let(NowPlaying::fromRadioFrance)
        InternetRadios.TitleSource.Skyrock -> call(Request.Builder().url(skyrockUrl).build())
            ?.let { NowPlaying.fromSkyrock(it, System.currentTimeMillis() / 1_000) }
        is InternetRadios.TitleSource.Nova -> call(Request.Builder().url(novaUrl).build())?.let { NowPlaying.fromNova(it, source.stream) }
        is InternetRadios.TitleSource.Indes -> call(
            Request.Builder().url(indesUrl(source.site))
                .post("""{"radio_ids":["${source.radioId}"]}""".toRequestBody("application/json".toMediaType()))
                .build(),
        )?.let { NowPlaying.fromIndes(it, source.radioId) }
        is InternetRadios.TitleSource.Lagardere -> call(Request.Builder().url(lagardereUrl(source.site)).build())?.let(NowPlaying::fromLagardere)
        is InternetRadios.TitleSource.Radioking -> call(Request.Builder().url(radiokingUrl(source.radioId)).build())?.let(NowPlaying::fromRadioking)
        is InternetRadios.TitleSource.RtlProgram -> call(Request.Builder().url(rtlUrl(source.site)).build())?.let(NowPlaying::fromRtlProgram)
    }

    internal suspend fun call(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            client().newCall(request).await().use { response -> if (response.isSuccessful) response.body.string() else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn(TAG, "Morceau en cours indisponible : ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "RadioNowPlaying"
        const val CACHE_MILLIS = 20_000L
    }
}
