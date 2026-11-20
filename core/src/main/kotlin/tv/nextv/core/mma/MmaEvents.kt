package tv.nextv.core.mma

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Organisations de la section « MMA ». [espnLeague] : calendrier complet d'ESPN (cartes, horaires, résultats) ; les
 * autres ne sont trouvées que dans le guide des programmes, par les mots de [guideWords]. [usualGuideIds] : chaînes du
 * guide qui diffusent ses galas en France (RMC Sport pour l'UFC…), proposées quand le guide ne couvre pas encore la date.
 */
enum class MmaPromotion(val label: String, val espnLeague: String?, val guideWords: List<String>, val usualGuideIds: List<String> = emptyList()) {
    UFC("UFC", "ufc", listOf("ufc"), RMC_SPORT),
    PFL("PFL", "pfl", listOf("pfl", "bellator"), RMC_SPORT),
    CAGE_WARRIORS("Cage Warriors", "cage-warriors", listOf("cage warriors"), RMC_SPORT),
    KSW("KSW", null, listOf("ksw"), RMC_SPORT),
    ARES("ARES", null, listOf("ares fighting", "ares"), listOf("canalplussport360.fr", "canalplussport.fr")),
    HEXAGONE("Hexagone", null, listOf("hexagone"), RMC_SPORT),
    ONE("ONE", null, listOf("one championship", "one fight night")),
    OTHER("Autres galas", null, emptyList()),
}

/** RMC Sport 1, puis 2 : chaînes du MMA en France (UFC, PFL, Cage Warriors, KSW, Hexagone ; guide du 20/11/2026). */
private val RMC_SPORT = listOf("rmcsport1.fr", "rmcsport2.fr")

/** Combattant : nom, pays, drapeau, palmarès (« 32-6-1 ») et identifiant ESPN (portrait) quand ESPN les donne. */
data class MmaFighter(
    val name: String,
    val shortName: String = name,
    val country: String? = null,
    val flagUrl: String? = null,
    val record: String? = null,
    val espnId: String? = null,
) {
    /**
     * Portrait du combattant (fond transparent), réduit par le serveur d'images d'ESPN à [width] pixels de large (l'original
     * fait 600 × 436, ~200 Ko) ; absent pour certains combattants (l'image ne charge pas).
     */
    fun photoUrl(width: Int = 240): String? = espnId?.let {
        "https://a.espncdn.com/combiner/i?img=/i/headshots/mma/players/full/$it.png&w=$width&h=${width * 436 / 600}"
    }
}

/** Fin d'un combat : vainqueur ([winner] 0 ou 1), reprise et temps, ou décision des juges. */
data class MmaResult(val winner: Int?, val round: Int, val clock: String, val decision: Boolean)

/** Combat d'une carte. [mainCard] : carte principale (sinon préliminaires). */
data class MmaFight(
    val weightClass: String?,
    val fighters: List<MmaFighter>,
    val startMillis: Long,
    val mainCard: Boolean,
    val live: Boolean = false,
    val result: MmaResult? = null,
) {
    /** « Procházka – Stirling ». */
    val label: String get() = fighters.joinToString(" – ") { it.name }
}

/** Gala : nom (« UFC Fight Night: Procházka vs. Stirling »), début des préliminaires et de la carte principale. */
data class MmaEvent(
    val id: String,
    val promotion: MmaPromotion,
    val name: String,
    val startMillis: Long,
    /** Début de la carte principale, `null` si ESPN ne la distingue pas. */
    val mainCardMillis: Long?,
    /** Combats, le combat principal en premier. */
    val fights: List<MmaFight>,
    val finished: Boolean = false,
    val venue: String? = null,
) {
    /** Combat principal (le dernier de la soirée). */
    val mainEvent: MmaFight? get() = fights.firstOrNull()

    /** Fin estimée : 4 h après le début de la carte principale (ou 6 h après le début). */
    val endMillis: Long get() = (mainCardMillis ?: startMillis) + if (mainCardMillis != null) MAIN_CARD_MILLIS else WHOLE_EVENT_MILLIS

    fun isLive(now: Long): Boolean = !finished && startMillis <= now && endMillis > now

    companion object {
        const val MAIN_CARD_MILLIS = 4 * 60 * 60_000L
        const val WHOLE_EVENT_MILLIS = 6 * 60 * 60_000L
    }
}

/** Lecture du tableau des galas d'ESPN (`site.api.espn.com/apis/site/v2/sports/mma/<ligue>/scoreboard`). */
object MmaEspn {

    /** Galas de la réponse, `null` si elle est illisible. */
    fun parse(body: String, promotion: MmaPromotion): List<MmaEvent>? = try {
        val events = JSONObject(body).optJSONArray("events") ?: JSONArray()
        (0 until events.length()).mapNotNull { event(events.getJSONObject(it), promotion) }
    } catch (e: JSONException) {
        null
    }

    private fun event(json: JSONObject, promotion: MmaPromotion): MmaEvent? {
        val start = time(json.optString("date")) ?: return null
        val competitions = json.optJSONArray("competitions") ?: JSONArray()
        val fights = (0 until competitions.length()).mapNotNull { fight(competitions.getJSONObject(it)) }
        // Plusieurs horaires dans la soirée : le dernier est celui de la carte principale.
        val times = fights.map { it.startMillis }.distinct().sorted()
        val mainStart = times.lastOrNull()?.takeIf { times.size > 1 }
        val ordered = fights.map { it.copy(mainCard = mainStart == null || it.startMillis >= mainStart) }
            // ESPN range du premier au dernier combat : le combat principal en tête, carte principale d'abord.
            .reversed()
        val venue = json.optJSONArray("venues")?.optJSONObject(0)?.optString("fullName")?.takeIf { it.isNotBlank() }
            ?: competitions.optJSONObject(0)?.optJSONObject("venue")?.let { v ->
                listOfNotNull(v.optString("fullName").takeIf { it.isNotBlank() }, v.optJSONObject("address")?.optString("city")?.takeIf { it.isNotBlank() })
                    .joinToString(", ").takeIf { it.isNotBlank() }
            }
        val state = json.optJSONObject("status")?.optJSONObject("type")?.optString("state")
        return MmaEvent(
            id = json.optString("id"),
            promotion = promotion,
            name = json.optString("name").trim(),
            startMillis = times.firstOrNull() ?: start,
            mainCardMillis = mainStart,
            fights = ordered,
            finished = state == "post",
            venue = venue,
        )
    }

    private fun fight(json: JSONObject): MmaFight? {
        val start = time(json.optString("date")) ?: return null
        val competitors = json.optJSONArray("competitors") ?: return null
        val ordered = (0 until competitors.length()).map { competitors.getJSONObject(it) }.sortedBy { it.optInt("order", 0) }
        val fighters = ordered.map { c ->
            val athlete = c.optJSONObject("athlete") ?: JSONObject()
            MmaFighter(
                name = athlete.optString("displayName").ifBlank { athlete.optString("fullName") }.trim(),
                shortName = athlete.optString("shortName").trim().ifBlank { athlete.optString("displayName").trim() },
                country = athlete.optJSONObject("flag")?.optString("alt")?.takeIf { it.isNotBlank() },
                flagUrl = athlete.optJSONObject("flag")?.optString("href")?.takeIf { it.startsWith("http") },
                record = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary")?.takeIf { it.isNotBlank() },
                espnId = c.optString("id").takeIf { it.isNotBlank() && it.all(Char::isDigit) },
            )
        }
        if (fighters.size != 2 || fighters.any { it.name.isBlank() }) return null
        val status = json.optJSONObject("status") ?: JSONObject()
        val type = status.optJSONObject("type") ?: JSONObject()
        val state = type.optString("state")
        val result = if (state == "post") {
            val winner = ordered.indexOfFirst { it.optBoolean("winner") }.takeIf { it >= 0 }
            val round = status.optInt("period", 0)
            val clock = status.optString("displayClock")
            val rounds = json.optJSONObject("format")?.optJSONObject("regulation")?.optInt("periods", 0) ?: 0
            // Allé jusqu'au bout de la dernière reprise : décision des juges.
            MmaResult(winner, round, clock, decision = rounds > 0 && round >= rounds && clock == "5:00")
        } else {
            null
        }
        return MmaFight(
            weightClass = json.optJSONObject("type")?.optString("abbreviation")?.takeIf { it.isNotBlank() }?.let(::frenchWeightClass),
            fighters = fighters,
            startMillis = start,
            mainCard = true,
            live = state == "in",
            result = result,
        )
    }

    /** Catégories de poids en français. */
    fun frenchWeightClass(name: String): String {
        val women = name.startsWith("Women's ", ignoreCase = true)
        val base = name.removePrefix("Women's ").removePrefix("women's ").trim()
        val french = WEIGHT_CLASSES[base.lowercase(Locale.ROOT)] ?: return name
        return if (women) "$french (femmes)" else french
    }

    private val WEIGHT_CLASSES = mapOf(
        "strawweight" to "Poids pailles",
        "flyweight" to "Poids mouches",
        "bantamweight" to "Poids coqs",
        "featherweight" to "Poids plumes",
        "lightweight" to "Poids légers",
        "welterweight" to "Poids mi-moyens",
        "middleweight" to "Poids moyens",
        "light heavyweight" to "Poids mi-lourds",
        "heavyweight" to "Poids lourds",
        "catchweight" to "Poids intermédiaire",
    )

    private fun time(text: String): Long? {
        if (text.isBlank()) return null
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time
            } catch (e: ParseException) {
                // Format suivant.
            }
        }
        return null
    }
}

/**
 * Programmes MMA du guide : organisation reconnue dans le titre ou le sous-titre (« MMA : Cage Warriors », « KSW 123 »,
 * « ARES 45. 1re partie ») ; magazines, temps forts, classiques, conférences et jiu-jitsu écartés.
 */
object MmaGuide {

    private val MARKS = Regex("\\p{Mn}+")

    private fun fold(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

    /** Ce qui n'est pas un gala diffusé : magazines, résumés, avant-programmes, autres disciplines. */
    private val NOT_AN_EVENT = Regex(
        """\b(format|magazine|temps forts|meilleurs moments|best of|classiques?|countdown|conference|soiree speciale|""" +
            """embedded|road to|the ultimate fighter|bjj|jiu-?jitsu|pesee|pesees|weigh-?ins?|face ?off|resume|highlights|""" +
            """documentaire|retro|portrait|clips?|replay|rediffusion)\b""",
    )

    private val MMA_WORDS = Regex("""\b(mma|arts martiaux mixtes)\b""")

    /**
     * Organisation d'un programme qui est un gala de MMA, `null` sinon. « MMA » seul, sans organisation connue :
     * [MmaPromotion.OTHER].
     */
    fun promotionOf(title: String, subtitle: String?, category: String?): MmaPromotion? {
        val text = fold(listOfNotNull(title, subtitle).joinToString(" "))
        if (NOT_AN_EVENT.containsMatchIn(text)) return null
        val known = MmaPromotion.entries.firstOrNull { promotion ->
            promotion.guideWords.any { word -> Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text) }
        }
        // « ARES » seul n'est un gala qu'avec le MMA cité quelque part (titre, sous-titre ou genre).
        if (known == MmaPromotion.ARES && "ares fighting" !in text && !MMA_WORDS.containsMatchIn(text + " " + fold(category.orEmpty()))) return null
        if (known != null) return known
        return MmaPromotion.OTHER.takeIf { MMA_WORDS.containsMatchIn(fold(title)) }
    }

    /** Nom affiché d'un gala trouvé dans le guide : « MMA : Cage Warriors » → « Cage Warriors ». */
    fun eventName(title: String): String = title.replace(Regex("""^\s*(mma|arts martiaux mixtes)\s*:\s*""", RegexOption.IGNORE_CASE), "").trim().ifBlank { title }
}
