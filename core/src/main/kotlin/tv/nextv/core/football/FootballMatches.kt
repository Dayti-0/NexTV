package tv.nextv.core.football

import java.text.Normalizer

/**
 * Compétitions de la section « Foot ». [apiId] : identifiant football-data.org pour celles que
 * son offre gratuite couvre ; les autres ne sont trouvées que dans le guide des programmes.
 */
enum class Competition(val label: String, val apiId: Int?) {
    LIGUE_1("Ligue 1", 2015),
    PREMIER_LEAGUE("Premier League", 2021),
    LA_LIGA("Liga", 2014),
    SERIE_A("Serie A", 2019),
    BUNDESLIGA("Bundesliga", 2002),
    CHAMPIONS_LEAGUE("Ligue des champions", 2001),
    EUROPA_LEAGUE("Ligue Europa", null),
    CONFERENCE_LEAGUE("Ligue Conférence", null),
    NATIONAL_TEAMS("Équipes nationales", null),
    ;

    companion object {
        fun fromApiId(id: Int): Competition? = entries.firstOrNull { it.apiId == id }
    }
}

/** Match de football : compétition et équipes (« Paris SG », « Marseille »). */
data class Fixture(val competition: Competition, val home: String, val away: String) {
    /** « Paris SG – Marseille ». */
    val label: String get() = "$home – $away"
}

/** Règles de texte communes à la recherche des matchs dans le guide. */
internal object FootballText {

    private val MARKS = Regex("\\p{Mn}+")

    /** Minuscules sans accents (« Équipe » → « equipe »). */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace('’', '\'')

    /** Autres sports : la Ligue des champions ou la Coupe du monde existent aussi en judo, en rugby… */
    val OTHER_SPORTS = Regex(
        """\b(rugby|handball|hand|basket|basketball|volley|volleyball|hockey|futsal|judo|tennis|cyclisme|cyclo-?cross|""" +
            """athletisme|natation|plongeon|flechettes|darts|snooker|billard|e-?sports?|padel|badminton|escrime|lutte|""" +
            """boxe|mma|golf|formule 1|f1|motogp|ski|biathlon|snowboard|patinage|gymnastique|equitation|water-?polo|""" +
            """beach|baseball|cricket|nfl|football americain|football us|triathlon|aviron|canoe|voile|surf|escalade|""" +
            """petanque)\b""",
    )

    /** Football féminin (la section suit les compétitions masculines), y compris dans la description. */
    val WOMEN = Regex("""\b(feminine|feminines|feminin|women|womens|frauen|wsl|ladies|arkema|liga f|premiere ligue(?! des))\b""")

    /** Émissions autour du football qui ne sont pas des matchs. */
    val NOT_A_MATCH = Regex(
        """\b(football club|foot club|magazine|mag|journal|resume|resumes|highlights|debrief|avant[- ]match|apres[- ]match|""" +
            """best of|le meilleur|tirage|plateau|emission|talk|quotidien|actu|multiplex|rediffusion)\b""",
    )

    val FOOTBALL = Regex("""\b(foot|football|soccer)\b""")

    /** Genres possibles d'une retransmission (« Sport, Football », « Inconnu »…). */
    val SPORT_GENRE = Regex("""\b(sport|sports|sportif|sportive|foot|football|soccer|inconnu|autre|unknown|other)\b""")

    /** Mots d'un nom de compétition : un titre qui en contient sans être reconnu parle d'une autre compétition. */
    val COMPETITION_WORDS = Regex(
        """\b(coupe|cup|ligue|league|liga|serie|bundesliga|championship|eredivisie|supercoupe|trophee|trophy|pokal|copa|""" +
            """coppa|championnat)\b""",
    )
}

/**
 * Repère les matchs de football dans le guide des programmes : « Football : Ligue 1 McDonald's -
 * Paris SG / Marseille », « Premier League : Liverpool - Arsenal », « UEFA Champions League »
 * avec « Bayern Munich vs. Inter »…
 *
 * Seuls les vrais matchs sont gardés : deux équipes identifiables, et aucune trace d'un autre
 * sport. Les magazines, résumés, multiplex et rediffusions sont écartés.
 */
object FootballMatches {

    private class Pattern(val competition: Competition, val include: Regex, val exclude: Regex? = null)

    /** Du plus précis au plus général (« Ligue Europa Conférence » avant « Ligue Europa »). */
    private val PATTERNS = listOf(
        Pattern(Competition.CONFERENCE_LEAGUE, Regex("""\b(ligue (europa )?conference|(europa )?conference league)\b""")),
        Pattern(Competition.EUROPA_LEAGUE, Regex("""\b(ligue europa|europa league)\b""")),
        Pattern(Competition.CHAMPIONS_LEAGUE, Regex("""\b(ligue des champions|champions league)\b""")),
        Pattern(Competition.PREMIER_LEAGUE, Regex("""\bpremier league\b""")),
        Pattern(
            Competition.LA_LIGA,
            Regex("""\b(laliga|la liga|liga|championnat d'espagne)\b"""),
            Regex("""\bliga (portugal|mx|nos|hypermotion|smartbank|profesional|argentina|2)\b|\bbundesliga\b"""),
        ),
        Pattern(Competition.SERIE_A, Regex("""\b(serie a|championnat d'italie)\b"""), Regex("""\b(bresil|brasil|brasileir\w*)\b""")),
        Pattern(Competition.BUNDESLIGA, Regex("""\bbundesliga\b"""), Regex("""\b(2\.? ?bundesliga|bundesliga 2|autriche|osterreich|austria)\b""")),
        // « Ligue 1 », « Ligue 1+ », « Ligue 1 McDonald's » ; pas « Ligue 10 ».
        Pattern(Competition.LIGUE_1, Regex("""\bligue 1(?!\d)""")),
        Pattern(
            Competition.NATIONAL_TEAMS,
            Regex(
                """\b(coupe du monde|world cup|euro \d{4}|championnat d'europe|ligue des nations|nations league|""" +
                    """eliminatoires|qualifications|equipe de france|amical international)\b""",
            ),
            Regex("""\b(des clubs|club world cup|u ?\d{2}|espoirs)\b"""),
        ),
    )

    /** Mots qui désignent la compétition ou la journée, jamais une équipe. */
    private val NOT_A_TEAM = Regex(
        """\b(ligue|league|liga|serie|bundesliga|journee|match|matchs|football|foot|multiplex|uefa|coupe|finale|demi|""" +
            """quart|quarts|huitieme|huitiemes|barrage|barrages|tour|groupe|phase|aller|retour|j\d+|\d+e)\b""",
    )

    /** Séparateurs entre les deux équipes, du plus sûr au plus ambigu (« Paris-SG » n'en est pas un). */
    private val SEPARATORS = listOf(" / ", " vs. ", " vs ", " v ", " contre ", " – ", " — ", " - ", "/")

    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

    /**
     * Match de football du programme, `null` s'il ne s'agit pas d'un match de l'une des
     * compétitions (ou si les équipes ne sont pas indiquées).
     */
    fun fixtureOf(title: String, subtitle: String?, category: String?, description: String?): Fixture? {
        val competition = competitionOf(title, subtitle, category, description) ?: return null
        val sentences = description?.split(SENTENCE_END).orEmpty()
        val teams = (listOfNotNull(subtitle, title) + sentences).firstNotNullOfOrNull(::teams) ?: return null
        return Fixture(competition, teams.first, teams.second)
    }

    /** Compétition d'un programme de football, sans exiger les équipes. */
    internal fun competitionOf(title: String, subtitle: String?, category: String?, description: String?): Competition? {
        val heading = FootballText.fold(listOfNotNull(title, subtitle).joinToString(" "))
        val genre = FootballText.fold(category.orEmpty())
        val details = FootballText.fold(description.orEmpty())
        if (FootballText.NOT_A_MATCH.containsMatchIn(FootballText.fold(title)) || FootballText.NOT_A_MATCH.containsMatchIn(genre)) return null
        if (FootballText.OTHER_SPORTS.containsMatchIn(heading) || FootballText.OTHER_SPORTS.containsMatchIn(genre)) return null
        if (FootballText.WOMEN.containsMatchIn("$heading $genre $details")) return null
        // Documentaire, série… : le genre indiqué n'est pas sportif.
        if (genre.isNotBlank() && !FootballText.SPORT_GENRE.containsMatchIn(genre)) return null
        val football = FootballText.FOOTBALL.containsMatchIn(heading) || FootballText.FOOTBALL.containsMatchIn(genre)
        // Sans « football » dans le titre ni le genre, la description ne doit pas parler d'un autre sport.
        if (!football && FootballText.OTHER_SPORTS.containsMatchIn(details)) return null
        find(heading)?.let { return it }
        // Le titre nomme une autre compétition (« Coupe de la Ligue anglaise », « Liga 2 ») : la description,
        // qui peut citer d'autres compétitions, n'est pas consultée.
        if (FootballText.COMPETITION_WORDS.containsMatchIn(heading)) return null
        return if (football || FootballText.FOOTBALL.containsMatchIn(details)) find(details) else null
    }

    /** Les deux équipes d'un texte comme « Ligue 1 - 11e journée : Paris-SG / Marseille ». */
    internal fun teams(text: String): Pair<String, String>? {
        for (separator in SEPARATORS) {
            var index = text.indexOf(separator, ignoreCase = true)
            while (index >= 0) {
                val home = text.substring(0, index).split(":", " - ", " – ", " — ", ",", "|").last().trim()
                val away = text.substring(index + separator.length).split(":", " - ", " – ", " — ", ",", "|", "(").first()
                    .trim().trimEnd('.', '!', '?', ';')
                if (isTeam(home) && isTeam(away)) return home to away
                index = text.indexOf(separator, index + separator.length, ignoreCase = true)
            }
        }
        return null
    }

    /** Nom d'équipe plausible : court, avec une majuscule ou un chiffre en tête (« Paris-SG », « 1. FC Köln »). */
    private fun isTeam(name: String): Boolean =
        name.length in 2..32 && name.split(' ').size <= 5 && name.any { it.isLetter() } && name.none { it in "/()[]" } &&
            (name.first().isUpperCase() || name.first().isDigit()) && !NOT_A_TEAM.containsMatchIn(FootballText.fold(name))

    private fun find(text: String): Competition? =
        PATTERNS.firstOrNull { it.include.containsMatchIn(text) && it.exclude?.containsMatchIn(text) != true }?.competition
}
