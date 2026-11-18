package tv.nextv.core.audio

/**
 * Chaînes qui n'ont jamais de pubs, sur lesquelles « Baisser le son des pubs » ne cherche rien : les chaînes « 24/24 » de
 * M6+ (Les Marseillais 24/24, Incroyables Transformations 24/24, Turbo 24/24…). Elles n'ont qu'un écran fixe « M6+ revient
 * dans un instant » entre deux épisodes, après lequel la détection prenait le retour du programme pour une page de pubs
 * ouverte par le carton (50 s baissées à tort les nuits du 16 au 18/11/2026, sur la box).
 */
object AdFreeChannels {
    private val ROUND_THE_CLOCK = Regex("""\b24\s*/\s*24\b""")

    fun isAdFree(name: String?): Boolean = name != null && ROUND_THE_CLOCK.containsMatchIn(name)
}
