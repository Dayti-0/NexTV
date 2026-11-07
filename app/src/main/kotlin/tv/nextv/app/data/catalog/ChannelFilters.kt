package tv.nextv.app.data.catalog

import kotlinx.coroutines.flow.first
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.app.data.db.HIDDEN_ITEM
import tv.nextv.app.data.db.HIDDEN_UHD
import tv.nextv.app.data.db.HiddenGroupDao
import tv.nextv.core.catalog.FranceRegions

/**
 * Filtres des réglages appliqués aux chaînes d'une playlist : versions 4K masquées, et une seule
 * édition régionale de France 3. Rangés avec les catégories masquées, ils s'appliquent à toutes les
 * listes (accueil, guide, zapping, recherche) ; recalculés après chaque mise à jour de la playlist.
 */
class ChannelFilters(private val channelDao: ChannelDao, private val hiddenGroupDao: HiddenGroupDao) {

    suspend fun apply(sourceId: Long, hideUhd: Boolean, france3Region: String?) {
        replace(sourceId, HIDDEN_UHD, if (hideUhd) setOf("") else emptySet())
        val otherRegions = if (france3Region == null) {
            emptySet()
        } else {
            channelDao.franceTroisCandidates(sourceId)
                .filter { FranceRegions.regionOf(it.name)?.key?.let { key -> key != france3Region } == true }
                .mapTo(HashSet()) { it.itemKey }
        }
        replace(sourceId, HIDDEN_ITEM, otherRegions)
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
