package tv.nextv.app.data.football

import android.content.SharedPreferences
import tv.nextv.core.football.Competition

/**
 * Tour des chaînes « événement » retenu d'un jour à l'autre : premier match de la journée de chaque
 * compétition et le numéro de sa chaîne (« Málaga – Racing sur LALIGA 1 »). Le lendemain, le tour
 * continue à partir de lui (« Elche – Betis reste sur LALIGA 5 ») au lieu de repartir de la chaîne 1.
 */
class EventTurnStore(private val preferences: SharedPreferences) {

    /** Match de référence : identifiant, coup d'envoi et numéro de chaîne. */
    data class Turn(val matchId: Long, val kickoffMillis: Long, val number: Int)

    fun read(): Map<Competition, Turn> = Competition.entries.mapNotNull { competition ->
        val parts = preferences.getString(competition.name, null)?.split(';') ?: return@mapNotNull null
        val turn = Turn(
            parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null,
            parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null,
            parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
        )
        competition to turn
    }.toMap()

    fun write(turns: Map<Competition, Turn>) {
        if (turns.isEmpty() || turns.all { (competition, turn) -> read()[competition] == turn }) return
        preferences.edit().apply {
            turns.forEach { (competition, turn) -> putString(competition.name, "${turn.matchId};${turn.kickoffMillis};${turn.number}") }
        }.apply()
    }
}
