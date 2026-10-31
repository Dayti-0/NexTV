package tv.nextv.core.epg

import tv.nextv.core.catalog.ContentClassifier

/**
 * Chaînes gratuites de la TNT et leur numéro (numérotation de l'Arcom depuis juin 2025), repérées
 * par leur nom (sans préfixe de pays ni mention de qualité : « FR| FRANCE 2 FHD » → 2) ou par leur
 * identifiant de guide (« France2.fr »). Sert à ranger le programme du soir comme un magazine télé.
 */
object TntChannels {

    private val NUMBERS: Map<String, Int> = buildMap {
        fun add(number: Int, vararg names: String) = names.forEach { put(it, number) }
        add(1, "tf1")
        add(2, "france2")
        add(3, "france3")
        add(4, "france4")
        add(5, "france5")
        add(6, "m6")
        add(7, "arte", "artefrance")
        add(8, "lcp", "lcpan", "lcppublicsenat", "lcpassembleenationale", "publicsenat")
        add(9, "w9")
        add(10, "tmc")
        add(11, "tfx")
        add(12, "gulli")
        add(13, "bfmtv", "bfm")
        add(14, "cnews")
        add(15, "lci")
        add(16, "franceinfo", "franceinfotv")
        add(17, "cstar")
        add(18, "t18", "cmitv")
        add(19, "novo19", "oftv", "ouestfrancetv")
        add(20, "tf1seriesfilms", "tf1seriefilms")
        add(21, "lequipe", "lequipetv", "lequipe21")
        add(22, "6ter")
        add(23, "rmcstory")
        add(24, "rmcdecouverte")
        add(25, "cherie25")
    }

    /** Numéro TNT de la chaîne, ou `null` si ce n'est pas une chaîne gratuite de la TNT. */
    fun number(name: String, epgChannelId: String? = null): Int? {
        val byName = NUMBERS[EpgImporter.normalizeName(ContentClassifier.cleanTitle(name))]
        if (byName != null) return byName
        // Identifiant de guide : « France2.fr », « TF1.fr »…
        val id = epgChannelId?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: return null
        return NUMBERS[EpgImporter.normalizeName(id)]
    }
}

/** Programmes d'une soirée sur une chaîne : la première partie (vers 21 h) et la suivante. */
data class Evening<T>(val first: T?, val second: T?)

/**
 * Choix des programmes de la soirée, comme dans un magazine télé : le programme principal qui
 * commence vers 21 h (les programmes courts — météo, résultats du loto — sont ignorés), puis celui
 * de la deuxième partie de soirée.
 */
object EveningPicker {

    /**
     * @param primeTime heure de référence de la soirée (21 h du jour voulu, en millisecondes).
     * @param programmes programmes de la chaîne autour de la soirée, dans n'importe quel ordre.
     */
    fun <T> pick(programmes: List<T>, primeTime: Long, start: (T) -> Long, stop: (T) -> Long): Evening<T> {
        val sorted = programmes.sortedBy(start)
        fun T.isMain() = stop(this) - start(this) >= MIN_MAIN_MILLIS
        val first = sorted.firstOrNull { it.isMain() && start(it) in (primeTime - EARLIEST_START)..(primeTime + LATEST_START) }
            // Sinon, le programme en cours à 21 h 10 (film commencé plus tôt, soirée sans programme court).
            ?: sorted.firstOrNull { start(it) <= primeTime + ON_AIR_OFFSET && stop(it) > primeTime + ON_AIR_OFFSET }
            ?: return Evening(null, null)
        val second = sorted.firstOrNull {
            it !== first && it.isMain() && start(it) >= stop(first) - TOLERANCE && start(it) < primeTime + LATEST_SECOND
        }
        return Evening(first, second)
    }

    private const val MINUTE = 60_000L

    /** En dessous, programme court (météo, tirage, programme de nuit). */
    private const val MIN_MAIN_MILLIS = 20 * MINUTE

    /** Première partie de soirée : entre 20 h 40 et 21 h 45. */
    private const val EARLIEST_START = 20 * MINUTE
    private const val LATEST_START = 45 * MINUTE
    private const val ON_AIR_OFFSET = 10 * MINUTE

    /** Deuxième partie : commence avant 1 h du matin. */
    private const val LATEST_SECOND = 4 * 60 * MINUTE
    private const val TOLERANCE = MINUTE
}
