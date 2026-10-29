package tv.nextv.app

import android.util.Log
import tv.nextv.core.log.Logger

/**
 * Journalisation Logcat. Toujours enveloppé dans un [tv.nextv.core.log.RedactingLogger] :
 * aucun message n'atteint Logcat sans avoir été expurgé.
 */
class AndroidLogger(private val verbose: Boolean) : Logger {

    override fun debug(tag: String, message: String) {
        if (verbose) Log.d(prefix(tag), message)
    }

    override fun info(tag: String, message: String) {
        if (verbose) Log.i(prefix(tag), message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(prefix(tag), message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(prefix(tag), message, throwable)
    }

    private fun prefix(tag: String) = "NexTV/$tag".take(MAX_TAG_LENGTH)

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}
