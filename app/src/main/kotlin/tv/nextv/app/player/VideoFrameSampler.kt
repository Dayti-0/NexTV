package tv.nextv.app.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Copie réduite de l'image affichée par le lecteur, en niveaux de gris, pour suivre le logo de la chaîne
 * (option « baisser le son des pubs », [AdVolumeControl.frame]). Une seule petite image est gardée en
 * mémoire et réutilisée à chaque copie.
 */
class VideoFrameSampler {
    private val bitmap = Bitmap.createBitmap(AdVolumeControl.FRAME_WIDTH, AdVolumeControl.FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(AdVolumeControl.FRAME_WIDTH * AdVolumeControl.FRAME_HEIGHT)
    private val handler = Handler(Looper.getMainLooper())

    /** Image en cours de [view], ou `null` si elle ne peut pas être lue (pas encore d'image, appareil qui le refuse). */
    suspend fun sample(view: SurfaceView): ByteArray? {
        if (!view.holder.surface.isValid) return null
        val copied = suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(view, bitmap, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, handler)
            } catch (e: IllegalArgumentException) {
                // Surface détruite entre-temps (changement de chaîne, sortie du lecteur).
                continuation.resume(false)
            }
        }
        if (!copied) return null
        return withContext(Dispatchers.Default) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ByteArray(pixels.size) { i ->
                val pixel = pixels[i]
                // Luminance approchée : (2 R + 5 V + B) / 8.
                ((2 * (pixel shr 16 and 0xFF) + 5 * (pixel shr 8 and 0xFF) + (pixel and 0xFF)) shr 3).toByte()
            }
        }
    }

    fun release() = bitmap.recycle()
}
