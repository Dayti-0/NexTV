package tv.nextv.core.epg

import java.text.Normalizer

/**
 * Rediffusions du guide. Les guides ne les marquent presque jamais (xmltvfr : aucune balise
 * `previously-shown` sur beIN SPORTS), mais une chaîne de sport repasse le même match plusieurs
 * fois sous le même titre (« Football : Süper Lig | Galatasaray / Fenerbahçe » : 4 fois en deux
 * jours le 18/11/2026). Un match déjà passé dans le guide plus tôt est donc une rediffusion.
 */
object Rebroadcast {

    /** Un programme déjà diffusé moins de [MIN_GAP_MILLIS] plus tôt n'est pas une rediffusion (même direct sur deux chaînes). */
    const val MIN_GAP_MILLIS = 60 * 60_000L

    /** Diffusions plus anciennes ignorées : matchs aller et retour sous le même titre. */
    const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60_000L

    private val MARKS = Regex("\\p{Mn}+")

    /** Rediffusion annoncée par le titre ou le sous-titre (« (R) », « Rediffusion », « en différé »). */
    private val ANNOUNCED = Regex("""\b(rediffusion|rediff|redif|en differe|replay)\b|\((r|rediff?)\)""")

    /** Affiche entre deux camps : « Galatasaray / Fenerbahçe », « Lakers vs Celtics ». */
    private val EVENT = Regex("""\S\s+/\s+\S|\bvs\.?\s""")

    /** Mots des programmes qui encadrent une rencontre sans en être une. */
    private val NOT_AN_EVENT = Regex("""\b(avant[- ]match|apres[- ]match|resume|resumes|magazine|extra|zap)\b""")

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    fun announced(title: String, subtitle: String?): Boolean =
        ANNOUNCED.containsMatchIn(fold(listOfNotNull(title, subtitle).joinToString(" ")))

    /**
     * Rencontre sportive (deux camps dans le titre) : seule à être reconnue rediffusée par son titre
     * déjà vu. Un magazine, une série ou un journal repassent sous le même titre avec un contenu neuf.
     */
    fun isEvent(title: String): Boolean {
        val folded = fold(title)
        return EVENT.containsMatchIn(folded) && !NOT_AN_EVENT.containsMatchIn(folded)
    }

    /** Fenêtre des débuts d'une diffusion antérieure du programme qui commence à [startMillis]. */
    fun earlierWindow(startMillis: Long): LongRange = (startMillis - MAX_AGE_MILLIS)..(startMillis - MIN_GAP_MILLIS)

    /**
     * Rediffusion : annoncée par le guide, ou rencontre dont le même titre est déjà passé plus tôt
     * ([airedBefore] : une diffusion du même titre a commencé dans [earlierWindow]).
     */
    inline fun isRebroadcast(title: String, subtitle: String?, airedBefore: () -> Boolean): Boolean =
        announced(title, subtitle) || (isEvent(title) && airedBefore())
}
