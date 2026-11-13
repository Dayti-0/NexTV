package tv.nextv.app.player

import android.os.Build
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.WeakHashMap

/**
 * Couche vidéo propre à un lecteur de direct (Android 10 et plus) : son décodeur y dessine du début à la fin, et
 * l'écran qui montre le lecteur (aperçu de l'accueil, plein écran) la place sous sa zone vidéo ([show]).
 *
 * Passer de l'aperçu au plein écran, et inversement, ne change donc jamais la destination du décodeur. La lui
 * changer laissait l'image noire : certains décodeurs n'affichent plus rien sur la nouvelle zone tout en disant le
 * contraire (box Raspberry Pi sous LineageOS), d'autres sont fermés puis rouverts par Media3 (plusieurs Fire TV) et
 * attendent la prochaine image complète du flux, parfois 20 s plus loin.
 *
 * Fil principal.
 */
class VideoLayer internal constructor(private val control: SurfaceControl, val surface: Surface) {
    /** Zone vidéo sous laquelle la couche est placée (`null` : hors de l'écran, le décodeur continue). */
    private var host: SurfaceView? = null

    /** Cachée tant que le flux en cours n'a pas d'image (aperçu : sinon la dernière image de la chaîne d'avant). */
    private var hideWithoutImage = false

    /** Le décodeur a dessiné une image du flux en cours : rien à attendre en changeant d'écran. */
    var hasImage = false
        private set

    private var released = false

    /** Place la couche sous la zone vidéo [view] (taille [width] × [height]), et la retire de la précédente. */
    fun show(view: SurfaceView, width: Int, height: Int, hideWithoutImage: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (released || width <= 0 || height <= 0) return
        val parent = view.surfaceControl ?: return
        if (!parent.isValid) return
        host = view
        this.hideWithoutImage = hideWithoutImage
        transact { transaction ->
            transaction
                .reparent(control, parent)
                // Image du décodeur mise à la taille de la zone (son cadre suit déjà le format de l'image).
                .setBufferSize(control, width, height)
                // Au-dessus du contenu propre de la zone (vide).
                .setLayer(control, 1)
                .setVisibility(control, visible())
        }
    }

    /** La zone [view] disparaît : la couche passe hors de l'écran si elle y était encore (le décodeur continue). */
    fun leave(view: SurfaceView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (released || host !== view) return
        host = null
        transact { it.reparent(control, null).setVisibility(control, false) }
    }

    /** Le flux en cours a une image, ou n'en a plus (arrêté, en erreur, autre flux en préparation). */
    internal fun onImage(has: Boolean) {
        if (hasImage == has) return
        hasImage = has
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!released && host != null && hideWithoutImage) transact { it.setVisibility(control, visible()) }
    }

    private fun visible() = hasImage || !hideWithoutImage

    @RequiresApi(Build.VERSION_CODES.Q)
    private inline fun transact(change: (SurfaceControl.Transaction) -> SurfaceControl.Transaction) {
        try {
            SurfaceControl.Transaction().use { change(it).apply() }
        } catch (e: RuntimeException) {
            // Zone détruite entre-temps (changement d'écran) : la couche sera replacée par la suivante.
        }
    }

    /** Lecteur rendu : la couche et sa surface aussi. */
    internal fun release() {
        if (released) return
        released = true
        host = null
        surface.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) control.release()
    }

    internal companion object {
        @RequiresApi(Build.VERSION_CODES.Q)
        fun create(): VideoLayer? = try {
            val control = SurfaceControl.Builder()
                .setName("NixTV vidéo")
                .setBufferSize(0, 0)
                .build()
            VideoLayer(control, Surface(control))
        } catch (e: Throwable) {
            // Appareil qui refuse : le lecteur dessine directement dans la zone vidéo de l'écran, comme avant.
            null
        }
    }
}

/**
 * Lecteurs qui ont leur couche vidéo ([VideoLayer]). Seuls ceux-là passent d'un écran à l'autre sans rouvrir le flux :
 * sans elle, la nouvelle zone vidéo pouvait rester noire. Fil principal.
 */
@OptIn(UnstableApi::class)
object VideoLayers {
    private val layers = WeakHashMap<Player, Any>()

    /** L'appareil permet les couches vidéo (Android 10 et plus). */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Donne à [player] sa couche vidéo, gardée jusqu'à ce qu'il soit rendu. Faux si l'appareil ne le permet pas. */
    fun attach(player: ExoPlayer): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val layer = VideoLayer.create() ?: return false
        // Destination du décodeur, une fois pour toutes.
        player.setVideoSurface(layer.surface)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = layer.onImage(true)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) layer.onImage(false)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
                layers.remove(player)
                layer.release()
            }
        })
        layers[player] = layer
        return true
    }

    /** Couche vidéo de [player], `null` s'il dessine directement dans la zone vidéo de l'écran. */
    fun of(player: Player?): VideoLayer? {
        if (player == null || !supported) return null
        return layers[player] as? VideoLayer
    }

    /** [player] a sa couche vidéo. */
    fun has(player: Player?): Boolean = player != null && layers.containsKey(player)
}
