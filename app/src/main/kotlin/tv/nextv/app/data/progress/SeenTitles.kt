package tv.nextv.app.data.progress

/**
 * Films et séries rangés à la fin des listes : ceux vus jusqu'au bout (« Déjà vu ») et qui ne sont pas en cours.
 * Une série compte dès qu'un de ses épisodes a été vu en entier et qu'elle n'a plus de reprise (dernier épisode
 * terminé) ; commencée, elle garde sa place.
 */
object SeenTitles {
    /**
     * @param watched clés « Déjà vu » : films, et épisodes (`clé de la série|S1E2`).
     * @param inProgress clés des films et séries en cours (reprises).
     */
    fun of(watched: Collection<String>, inProgress: Collection<String>): Set<String> {
        val result = HashSet<String>()
        for (key in watched) result += key.substringBefore('|')
        result.removeAll(inProgress.toSet())
        return result
    }
}
