package tv.nextv.core.epg

import org.xmlpull.v1.XmlPullParser
import tv.nextv.core.catalog.ContentClassifier
import tv.nextv.core.catalog.FranceRegions
import tv.nextv.core.model.Programme
import java.io.InputStream
import java.text.Normalizer

/** Chaîne de la playlist à associer au guide (identifiant de ligne en base + noms). */
data class EpgChannelRef(
    val channelId: Long,
    val tvgId: String?,
    val tvgName: String?,
    val name: String,
)

/** Destination des programmes importés, par lots. */
fun interface ProgrammeSink {
    suspend fun write(batch: List<Programme>)
}

data class EpgImportOptions(
    /** Programmes terminés depuis plus longtemps que cette durée : ignorés (le replay remonte à la veille). */
    val pastWindowMillis: Long = 2 * 24 * HOUR,
    /** Programmes commençant au-delà de cette durée : ignorés. */
    val futureWindowMillis: Long = 8 * 24 * HOUR,
    val batchSize: Int = 1000,
) {
    private companion object {
        const val HOUR = 3_600_000L
    }
}

/**
 * @property mapping association chaîne de la playlist → identifiant de chaîne XMLTV.
 * @property coveredEpgIds identifiants XMLTV pour lesquels des programmes ont été importés.
 * @property guideOnlyChannels chaînes de sport du guide sans chaîne de la playlist associée, dont les
 * matchs de football ont tout de même été importés (identifiant → nom) : la section Foot peut dire
 * sur quelle chaîne passe un match, même absente de la playlist.
 */
data class EpgImportResult(
    val mapping: Map<Long, String>,
    val programmeCount: Int,
    val skippedProgrammes: Int,
    val coveredEpgIds: Set<String>,
    val guideOnlyChannels: Map<String, String> = emptyMap(),
)

/**
 * Importe un guide XMLTV pour les chaînes d'une playlist.
 *
 * Association, par ordre de priorité : `tvg-id` identique à l'identifiant XMLTV (casse ignorée),
 * puis `tvg-name`, puis nom de la chaîne comparés aux `display-name` (accents, casse et
 * ponctuation ignorés). Seuls les programmes des chaînes associées et compris dans la fenêtre
 * temporelle sont conservés, ce qui borne le volume stocké.
 */
class EpgImporter(
    private val parserFactory: () -> XmlPullParser,
    private val options: EpgImportOptions = EpgImportOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param excludedEpgIds identifiants déjà couverts par un guide précédent (plusieurs guides
     * pour une même playlist : le premier qui fournit une chaîne l'emporte).
     */
    suspend fun import(
        input: InputStream,
        channels: List<EpgChannelRef>,
        sink: ProgrammeSink,
        excludedEpgIds: Set<String> = emptySet(),
        checkCancelled: () -> Unit = {},
    ): EpgImportResult {
        val reader = XmltvReader(input, parserFactory)
        val now = clock()
        val windowStart = now - options.pastWindowMillis
        val windowEnd = now + options.futureWindowMillis

        val xmltvChannels = ArrayList<XmltvChannel>()
        var mapping: Map<Long, String>? = null
        var relevant: Set<String> = emptySet()
        // Chaînes de sport du guide sans chaîne de la playlist : seuls leurs matchs de football sont gardés.
        var sports: Map<String, String> = emptyMap()
        val sportsKept = HashMap<String, String>()
        val covered = HashSet<String>()
        val batch = ArrayList<Programme>(options.batchSize)
        // Programme sans heure de fin, par chaîne : sa fin sera le début du programme suivant.
        val openEnded = HashMap<String, XmltvProgramme>()
        var written = 0
        var items = 0

        suspend fun emit(programme: XmltvProgramme, stop: Long, guideOnly: Boolean = false) {
            if (stop <= windowStart || programme.startMillis >= windowEnd) return
            val id = normalizeId(programme.channelId)
            batch += Programme(
                epgChannelId = id,
                startMillis = programme.startMillis,
                stopMillis = stop,
                title = programme.title,
                subtitle = programme.subtitle,
                description = programme.description,
                category = programme.category,
                year = programme.year,
                imageUrl = programme.iconUrl,
            )
            if (guideOnly) sportsKept[id] = sports.getValue(id) else covered += id
            if (batch.size >= options.batchSize) {
                sink.write(batch.toList())
                written += batch.size
                batch.clear()
            }
        }

        while (true) {
            val item = reader.next() ?: break
            if (++items % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
            when (item) {
                is XmltvChannel -> if (mapping == null) xmltvChannels += item
                is XmltvProgramme -> {
                    if (mapping == null) {
                        val found = match(channels, xmltvChannels, excludedEpgIds)
                        mapping = found
                        relevant = found.values.toSet()
                        sports = xmltvChannels.filter(::isSportsChannel)
                            .associate { channel -> normalizeId(channel.id) to (channel.displayNames.firstOrNull() ?: channel.id) }
                            .filterKeys { id -> id !in relevant && id !in excludedEpgIds }
                        // Les programmes des chaînes absentes de la playlist (souvent la majorité) ne sont plus analysés.
                        reader.programmeFilter = { channelId -> normalizeId(channelId).let { it in relevant || it in sports } }
                    }
                    val id = normalizeId(item.channelId)
                    if (id !in relevant) {
                        val stop = item.stopMillis
                        if (id in sports && stop != null && isFootball(item)) emit(item, stop, guideOnly = true)
                        continue
                    }
                    openEnded.remove(id)?.let { previous -> emit(previous, item.startMillis) }
                    if (item.stopMillis == null) openEnded[id] = item else emit(item, item.stopMillis)
                }
            }
        }
        // Un dernier programme sans heure de fin ni programme suivant est ignoré : aucune durée n'est inventée.
        if (batch.isNotEmpty()) {
            sink.write(batch.toList())
            written += batch.size
        }
        val finalMapping = (mapping ?: match(channels, xmltvChannels, excludedEpgIds))
            .filterValues { it in covered }
        return EpgImportResult(finalMapping, written, reader.skippedProgrammes, covered, sportsKept)
    }

    /** Chaîne de sport française du guide (beIN, Ligue 1+, DAZN, Canal+, RMC Sport, L'Équipe…). */
    private fun isSportsChannel(channel: XmltvChannel): Boolean {
        if (!channel.id.endsWith(".fr", ignoreCase = true)) return false
        val names = (channel.displayNames + channel.id).joinToString(" ") { normalizeName(it) }
        return SPORTS_HINTS.any { it in names }
    }

    private fun isFootball(programme: XmltvProgramme): Boolean {
        val text = "${programme.title} ${programme.category.orEmpty()}".lowercase()
        return "foot" in text || "soccer" in text
    }

    /**
     * Associe les chaînes de la playlist aux chaînes XMLTV déclarées. Si le guide ne déclare
     * aucune chaîne (fichier non conforme), les `tvg-id` sont pris tels quels.
     */
    internal fun match(
        channels: List<EpgChannelRef>,
        xmltvChannels: List<XmltvChannel>,
        excluded: Set<String>,
    ): Map<Long, String> {
        val result = HashMap<Long, String>()
        if (xmltvChannels.isEmpty()) {
            for (channel in channels) {
                val id = channel.tvgId?.let(::normalizeId)?.takeIf { it.isNotEmpty() && it !in excluded } ?: continue
                result[channel.channelId] = id
            }
            return result
        }
        val ids = HashSet<String>()
        val byName = HashMap<String, String>()
        // Même nom dans plusieurs pays (« Canal+ » français et suisse) : la chaîne française d'abord.
        for (xmltv in xmltvChannels.sortedBy { !it.id.endsWith(".fr", ignoreCase = true) }) {
            val id = normalizeId(xmltv.id)
            if (id in excluded) continue
            ids += id
            // Identifiant avec et sans domaine (« Ligue1Plus5.fr » → « Ligue1Plus5 »).
            for (name in xmltv.displayNames + xmltv.id + xmltv.id.substringBeforeLast('.')) {
                byName.putIfAbsent(normalizeName(name), id)
            }
        }
        // Rapprochement souple, en dernier recours (« LIGUE 1+ MATCH 05 » → « Ligue 1+ 5 », « L'EQUIPE » →
        // « La chaine l'Équipe », « TV5 NEWS » → « TV5 Monde Info »).
        val loose = lazy(LazyThreadSafetyMode.NONE) {
            val known = xmltvChannels.filter { normalizeId(it.id) !in excluded }
            ChannelNames.Index(
                names = known.groupBy({ normalizeId(it.id) }) { it.displayNames.ifEmpty { listOf(it.id.substringBeforeLast('.')) } }
                    .mapValues { (_, names) -> names.flatten() },
                // Identifiant sans domaine : souvent l'ancien nom (« LEquipe21 », « PlaneteJustice »).
                formerNames = known.groupBy({ normalizeId(it.id) }) { it.id.substringBeforeLast('.') },
            )
        }
        // Éditions régionales de France 3 du guide, par antenne : les playlists les écrivent de bien des façons
        // (« NORD PDC », « BNORMANDIE », « POITOUCHAR », « PROV ALPES »), le guide aussi (« France3BasseNormandie »).
        val regional = lazy(LazyThreadSafetyMode.NONE) {
            val antennas = HashMap<String, String>()
            for (xmltv in xmltvChannels.sortedBy { !it.id.endsWith(".fr", ignoreCase = true) }) {
                val id = normalizeId(xmltv.id).takeIf { it !in excluded } ?: continue
                for (name in xmltv.displayNames + xmltv.id.substringBeforeLast('.')) {
                    FranceRegions.regionOfGuide(name)?.let { antennas.putIfAbsent(it.key, id) }
                }
            }
            antennas
        }
        for (channel in channels) {
            val names = listOfNotNull(channel.tvgName, channel.name)
            // Chaîne d'un autre pays (« |DE| DAZN 1 », « |AR| BEIN SPORTS 1 ») : seulement une chaîne de ce pays dans
            // le guide, jamais la française du même nom (autre programme). Pays absent du guide : pas de programme.
            val domains = names.firstNotNullOfOrNull(::countryDomains)
            if (domains != null && domains.isEmpty()) continue
            val allowed = { id: String? -> id?.takeIf { domains == null || it.substringAfterLast('.', "") in domains } }
            val byId = allowed(channel.tvgId?.let(::normalizeId)?.takeIf { it in ids })
            val region = lazy(LazyThreadSafetyMode.NONE) { names.firstNotNullOfOrNull(FranceRegions::regionOf) }
            // Nom exact, puis sans préfixe de langue ni mention de qualité (« FR| LIGUE 1+ 5 FHD » → « Ligue 1+ 5 »).
            val matched = byId
                ?: names.firstNotNullOfOrNull { allowed(byName[normalizeName(it)]) }
                ?: names.firstNotNullOfOrNull { allowed(byName[normalizeName(ContentClassifier.cleanTitle(it))]) }
                ?: region.value?.let { allowed(regional.value[it.key]) }
                ?: names.firstNotNullOfOrNull { allowed(loose.value.find(ContentClassifier.cleanTitle(it))) }
                // Édition régionale absente du guide : le programme national de France 3, le même hors décrochages.
                ?: region.value?.let { allowed(byName[FRANCE_3]) }
            if (matched != null) result[channel.channelId] = matched
        }
        return result
    }

    companion object {
        /**
         * Version des règles d'import (association des chaînes, programmes gardés). Un guide importé
         * avec une version antérieure est réimporté dès le lancement suivant, sans attendre sa
         * réimportation quotidienne.
         */
        const val VERSION = 8

        /** Nom du guide de la chaîne nationale France 3 ([normalizeName]). */
        private const val FRANCE_3 = "france3"

        private const val CANCELLATION_CHECK_INTERVAL = 500
        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}]+")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val LEADING_ZEROS = Regex("(?<!\\d)0+(?=\\d)")
        private val SPORTS_HINTS = listOf("sport", "foot", "ligue1", "bein", "dazn", "canalplus", "rmc", "equipe", "primevideo", "amazon")

        fun normalizeId(id: String): String = id.trim().lowercase()

        /** Pays écrit en tête du nom et suivi d'un séparateur (« |DE| », « ES: », « [UK] ») : pas « MA CHAINE ». */
        private val COUNTRY_PREFIX = Regex("""^[\s|\[(]*([A-Z]{2,3})\s*(?:[|:\])]|-\s)""")

        /**
         * Suffixes des identifiants du guide permis pour une chaîne marquée d'un pays étranger ; `null` : chaîne
         * française ou sans pays (aucune limite). Belgique et Suisse gardent aussi les chaînes françaises (AB3,
         * ABXplore, chaînes reprises telles quelles). Pays inconnu du guide : liste vide.
         */
        internal fun countryDomains(name: String): Set<String>? {
            val code = COUNTRY_PREFIX.find(name)?.groupValues?.get(1) ?: return null
            return when (code) {
                "FR", "FRA" -> null
                "BE", "CH", "LU" -> setOf(code.lowercase(), "fr")
                "DE", "AT", "GER" -> setOf("de")
                "UK", "GB", "EN", "ENG" -> setOf("uk")
                "US", "USA" -> setOf("us")
                "CA" -> setOf("ca")
                "ES", "ESP" -> setOf("es")
                "PT" -> setOf("pt")
                "BR" -> setOf("br")
                "AR", "ARA", "DZ", "MA", "TN" -> setOf("ar")
                "SN" -> setOf("sn")
                "CI" -> setOf("ci")
                "CN" -> setOf("cn")
                "AF" -> setOf("af")
                "IT", "ITA", "PL", "TR", "TUR", "NL", "RU", "RO", "GR", "SE", "DK", "NO", "FI", "IN", "HR", "BA", "AL", "CZ",
                "AM", "RS", "HU", "BG", "UA", "IL", "IR", "PK", "JP", "KR", "MX", "LAT", "SW", "SWE", "BH", "ZA", "SI", "MK", "ME", "SK",
                "LT", "LV", "EE", "IS", "EX", "YU",
                -> emptySet()
                // Autres mentions (« |INT| », « VIP »…) : pas un pays connu, aucune limite.
                else -> null
            }
        }

        /**
         * Minuscules, sans accents ni ponctuation ; « + » devient « plus » (« Canal+ » = « CanalPlus »,
         * « Ligue 1+ »), « & » devient « et » (« Science & Vie TV ») et les zéros de tête des numéros
         * disparaissent (« Ligue 1+ 05 » = « Ligue 1+ 5 »).
         */
        fun normalizeName(name: String): String {
            val decomposed = Normalizer.normalize(name.trim().lowercase().replace("+", "plus").replace("&", "et"), Normalizer.Form.NFD)
            return NON_ALPHANUMERIC.replace(LEADING_ZEROS.replace(DIACRITICS.replace(decomposed, ""), ""), "")
        }
    }
}
