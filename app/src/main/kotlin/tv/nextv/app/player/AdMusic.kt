package tv.nextv.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nextv.core.catalog.InternetRadios
import java.io.File

/**
 * Radios sans pub proposées pendant les pubs de la télé : celles de Radio France, puis les Web radios de l'appli
 * (rap, hip-hop, groove, ska, reggae… voir [InternetRadios]), sans doublon.
 */
object AdRadios {
    /** @property genre genre affiché sous le nom dans le choix de la radio. */
    data class Radio(val key: String, val name: String, val url: String, val genre: String = "Radio France")

    private val radioFrance = listOf(
        Radio("fip", "FIP", "https://icecast.radiofrance.fr/fip-hifi.aac"),
        Radio("fip_pop", "FIP Pop", "https://icecast.radiofrance.fr/fippop-hifi.aac"),
        Radio("fip_jazz", "FIP Jazz", "https://icecast.radiofrance.fr/fipjazz-hifi.aac"),
        Radio("fip_groove", "FIP Groove", "https://icecast.radiofrance.fr/fipgroove-hifi.aac"),
        Radio("fip_electro", "FIP Electro", "https://icecast.radiofrance.fr/fipelectro-hifi.aac"),
        Radio("fip_world", "FIP Monde", "https://icecast.radiofrance.fr/fipworld-hifi.aac"),
        Radio("fip_new", "FIP Nouveautés", "https://icecast.radiofrance.fr/fipnouveautes-hifi.aac"),
        Radio("fip_reggae", "FIP Reggae", "https://icecast.radiofrance.fr/fipreggae-hifi.aac"),
        Radio("france_musique", "France Musique", "https://icecast.radiofrance.fr/francemusique-hifi.aac"),
    )

    val all: List<Radio> = radioFrance + InternetRadios.all
        .filter { web -> radioFrance.none { it.url == web.url } }
        .map { Radio("web_${it.key}", it.name, it.url, it.genre) }

    fun of(key: String?): Radio = all.firstOrNull { it.key == key } ?: all.first()
}

/**
 * Musique gardée sur l'appareil pour les pubs : un dossier de l'application (rempli depuis le téléphone, par l'adresse de
 * Réglages › Son), et le dossier « NixTV/musique » des clés USB branchées, s'il est lisible.
 */
class AdMusicLibrary(private val internal: File, private val usbFolders: () -> List<File> = { emptyList() }) {

    private val mutableCount = MutableStateFlow(0)

    /** Nombre de morceaux disponibles (relu par [refresh]). */
    val count: StateFlow<Int> = mutableCount.asStateFlow()

    /** Dossier où arrivent les morceaux envoyés depuis le téléphone. */
    val uploads: File get() = internal.also { it.mkdirs() }

    /** Morceaux disponibles, dans l'ordre des noms. */
    fun tracks(): List<File> = (listOf(internal) + usbFolders()).flatMap { folder ->
        try {
            folder.listFiles { file -> file.isFile && file.extension.lowercase() in EXTENSIONS }.orEmpty().toList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }.sortedBy { it.name.lowercase() }

    /** Morceaux vus à la dernière lecture ([refresh]) : lus sans toucher aux clés USB (lentes) sur le fil principal. */
    @Volatile
    var knownTracks: List<File> = emptyList()
        private set

    /** Relit les morceaux (dossiers des clés USB compris) : hors du fil principal. */
    fun refresh() {
        val found = tracks()
        knownTracks = found
        mutableCount.value = found.size
    }

    /** Morceau envoyé : nom gardé s'il est sûr (pas de dossier, extension de musique), sinon `null`. */
    fun target(name: String): File? {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (clean.isEmpty() || clean.startsWith('.') || clean.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
        return File(uploads, clean.take(MAX_NAME))
    }

    /** Il reste de la place pour recevoir [bytes] octets de musique. */
    fun hasRoom(bytes: Long): Boolean = bytes in 1..MAX_TRACK_BYTES && uploads.usableSpace - bytes > MIN_FREE_BYTES

    /** Supprime la musique envoyée depuis le téléphone (pas celle des clés USB). */
    fun deleteUploads() {
        internal.listFiles()?.forEach { it.delete() }
        refresh()
    }

    companion object {
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
        private const val MAX_NAME = 120
        private const val MAX_TRACK_BYTES = 300L * 1024 * 1024
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
    }
}

/**
 * Lecteur de la musique des pubs : une radio ou les morceaux de l'appareil, au hasard, qui montent en fondu quand la pub
 * commence ([start]) et s'effacent quand le programme reprend ([stop]). Un lecteur à part, qui ne prend pas le son au
 * lecteur de la chaîne (pas de demande de « focus » audio). À utiliser sur le fil principal.
 */
@OptIn(UnstableApi::class)
class AdMusicPlayer(private val context: Context) {

    sealed interface Source {
        data class Radio(val radio: AdRadios.Radio) : Source
        data class Tracks(val files: List<File>) : Source
    }

    private var player: ExoPlayer? = null
    private var source: Source? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fade: Runnable? = null

    /** Musique en cours (fondu compris). */
    var playing: Boolean = false
        private set

    /** La musique ne se lit pas (radio injoignable, fichier illisible) : la télé est alors seulement baissée. */
    @Volatile
    var failed: Boolean = false
        private set

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            failed = true
            // Morceau illisible : le suivant ; radio injoignable : rien de plus.
            val current = player ?: return
            if (source is Source.Tracks && current.mediaItemCount > 1) {
                current.seekToNextMediaItem()
                current.prepare()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failed = false
        }
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()
        .also {
            it.addListener(listener)
            player = it
        }

    /** La pub commence : [wanted] monte en fondu (la radio est rouverte, la musique reprend où elle en était). */
    fun start(wanted: Source) {
        if (playing && wanted == source) return
        val player = player()
        if (wanted != source || wanted is Source.Radio) {
            source = wanted
            failed = false
            when (wanted) {
                is Source.Radio -> player.setMediaItem(MediaItem.fromUri(wanted.radio.url))
                is Source.Tracks -> {
                    if (wanted.files.isEmpty()) return
                    player.setMediaItems(wanted.files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) })
                    player.shuffleModeEnabled = true
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    // Au hasard dès le premier morceau.
                    player.seekTo((0 until wanted.files.size).random(), 0)
                }
            }
            player.prepare()
        }
        playing = true
        player.volume = 0f
        player.play()
        ramp(from = 0f, to = VOLUME, millis = FADE_IN_MILLIS) {}
    }

    /** Le programme reprend : la musique s'efface, puis s'arrête (la radio est fermée, la musique mise en pause). */
    fun stop() {
        if (!playing) return
        playing = false
        val player = player ?: return
        ramp(from = player.volume, to = 0f, millis = FADE_OUT_MILLIS) {
            if (source is Source.Radio) player.stop() else player.pause()
        }
    }

    private fun ramp(from: Float, to: Float, millis: Long, done: () -> Unit) {
        fade?.let(handler::removeCallbacks)
        val started = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val player = player ?: return
                val t = ((android.os.SystemClock.uptimeMillis() - started) / millis.toFloat()).coerceIn(0f, 1f)
                player.volume = from + (to - from) * t
                if (t < 1f) handler.postDelayed(this, FADE_STEP_MILLIS) else done()
            }
        }
        fade = step
        handler.post(step)
    }

    fun release() {
        fade?.let(handler::removeCallbacks)
        player?.release()
        player = null
        playing = false
    }

    private companion object {
        const val VOLUME = 0.8f
        const val FADE_IN_MILLIS = 1_500L
        const val FADE_OUT_MILLIS = 800L
        const val FADE_STEP_MILLIS = 50L
    }
}
