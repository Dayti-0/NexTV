package tv.nextv.app.player

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Clés USB (et cartes mémoire) branchées, où les enregistrements pour l'analyse des pubs peuvent aller : le dossier
 * propre à l'application sur chacune (sans autorisation à demander), avec le nom que le système leur donne.
 */
fun removableRecordingPlaces(context: Context): List<AdRecordings.Location> {
    val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    return context.getExternalFilesDirs(RECORDINGS_FOLDER).drop(1).filterNotNull().mapNotNull { directory ->
        try {
            if (Environment.getExternalStorageState(directory) != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) storage?.getStorageVolume(directory) else null
            if (volume != null && !volume.isRemovable) return@mapNotNull null
            directory.mkdirs()
            // Identifiant du volume (le même à chaque branchement), à défaut le chemin de la clé.
            val key = volume?.uuid ?: directory.path.substringBefore("/Android/")
            AdRecordings.Location(key, volume?.getDescription(context) ?: File(key).name, directory)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}

private const val RECORDINGS_FOLDER = "ad_recordings"
