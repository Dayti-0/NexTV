package tv.nextv.app.data.catalog

import kotlinx.coroutines.flow.first
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.app.data.db.HIDDEN_FOREIGN
import tv.nextv.app.data.db.HIDDEN_ITEM
import tv.nextv.app.data.db.HIDDEN_UHD
import tv.nextv.app.data.db.HiddenGroupDao
import tv.nextv.app.data.db.LineupRow
import tv.nextv.core.catalog.ChannelLineup
import tv.nextv.core.catalog.FranceRegions
import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.StreamDescriptor

/**
 * Filtres des réglages appliqués aux chaînes d'une playlist : chaînes rassemblées ou non, versions 4K masquées, une
 * seule édition régionale de France 3, chaînes françaises seulement. Rangés avec les catégories masquées, ils s'appliquent à toutes les listes
 * (accueil, guide, zapping, recherche) ; recalculés après chaque mise à jour de la playlist.
 */
class ChannelFilters(private val channelDao: ChannelDao, private val hiddenGroupDao: HiddenGroupDao) {

    /**
     * @param france3Region région de France 3 gardée (clé de [FranceRegions]) ; `null` : toutes les éditions régionales,
     *   ou aucune si les chaînes sont rassemblées (France 3 seule).
     * @param mergeChannels « Rassembler les chaînes » : versions de son réunies, ordre rangé des listes, séparateurs de
     *   la playlist masqués.
     * @param frenchOnly « Chaînes françaises seulement » : chaînes des autres pays masquées des listes (pas de la
     *   section Foot).
     */
    data class Settings(val hideUhd: Boolean, val france3Region: String?, val mergeChannels: Boolean, val frenchOnly: Boolean = false)

    suspend fun apply(sourceId: Long, hideUhd: Boolean, france3Region: String?, mergeChannels: Boolean = true, frenchOnly: Boolean = false) =
        apply(sourceId, Settings(hideUhd, france3Region, mergeChannels, frenchOnly))

    suspend fun apply(sourceId: Long, settings: Settings) {
        // Clés de version et ordre des listes : seulement s'ils ne sont pas déjà ceux du réglage.
        channelDao.applyGrouping(sourceId, settings.mergeChannels)
        replace(sourceId, HIDDEN_UHD, if (settings.hideUhd) setOf("") else emptySet())
        replace(sourceId, HIDDEN_FOREIGN, if (settings.frenchOnly) setOf("") else emptySet())
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

    /**
     * Chaînes déjà enregistrées rangées de nouveau selon les règles en vigueur de [ChannelLineup] (rang, catégorie par
     * thème, nom propre, nationalité), sans relire la playlist : une relecture en arrière-plan peut tarder (grosse
     * playlist, lecture qui l'interrompt), et les listes gardaient d'ici là les catégories du fournisseur.
     */
    suspend fun rearrange(sourceId: Long) = channelDao.rearrange(sourceId) { rows -> ChannelLineup.arrange(rows.map(::channelOf)) }

    /** Éditions régionales de France 3 de la playlist, par ordre alphabétique. */
    suspend fun france3Regions(sourceId: Long): List<FranceRegions.Region> =
        channelDao.franceTroisCandidates(sourceId)
            .mapNotNull { FranceRegions.regionOf(it.name) }
            .distinctBy { it.key }
            .sortedBy { it.key }

    /** Chaîne telle que le chargeur de playlist l'a donnée à ChannelLineup (ce qui sert au rangement). */
    private fun channelOf(row: LineupRow) = Channel(
        position = row.position,
        name = row.name,
        stream = StreamDescriptor(""),
        groupTitle = row.groupTitle,
        kind = ContentKind.LIVE,
        title = row.baseTitle ?: row.title,
        // Clé des chaînes rassemblées : la clé stable (sans les suffixes de son quand elles ne le sont pas).
        variantKey = when {
            row.variantKey.isEmpty() -> ""
            row.itemKey.startsWith(LIVE_PREFIX) -> row.itemKey.removePrefix(LIVE_PREFIX)
            else -> row.variantKey
        },
    )

    private companion object {
        const val LIVE_PREFIX = "live:"
    }

    /** Écrit seulement ce qui change : chaque écriture fait recharger toutes les listes. */
    private suspend fun replace(sourceId: Long, kind: String, titles: Set<String>) {
        if (hiddenGroupDao.observeTitles(sourceId, kind).first().toSet() == titles) return
        hiddenGroupDao.replace(sourceId, kind, titles)
    }
}
