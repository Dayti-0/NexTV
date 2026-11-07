package tv.nextv.app.player

/**
 * Débit reçu du réseau pour le flux en cours, sur des fenêtres de [windowMillis]. Mesure faite à part
 * de celle d'ExoPlayer, qui n'en donne une qu'à la fin d'un téléchargement : jamais pour un flux continu
 * (MPEG-TS), qui ne finit pas. Appelé depuis le fil du chargement.
 */
class ReceivedRate(private val windowMillis: Long = WINDOW_MILLIS) {
    private var windowStart = -1L
    private var bytes = 0L

    /** Repart de zéro (nouveau flux). */
    @Synchronized
    fun reset() {
        windowStart = -1L
        bytes = 0L
    }

    /** [count] octets reçus à [nowMillis] ; @return le débit en Mbit/s quand une fenêtre se termine, sinon `null`. */
    @Synchronized
    fun add(count: Int, nowMillis: Long): Double? {
        if (windowStart < 0) {
            windowStart = nowMillis
            bytes = 0L
        }
        bytes += count
        val elapsed = nowMillis - windowStart
        if (elapsed < windowMillis) return null
        val mbps = bytes * 8.0 / elapsed / 1_000.0
        windowStart = nowMillis
        bytes = 0L
        return mbps
    }

    companion object {
        const val WINDOW_MILLIS = 2_000L
    }
}
