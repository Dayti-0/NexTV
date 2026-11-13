package tv.nextv.core.catalog

import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind

/**
 * Regroupement des versions d'une chaîne que la playlist n'écrit pas de la même façon d'une qualité à l'autre :
 * la clé de version ([QualityParser.Result.variantKey]) est ramenée à une seule écriture.
 */
object LiveVariants {

    /** Mot ajouté ou omis d'un nom à l'autre sans changer de chaîne (« LE FIGARO TV IDF », « LE FIGARO IDF »). */
    private const val GENERIC = "tv"

    /** Lettres qu'une clé doit garder sans « tv » pour désigner une chaîne (« fr tv » ne désigne pas « fr »). */
    private const val MIN_KEY_LENGTH = 4

    /** Clé de version d'une chaîne : une édition régionale de France 3 porte le nom de son antenne. */
    fun key(variantKey: String): String = FranceRegions.variantKey(variantKey) ?: variantKey

    /** La clé sans le mot « tv », ou `null` si elle ne le contient pas ou ne désigne plus rien sans lui. */
    fun withoutGeneric(variantKey: String): String? {
        if (GENERIC !in variantKey) return null
        val words = variantKey.split(' ')
        val kept = words.filter { it != GENERIC }
        if (kept.size == words.size) return null
        return kept.joinToString(" ").takeIf { it.length >= MIN_KEY_LENGTH }
    }

    /**
     * Chaînes dont les noms ne diffèrent que par « TV » (« LE FIGARO TV IDF » en HD et « LE FIGARO IDF » en FHD) :
     * elles prennent la même clé, celle sans « tv ». Une chaîne seule à porter son nom garde sa clé (« BFM TV »
     * sans « BFM » à côté ne change pas, ses favoris non plus).
     */
    fun unify(channels: List<Channel>): List<Channel> {
        val keys = HashSet<String>()
        for (channel in channels) {
            if (channel.kind == ContentKind.LIVE && channel.variantKey.isNotEmpty()) keys += channel.variantKey
        }
        // Clé sans « tv » → clés qui s'y ramènent.
        val shared = HashMap<String, MutableSet<String>>()
        for (key in keys) {
            val base = withoutGeneric(key) ?: continue
            shared.getOrPut(base) { HashSet() } += key
        }
        val renamed = HashMap<String, String>()
        for ((base, written) in shared) {
            // Une autre écriture existe : la chaîne sans « tv », ou « tv » placé ailleurs.
            if (base in keys || written.size > 1) written.forEach { renamed[it] = base }
        }
        if (renamed.isEmpty()) return channels
        return channels.map { channel ->
            val key = renamed[channel.variantKey]
            if (key != null && channel.kind == ContentKind.LIVE) channel.copy(variantKey = key) else channel
        }
    }

    /**
     * Versions de même qualité d'une chaîne dans la même catégorie, dont l'une annonce un meilleur son
     * (« CANAL+ CINEMA(S) (DOUBLE AUDIO) », « … DOLBY DIGITAL », « … ») : la version au meilleur son prend la place
     * de la première dans la playlist, pour être celle affichée et ouverte (double audio, puis Dolby, puis les autres).
     * Les places des autres chaînes ne changent pas.
     */
    fun preferBetterAudio(channels: List<Channel>): List<Channel> {
        val groups = HashMap<Triple<String, Any?, String?>, MutableList<Channel>>()
        for (channel in channels) {
            if (channel.kind != ContentKind.LIVE || channel.variantKey.isEmpty()) continue
            groups.getOrPut(Triple(channel.variantKey, channel.quality, channel.groupTitle)) { ArrayList(2) } += channel
        }
        val moved = HashMap<Int, Int>()
        for (group in groups.values) {
            if (group.size < 2) continue
            val ranks = group.associate { it.position to QualityParser.parse(it.name).audioRank }
            if (ranks.values.all { it == 0 }) continue
            val places = group.map { it.position }.sorted()
            val ordered = group.sortedWith(compareByDescending<Channel> { ranks.getValue(it.position) }.thenBy { it.position })
            ordered.forEachIndexed { index, channel -> if (channel.position != places[index]) moved[channel.position] = places[index] }
        }
        if (moved.isEmpty()) return channels
        return channels.map { channel -> moved[channel.position]?.let { channel.copy(position = it) } ?: channel }
            .sortedBy { it.position }
    }
}
