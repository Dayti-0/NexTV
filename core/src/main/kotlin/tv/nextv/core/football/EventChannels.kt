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
     * Chaînes probables de chaque match. Le fournisseur met chaque match sur la chaîne suivante, à tour de
     * rôle (après LALIGA 5, LALIGA 1) : la précédente est souvent encore occupée (temps additionnel,
     * interviews). Un match dont la chaîne est connue ([anchors] : son numéro, d'après le guide du
     * fournisseur) donne donc celle de tous les autres. Sans lui, la numérotation part du premier match de
     * la journée ([dayStart], minuit) sur la chaîne 1, matchs terminés compris. Deux matchs au même coup
     * d'envoi se partagent leurs deux chaînes, celle de leur rang en premier.
     */
    fun <T> assign(
        matches: List<ScheduledMatch>,
        channels: List<Pair<T, EventChannel>>,
        dayStart: Long,
        anchors: Map<ScheduledMatch, Int> = emptyMap(),
    ): Map<ScheduledMatch, List<T>> {
        val result = LinkedHashMap<ScheduledMatch, List<T>>()
        for ((competition, numbered) in primaries(channels).groupBy { it.second.competition }) {
            val byNumber = numbered.associate { (item, event) -> event.number to item }
            val count = byNumber.keys.max()
            val played = matches.filter { it.competition == competition && it.status in NUMBERED }
            val anchor = anchors.entries.firstOrNull { it.key.competition == competition && it.key in played }
            val ordered = played
                .filter { anchor != null || it.kickoffMillis >= dayStart }
                .sortedWith(compareBy({ it.kickoffMillis }, { it.id }))
            // Rang 0 : le match de référence (sur sa chaîne connue), sinon le premier de la journée (chaîne 1).
            val origin = anchor?.let { ordered.indexOf(it.key) } ?: 0
            val originNumber = anchor?.value ?: 1
            // Avec une référence, à tour de rôle ; sans, le rang dans la journée (rien au-delà de la dernière chaîne).
            fun numberOf(index: Int): Int =
                if (anchor == null) index + 1 else Math.floorMod(originNumber - 1 + index - origin, count) + 1
            var index = 0
            for (group in ordered.groupBy { it.kickoffMillis }.values) {
                val numbers = (index until index + group.size).map(::numberOf)
                if (group.size <= MAX_TIED) {
                    group.forEachIndexed { position, match ->
                        val own = numbers[position]
                        val items = (listOf(own) + numbers.filter { it != own }).mapNotNull { byNumber[it] }
                        if (items.isNotEmpty()) result[match] = items
                    }
                }
                index += group.size
            }
        }
        return result
    }

    /** Matchs numérotés : joués ou à jouer (pas ceux reportés ou annulés). */
    private val NUMBERED = setOf(MatchStatus.SCHEDULED, MatchStatus.LIVE, MatchStatus.FINISHED)
}
