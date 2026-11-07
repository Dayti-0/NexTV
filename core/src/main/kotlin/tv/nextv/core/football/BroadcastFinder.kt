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
        // Noms de tous les clubs du calendrier : une affiche qui en nomme un autre est celle d'un autre match.
        val clubs = teams.values.flatMapTo(HashSet()) { (home, away) -> home + away }
        val result = LinkedHashMap<ScheduledMatch, MutableList<T>>()
        for (broadcast in broadcasts) {
            // Seuls les matchs dont le coup d'envoi tombe pendant le programme sont comparés.
            val candidates = matches.filter { covers(broadcast, it.kickoffMillis) }
            if (candidates.isEmpty()) continue
            val heading = FootballText.fold(listOfNotNull(broadcast.title, broadcast.subtitle).joinToString(" "))
            val genre = FootballText.fold(broadcast.category.orEmpty())
            if (excluded(broadcast, heading, genre)) continue
            val text = textOf(broadcast)
            val headline = " " + normalize(listOfNotNull(broadcast.title, broadcast.subtitle).joinToString(" ")) + " "
            val competition = FootballMatches.competitionOf(broadcast.title, broadcast.subtitle, broadcast.category, broadcast.description)
            // Affiche nommée par le programme (« Monaco / Lens ») : il ne diffuse pas un match d'autres équipes.
            val named = FootballMatches.fixtureOf(broadcast.title, broadcast.subtitle, broadcast.category, broadcast.description)
            val football = competition != null || FootballText.FOOTBALL.containsMatchIn(heading) || FootballText.FOOTBALL.containsMatchIn(genre)
            var best: ScheduledMatch? = null
            var bestScore = 0
            var tie = false
            for (match in candidates) {
                val (home, away) = teams.getValue(match)
                if (named != null && !shows(named, home, away, clubs)) continue
                val cited = citations(home, away, text)
                val score = when {
                    cited == 2 -> 3
                    // Sélections : un seul pays cité doit l'être dans le titre, pas seulement dans la description
                    // (celle d'un match de club parle du « championnat d'Italie »).
                    match.competition == Competition.NATIONAL_TEAMS && citations(home, away, headline) == 0 -> 0
                    // Une seule équipe citée : seulement si le programme ne parle pas d'une autre compétition
                    // (« Championnat de France » ne désigne pas l'équipe de France).
                    cited == 1 && competition != null && !compatible(competition, match.competition) -> 0
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

    /** Nombre d'équipes ([home], [away] : leurs noms) citées dans [text] (normalisé, entouré d'espaces). */
    private fun citations(home: Set<String>, away: Set<String>, text: String): Int =
        listOf(home, away).count { names -> names.any { " $it " in text } }

    /** Le nom d'équipe [name] du guide désigne l'une des deux équipes ([home], [away] : leurs noms). */
    private fun names(name: String, home: Set<String>, away: Set<String>): Boolean {
        val text = " " + normalize(name) + " "
        return (home + away).any { " $it " in text }
    }

    /**
     * L'affiche nommée par le programme ([named]) peut être celle du match ([home], [away] : noms de ses
     * équipes) : l'une de ses équipes au moins est reconnue, et l'autre n'est pas un autre club du
     * calendrier ([clubs] : leurs noms). « FC Barcelone / Deportivo Alavés » ne diffuse pas
     * Séville – Alavés ; « Inter Milan / Côme » diffuse bien Inter – Como (« Côme » : nom inconnu).
     */
    private fun shows(named: Fixture, home: Set<String>, away: Set<String>, clubs: Set<String>): Boolean {
        val first = names(named.home, home, away)
        val second = names(named.away, home, away)
        return (first || second) && (first || !designates(named.home, clubs)) && (second || !designates(named.away, clubs))
    }

    /** Le nom [name] du guide contient l'un des noms de club [clubs] (mots entiers). */
    private fun designates(name: String, clubs: Set<String>): Boolean {
        val words = normalize(name).split(' ').filter { it.isNotEmpty() }
        return words.indices.any { from -> (from + 1..words.size).any { to -> words.subList(from, to).joinToString(" ") in clubs } }
    }

    /** Même compétition, ou deux coupes d'Europe (le guide annonce parfois l'une pour l'autre). */
    private fun compatible(a: Competition, b: Competition): Boolean = a == b || (a in EUROPEAN_CUPS && b in EUROPEAN_CUPS)

    private val EUROPEAN_CUPS = setOf(Competition.CHAMPIONS_LEAGUE, Competition.EUROPA_LEAGUE, Competition.CONFERENCE_LEAGUE)

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

    /**
     * Même club sous deux noms de sources différentes : « Paris Saint-Germain FC » (football-data.org,
     * équipes suivies) et « Paris Saint-Germain » (ESPN), « FC Bayern München » et « Bayern Munich ».
     * « Manchester City » et « Manchester United » restent distincts.
     */
    fun sameClub(a: String, b: String): Boolean {
        if (a == b) return true
        val known = cachedAliases(a)
        return cachedAliases(b).any { it in known }
    }

    private val aliasCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun cachedAliases(name: String): Set<String> =
        aliasCache[name] ?: aliases(Team(name, null, null, null)).also {
            if (aliasCache.size > ALIAS_CACHE_SIZE) aliasCache.clear()
            aliasCache[name] = it
        }

    private const val ALIAS_CACHE_SIZE = 2_000

    /** Noms sous lesquels une équipe peut apparaître dans le guide (minuscules, sans accents ni ponctuation). */
    internal fun aliases(team: Team): Set<String> {
        // Sélection nationale : son nom anglais (calendrier) et français (guide), toujours reconnus.
        val country = CountryNames.french(team.name)?.let { setOf(normalize(team.name), normalize(it)) }.orEmpty()
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
        return names.filter { it.length >= 2 && it !in AFFIXES && it !in GENERIC }.toSet() + country
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
        // « Rome » et « Turin » seuls ne sont pas traduits : le guide écrit « Lazio Rome » et « Juventus Turin ».
        "bologna" to listOf("bologne"),
        "valencia" to listOf("valence"),
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
        "paris saint germain" to listOf("psg", "paris sg"),
        "as roma" to listOf("as rome"),
        // « Deportivo » seul (calendrier d'ESPN) est un mot trop courant : la ville le désigne.
        "deportivo" to listOf("deportivo la coruna", "deportivo de la coruna", "la coruna", "coruna"),
        "deportivo la coruna" to listOf("deportivo de la coruna", "la coruna", "coruna"),
    )
}
