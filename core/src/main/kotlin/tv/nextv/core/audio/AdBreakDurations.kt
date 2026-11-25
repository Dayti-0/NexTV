package tv.nextv.core.audio

/**
 * Durées des dernières pages de pub de chaque chaîne : leur médiane sert d'estimation de la fin de la page en cours
 * (« Fin prévue dans 2 min » de l'écran des pubs) quand le flux n'en donne pas la durée.
 */
class AdBreakDurations(initial: Map<String, List<Long>> = emptyMap()) {
    private val byChannel = HashMap<String, ArrayList<Long>>().apply { initial.forEach { (channel, list) -> put(channel, ArrayList(list.takeLast(KEPT))) } }

    /** Page de pub de [millis] terminée sur [channel] ; `false` si elle est trop courte ou trop longue pour être gardée. */
    @Synchronized
    fun add(channel: String, millis: Long): Boolean {
        if (channel.isBlank() || millis < MIN_MILLIS || millis > MAX_MILLIS) return false
        val list = byChannel.getOrPut(channel) { ArrayList() }
        list += millis
        while (list.size > KEPT) list.removeAt(0)
        return true
    }

    /** Durée habituelle d'une page de pub sur [channel] ; `null` tant qu'il y en a moins de [MIN_SAMPLES]. */
    @Synchronized
    fun typical(channel: String): Long? {
        val list = byChannel[channel]?.takeIf { it.size >= MIN_SAMPLES } ?: return null
        return list.sorted()[list.size / 2]
    }

    @Synchronized
    fun encode(): String = byChannel.entries.joinToString("\n") { (channel, list) ->
        channel.replace('\t', ' ').replace('\n', ' ') + "\t" + list.joinToString(",")
    }

    companion object {
        /** Plus courte : une baisse brève prise à tort ; plus longue : une erreur de la détection. */
        const val MIN_MILLIS = 30_000L
        const val MAX_MILLIS = 12 * 60_000L
        const val KEPT = 10
        const val MIN_SAMPLES = 3

        fun decode(text: String?): AdBreakDurations = AdBreakDurations(
            text.orEmpty().lineSequence().mapNotNull { line ->
                val channel = line.substringBefore('\t').takeIf { '\t' in line && it.isNotBlank() } ?: return@mapNotNull null
                channel to line.substringAfter('\t').split(',').mapNotNull { it.trim().toLongOrNull() }
            }.toMap(),
        )
    }
}
