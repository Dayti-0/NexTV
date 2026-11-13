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

    /**
     * Clé de version d'une chaîne : une édition régionale de France 3 porte le nom de son antenne ; les autres noms
     * sont ramenés à une seule écriture ([spelling]).
     */
    fun key(variantKey: String): String = FranceRegions.variantKey(variantKey) ?: spelling(variantKey)

    /**
     * Écritures d'un même nom d'une qualité à l'autre ramenées à une seule (mots d'une clé de version) :
     * - « + » collé au mot qui le précède : « TELETOON +1 » et « TELETOON+ 1 », « DISNEY CHANNEL+1 » et
     *   « DISNEY CHANNEL +1 », « PLANETE + » et « PLANETE+ », « CANAL PLUS » et « CANAL+ » ;
     * - « et » (« & ») omis : « CHASSE & PÊCHE » et « CHASSE ET PECHE » ;
     * - pluriels : « SEASON » et « SEASONS », « DISCOVERY INVESTIGATIONS » et « DISCOVERY INVESTIGATION » ;
     * - « (S) » de « Cinéma(s) » : « CANAL+ CINEMA(S) » et « CANAL+ CINEMAS » ;
     * - zéros de tête des numéros : « MATCH 05 » et « MATCH 5 ».
     */
    fun spelling(variantKey: String): String {
        if (variantKey.isEmpty()) return variantKey
        val words = ArrayList<String>()
        for (word in variantKey.split(' ')) {
            if (word.isEmpty()) continue
            // « +1 », « channel+1 » : le « + » est un mot à part, recollé ensuite au mot qui le précède.
            var start = 0
            for (index in word.indices) {
                if (word[index] != '+') continue
                if (index > start) words += word.substring(start, index)
                words += "+"
                start = index + 1
            }
            if (start < word.length) words += word.substring(start)
        }
        val kept = ArrayList<String>(words.size)
        for (written in words) {
            val word = if (written == "plus" && kept.isNotEmpty()) "+" else written
            val previous = kept.lastOrNull()
            when {
                word == "+" && previous != null -> kept[kept.lastIndex] = "$previous+"
                (word == "et" || word == "and") && previous != null -> Unit
                word == "s" && previous != null && previous.length >= PLURAL_MIN_LENGTH - 1 && previous.last().isLetter() -> Unit
                word.all { it.isDigit() } -> kept += word.trimStart('0').ifEmpty { "0" }
                else -> kept += singular(word)
            }
        }
        for ((written, replacement) in ALIASES) {
            val index = kept.windowed(written.size).indexOf(written)
            if (index < 0) continue
            repeat(written.size) { kept.removeAt(index) }
            kept.addAll(index, replacement)
        }
        return kept.joinToString(" ")
    }

    /** Autres noms d'une même chaîne dans une playlist (faute de frappe, nom long et nom court). */
    private val ALIASES: List<Pair<List<String>, List<String>>> = listOf(
        listOf("stangray") to listOf("stingray"),
        listOf("e", "entertainment") to listOf("e"),
    )

    /** Mot au singulier : « seasons » → « season », sans toucher aux mots courts ni à « paris », « campus », « boss ». */
    private fun singular(word: String): String =
        if (word.length >= PLURAL_MIN_LENGTH && word.last() == 's' && word.all { it.isLetter() } &&
            !word.endsWith("ss") && !word.endsWith("is") && !word.endsWith("us")
        ) {
            word.dropLast(1)
        } else {
            word
        }

    /** Lettres qu'un mot doit compter pour perdre son « s » final (« news », « docs », « kids » restent tels quels). */
    private const val PLURAL_MIN_LENGTH = 5

    /** Début de la clé d'un séparateur de la playlist (« ▀▄ FR ▀▄ CINEMA ▄▀ ») : jamais une version d'une vraie chaîne. */
    const val SEPARATOR_PREFIX = "--"

    /** Clé d'un séparateur : celle de son texte, précédée de [SEPARATOR_PREFIX]. */
    fun separatorKey(variantKey: String): String = if (variantKey.isEmpty()) SEPARATOR_PREFIX else "$SEPARATOR_PREFIX $variantKey"

    fun isSeparatorKey(variantKey: String): Boolean = variantKey.startsWith(SEPARATOR_PREFIX)

    /** Fin de la clé d'une version « Double audio » quand les versions de son ne sont pas rassemblées. */
    const val MULTI_AUDIO_SUFFIX = " double audio"

    /** Fin de la clé d'une version « Dolby Digital » quand les versions de son ne sont pas rassemblées. */
    const val DOLBY_SUFFIX = " dolby"

    /**
     * Clé d'une version quand les chaînes ne sont pas rassemblées (réglage « Rassembler les chaînes » coupé) : ses
     * versions double audio et Dolby sont des chaînes à part, comme la playlist les présente.
     */
    fun splitKey(variantKey: String, audio: Int): String = when {
        variantKey.isEmpty() || isSeparatorKey(variantKey) -> variantKey
        audio == QualityParser.AUDIO_MULTI -> variantKey + MULTI_AUDIO_SUFFIX
        audio == QualityParser.AUDIO_DOLBY -> variantKey + DOLBY_SUFFIX
        else -> variantKey
    }

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
            if (channel.kind == ContentKind.LIVE && channel.variantKey.isNotEmpty() && !isSeparatorKey(channel.variantKey)) keys += channel.variantKey
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
