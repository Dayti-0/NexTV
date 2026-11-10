package tv.nextv.app.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.concurrent.thread

/**
 * Lecture par le moteur de VLC (libVLC), dans le lecteur de l'appli : quand celui-ci ne sait pas lire le son
 * (ou la vidéo) d'un flux, VLC prend la suite, avec le même écran et les mêmes commandes. Ses réglages sont
 * ceux de l'application VLC pour les flux réseau (reconnexion, mémoire tampon).
 */
@OptIn(UnstableApi::class)
class VlcPlayback(context: Context, private val listener: Listener) {

    interface Listener {
        /** Lecture commencée (image et son). */
        fun onPlaying()

        /** Fin du film ou de l'épisode. */
        fun onEnded()

        /** VLC non plus ne sait pas le lire (ou le flux ne répond pas). */
        fun onError()

        /** Pistes son et sous-titres connues ou changées. */
        fun onTracksChanged()

        /** Chargement en cours (mémoire tampon) ou terminé. */
        fun onBuffering(buffering: Boolean)
    }

    private val libVlc = LibVLC(context.applicationContext, OPTIONS)
    private val mediaPlayer = MediaPlayer(libVlc)
    private var attached: VLCVideoLayout? = null

    /** La lecture a commencé au moins une fois : une « fin » signalée avant est un flux que VLC n'a pas su ouvrir. */
    private var started = false

    /** Le même lecteur vu par l'écran (barre de progression, pause, déplacements). */
    val player = VlcPlayer(mediaPlayer)

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    started = true
                    player.update(buffering = false)
                    listener.onPlaying()
                    listener.onTracksChanged()
                }
                MediaPlayer.Event.Buffering -> {
                    val buffering = event.buffering < 100f
                    player.update(buffering = buffering)
                    listener.onBuffering(buffering)
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.LengthChanged, MediaPlayer.Event.SeekableChanged -> player.update()
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> listener.onTracksChanged()
                MediaPlayer.Event.EndReached -> {
                    if (started) {
                        player.update(ended = true)
                        listener.onEnded()
                    } else {
                        // Rien n'a été lu : pas la fin du film (qui fermerait le lecteur), mais un échec.
                        listener.onError()
                    }
                }
                MediaPlayer.Event.EncounteredError -> listener.onError()
            }
        }
    }

    /** Lit [url] (identifiants compris s'il en faut) à partir de [startMs], avec le User-Agent de la playlist. */
    fun play(url: String, userAgent: String?, startMs: Long) {
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            userAgent?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
            if (startMs > 0) addOption(":start-time=${startMs / 1000.0}")
        }
        mediaPlayer.media = media
        media.release()
        started = false
        player.update(buffering = true, ended = false)
        mediaPlayer.play()
    }

    /** Image dans [layout] (vue de l'écran du lecteur). */
    fun attach(layout: VLCVideoLayout) {
        if (attached === layout) return
        if (attached != null) mediaPlayer.detachViews()
        mediaPlayer.attachViews(layout, null, true, false)
        attached = layout
    }

    fun detach(layout: VLCVideoLayout) {
        if (attached !== layout) return
        mediaPlayer.detachViews()
        attached = null
    }

    /** Cadrage de l'image : entière, plein écran, ou zoom (bords rognés). */
    fun setScale(fill: Boolean, zoom: Boolean) {
        mediaPlayer.videoScale = when {
            zoom -> MediaPlayer.ScaleType.SURFACE_FILL
            fill -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    /** Pistes son : identifiant VLC et nom (« Piste 1 - [Français] »), et celle qui est lue. */
    fun audioTracks(): List<Pair<Int, String>> = mediaPlayer.audioTracks.orEmpty().filter { it.id >= 0 }.map { it.id to it.name }
    val audioTrack: Int get() = mediaPlayer.audioTrack
    fun selectAudio(id: Int) {
        mediaPlayer.audioTrack = id
    }

    /** Sous-titres (sans « Désactiver ») ; -1 : aucun. */
    fun subtitleTracks(): List<Pair<Int, String>> = mediaPlayer.spuTracks.orEmpty().filter { it.id >= 0 }.map { it.id to it.name }
    val subtitleTrack: Int get() = mediaPlayer.spuTrack
    fun selectSubtitles(id: Int) {
        mediaPlayer.spuTrack = id
    }

    /**
     * Arrête la lecture et libère le moteur. L'arrêt de libVLC attend la fin de ses fils (réseau, image), qui
     * peuvent eux-mêmes attendre le fil principal : fait sur le fil principal, il figeait l'application (« ne
     * répond plus ») en quittant le lecteur. L'image est donc détachée ici, puis l'arrêt se fait à part.
     */
    fun release() {
        mediaPlayer.setEventListener(null)
        attached?.let { runCatching { mediaPlayer.detachViews() } }
        attached = null
        player.release()
        stopInBackground(release = true)
    }

    /** Arrêt (et libération si [release]) du moteur hors du fil principal ; les erreurs d'un moteur déjà arrêté sont ignorées. */
    private fun stopInBackground(release: Boolean) {
        thread(name = "nextv-vlc-stop", isDaemon = true) {
            runCatching { mediaPlayer.stop() }
            if (release) {
                runCatching { mediaPlayer.release() }
                runCatching { libVlc.release() }
            }
        }
    }

    private companion object {
        val OPTIONS = arrayListOf(
            "--http-reconnect",
            "--network-caching=2000",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1",
        )
    }
}

/**
 * Le lecteur VLC présenté comme un lecteur Media3 : la barre de progression, la pause et les déplacements de
 * l'écran du lecteur fonctionnent sans savoir lequel des deux moteurs lit.
 */
@OptIn(UnstableApi::class)
class VlcPlayer(private val mediaPlayer: MediaPlayer) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playWhenReady = true
    private var buffering = true
    private var ended = false
    private var released = false

    fun update(buffering: Boolean = this.buffering, ended: Boolean = this.ended) {
        this.buffering = buffering
        this.ended = ended
        if (!released) invalidateState()
    }

    override fun getState(): State {
        if (released) return State.Builder().setPlaybackState(Player.STATE_IDLE).build()
        val length = mediaPlayer.length
        val item = MediaItemData.Builder(ITEM)
            .setDurationUs(if (length > 0) length * 1_000 else C.TIME_UNSET)
            .setIsSeekable(mediaPlayer.isSeekable)
            .build()
        val time = PositionSupplier { mediaPlayer.time.coerceAtLeast(0) }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    ended -> Player.STATE_ENDED
                    buffering -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setPlaylist(listOf(item))
            .setContentPositionMs(time)
            .setContentBufferedPositionMs(time)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            if (ended) {
                ended = false
                mediaPlayer.time = 0
            }
            mediaPlayer.play()
        } else {
            mediaPlayer.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        ended = false
        mediaPlayer.setTime(positionMs.coerceAtLeast(0), true)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // Jamais sur le fil principal : l'arrêt de libVLC peut l'attendre lui-même (application figée).
        thread(name = "nextv-vlc-stop", isDaemon = true) { runCatching { mediaPlayer.stop() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val ITEM = "vlc"
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
