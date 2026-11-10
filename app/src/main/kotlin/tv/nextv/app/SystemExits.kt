package tv.nextv.app

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.concurrent.thread

/**
 * Fermetures de l'application par le système, qu'elle ne peut pas enregistrer elle-même (elle n'est déjà plus
 * là) : mémoire insuffisante, application qui ne répond plus, plantage d'une bibliothèque native. Constatées au
 * lancement suivant et notées dans « Dernier incident ».
 *
 * - Android 11 et plus : le système en donne la raison exacte.
 * - Avant : seul indice, la session restée ouverte ([CrashLog.takePreviousSession]) alors que l'appareil n'a pas
 *   été éteint et que l'application n'a pas été mise à jour.
 */
object SystemExits {

    /** Nombre de démarrages de l'appareil (pour reconnaître une coupure de courant d'une fermeture de l'application). */
    fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)

    /** Mémoire de l'application et de l'appareil, en une ligne (« appli 142 / 384 Mo, natif 96 Mo, appareil 310 Mo libres »). */
    fun memory(context: Context): String? = runCatching {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val device = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        "appli $used / ${runtime.maxMemory() / MB} Mo, natif ${Debug.getNativeHeapAllocatedSize() / MB} Mo, " +
            "appareil ${device.availMem / MB} Mo libres" + if (device.lowMemory) " (mémoire basse)" else ""
    }.getOrNull()

    /** Niveau d'un signal de mémoire du système (`onTrimMemory`) ; `null` : sans intérêt (application en arrière-plan). */
    @Suppress("DEPRECATION")
    fun pressureName(level: Int): String? = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "modérée"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "basse"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "critique"
        else -> null
    }

    /**
     * Au lancement : note dans [log] la fermeture précédente si le système en est la cause. Les plantages de
     * l'application elle-même sont déjà notés, avec leur trace.
     */
    fun check(context: Context, log: CrashLog, run: (() -> Unit) -> Unit = { task -> thread(name = "nextv-exits", isDaemon = true) { task() } }) {
        // Lue tout de suite (avant que la nouvelle session ne la remplace) ; le reste hors du fil principal.
        val session = log.takePreviousSession()
        run {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    checkExitInfo(context, log, session)
                } else if (session != null) {
                    checkSession(context, log, session)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkExitInfo(context: Context, log: CrashLog, session: CrashLog.Session?) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_READ)
            .firstOrNull { it.processName == context.packageName } ?: return
        // Chaque fermeture n'est notée qu'une fois (elle resterait sinon après « Effacer »).
        val seen = File(context.filesDir, SEEN_FILE)
        if (exit.timestamp <= (runCatching { seen.readText().trim().toLong() }.getOrNull() ?: 0L)) return
        runCatching { seen.writeText(exit.timestamp.toString()) }
        val foreground = exit.importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val summary = when (exit.reason) {
            ApplicationExitInfo.REASON_ANR -> "L’application ne répondait plus : fermée par le système (ANR)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Plantage d’une bibliothèque native (décodeur, lecteur vidéo…)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "L’application n’a pas pu démarrer"
            // Fermée en arrière-plan pour faire de la place : fonctionnement normal du système, pas un incident.
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (foreground) "Fermée par le système pendant l’utilisation : mémoire insuffisante" else return
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> if (foreground) "Fermée par le système : trop de ressources utilisées" else return
            // Signal 9 : c'est ainsi que certains appareils ferment une application quand la mémoire manque.
            ApplicationExitInfo.REASON_SIGNALED -> if (foreground && exit.status == SIGKILL) {
                "Fermée par le système pendant l’utilisation (signal 9 : mémoire insuffisante, sans doute)"
            } else {
                return
            }
            // Plantage de l'application (déjà noté avec sa trace), arrêt demandé, mise à jour… : rien à ajouter.
            else -> return
        }
        // Un plantage noté par l'application au même moment a sa trace : il est plus utile que ce résumé.
        val last = log.last()
        if (last != null && last.kind == CrashLog.Kind.CRASH && last.atMillis >= exit.timestamp - SAME_EXIT_MILLIS) return
        val details = buildList {
            add("Raison ${exit.reason}, état ${exit.status}, importance ${exit.importance}")
            exit.description?.takeIf { it.isNotBlank() }?.let { add("Système : $it") }
            if (exit.pss > 0 || exit.rss > 0) add("Mémoire de l’appli à la fermeture : ${exit.pss / KB} Mo (PSS), ${exit.rss / KB} Mo (RSS)")
            addAll(sessionLines(session?.takeIf { exit.timestamp - it.atMillis in 0..SESSION_MAX_AGE_MILLIS }))
            if (exit.reason == ApplicationExitInfo.REASON_ANR) addAll(mainThreadTrace(exit))
        }
        log.recordSystemExit(exit.timestamp, summary, details)
    }

    /** Pile du fil principal d'une application bloquée, lue dans le rapport du système. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun mainThreadTrace(exit: ApplicationExitInfo): List<String> = runCatching {
        exit.traceInputStream?.bufferedReader()?.use { reader ->
            reader.lineSequence()
                .dropWhile { !it.startsWith("\"main\"") }
                .takeWhile { it.isNotBlank() }
                .filter { it.startsWith("\"main\"") || it.trim().startsWith("at ") }
                .take(ANR_LINES)
                .toList()
        }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Avant Android 11 : la session était encore ouverte. Ce n'est un incident que si l'appareil n'a pas été éteint
     * (une clé TV perd le courant avec la télé) et que l'application n'a pas été mise à jour entre-temps.
     */
    internal fun checkSession(context: Context, log: CrashLog, session: CrashLog.Session) {
        if (session.version != BuildConfig.VERSION_NAME) return
        if (session.boot == 0 || session.boot != bootCount(context)) return
        // Plantage de l'application noté depuis : c'est lui, avec sa trace.
        val last = log.last()
        if (last != null && last.atMillis >= session.atMillis) return
        log.recordSystemExit(
            session.atMillis,
            "Fermeture brutale pendant l’utilisation (par le système : mémoire insuffisante ou bibliothèque native)",
            listOf("Aucune erreur notée par l’application avant sa fermeture.") + sessionLines(session),
        )
    }

    private fun sessionLines(session: CrashLog.Session?): List<String> = listOfNotNull(
        session?.screen?.let { "Écran affiché : $it" },
        session?.memory?.let { "Dernier relevé de mémoire : $it" },
        session?.pressure?.let { "Mémoire signalée $it" },
    )

    private const val MB = 1024L * 1024
    private const val KB = 1024L
    private const val SIGKILL = 9
    private const val EXITS_READ = 5
    private const val ANR_LINES = 24
    private const val SEEN_FILE = "exit_seen.txt"

    /** Fermeture du système et plantage noté par l'application à quelques secondes d'écart : le même. */
    private const val SAME_EXIT_MILLIS = 10_000L

    /** Session trop ancienne pour décrire la fermeture (autre lancement). */
    private const val SESSION_MAX_AGE_MILLIS = 6 * 60 * 60_000L
}
