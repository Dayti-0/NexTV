package tv.nextv.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import java.io.File

/**
 * Clés USB (et cartes mémoire) branchées, où les enregistrements pour l'analyse des pubs peuvent aller.
 *
 * Deux façons d'y écrire :
 * - le dossier propre à l'application sur la clé (`Android/data/…`), quand le système le donne (sans autorisation) ;
 * - sinon, à partir d'Android 11, un dossier « NixTV » à la racine de la clé, avec l'autorisation « Accès à tous les
 *   fichiers » ([allFilesAccessIntent]) : certains systèmes (box sous LineageOS…) ne donnent pas le premier.
 *
 * Une clé vue mais où l'on ne peut pas écrire est rendue quand même ([AdRecordings.Location.access]), pour que Réglages
 * dise quoi faire.
 */
fun removableRecordingPlaces(context: Context): List<AdRecordings.Location> {
    val appDirs = try {
        context.getExternalFilesDirs(RECORDINGS_FOLDER).drop(1).filterNotNull()
    } catch (e: RuntimeException) {
        emptyList()
    }
    val places = ArrayList<AdRecordings.Location>()
    val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            storage?.storageVolumes.orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
    } else {
        emptyList()
    }
    for (volume in volumes) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !volume.isRemovable) continue
        val root = rootOf(volume)
        val key = volume.uuid ?: root?.path ?: continue
        val label = volume.getDescription(context) ?: root?.name ?: key
        if (volume.state != Environment.MEDIA_MOUNTED) {
            places += AdRecordings.Location(key, label, File(root ?: File("/storage/$key"), RECORDINGS_FOLDER), AdRecordings.Access.NOT_MOUNTED)
            continue
        }
        // 1. Dossier de l'application sur la clé, donné par le système.
        val own = appDirs.firstOrNull { dir -> root != null && dir.path.startsWith(root.path + "/") }
        if (own != null && writable(own)) {
            places += AdRecordings.Location(key, label, own)
            continue
        }
        // 2. Dossier « NixTV » à la racine, avec l'accès à tous les fichiers (Android 11 et plus).
        val folder = root?.let { File(it, "NixTV/$RECORDINGS_FOLDER") }
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        places += when {
            folder != null && allFiles && writable(folder) -> AdRecordings.Location(key, label, folder)
            folder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFiles ->
                AdRecordings.Location(key, label, folder, AdRecordings.Access.NEEDS_PERMISSION)
            else -> AdRecordings.Location(key, label, folder ?: File("/storage/$key", RECORDINGS_FOLDER), AdRecordings.Access.READ_ONLY)
        }
    }
    // Clés que le système donne sans les décrire (anciens Android) : dossiers de l'application restants.
    for (dir in appDirs) {
        if (places.any { dir.path.startsWith(it.directory.path.substringBefore("/Android/") + "/") }) continue
        if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED || !writable(dir)) continue
        val root = dir.path.substringBefore("/Android/")
        places += AdRecordings.Location(root, File(root).name, dir)
    }
    return places
}

/** Écran des réglages du système pour accorder « Accès à tous les fichiers » à l'application (Android 11 et plus). */
fun allFilesAccessIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    if (own.resolveActivity(context.packageManager) != null) return own
    val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    return all.takeIf { it.resolveActivity(context.packageManager) != null }
}

/**
 * Stockages que le système annonce, décrits pour les réglages (« SanDisk : monté, amovible ») : quand une clé n'est pas
 * proposée, la photo de cette ligne dit pourquoi.
 */
fun describeStorage(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ""
    val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return ""
    return try {
        storage.storageVolumes.joinToString("  ·  ") { volume ->
            val kind = when {
                volume.isPrimary -> "principal"
                volume.isRemovable -> "amovible"
                else -> "fixe"
            }
            "${volume.getDescription(context)} : ${volume.state}, $kind"
        }
    } catch (e: RuntimeException) {
        ""
    }
}

/** Racine d'une clé (« /storage/1234-ABCD ») ; avant Android 11, par la méthode cachée du système. */
private fun rootOf(volume: StorageVolume): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
    return try {
        volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
    } catch (e: ReflectiveOperationException) {
        try {
            (volume.javaClass.getMethod("getPath").invoke(volume) as? String)?.let(::File)
        } catch (e: ReflectiveOperationException) {
            null
        }
    } catch (e: RuntimeException) {
        null
    }
}

/** Dossier créé (si besoin) et où l'on peut vraiment écrire (un fichier d'essai). */
private fun writable(dir: File): Boolean = try {
    dir.mkdirs()
    val probe = File(dir, ".essai")
    probe.writeText("ok")
    probe.delete()
} catch (e: java.io.IOException) {
    false
} catch (e: SecurityException) {
    false
}

private const val RECORDINGS_FOLDER = "ad_recordings"
