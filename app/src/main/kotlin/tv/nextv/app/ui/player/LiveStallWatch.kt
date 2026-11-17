package tv.nextv.app.ui.player

/**
 * Direct figé sans erreur : le lecteur se croit en lecture (ou en chargement) mais plus rien n'avance — ni images
 * décodées, ni position. Arrive après des heures (fournisseur qui garde la connexion ouverte sans plus rien envoyer
 * d'utile, décodeur bloqué) : l'écran restait noir ou figé jusqu'au changement de chaîne.
 *
 * [check] reçoit deux fois par seconde un repère d'avancement ([progress], `null` quand la surveillance ne s'applique
 * pas : pause, différé, erreur…) et dit quand il n'a pas bougé depuis [limitMillis].
 */
internal class LiveStallWatch(private val limitMillis: Long = DEFAULT_LIMIT_MILLIS) {

    private var marker: Long? = null
    private var since = 0L

    /** Nouveau flux, ou surveillance interrompue : on repart de zéro. */
    fun reset() {
        marker = null
    }

    /** Vrai une fois quand [progress] n'a pas changé depuis [limitMillis] (la surveillance repart ensuite de zéro). */
    fun check(now: Long, progress: Long?): Boolean {
        if (progress == null) {
            marker = null
            return false
        }
        if (progress != marker) {
            marker = progress
            since = now
            return false
        }
        if (now - since < limitMillis) return false
        marker = null
        return true
    }

    companion object {
        /** Moins que le temps de lecture « stable » des relances (30 s) : un flux rouvert qui se refige compte. */
        const val DEFAULT_LIMIT_MILLIS = 20_000L
    }
}
