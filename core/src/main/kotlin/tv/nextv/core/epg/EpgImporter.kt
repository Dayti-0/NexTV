package tv.nextv.core.epg

import org.xmlpull.v1.XmlPullParser
import tv.nextv.core.catalog.ContentClassifier
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
 */
data class EpgImportResult(
    val mapping: Map<Long, String>,
    val programmeCount: Int,
    val skippedProgrammes: Int,
    val coveredEpgIds: Set<String>,
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
        val covered = HashSet<String>()
        val batch = ArrayList<Programme>(options.batchSize)
        // Programme sans heure de fin, par chaîne : sa fin sera le début du programme suivant.
        val openEnded = HashMap<String, XmltvProgramme>()
        var written = 0
        var items = 0

        suspend fun emit(programme: XmltvProgramme, stop: Long) {
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
            )
            covered += id
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
                    val currentMapping = mapping ?: match(channels, xmltvChannels, excludedEpgIds).also {
                        mapping = it
                        relevant = it.values.toSet()
                        // Les programmes des chaînes absentes de la playlist (souvent la majorité) ne sont plus analysés.
                        reader.programmeFilter = { channelId -> normalizeId(channelId) in relevant }
                    }
                    if (currentMapping.isEmpty()) continue
                    val id = normalizeId(item.channelId)
                    if (id !in relevant) continue
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
        return EpgImportResult(finalMapping, written, reader.skippedProgrammes, covered)
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
        for (xmltv in xmltvChannels) {
            val id = normalizeId(xmltv.id)
            if (id in excluded) continue
            ids += id
            for (name in xmltv.displayNames + xmltv.id) {
                byName.putIfAbsent(normalizeName(name), id)
            }
        }
        for (channel in channels) {
            val byId = channel.tvgId?.let(::normalizeId)?.takeIf { it in ids }
            val names = listOfNotNull(channel.tvgName, channel.name)
            // Nom exact, puis sans préfixe de langue ni mention de qualité (« FR| LIGUE 1+ 5 FHD » → « Ligue 1+ 5 »).
            val matched = byId
                ?: names.firstNotNullOfOrNull { byName[normalizeName(it)] }
                ?: names.firstNotNullOfOrNull { byName[normalizeName(ContentClassifier.cleanTitle(it))] }
            if (matched != null) result[channel.channelId] = matched
        }
        return result
    }

    companion object {
        private const val CANCELLATION_CHECK_INTERVAL = 500
        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}]+")
        private val DIACRITICS = Regex("\\p{Mn}+")

        fun normalizeId(id: String): String = id.trim().lowercase()

        /** Minuscules, sans accents ni ponctuation ; « + » devient « plus » (« Canal+ » = « CanalPlus », « Ligue 1+ »). */
        fun normalizeName(name: String): String {
            val decomposed = Normalizer.normalize(name.trim().lowercase().replace("+", "plus"), Normalizer.Form.NFD)
            return NON_ALPHANUMERIC.replace(DIACRITICS.replace(decomposed, ""), "")
        }
    }
}
