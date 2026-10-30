package tv.nextv.app.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.BuildConfig
import tv.nextv.app.NexTvApplication
import tv.nextv.core.log.Logger
import tv.nextv.core.update.AppRelease
import tv.nextv.core.update.UpdateCheck
import tv.nextv.core.update.UpdateChecker
import tv.nextv.core.update.UpdateError
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val downloaded: Long, val total: Long) : UpdateState

    /** APK transmis à l'installateur du système, qui demande confirmation. */
    data class Installing(val release: AppRelease) : UpdateState

    /** NexTV doit d'abord être autorisée à installer des applications (réglage du système). */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Failed(val error: UpdateFailure, val release: AppRelease?) : UpdateState
}

enum class UpdateFailure { NOT_PUBLISHED, NETWORK, SERVER, CORRUPTED, INSTALL }

/**
 * Mise à jour depuis l'application : dernière version publiée sur GitHub, téléchargée puis
 * confiée à l'installateur du système (même clé de signature : playlists et réglages conservés).
 */
class AppUpdater(
    context: Context,
    private val checker: UpdateChecker,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            mutableState.value = UpdateState.Checking
            mutableState.value = when (val result = checker.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheck.Available -> UpdateState.Available(result.release)
                UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Failed -> UpdateState.Failed(result.error.toFailure(), null)
            }
        }
    }

    /** Télécharge puis installe [release] (après l'autorisation d'installer, si elle manque). */
    fun install(release: AppRelease) {
        if (job?.isActive == true) return
        if (!canInstall()) {
            mutableState.value = UpdateState.NeedsPermission(release)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val apk = File(context.cacheDir, "updates/NexTV-${release.version}.apk")
            apk.parentFile?.listFiles()?.forEach { it.delete() }
            mutableState.value = UpdateState.Downloading(release, 0, release.apkSize)
            var shown = 0L
            val error = checker.download(release, apk) { downloaded, total ->
                if (downloaded - shown >= PROGRESS_STEP_BYTES || downloaded == total) {
                    shown = downloaded
                    mutableState.value = UpdateState.Downloading(release, downloaded, total)
                }
            }
            if (error != null) {
                mutableState.value = UpdateState.Failed(error.toFailure(), release)
                return@launch
            }
            mutableState.value = UpdateState.Installing(release)
            runCatching { commit(apk) }.onFailure {
                logger.warn(TAG, "Installation de la mise à jour impossible", it)
                mutableState.value = UpdateState.Failed(UpdateFailure.INSTALL, release)
            }
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Réglage du système « Installer des applis inconnues » pour NexTV (Android 8 et plus). */
    fun permissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("NexTV.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Modifiable : l'installateur y ajoute le résultat.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, confirmation: Intent?) {
        when (status) {
            // Confirmation de l'utilisateur demandée par le système.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirmation?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // L'application est remplacée par la nouvelle version.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Installation refusée par l'utilisateur : la mise à jour reste proposée.
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableState.update { current ->
                (current as? UpdateState.Installing)?.let { UpdateState.Available(it.release) } ?: current
            }
            else -> mutableState.update { current ->
                logger.warn(TAG, "Installation refusée par le système (statut $status)")
                UpdateState.Failed(UpdateFailure.INSTALL, (current as? UpdateState.Installing)?.release)
            }
        }
    }

    private fun UpdateError.toFailure(): UpdateFailure = when (this) {
        UpdateError.NOT_PUBLISHED -> UpdateFailure.NOT_PUBLISHED
        UpdateError.NETWORK -> UpdateFailure.NETWORK
        UpdateError.SERVER -> UpdateFailure.SERVER
        UpdateError.CORRUPTED -> UpdateFailure.CORRUPTED
    }

    private companion object {
        const val TAG = "Update"
        const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}

/** Résultat de l'installateur du système (non exporté : seul le système l'appelle, via notre PendingIntent). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        (context.applicationContext as NexTvApplication).container.updater.onInstallResult(status, confirmation)
    }
}
