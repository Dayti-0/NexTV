package tv.nextv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import okio.Path.Companion.toOkioPath
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import android.app.ActivityManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call
import okhttp3.Request
import tv.nextv.app.player.MemoryPressure
import tv.nextv.app.player.smallMemoryDevice
import tv.nextv.app.ui.home.TrailerMemory
import tv.nextv.core.log.Redactor
import tv.nextv.core.net.RequestPurpose
import java.io.File

class NexTvApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    /** Dernier plantage (réglages). Installé en premier : un plantage au démarrage est aussi enregistré. */
    val crashLog: CrashLog by lazy {
        CrashLog(
            file = File(filesDir, CRASH_FILE),
            memory = { SystemExits.memory(this) },
            processMemory = SystemExits::processMemory,
            redact = { text -> if (::container.isInitialized) container.redactor.redact(text) else Redactor().redact(text) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        crashLog.install()
        // Fermeture précédente par le système (mémoire, blocage…) : notée dans « Dernier incident ».
        SystemExits.check(this, crashLog)
        container = AppContainer(this)
        container.start()
        // Cache disque des images ouvert tout de suite, en arrière-plan : sa première ouverture (lecture du journal
        // d'un cache plein) bloquait ~2 s toutes les images de l'accueil sur la box (8 en attente, 28/11/2026).
        Thread({ runCatching { SingletonImageLoader.get(this).diskCache?.openSnapshot(DISK_CACHE_WARM_UP_KEY)?.close() } }, "image-cache-warm-up")
            .apply { isDaemon = true }
            .start()
        // Session notée tant que l'application est à l'écran : où elle en était si le système la ferme.
        val boot = SystemExits.bootCount(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            private val handler = Handler(Looper.getMainLooper())
            private var ticks = 0

            // Relevé de la mémoire toutes les 15 s (la mémoire réelle toutes les minutes) tant que l'application est
            // à l'écran : la Fire TV la fermait sans que le dernier relevé dise ce qui s'était passé juste avant.
            private val tick = object : Runnable {
                override fun run() {
                    crashLog.tick(withProcess = ++ticks % PROCESS_EVERY_TICKS == 0)
                    handler.postDelayed(this, CrashLog.TICK_MILLIS)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    crashLog.foreground(boot)
                    handler.postDelayed(tick, CrashLog.TICK_MILLIS)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started <= 0) {
                    started = 0
                    handler.removeCallbacks(tick)
                    crashLog.background()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SystemExits.pressureName(level)?.let(crashLog::memoryPressure)
        // Mémoire presque épuisée : la bande-annonce (page YouTube, très gourmande) s'arrête et les suivantes
        // attendent ; c'est elle qui faisait fermer l'application par le système sur la Fire TV.
        @Suppress("DEPRECATION")
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) TrailerMemory.pause()
        @Suppress("DEPRECATION")
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) freeMemoryForPlayback()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        freeMemoryForPlayback()
    }

    /**
     * Mémoire presque épuisée pendant une lecture (télé Sony : application fermée par le système en plein direct,
     * juste après le signal « critique ») : les images de l'accueil gardées en mémoire, invisibles pendant la lecture,
     * sont rendues (le cache n'en rendait que la moitié ; elles se relisent sur le disque au retour), et les lecteurs
     * gardent moins de flux d'avance pendant quelques minutes ([MemoryPressure]).
     */
    private fun freeMemoryForPlayback() {
        MemoryPressure.signal()
        if (!::container.isInitialized || !container.playbackOpen.value) return
        runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    /** Les logos passent par le même client HTTP (proxy « playlists et logos » compris). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val clients = container.httpClients
        val callFactory = object : Call.Factory {
            override fun newCall(request: Request): Call = clients.client(RequestPurpose.IMAGE).newCall(request)
        }
        // Appareil à petite mémoire (box d'entrée de gamme, Fire TV d'environ 1 Go) : cache plus petit, images en 16 bits.
        val activityManager = getSystemService(ActivityManager::class.java)
        val lowMemory = activityManager?.isLowRamDevice == true || (activityManager?.memoryClass ?: 256) <= LOW_MEMORY_CLASS_MB ||
            smallMemoryDevice(this)
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { callFactory })) }
            // Affiches et visuels apparaissent en fondu (court : la fluidité passe avant l'effet).
            .crossfade(IMAGE_CROSSFADE_MILLIS)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, if (lowMemory) 0.15 else 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .allowRgb565(lowMemory)
            .build()
    }
}

private const val CRASH_FILE = "last_crash.txt"

/** Clé lue (absente) pour ouvrir le cache disque des images au lancement. */
private const val DISK_CACHE_WARM_UP_KEY = "warm-up"

/** Fondu d'apparition des images. */
private const val IMAGE_CROSSFADE_MILLIS = 180

/** Images gardées sur le disque (affiches, visuels TMDB) : rechargées sans réseau. */
private const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024

/** Mémoire allouée à l'appli en dessous de laquelle l'appareil est traité comme modeste. */
private const val LOW_MEMORY_CLASS_MB = 128

/** Mémoire réelle relevée un relevé régulier sur quatre (toutes les minutes). */
private const val PROCESS_EVERY_TICKS = 4
