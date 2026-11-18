package tv.nextv.core.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import tv.nextv.core.io.ContentOpener
import tv.nextv.core.io.OpenRequest
import tv.nextv.core.io.OpenedContent
import tv.nextv.core.model.SourceLocation
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Outil (ignoré sans `EPG_PROBE`) : combien de chaînes d'une playlist chaque guide XMLTV associe, dans l'ordre où l'appli
 * les importe (le premier qui fournit une chaîne l'emporte). `EPG_PROBE` = fichier TSV (id, tvg-id, tvg-name, nom,
 * catégorie), `EPG_GUIDES` = guides (.xml ou .xml.gz) séparés par des virgules, `EPG_GROUPS` = catégories à détailler.
 */
class EpgProbeTool {
    @Test
    fun probe() {
        val refsFile = System.getenv("EPG_PROBE")
        assumeTrue(refsFile != null)
        val rows = File(refsFile!!).readLines().map { it.split('\t') }
        val groups = rows.associate { it[0].toLong() to it[4] }
        val refs = rows.map { EpgChannelRef(it[0].toLong(), it[1].ifBlank { null }, it[2].ifBlank { null }, it[3]) }
        val detail = System.getenv("EPG_GROUPS").orEmpty().split(',').filter { it.isNotBlank() }
        val mapping = HashMap<Long, Pair<String, String>>()
        val covered = HashSet<String>()
        for (path in System.getenv("EPG_GUIDES")!!.split(',')) {
            val file = File(path)
            val names = HashMap<String, String>()
            // Guide décompressé dans un fichier, comme le cache de l'appli (EpgLoader.importFile : second passage).
            val plain = if (path.endsWith(".gz")) File.createTempFile("guide", ".xml").also { out ->
                out.deleteOnExit()
                GZIPInputStream(file.inputStream()).use { input -> out.outputStream().use { input.copyTo(it) } }
            } else file
            val open = { plain.inputStream() }
            open().use { input ->
                val reader = XmltvReader(input) { KXmlParser() }
                reader.programmeFilter = { false }
                while (true) {
                    val item = reader.next() ?: break
                    if (item is XmltvChannel) names[EpgImporter.normalizeId(item.id)] = item.displayNames.firstOrNull() ?: item.id
                }
            }
            val remaining = refs.filter { it.channelId !in mapping }
            val loader = EpgLoader(object : ContentOpener {
                override fun supports(location: SourceLocation) = false
                override suspend fun open(location: SourceLocation, request: OpenRequest): OpenedContent = error("hors ligne")
            }, EpgImporter({ KXmlParser() }))
            val result = runBlocking { loader.importFile(plain, remaining, { }, covered) }
            covered += result.coveredEpgIds
            result.mapping.forEach { (id, epg) -> mapping[id] = file.name to (names[epg] ?: epg) }
            val byGroup = result.mapping.keys.groupingBy { groups.getValue(it) }.eachCount().entries.sortedByDescending { it.value }
            println("== ${file.name} : ${result.mapping.size} chaînes, ${result.programmeCount} programmes")
            byGroup.take(25).forEach { println("   ${it.value}\t${it.key}") }
            if (covered.size != result.coveredEpgIds.size) {
                result.mapping.filterKeys { groups.getValue(it) !in detail }.forEach { (id, epg) ->
                    println("      ${groups.getValue(id)}\t${refs.first { it.channelId == id }.name}\t→ ${names[epg] ?: epg}")
                }
            }
        }
        for (group in detail) {
            val inGroup = refs.filter { groups[it.channelId] == group }
            println("== $group : ${inGroup.count { it.channelId in mapping }} / ${inGroup.size}")
            inGroup.forEach { ref -> println("   ${mapping[ref.channelId]?.let { "${it.first}\t${it.second}" } ?: "-\t-"}\t${ref.name}") }
        }
    }
}
