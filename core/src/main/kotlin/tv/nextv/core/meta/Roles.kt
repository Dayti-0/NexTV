package tv.nextv.core.meta

/**
 * Mentions que TMDB écrit en anglais à côté d'un rôle, quelle que soit la langue demandée
 * (« Gus' Mom (voice) », « Self », « Narrator (uncredited) ») : traduites en français. Le nom du
 * personnage lui-même est laissé tel que TMDB le donne.
 */
internal object Roles {

    /** Mentions entre parenthèses, les plus longues d'abord (« singing voice » avant « voice »). */
    private val MENTIONS = listOf(
        "singing voice" to "voix chantée",
        "speaking voice" to "voix parlée",
        "archive footage" to "images d'archives",
        "archive sound" to "archives sonores",
        "credit only" to "au générique seulement",
        "uncredited" to "non crédité",
        "voice" to "voix",
    )
    private val PARENTHESES = Regex("""\(([^()]*)\)""")
    private val WHOLE_WORDS = MENTIONS.map { (english, french) -> Regex("(?i)(?<![\\p{L}])${Regex.escape(english)}(?![\\p{L}])") to french }

    private const val FEMALE = 1
    private const val MALE = 2

    /** @param gender genre de la personne d'après TMDB (1 : femme, 2 : homme, autre : inconnu). */
    fun toFrench(role: String, gender: Int): String {
        val translated = PARENTHESES.replace(role) { match ->
            var inside = match.groupValues[1]
            for ((english, french) in WHOLE_WORDS) inside = english.replace(inside, french)
            "($inside)"
        }
        // Le rôle lui-même, quand il n'est pas un nom de personnage.
        val open = translated.indexOf('(').let { if (it < 0) translated.length else it }
        val name = translated.substring(0, open).trim()
        val french = when (name.lowercase()) {
            "self", "himself", "herself", "themselves", "themself" -> when {
                name.equals("herself", ignoreCase = true) || gender == FEMALE -> "Elle-même"
                name.equals("himself", ignoreCase = true) || gender == MALE -> "Lui-même"
                else -> "Dans son propre rôle"
            }
            "narrator" -> if (gender == FEMALE) "Narratrice" else "Narrateur"
            else -> return translated
        }
        return (french + " " + translated.substring(open)).trim()
    }
}
