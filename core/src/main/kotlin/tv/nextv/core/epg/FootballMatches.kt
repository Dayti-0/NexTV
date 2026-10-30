package tv.nextv.core.epg

import java.text.Normalizer

/** Compétitions suivies par la section « Foot ». */
enum class Competition(val label: String) {
    LIGUE_1("Ligue 1"),
    PREMIER_LEAGUE("Premier League"),
    CHAMPIONS_LEAGUE("Ligue des champions"),
}

/**
 * Repère, dans le guide des programmes, les retransmissions de matchs des [Competition] :
 * « Football : Ligue 1 McDonald's - Paris SG / Marseille », « Premier League : Liverpool -
 * Arsenal », « UEFA Champions League »… Les magazines et résumés (« Canal Football Club »,
 * « Résumés de la Ligue 1 ») sont écartés.
 */
object FootballMatches {

    private val PATTERNS: List<Pair<Competition, Regex>> = listOf(
        Competition.CHAMPIONS_LEAGUE to Regex("""\b(ligue des champions|champions league)\b"""),
        Competition.PREMIER_LEAGUE to Regex("""\bpremier league\b"""),
        // « Ligue 1 », « Ligue 1+ », « Ligue 1 McDonald's » ; pas « Ligue 10 » ni la Ligue 1 de rugby ou de handball.
        Competition.LIGUE_1 to Regex("""\bligue 1(?!\d)"""),
    )

    private val OTHER_SPORTS = Regex("""\b(rugby|handball|basket|volley|hockey|futsal|feminine|feminin|arkema)\b""")

    /** Émissions autour du football qui ne sont pas des matchs. */
    private val NOT_A_MATCH = Regex(
        """\b(football club|foot club|magazine|mag|journal|resume|resumes|highlights|debrief|avant[- ]match|apres[- ]match|""" +
            """best of|le meilleur|tirage|plateau|emission|talk|quotidien|actu)\b""",
    )

    private val FOOTBALL = Regex("""\bfoot(ball)?\b""")

    /**
     * Compétition du programme, `null` s'il ne s'agit pas d'un match de l'une d'elles. La
     * description n'est lue que pour un programme de football (titre ou genre).
     */
    fun competitionOf(title: String, subtitle: String?, category: String?, description: String?): Competition? {
        val heading = fold(listOfNotNull(title, subtitle).joinToString(" "))
        val genre = fold(category.orEmpty())
        if (NOT_A_MATCH.containsMatchIn(fold(title)) || NOT_A_MATCH.containsMatchIn(genre)) return null
        if (OTHER_SPORTS.containsMatchIn(heading) || OTHER_SPORTS.containsMatchIn(genre)) return null
        find(heading)?.let { return it }
        val football = FOOTBALL.containsMatchIn(heading) || FOOTBALL.containsMatchIn(genre)
        return if (football && description != null) find(fold(description)) else null
    }

    private fun find(text: String): Competition? = PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first

    private val MARKS = Regex("\\p{Mn}+")

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace('’', '\'')
}
