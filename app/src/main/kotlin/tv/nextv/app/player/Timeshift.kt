package tv.nextv.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Différé d'un direct : à la mise en pause, le flux continue d'être reçu et écrit dans un fichier,
 * puis la lecture reprend depuis ce fichier. Pour les flux continus (MPEG-TS…) que le serveur
 * coupe si on cesse de les lire.
 *
 * L'enregistrement s'arrête à [maxBytes] ; la lecture atteint alors la fin du fichier.
 */
class TimeshiftBuffer(private val file: File, private val maxBytes: Long) {

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val output = RandomAccessFile(file, "rw").apply { setLength(0) }

    @Volatile
    var written: Long = 0
        private set

    @Volatile
    var finished: Boolean = false
        private set

    /** Limite atteinte : l'enregistrement s'est arrêté. */
    @Volatile
    var full: Boolean = false
        private set

    @Volatile
    private var failure: IOException? = null

    /** @return faux quand la limite est atteinte (l'enregistrement doit s'arrêter). */
    fun append(bytes: ByteArray, length: Int): Boolean {
        val allowed = minOf(length.toLong(), maxBytes - written).toInt()
        if (allowed > 0) {
            output.seek(written)
            output.write(bytes, 0, allowed)
        }
        lock.withLock {
            written += allowed
            if (allowed < length) full = true
            dataAvailable.signalAll()
        }
        return !full
    }

    fun finish(error: IOException? = null) {
        lock.withLock {
            failure = error
            finished = true
            dataAvailable.signalAll()
        }
    }

    /** Lit à partir de [position] ; attend les données encore à recevoir. -1 en fin d'enregistrement. */
    fun read(input: RandomAccessFile, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            while (written <= position && !finished) {
                try {
                    dataAvailable.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Différé interrompu")
                }
            }
            if (written <= position) {
                failure?.let { throw it }
                return -1
            }
        }
        val available = minOf(length.toLong(), written - position).toInt()
        input.seek(position)
        return input.read(buffer, offset, available)
    }

    fun openReader(): RandomAccessFile = RandomAccessFile(file, "r")

    /** Arrête et supprime l'enregistrement. */
    fun release() {
        finish()
        runCatching { output.close() }
        file.delete()
    }

    private companion object {
        const val WAIT_MILLIS = 250L
    }
}

/** Reçoit le direct ([call]) et l'écrit dans [buffer] jusqu'à l'arrêt ou la limite. */
class TimeshiftRecorder(private val call: Call, private val buffer: TimeshiftBuffer) {

    private val thread = Thread({ record() }, "nextv-timeshift").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        call.cancel()
        thread.interrupt()
    }

    private fun record() {
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.byteStream().use { input ->
                    val chunk = ByteArray(CHUNK_SIZE)
                    while (!Thread.currentThread().isInterrupted) {
                        val n = input.read(chunk)
                        if (n < 0 || !buffer.append(chunk, n)) break
                    }
                }
            }
            buffer.finish()
        } catch (e: IOException) {
            buffer.finish(if (call.isCanceled()) null else e)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}

/** Source de données du lecteur : lit l'enregistrement du différé au fur et à mesure. */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: TimeshiftBuffer) : BaseDataSource(/* isNetwork = */ false) {

    private var input: RandomAccessFile? = null
    private var position = 0L
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        input = buffer.openReader()
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val reader = input ?: throw IOException("Différé fermé")
        val n = buffer.read(reader, position, target, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        input?.let { runCatching { it.close() } }
        if (input != null) transferEnded()
        input = null
        uri = null
    }
}
