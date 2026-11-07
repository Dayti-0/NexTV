package tv.nextv.core.football

/**
 * Chaîne « événement » d'une playlist : compétition, numéro et famille, le texte qui précède
 * (« FR| DAZN LALIGA 2 (LIVE MATCH ONLY) » : Liga, 2, « fr dazn » ; « FR| DAZN 1 SERIE A (LIVE MATCH ONLY) » :
 * Serie A, 1, « fr dazn »). Les chaînes d'une même famille suivent la même numérotation.
 */
data class EventChannel(val competition: Competition, val number: Int, val family: String = "")

/**
 * Chaînes « événement » des playlists, sans programme dans le guide : « FR| DAZN LALIGA 2 (LIVE MATCH ONLY) »
 * diffuse le 2e match de Liga de la journée, dans l'ordre des coups d'envoi. Leurs images d'attente le
 * montrent (samedi 7 novembre 2026 au matin : LALIGA 1 à 4 pour les quatre matchs du samedi, LALIGA 5 pour
 * Elche – Betis le dimanche à 14:00 ; DAZN 1 et 2 SERIE A pour les deux matchs de 15:00, DAZN 3 SERIE A pour
 * celui de 18:00), et un match garde sa chaîne quand le précédent est terminé (Espanyol – Deportivo toujours
 * sur LALIGA 2 après Málaga – Racing). Le guide ne le dit pas : la chaîne n'est que probable.
 */
object EventChannels {

    /** Mentions des chaînes d'un seul match : « (LIVE MATCH) », « EVENT », « PPV ». */
    private val MARKER = Regex("""\b(live|match|matchs|event|events|evenement|evenements|ppv)\b""")

    /**
     * Championnat et numéro juste après ou juste avant son nom (« laliga 2 », « 1 serie a », pas
     * « laliga hypermotion 2 »). Ligue 1 n'y est pas : ses chaînes Ligue 1+ ont leurs programmes dans
     * le guide et une autre numérotation.
     */
    private val PATTERNS = listOf(
        Competition.LA_LIGA to "la ?liga",
        Competition.SERIE_A to "serie ?a",
        Competition.PREMIER_LEAGUE to "premier league",
        Competition.BUNDESLIGA to "bundesliga",
    ).map { (competition, name) ->
        competition to listOf(Regex("""\b$name ?(\d{1,2})\b"""), Regex("""\b(\d{1,2}) ?$name\b"""))
    }

    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Plus de matchs au même coup d'envoi : leur ordre sur les chaînes n'est pas devinable. */
    private const val MAX_TIED = 2

    /** Compétition, numéro et famille d'une chaîne événement d'après son nom ; `null` pour une autre chaîne. */
    fun parse(name: String): EventChannel? {
        val text = FootballText.fold(name).replace(NON_WORD, " ").trim()
        if (!MARKER.containsMatchIn(text)) return null
        for ((competition, patterns) in PATTERNS) {
            for (pattern in patterns) {
                val found = pattern.find(text) ?: continue
                val number = found.groupValues[1].toIntOrNull() ?: continue
                if (number in 1..20) return EventChannel(competition, number, text.substring(0, found.range.first).trim())
            }
        }
        return null
    }

    /**
     * Chaînes à retenir, la meilleure d'abord dans [channels] : pour chaque compétition, la famille de la
     * première (une autre a sa propre numérotation) et la première chaîne de chaque numéro.
     */
    fun <T> primaries(channels: List<Pair<T, EventChannel>>): List<Pair<T, EventChannel>> =
        channels.groupBy { it.second.competition }.values.flatMap { numbered ->
            val family = numbered.first().second.family
            numbered.filter { it.second.family == family }.distinctBy { it.second.number }
        }

    /**
     * Chaînes probables de chaque match : le fournisseur numérote les matchs d'une compétition depuis le
     * début de la journée ([dayStart], minuit), dans l'ordre des coups d'envoi, matchs terminés compris,
     * et continue avec ceux des jours suivants sur les chaînes qui restent. Deux matchs au même coup
     * d'envoi se partagent leurs deux chaînes, celle de leur rang en premier.
     */
    fun <T> assign(matches: List<ScheduledMatch>, channels: List<Pair<T, EventChannel>>, dayStart: Long): Map<ScheduledMatch, List<T>> {
        val result = LinkedHashMap<ScheduledMatch, List<T>>()
        for ((competition, numbered) in primaries(channels).groupBy { it.second.competition }) {
            val byNumber = numbered.associate { (item, event) -> event.number to item }
            val day = matches
                .filter { it.competition == competition && it.kickoffMillis >= dayStart && it.status in NUMBERED }
                .sortedWith(compareBy({ it.kickoffMillis }, { it.id }))
                .groupBy { it.kickoffMillis }
            var rank = 1
            for (group in day.values) {
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
        return result
    }

    /** Matchs numérotés : joués ou à jouer (pas ceux reportés ou annulés). */
    private val NUMBERED = setOf(MatchStatus.SCHEDULED, MatchStatus.LIVE, MatchStatus.FINISHED)
}
