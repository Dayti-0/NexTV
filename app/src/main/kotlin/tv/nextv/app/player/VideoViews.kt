package tv.nextv.app.player

import android.content.Context
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.WeakHashMap
import tv.nextv.app.R

/**
 * Vues vidéo de l'aperçu de l'accueil et du plein écran.
 *
 * Android 10 et plus : la PlayerView de Media3 (format de l'image, volet noir, sous-titres) avec sa propre zone vidéo.
 * Un lecteur qui a sa couche vidéo ([VideoLayers]) est placé sous cette zone, sans changer la destination de son
 * décodeur ; les autres (films, réglage coupé) dessinent directement dans la zone, comme avant. Avant Android 10 : la
 * PlayerView habituelle. Fil principal.
 */
@OptIn(UnstableApi::class)
object VideoViews {
    private val bindings = WeakHashMap<PlayerView, Binding>()

    /**
     * Nouvelle vue vidéo. [hideWithoutImage] : la couche d'un lecteur reste cachée tant que son flux en cours n'a pas
     * d'image (aperçu, dont le volet est transparent : sinon la dernière image de la chaîne précédente resterait).
     */
    fun create(context: Context, hideWithoutImage: Boolean = false): PlayerView {
        if (!VideoLayers.supported) return PlayerView(context)
        val view = LayoutInflater.from(context).inflate(R.layout.video_player_view, null, false) as PlayerView
        val surfaceView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Dans le cadre qui suit le format de l'image, sous le volet et les sous-titres (comme la zone de Media3).
        val frame = view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
        frame.addView(surfaceView, 0)
        bindings[view] = Binding(view, surfaceView, hideWithoutImage)
        return view
    }

    /** Montre [player] dans [view] (créée par [create]). */
    fun bind(view: PlayerView, player: Player?) {
        val binding = bindings[view]
        if (binding == null) view.player = player else binding.bind(player)
    }

    /** Lecteur montré par [view] (celui confié à un autre écran tant que cet écran reste affiché). */
    fun playerOf(view: PlayerView): Player? = bindings[view]?.player ?: view.player

    /** Vue retirée de l'écran. */
    fun release(view: PlayerView) {
        val binding = bindings.remove(view)
        if (binding == null) view.player = null else binding.release()
    }

    /** Surface où lire l'image affichée par [view] (copie pour suivre le logo de la chaîne), `null` sans image. */
    fun frameSurface(view: PlayerView): Surface? {
        val binding = bindings[view] ?: return (view.videoSurfaceView as? SurfaceView)?.holder?.surface
        return VideoLayers.of(binding.player)?.surface ?: binding.surfaceView.holder.surface?.takeIf { binding.player != null }
    }

    private class Binding(val view: PlayerView, val surfaceView: SurfaceView, val hideWithoutImage: Boolean) : SurfaceHolder.Callback {
        var player: Player? = null
            private set

        private var width = 0
        private var height = 0

        init {
            surfaceView.holder.addCallback(this)
        }

        fun bind(next: Player?) {
            val old = player
            if (next === old) return
            // Lecteur confié à un autre écran (retour à l'accueil) : son image continue ici jusqu'à ce que l'autre écran
            // place sa couche sous sa propre zone vidéo, au lieu d'un écran noir pendant le passage.
            if (next == null && VideoLayers.has(old)) return
            if (old != null) {
                val layer = VideoLayers.of(old)
                if (layer != null) layer.leave(surfaceView) else old.clearVideoSurfaceView(surfaceView)
            }
            player = next
            view.player = next
            if (next == null) return
            val layer = VideoLayers.of(next)
            if (layer == null) {
                next.setVideoSurfaceView(surfaceView)
                return
            }
            // Zone déjà prête (sinon, à sa création : surfaceChanged).
            if (surfaceView.holder.surface?.isValid == true) {
                val shownWidth = width.takeIf { it > 0 } ?: surfaceView.width
                val shownHeight = height.takeIf { it > 0 } ?: surfaceView.height
                layer.show(surfaceView, shownWidth, shownHeight, hideWithoutImage)
            }
            // Direct repris en pleine lecture : il a déjà son image, aucune « première image » n'ouvrira le volet noir.
            if (layer.hasImage) view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.visibility = View.INVISIBLE
        }

        fun release() {
            surfaceView.holder.removeCallback(this)
            val old = player
            player = null
            if (old != null) {
                val layer = VideoLayers.of(old)
                if (layer != null) layer.leave(surfaceView) else old.clearVideoSurfaceView(surfaceView)
            }
            view.player = null
        }

        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width
            this.height = height
            VideoLayers.of(player)?.show(surfaceView, width, height, hideWithoutImage)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            width = 0
            height = 0
            VideoLayers.of(player)?.leave(surfaceView)
        }
    }
}
