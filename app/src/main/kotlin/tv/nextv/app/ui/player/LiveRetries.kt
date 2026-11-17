package tv.nextv.app.ui.player

/**
 * Relances d'un direct interrompu (coupure du réseau, flux fermé par le serveur).
 *
 * - Seul un direct qui a déjà joué est relancé : une chaîne qui ne démarre pas du tout est en panne, son
 *   erreur s'affiche tout de suite (pas d'attente en zappant sur une chaîne morte).
 * - [delays] donne l'attente avant chaque relance ; une fois toutes faites sans lecture stable entre-temps,
 *   l'erreur s'affiche.
 * - Après une lecture stable ([stable]), les relances repartent de zéro.
 */
internal class LiveRetries(private val delays: List<Long> = DEFAULT_DELAYS) {

    /** Le direct a déjà joué depuis l'ouverture de la chaîne. */
    private var played = false

    /** Le direct a déjà joué depuis l'ouverture de la chaîne (un blocage avant n'est qu'un démarrage lent). */
    val hasPlayed: Boolean get() = played

    /** Relances faites depuis la dernière lecture stable. */
    var done = 0
        private set

    /** Autre chaîne (ou nouvel essai demandé) : tout repart de zéro. */
    fun newChannel() {
        played = false
        done = 0
    }

    /** Le direct joue. */
    fun playing() {
        played = true
    }

    /** Le direct a joué assez longtemps sans coupure. */
    fun stable() {
        done = 0
    }

    /** Attente avant la prochaine relance ; `null` : pas de relance (chaîne jamais lue, ou essais épuisés). */
    fun next(): Long? {
        if (!played) return null
        val wait = delays.getOrNull(done) ?: return null
        done++
        return wait
    }

    private companion object {
        /**
         * Essais de plus en plus espacés, pendant un peu plus de 4 min : une coupure du fournisseur la nuit (serveur
         * redémarré, source de la chaîne absente un moment) se rattrape seule. Trois essais en 8 s laissaient l'erreur
         * affichée, puis l'appareil se mettait en veille : écran noir au matin.
         */
        val DEFAULT_DELAYS = listOf(1_000L, 2_500L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 60_000L, 60_000L)
    }
}
