package tv.nextv.core.source

import kotlinx.coroutines.CancellationException
import tv.nextv.core.error.SourceException
import tv.nextv.core.io.CountingInputStream
import tv.nextv.core.io.LimitedInputStream
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.io.Streams
import tv.nextv.core.net.NetworkErrorMapper
import java.io.BufferedInputStream
import java.io.IOException

internal object ContentPipeline {

    /**
     * Comptage des octets reçus (progression) → limite de téléchargement → décompression gzip
     * éventuelle → limite décompressée → tampon.
     */
    fun prepare(content: OpenedContent, limits: ContentLimits, onBytesRead: ((Long) -> Unit)? = null): BufferedInputStream {
        val raw = if (onBytesRead != null) CountingInputStream(content.stream, onCount = onBytesRead) else content.stream
        val downloaded = LimitedInputStream(raw, limits.maxDownloadBytes)
        val decoded = LimitedInputStream(Streams.decompressIfGzip(downloaded), limits.maxDecodedBytes)
        return Streams.buffered(decoded)
    }

    /** Les erreurs d'E/S survenant pendant la lecture (délai dépassé, coupure…) deviennent des [SourceException]. */
    inline fun <T> mapReadErrors(viaProxy: Boolean, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceException) {
        throw e
    } catch (e: IOException) {
        throw SourceException(NetworkErrorMapper.map(e, viaProxy = viaProxy), e)
    }
}
