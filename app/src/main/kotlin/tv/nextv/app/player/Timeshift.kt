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

/** Fichier du différé, dans le cache de l'application (un seul à la fois ; supprimé au lancement s'il en reste un). */
const val TIMESHIFT_FILE = "timeshift.ts"

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

/**
 * Source de données du lecteur : lit l'enregistrement du différé au fur et à mesure, à partir de
 * [startOffset] (retour en arrière dans le direct : quelques secondes avant ce qui était à l'écran).
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: TimeshiftBuffer, private val startOffset: Long = 0) : BaseDataSource(/* isNetwork = */ false) {

    private var input: RandomAccessFile? = null
    private var position = 0L
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position + startOffset
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

/**
 * Direct en cours, gardé pour revenir en arrière (← dans le lecteur) : les dernières secondes reçues en
 * mémoire ([tailBytes], en anneau, rien sur le disque) et l'heure d'arrivée de chaque morceau.
 *
 * Au premier retour en arrière (ou à la pause), [startDisk] recopie ces secondes dans un enregistrement
 * sur le disque ([TimeshiftBuffer]) ; la même connexion continue d'y écrire la suite ([takeOver] : rien
 * n'est perdu ni rechargé), et le lecteur relit l'enregistrement à partir du moment voulu.
 *
 * Les positions sont comptées depuis le début du flux (octets reçus).
 */
class LiveRecording(private val ring: ByteArray) {

    constructor(tailBytes: Int) : this(ByteArray(tailBytes))

    /**
     * Verrou : l'anneau lui-même. Il est réutilisé d'une chaîne à l'autre (pas de nouveau bloc de plusieurs
     * dizaines de Mo à chaque zapping) : les directs successifs qui se le partagent ne s'y mélangent pas.
     */
    private val lock: Any = ring

    /** Remplacé par le direct d'une autre chaîne, qui écrit maintenant dans l'anneau : celui-ci n'y touche plus. */
    @Volatile
    private var retired = false

    /** Protège le passage sur le disque face à un arrêt simultané ([startDisk] hors du fil principal, [stop] dessus). */
    private val diskLock = Any()

    /** Octets reçus depuis le début du flux. */
    @Volatile
    var total: Long = 0
        private set

    /** Enregistrement sur le disque, et position du flux de son premier octet. */
    @Volatile
    var disk: TimeshiftBuffer? = null
        private set

    @Volatile
    var diskBase: Long = 0
        private set

    // Heure d'arrivée (SystemClock.elapsedRealtime) des positions, toutes les SAMPLE_MILLIS.
    private val sampleTimes = LongArray(SAMPLES)
    private val samplePositions = LongArray(SAMPLES)
    private var sampleCount = 0
    private var lastSample = 0L

    @Volatile
    private var takeOverRequested = false
    private var pump: Thread? = null

    @Volatile
    private var stopped = false

    /** Morceau reçu du direct. */
    fun append(bytes: ByteArray, offset: Int, length: Int, now: Long = android.os.SystemClock.elapsedRealtime()) {
        if (length <= 0) return
        synchronized(lock) {
            val diskBuffer = disk
            if (diskBuffer == null) {
                // L'anneau appartient désormais au direct suivant (derniers octets d'un flux en train de se fermer).
                if (retired) return
                // En mémoire seulement (anneau) tant qu'on regarde le direct.
                var copied = 0
                while (copied < length) {
                    val at = ((total + copied) % ring.size).toInt()
                    val n = minOf(length - copied, ring.size - at)
                    System.arraycopy(bytes, offset + copied, ring, at, n)
                    copied += n
                }
            } else {
                val more = try {
                    diskBuffer.append(if (offset == 0) bytes else bytes.copyOfRange(offset, offset + length), length)
                } catch (e: IOException) {
                    // Stockage plein, fichier retiré : l'enregistrement s'arrête là (la lecture du différé le signale).
                    diskBuffer.finish(e)
                    false
                }
                if (!more) stopped = true
            }
            total += length
            if (now - lastSample >= SAMPLE_MILLIS || sampleCount == 0) {
                val i = sampleCount % SAMPLES
                sampleTimes[i] = now
                samplePositions[i] = total
                sampleCount++
                lastSample = now
            }
        }
    }

    /**
     * Un autre direct prend l'anneau : celui-ci n'y écrit plus (son enregistrement sur le disque, s'il y en a
     * un, continue). Le retour en arrière n'est alors plus possible depuis sa mémoire.
     */
    fun retire() {
        retired = true
    }

    /** Position la plus ancienne encore disponible (début de l'enregistrement, ou de l'anneau). */
    fun oldest(): Long = if (disk != null) diskBase else maxOf(0, total - ring.size)

    /** Position du flux arrivée à l'heure [time] (elapsedRealtime), dans ce qui est encore disponible. */
    fun positionAt(time: Long): Long = synchronized(lock) {
        val count = minOf(sampleCount, SAMPLES)
        if (count == 0) return oldest()
        var position = samplePositions[(sampleCount - 1) % SAMPLES]
        // Du plus récent au plus ancien : le dernier repère arrivé avant l'heure voulue.
        for (k in 1..count) {
            val i = (sampleCount - k) % SAMPLES
            if (sampleTimes[i] <= time) {
                position = samplePositions[i]
                break
            }
            position = samplePositions[i]
        }
        position.coerceIn(oldest(), total)
    }

    /** Heure d'arrivée de la position [position] (elapsedRealtime). */
    fun timeAt(position: Long): Long = synchronized(lock) {
        val count = minOf(sampleCount, SAMPLES)
        if (count == 0) return android.os.SystemClock.elapsedRealtime()
        var time = sampleTimes[(sampleCount - count) % SAMPLES]
        for (k in count downTo 1) {
            val i = (sampleCount - k) % SAMPLES
            if (samplePositions[i] <= position) time = sampleTimes[i] else break
        }
        time
    }

    /**
     * Passe à l'enregistrement sur le disque ([file], au plus [maxBytes]) : les secondes en mémoire y sont
     * recopiées, puis tout ce qui arrive y est écrit. Jusqu'à 32 Mo à écrire : à appeler hors du fil
     * principal.
     *
     * @return `null` si c'est impossible (stockage plein, fichier refusé) ou si [stop] est arrivé entre-temps :
     * rien n'est alors gardé sur le disque, et le direct continue comme avant.
     */
    fun startDisk(file: File, maxBytes: Long): TimeshiftBuffer? = synchronized(lock) {
        disk?.let { return it }
        if (stopped || retired) return null
        var buffer: TimeshiftBuffer? = null
        try {
            val created = TimeshiftBuffer(file, maxBytes)
            buffer = created
            val from = oldest()
            var position = from
            val chunk = ByteArray(CHUNK)
            while (position < total) {
                if (stopped) {
                    created.release()
                    return null
                }
                val n = minOf((total - position).toInt(), CHUNK)
                var copied = 0
                while (copied < n) {
                    val at = ((position + copied) % ring.size).toInt()
                    val m = minOf(n - copied, ring.size - at)
                    System.arraycopy(ring, at, chunk, copied, m)
                    copied += m
                }
                created.append(chunk, n)
                position += n
            }
            // Arrêt demandé pendant la copie : soit [stop] voit l'enregistrement et le supprime, soit il est supprimé ici.
            synchronized(diskLock) {
                if (stopped) {
                    created.release()
                    return null
                }
                diskBase = from
                disk = created
            }
            created
        } catch (e: IOException) {
            runCatching { buffer?.release() }
            null
        }
    }

    /** À la fermeture de sa source par le lecteur, la connexion du direct continue d'écrire ici ([takeOver]). */
    fun requestTakeOver() {
        takeOverRequested = true
    }

    /**
     * Reprise demandée : le lecteur qui s'arrête ne lit plus la connexion. Son fil de lecture est interrompu
     * à l'arrêt, et une lecture faite après l'interruption fermerait la connexion avant sa reprise.
     */
    val handingOver: Boolean get() = takeOverRequested

    /** Appelé par [LiveRecordingDataSource.close] : reprend la connexion si demandé (sinon `false`). */
    @OptIn(UnstableApi::class)
    internal fun takeOver(upstream: androidx.media3.datasource.DataSource): Boolean {
        if (!takeOverRequested || stopped) return false
        takeOverRequested = false
        pump = Thread({
            val chunk = ByteArray(CHUNK)
            var error: IOException? = null
            try {
                while (!stopped && !Thread.currentThread().isInterrupted) {
                    val n = upstream.read(chunk, 0, chunk.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    if (n > 0) append(chunk, 0, n)
                }
            } catch (e: IOException) {
                if (!stopped) error = e
            } finally {
                runCatching { upstream.close() }
                disk?.finish(error)
            }
        }, "nextv-live-rewind").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Fin du différé : la connexion est fermée et l'enregistrement supprimé. */
    fun stop() {
        val recorded = synchronized(diskLock) {
            stopped = true
            disk
        }
        pump?.interrupt()
        recorded?.release()
    }

    private companion object {
        const val SAMPLES = 8_192
        const val SAMPLE_MILLIS = 250L
        const val CHUNK = 64 * 1024
    }
}

/**
 * Source réseau du direct qui garde aussi ce qu'elle reçoit dans l'enregistrement donné par [recordingOf]
 * (retour en arrière). `null` : rien n'est gardé pour l'instant (aperçu de l'accueil) ; il peut arriver en
 * cours de flux (aperçu repris par le plein écran), ce qui est reçu ensuite y est gardé.
 */
@OptIn(UnstableApi::class)
class LiveRecordingDataSource(
    private val upstream: androidx.media3.datasource.DataSource,
    private val recordingOf: () -> LiveRecording?,
) : androidx.media3.datasource.DataSource by upstream {
    private var opened = false

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec).also { opened = true }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val recording = recordingOf()
        if (recording?.handingOver == true) return C.RESULT_END_OF_INPUT
        val n = upstream.read(buffer, offset, length)
        if (n > 0) recording?.append(buffer, offset, n)
        return n
    }

    override fun close() {
        // Retour en arrière, pause : la connexion n'est pas fermée, elle continue d'enregistrer la suite.
        if (opened && recordingOf()?.takeOver(upstream) == true) {
            opened = false
            return
        }
        opened = false
        upstream.close()
    }
}
