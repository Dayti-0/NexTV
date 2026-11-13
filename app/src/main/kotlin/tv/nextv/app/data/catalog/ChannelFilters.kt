package tv.nextv.app.data.catalog

import kotlinx.coroutines.flow.first
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.app.data.db.HIDDEN_ITEM
import tv.nextv.app.data.db.HIDDEN_UHD
import tv.nextv.app.data.db.HiddenGroupDao
import tv.nextv.core.catalog.FranceRegions

/**
 * Filtres des réglages appliqués aux chaînes d'une playlist : chaînes rassemblées ou non, versions 4K masquées, une
 * seule édition régionale de France 3. Rangés avec les catégories masquées, ils s'appliquent à toutes les listes
 * (accueil, guide, zapping, recherche) ; recalculés après chaque mise à jour de la playlist.
 */
class ChannelFilters(private val channelDao: ChannelDao, private val hiddenGroupDao: HiddenGroupDao) {

    /**
     * @param france3Region région de France 3 gardée (clé de [FranceRegions]) ; `null` : toutes les éditions régionales,
     *   ou aucune si les chaînes sont rassemblées (France 3 seule).
     * @param mergeChannels « Rassembler les chaînes » : versions de son réunies, ordre rangé des listes, séparateurs de
     *   la playlist masqués.
     */
    data class Settings(val hideUhd: Boolean, val france3Region: String?, val mergeChannels: Boolean)

    suspend fun apply(sourceId: Long, hideUhd: Boolean, france3Region: String?, mergeChannels: Boolean = true) =
        apply(sourceId, Settings(hideUhd, france3Region, mergeChannels))

    suspend fun apply(sourceId: Long, settings: Settings) {
        // Clés de version et ordre des listes : seulement s'ils ne sont pas déjà ceux du réglage.
        channelDao.applyGrouping(sourceId, settings.mergeChannels)
        replace(sourceId, HIDDEN_UHD, if (settings.hideUhd) setOf("") else emptySet())
        val regions = channelDao.franceTroisCandidates(sourceId)
            .mapNotNull { row -> FranceRegions.regionOf(row.name)?.let { row.itemKey to it.key } }
        val hiddenRegions = when {
            settings.france3Region != null -> regions.filter { (_, key) -> key != settings.france3Region }
            settings.mergeChannels -> regions
            else -> emptyList()
        }
        val separators = if (settings.mergeChannels) channelDao.separatorKeys(sourceId) else emptyList()
        replace(sourceId, HIDDEN_ITEM, hiddenRegions.mapTo(HashSet()) { it.first } + separators)
    }

    /** Éditions régionales de France 3 de la playlist, par ordre alphabétique. */
    suspend fun france3Regions(sourceId: Long): List<FranceRegions.Region> =
        channelDao.franceTroisCandidates(sourceId)
            .mapNotNull { FranceRegions.regionOf(it.name) }
            .distinctBy { it.key }
            .sortedBy { it.key }

    /** Écrit seulement ce qui change : chaque écriture fait recharger toutes les listes. */
    private suspend fun replace(sourceId: Long, kind: String, titles: Set<String>) {
        if (hiddenGroupDao.observeTitles(sourceId, kind).first().toSet() == titles) return
        hiddenGroupDao.replace(sourceId, kind, titles)
    }
}
