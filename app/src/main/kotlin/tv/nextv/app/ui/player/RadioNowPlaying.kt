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
 * 2. radios de Radio France (FIP, Mouv', France Inter…) : leur service public « livemeta », relu à la fin du morceau ;
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
    /** Service de reconnaissance (autre adresse : tests). */
    private val recognitionUrl: String = "https://api.audd.io/",
) {
    private var job: Job? = null
    private var following: Pair<Boolean, String>? = null

    /** Reconnaissance refusée (limite du jour sans clé, clé invalide) : plus d'essai avant ce moment. */
    private var recognitionPausedUntil = 0L

    /** Radio affichée ([active] : son sans image, animation affichée) : suit son morceau en cours, ou s'arrête. */
    fun follow(active: Boolean, channelName: String) {
        val target = active to channelName
        if (target == following) return
        following = target
        job?.cancel()
        state.update { it.copy(nowPlaying = null, nowPlayingCover = null) }
        if (!active) return
        snippet.clear()
        job = scope.launch { loop(InternetRadios.byName(channelName)) }
    }

    private suspend fun loop(radio: InternetRadios.Radio?) {
        var nextRadioFrance = 0L
        var nextRecognition = SystemClock.elapsedRealtime() + FIRST_RECOGNITION_MILLIS
        var misses = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            when {
                // La radio annonce le morceau : c'est lui qui est affiché.
                !state.value.radioTitle.isNullOrBlank() -> {
                    if (state.value.nowPlaying != null) state.update { it.copy(nowPlaying = null, nowPlayingCover = null) }
                }
                radio?.radioFrance != null -> if (now >= nextRadioFrance) {
                    val playing = radioFrance(radio.radioFrance!!)
                    show(playing)
                    val untilEnd = playing?.untilMillis?.let { it - System.currentTimeMillis() + 3_000 } ?: RADIO_FRANCE_MILLIS
                    nextRadioFrance = now + untilEnd.coerceIn(10_000, 120_000)
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

    private fun show(playing: NowPlaying?) {
        state.update { it.copy(nowPlaying = playing?.label, nowPlayingCover = playing?.cover) }
    }

    private suspend fun radioFrance(station: Int): NowPlaying? = call(
        Request.Builder().url(radioFranceUrl(station)).build(),
    )?.let(NowPlaying::fromRadioFrance)

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

    private suspend fun call(request: Request): String? = withContext(Dispatchers.IO) {
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

        /** Son envoyé à la reconnaissance. */
        const val CLIP_MILLIS = 8_000L

        /** Première reconnaissance : le temps d'avoir assez de son. */
        const val FIRST_RECOGNITION_MILLIS = 9_000L

        /** Morceau reconnu sans durée connue : nouvel essai après ce temps. */
        const val UNKNOWN_LENGTH_MILLIS = 150_000L

        /** Radio France sans heure de fin : relu après ce temps. */
        const val RADIO_FRANCE_MILLIS = 30_000L

        /** Reconnaissance refusée (limite du jour) : pause. */
        const val PAUSE_AFTER_REFUSAL_MILLIS = 30 * 60_000L
    }
}
