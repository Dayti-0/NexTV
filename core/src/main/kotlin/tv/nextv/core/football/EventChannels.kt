package tv.nextv.core.football

/** Chaîne « événement » d'une playlist : compétition et numéro (« FR| DAZN LALIGA 2 (LIVE MATCH) » : Liga, 2). */
data class EventChannel(val competition: Competition, val number: Int)

/**
 * Chaînes « événement » des playlists, sans programme dans le guide : « FR| DAZN LALIGA 2 (LIVE MATCH) »
 * diffuse le 2e match de Liga du jour, dans l'ordre des coups d'envoi. Leur image d'attente le montre
 * (samedi 7 novembre 2026 : LALIGA 1 Málaga – Racing à 14:00, LALIGA 2 Espanyol – Deportivo à 16:15,
 * LALIGA 4 Valence – Real Madrid à 21:00). Le guide ne le dit pas : la chaîne n'est que probable.
 */
object EventChannels {

    /** Mentions des chaînes d'un seul match : « (LIVE MATCH) », « EVENT », « PPV ». */
    private val MARKER = Regex("""\b(live|match|matchs|event|events|evenement|evenements|ppv)\b""")

    /**
     * Championnat et numéro juste après son nom (« laliga 2 », pas « laliga hypermotion 2 »). Ligue 1 n'y
     * est pas : ses chaînes Ligue 1+ ont leurs programmes dans le guide et une autre numérotation.
     */
    private val PATTERNS = listOf(
        Competition.LA_LIGA to Regex("""\bla ?liga ?(\d{1,2})\b"""),
        Competition.SERIE_A to Regex("""\bserie ?a ?(\d{1,2})\b"""),
        Competition.PREMIER_LEAGUE to Regex("""\bpremier league ?(\d{1,2})\b"""),
        Competition.BUNDESLIGA to Regex("""\bbundesliga ?(\d{1,2})\b"""),
    )

    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Plus de matchs au même coup d'envoi : leur ordre sur les chaînes n'est pas devinable. */
    private const val MAX_TIED = 2

    /** Compétition et numéro d'une chaîne événement d'après son nom ; `null` pour une autre chaîne. */
    fun parse(name: String): EventChannel? {
        val text = " " + FootballText.fold(name).replace(NON_WORD, " ") + " "
        if (!MARKER.containsMatchIn(text)) return null
        for ((competition, pattern) in PATTERNS) {
            val number = pattern.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (number in 1..20) return EventChannel(competition, number)
        }
        return null
    }

    /**
     * Chaînes probables de chaque match : le n-ième match du jour ([dayOf] : début du jour d'une heure)
     * d'une compétition passe sur sa chaîne n ([channels] : la meilleure d'abord pour chaque numéro).
     * Deux matchs au même coup d'envoi se partagent leurs deux chaînes, celle de leur rang en premier.
     * [matches] : tous les matchs du jour, terminés compris (sinon les rangs se décalent).
     */
    fun <T> assign(matches: List<ScheduledMatch>, channels: List<Pair<T, EventChannel>>, dayOf: (Long) -> Long): Map<ScheduledMatch, List<T>> {
        val result = LinkedHashMap<ScheduledMatch, List<T>>()
        for ((competition, numbered) in channels.groupBy { it.second.competition }) {
            val byNumber = LinkedHashMap<Int, T>()
            numbered.forEach { (item, event) -> byNumber.putIfAbsent(event.number, item) }
            val days = matches.filter { it.competition == competition }.groupBy { dayOf(it.kickoffMillis) }
            for (dayMatches in days.values) {
                var rank = 1
                val kickoffs = dayMatches.sortedWith(compareBy({ it.kickoffMillis }, { it.id })).groupBy { it.kickoffMillis }
                for (group in kickoffs.values) {
                    val ranks = rank until rank + group.size
                    if (group.size <= MAX_TIED) {
                        group.forEachIndexed { index, match ->
                            val own = rank + index
                            val items = (listOf(own) + ranks.filter { it != own }).mapNotNull { byNumber[it] }
                            if (items.isNotEmpty()) result[match] = items
                        }
                    }
                    rank += group.size
                }
            }
        }
        return result
    }
}
