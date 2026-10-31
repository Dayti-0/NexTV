package tv.nextv.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call
import okhttp3.Request
import tv.nextv.core.log.Redactor
import tv.nextv.core.net.RequestPurpose
import java.io.File

class NexTvApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    /** Dernier plantage (réglages). Installé en premier : un plantage au démarrage est aussi enregistré. */
    val crashLog: CrashLog by lazy {
        CrashLog(File(filesDir, CRASH_FILE)) { text -> if (::container.isInitialized) container.redactor.redact(text) else Redactor().redact(text) }
    }

    override fun onCreate() {
        super.onCreate()
        crashLog.install()
        container = AppContainer(this)
        container.start()
    }

    /** Les logos passent par le même client HTTP (proxy « playlists et logos » compris). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val clients = container.httpClients
        val callFactory = object : Call.Factory {
            override fun newCall(request: Request): Call = clients.client(RequestPurpose.IMAGE).newCall(request)
        }
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { callFactory })) }
            .build()
    }
}

private const val CRASH_FILE = "last_crash.txt"
