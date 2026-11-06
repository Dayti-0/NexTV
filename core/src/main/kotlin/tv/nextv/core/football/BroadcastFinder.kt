package tv.nextv.core.football

/** Programme du guide susceptible de diffuser un match. */
data class Broadcast<T>(
    val item: T,
    val title: String,
    val subtitle: String?,
    val category: String?,
    val description: String?,
    val startMillis: Long,
    val stopMillis: Long,
)

/**
 * Retrouve dans le guide les programmes qui diffusent les matchs de football-data.org : un
 * programme qui couvre le coup d'envoi et cite les équipes (« PSG / Marseille » pour « Paris
 * Saint-Germain FC » contre « Olympique de Marseille »). Chaque programme va au match qui lui
 * correspond le mieux : les deux équipes citées, sinon une seule avec la compétition ou le mot
 * « football » ; à égalité, il n'est attribué à aucun.
 */
object BroadcastFinder {

    /** Le programme peut commencer avant le coup d'envoi (avant-match), mais doit couvrir le début du match. */
    private const val EARLIEST_START_MILLIS = 2 * 60 * 60_000L
    private const val LATEST_START_MILLIS = 20 * 60_000L
    private const val MIN_COVER_MILLIS = 30 * 60_000L

    fun <T> assign(matches: List<ScheduledMatch>, broadcasts: List<Broadcast<T>>): Map<ScheduledMatch, List<T>> {
        val teams = matches.associateWith { aliases(it.home) to aliases(it.away) }
        val result = LinkedHashMap<ScheduledMatch, MutableList<T>>()
        for (broadcast in broadcasts) {
            // Seuls les matchs dont le coup d'envoi tombe pendant le programme sont comparés.
            val candidates = matches.filter { covers(broadcast, it.kickoffMillis) }
            if (candidates.isEmpty()) continue
            val heading = FootballText.fold(listOfNotNull(broadcast.title, broadcast.subtitle).joinToString(" "))
            val genre = FootballText.fold(broadcast.category.orEmpty())
            if (excluded(broadcast, heading, genre)) continue
            val text = textOf(broadcast)
            val competition = FootballMatches.competitionOf(broadcast.title, broadcast.subtitle, broadcast.category, broadcast.description)
            val football = competition != null || FootballText.FOOTBALL.containsMatchIn(heading) || FootballText.FOOTBALL.containsMatchIn(genre)
            var best: ScheduledMatch? = null
            var bestScore = 0
            var tie = false
            for (match in candidates) {
                val (home, away) = teams.getValue(match)
                val cited = listOf(home, away).count { names -> names.any { " $it " in text } }
                val score = when {
                    cited == 2 -> 3
                    cited == 1 && competition == match.competition -> 2
                    cited == 1 && football -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    best = match
                    bestScore = score
                    tie = false
                } else if (score == bestScore && score > 0) {
                    tie = true
                }
            }
            if (best != null && !tie) result.getOrPut(best) { mutableListOf() } += broadcast.item
        }
        return result
    }

    /** Affiche du guide (« Bayer Leverkusen » – « Marseille ») dont la diffusion commence à [startMillis]. */
    data class GuideFixture(val home: String, val away: String, val startMillis: Long)

    /** Écart entre deux diffusions du même match (avant-match plus ou moins long selon la chaîne). */
    private const val SAME_FIXTURE_MILLIS = 75 * 60_000L

    /**
     * Autres programmes qui diffusent chaque affiche du guide : même match sous un autre titre
     * (« Leverkusen / Marseille » sans la compétition, « Bayer 04 Leverkusen – Olympique de Marseille »),
     * sur une autre chaîne. Les deux équipes doivent être citées et la diffusion commencer à moins
     * de 75 min de la première.
     */
    fun <T> sameFixtures(fixtures: List<GuideFixture>, broadcasts: List<Broadcast<T>>): List<List<T>> {
        val sorted = broadcasts.sortedBy { it.startMillis }
        val starts = LongArray(sorted.size) { sorted[it].startMillis }
        // Texte de chaque programme, préparé une seule fois et seulement pour ceux qui tombent dans une fenêtre.
        val texts = arrayOfNulls<String>(sorted.size)
        return fixtures.map { fixture ->
            val home = aliases(Team(fixture.home, null, null, null))
            val away = aliases(Team(fixture.away, null, null, null))
            if (home.isEmpty() || away.isEmpty()) return@map emptyList()
            val from = lowerBound(starts, fixture.startMillis - SAME_FIXTURE_MILLIS)
            val to = lowerBound(starts, fixture.startMillis + SAME_FIXTURE_MILLIS + 1)
            (from until to).mapNotNull { index ->
                val broadcast = sorted[index]
                if (broadcast.stopMillis - broadcast.startMillis < MIN_COVER_MILLIS) return@mapNotNull null
                val text = texts[index] ?: run {
                    val heading = FootballText.fold(listOfNotNull(broadcast.title, broadcast.subtitle).joinToString(" "))
                    val genre = FootballText.fold(broadcast.category.orEmpty())
                    (if (excluded(broadcast, heading, genre)) "" else textOf(broadcast)).also { texts[index] = it }
                }
                broadcast.item.takeIf { home.any { " $it " in text } && away.any { " $it " in text } }
            }
        }
    }

    private fun lowerBound(values: LongArray, key: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    /** Autre sport, football féminin, magazine… : jamais un match. */
    private fun excluded(broadcast: Broadcast<*>, heading: String, genre: String): Boolean =
        FootballText.NOT_A_MATCH.containsMatchIn(FootballText.fold(broadcast.title)) ||
            FootballText.OTHER_SPORTS.containsMatchIn(heading) || FootballText.OTHER_SPORTS.containsMatchIn(genre) ||
            FootballText.WOMEN.containsMatchIn("$heading $genre ${FootballText.fold(broadcast.description.orEmpty())}")

    private fun textOf(broadcast: Broadcast<*>): String =
        " " + normalize(listOfNotNull(broadcast.title, broadcast.subtitle, broadcast.description).joinToString(" ")) + " "

    /**
     * Écussons des équipes de football-data.org, retrouvés à partir d'un nom écrit dans le guide
     * (« Lyon », « AS Rome ») ; un nom porté par deux équipes différentes ne donne rien.
     */
    fun crests(teams: Collection<Team>): (String) -> String? {
        val index = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        for (team in teams) {
            val crest = team.crestUrl ?: continue
            for (alias in aliases(team)) {
                val known = index.putIfAbsent(alias, crest)
                if (known != null && known != crest) ambiguous += alias
            }
        }
        ambiguous.forEach(index::remove)
        return { name ->
            val normalized = normalize(name)
            val core = normalized.split(' ').filter { it.isNotEmpty() && it !in AFFIXES }
            index[normalized] ?: index[core.joinToString(" ")] ?: core.singleOrNull { it in index }?.let(index::get)
        }
    }

    private fun covers(broadcast: Broadcast<*>, kickoff: Long): Boolean =
        broadcast.startMillis >= kickoff - EARLIEST_START_MILLIS &&
            broadcast.startMillis <= kickoff + LATEST_START_MILLIS &&
            broadcast.stopMillis >= kickoff + MIN_COVER_MILLIS

    /** Noms sous lesquels une équipe peut apparaître dans le guide (minuscules, sans accents ni ponctuation). */
    internal fun aliases(team: Team): Set<String> {
        val names = mutableSetOf<String>()
        for (name in listOfNotNull(team.name, team.shortName).map(::normalize)) {
            names += name
            EXTRA[name]?.let { names += it }
            val core = name.split(' ').filter { it.isNotEmpty() && it !in AFFIXES }
            if (core.isEmpty()) continue
            names += core.joinToString(" ")
            names += core.joinToString(" ") { TRANSLATIONS[it]?.first() ?: it }
            // Mots distinctifs (« marseille », « bayern ») et leurs équivalents français.
            for (word in core) {
                if (word.length >= 4 && word !in GENERIC) names += word
                TRANSLATIONS[word]?.let { names += it }
            }
        }
        return names.filter { it.length >= 2 && it !in AFFIXES && it !in GENERIC }.toSet()
    }

    private fun normalize(text: String): String =
        FootballText.fold(text).replace(NON_WORD, " ").trim().replace(SPACES, " ")

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex(" {2,}")

    /** Mentions juridiques ou sportives sans valeur pour reconnaître l'équipe. */
    private val AFFIXES = setOf(
        "fc", "afc", "cf", "sc", "ac", "as", "ssc", "us", "cfc", "sv", "vfb", "vfl", "tsg", "bsc", "fk", "sk", "ogc", "rc", "rcd",
        "ud", "cd", "sd", "ca", "bc", "acf", "fsv", "club", "de", "del", "di", "e", "la", "le", "les", "l", "the", "calcio",
        "futbol", "football", "osc", "sco", "aj", "kv", "sl", "sad", "st", "1", "04", "05", "29", "1846", "1899", "1901",
        "1904", "1909", "1910",
    )

    /** Mots trop courants pour désigner seuls une équipe (« Paris », « United », « Real »…). */
    private val GENERIC = setOf(
        "paris", "saint", "germain", "olympique", "stade", "racing", "real", "sporting", "athletic", "atletico", "united",
        "city", "town", "county", "rovers", "wanderers", "albion", "villa", "forest", "palace", "borussia", "eintracht",
        "bayer", "union", "sport", "sports", "deportivo", "madrid", "manchester", "milano", "london", "west", "hove",
        "hotspur", "alsace", "lisboa", "portugal",
    )

    /** Équivalents français et surnoms usuels des chaînes, par mot du nom officiel. */
    private val TRANSLATIONS = mapOf(
        "munchen" to listOf("munich"),
        "barcelona" to listOf("barcelone", "barca"),
        "sevilla" to listOf("seville"),
        "napoli" to listOf("naples"),
        "roma" to listOf("rome"),
        "torino" to listOf("turin"),
        "bologna" to listOf("bologne"),
        "genoa" to listOf("genes"),
        "koln" to listOf("cologne"),
        "frankfurt" to listOf("francfort"),
        "freiburg" to listOf("fribourg"),
        "mainz" to listOf("mayence"),
        "augsburg" to listOf("augsbourg"),
        "bremen" to listOf("breme"),
        "hamburger" to listOf("hambourg"),
        "salzburg" to listOf("salzbourg"),
        "brugge" to listOf("bruges"),
        "internazionale" to listOf("inter"),
        "monchengladbach" to listOf("gladbach"),
        "rennais" to listOf("rennes"),
        "brestois" to listOf("brest"),
        "lyonnais" to listOf("lyon", "ol"),
        "marseille" to listOf("om"),
        "lille" to listOf("losc"),
        "juventus" to listOf("juve"),
        "tottenham" to listOf("spurs"),
        "wolverhampton" to listOf("wolves"),
        "psg" to listOf("paris sg", "paris saint germain"),
    )

    /** Surnoms rattachés au nom complet. */
    private val EXTRA = mapOf(
        "athletic club" to listOf("athletic bilbao", "bilbao"),
        "paris saint germain fc" to listOf("psg", "paris sg"),
    )
}
