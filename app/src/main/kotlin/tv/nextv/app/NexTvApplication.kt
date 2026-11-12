package tv.nextv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        // Session notée tant que l'application est à l'écran : où elle en était si le système la ferme.
        val boot = SystemExits.bootCount(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) crashLog.foreground(boot)
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started <= 0) {
                    started = 0
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
    }

    /** Les logos passent par le même client HTTP (proxy « playlists et logos » compris). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val clients = container.httpClients
        val callFactory = object : Call.Factory {
            override fun newCall(request: Request): Call = clients.client(RequestPurpose.IMAGE).newCall(request)
        }
        // Appareil à petite mémoire (box d'entrée de gamme) : cache plus petit, images en 16 bits.
        val activityManager = getSystemService(ActivityManager::class.java)
        val lowMemory = activityManager?.isLowRamDevice == true || (activityManager?.memoryClass ?: 256) <= LOW_MEMORY_CLASS_MB
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

/** Fondu d'apparition des images. */
private const val IMAGE_CROSSFADE_MILLIS = 180

/** Images gardées sur le disque (affiches, visuels TMDB) : rechargées sans réseau. */
private const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024

/** Mémoire allouée à l'appli en dessous de laquelle l'appareil est traité comme modeste. */
private const val LOW_MEMORY_CLASS_MB = 128
