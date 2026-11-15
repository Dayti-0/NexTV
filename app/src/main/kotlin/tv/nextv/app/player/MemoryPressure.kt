package tv.nextv.app.player

import android.os.SystemClock

/**
 * Le système manque de mémoire (signal « basse » ou « critique » pendant l'utilisation) : pendant
 * [WINDOW_MILLIS] après le dernier signal, les lecteurs gardent moins de flux d'avance ([LiveLoadControl]).
 *
 * Sur la télé Sony, l'application était fermée par le système en pleine lecture, juste après un signal
 * « critique » : la place rendue tout de suite (images en cache, tampon du flux) peut lui éviter de fermer
 * l'application à l'écran.
 */
object MemoryPressure {
    /** Instant du dernier signal (horloge depuis le démarrage) ; 0 : aucun. */
    @Volatile
    private var signaledAt = 0L

    fun signal(now: Long = SystemClock.elapsedRealtime()) {
        signaledAt = now.coerceAtLeast(1L)
    }

    fun active(now: Long = SystemClock.elapsedRealtime()): Boolean = signaledAt != 0L && now - signaledAt < WINDOW_MILLIS

    /** Pour les tests. */
    internal fun reset() {
        signaledAt = 0L
    }

    /** Durée de la prudence après le dernier signal. */
    const val WINDOW_MILLIS = 3 * 60_000L
}
