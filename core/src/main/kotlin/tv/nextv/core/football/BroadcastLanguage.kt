package tv.nextv.core.football

/**
 * Langue probable d'une chaîne qui diffuse un match, d'après son nom dans la playlist : les chaînes
 * françaises passent avant les autres (« FR| CANAL+ LIVE 10 » avant « DE| RTL HD+ »).
 */
object BroadcastLanguage {

    /** Préfixe de pays des playlists : « FR| », « FR: », « [FR] », « FR - », « |FR| ». */
    private val PREFIX = Regex("""^\W*([A-Za-z]{2,3})\s*[|:\]\-–]""")

    /** Pays (et préfixes) dont les chaînes parlent français. */
    private val FRENCH_PREFIXES = setOf("fr", "fra")

    /** Diffuseurs français du football, reconnus sans préfixe de pays. */
    private val FRENCH_BROADCASTERS = Regex(
        """\b(canal\s*\+|canal\s*plus|canal\+|bein|rmc|l\s*'?\s*equipe|eurosport|dazn|tf1|tmc|tfx|m6|w9|france\s*[2345]|""" +
            """ligue\s*1|amazon|prime\s*video|foot\s*\+|infosport|c8|cstar|gulli|6ter|rmc\s*story|sport\s*en\s*france)""",
        RegexOption.IGNORE_CASE,
    )

    /** 0 : chaîne française ; 1 : inconnue ; 2 : chaîne d'un autre pays. */
    fun rank(channelName: String): Int {
        val name = FootballText.fold(channelName)
        val prefix = PREFIX.find(name)?.groupValues?.get(1)
        return when {
            prefix != null && prefix in FRENCH_PREFIXES -> 0
            prefix != null && prefix.length == 2 && prefix !in NOT_COUNTRIES -> 2
            FRENCH_BROADCASTERS.containsMatchIn(name) -> 0
            else -> 1
        }
    }

    /**
     * Chaîne d'un diffuseur de la compétition en France, choisie en premier (Ligue 1 : Ligue 1+ ;
     * Bundesliga, Liga, Serie A : beIN Sports ; Premier League et coupes d'Europe : Canal+ ;
     * sélections : TF1, M6…).
     */
    fun isOfficial(competition: Competition, channelName: String): Boolean {
        val official = OFFICIAL[competition] ?: return false
        return official.containsMatchIn(FootballText.fold(channelName))
    }

    /**
     * Chaînes qui diffusent vraisemblablement le match : quand une chaîne française de son diffuseur
     * figure parmi elles, les autres chaînes françaises sont écartées. Leur guide vient alors d'un
     * autre pays (« FR| CANAL+ SPORT 1 » annoncé avec un match de Bundesliga, qui passe sur beIN
     * Sports, alors que la chaîne diffuse autre chose). Les chaînes étrangères restent.
     */
    fun <T> likelyBroadcasters(competition: Competition, shows: List<T>, channelName: (T) -> String): List<T> {
        val official = shows.any { rank(channelName(it)) == 0 && isOfficial(competition, channelName(it)) }
        if (!official) return shows
        return shows.filter { rank(channelName(it)) != 0 || isOfficial(competition, channelName(it)) }
    }

    private val BEIN = """\bbein\b"""
    private val CANAL_PLUS = """\bcanal\s*(\+|plus\b)"""

    /** « LIGUE 1+| MATCH 3 », « FR| BEIN SPORTS 3 », « FR| CANAL+ FOOT ». */
    private val OFFICIAL = mapOf(
        Competition.LIGUE_1 to Regex("""\bligue\s*1\s*\+|\bl1\s*\+|$BEIN"""),
        Competition.PREMIER_LEAGUE to Regex(CANAL_PLUS),
        Competition.LA_LIGA to Regex(BEIN),
        Competition.SERIE_A to Regex(BEIN),
        Competition.BUNDESLIGA to Regex(BEIN),
        Competition.CHAMPIONS_LEAGUE to Regex(CANAL_PLUS),
        Competition.EUROPA_LEAGUE to Regex(CANAL_PLUS),
        Competition.CONFERENCE_LEAGUE to Regex(CANAL_PLUS),
        // Sélections : les chaînes nationales gratuites (l'équipe de France joue le plus souvent sur TF1 ou M6).
        Competition.NATIONAL_TEAMS to Regex("""\b(tf1|m6|france\s*[234]|tmc|w9|tfx|l\s*'?\s*equipe)\b"""),
    )

    /** Débuts de nom à deux lettres qui ne sont pas des pays (« TV| », « HD: »). */
    private val NOT_COUNTRIES = setOf("tv", "hd", "sd", "uh", "vo", "vf", "my")
}
