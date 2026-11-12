package tv.nextv.app.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.nextv.app.data.db.ChannelDao
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.MeasuredQualityEntity
import tv.nextv.core.model.ContentKind
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.StreamQuality
import tv.nextv.core.source.Catchup
import tv.nextv.core.source.VodVideo

/**
 * Badge de qualité des films et épisodes dont le nom n'en annonce aucune. Deux sources :
 * - la lecture : la taille réelle de l'image, dès qu'un film ou un épisode est lu ([recordPlayback]) ;
 * - la fiche du film sur le serveur Xtream (`get_vod_info`), demandée pour les affiches affichées et à
 *   l'ouverture de sa fiche ([probe]), une seule fois par film (même quand le serveur ne la donne pas).
 * Le résultat est gardé ([MeasuredQualityEntity]) et repris à chaque actualisation de la playlist.
 */
class MeasuredQualities(
    private val dao: ChannelDao,
    private val source: suspend (Long) -> PlaylistSource?,
    private val vodVideo: suspend (PlaylistSource, String) -> VodVideo?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Films déjà demandés au serveur pendant cette session (réponse ou non). */
    private val asked = HashSet<String>()
    private val lock = Mutex()

    /** Image lue : sa taille donne la qualité d'un film ou d'un épisode qui n'en annonçait pas. `true` si retenue. */
    suspend fun recordPlayback(channel: ChannelEntity, width: Int?, height: Int?): Boolean {
        if (channel.kind == ContentKind.LIVE.name || channel.quality != null) return false
        val quality = StreamQuality.fromSize(width, height) ?: return false
        save(channel, quality, fromPlayback = true)
        return true
    }

    /**
     * Film sans qualité annoncée : sa fiche sur le serveur Xtream, si elle n'a jamais été lue. Rend la qualité
     * trouvée (déjà enregistrée et posée sur la ligne), `null` sinon.
     */
    suspend fun probe(channel: ChannelEntity): StreamQuality? {
        if (channel.kind != ContentKind.MOVIE.name || channel.quality != null) return null
        val streamId = Catchup.streamId(channel.streamUrl) ?: return null
        val key = "${channel.sourceId}|${channel.streamUrl}"
        lock.withLock { if (!asked.add(key)) return null }
        if (dao.measuredQuality(channel.sourceId, channel.streamUrl) != null) return null
        val playlist = source(channel.sourceId) ?: return null
        // Pas de réponse (réseau, pas un compte Xtream) : rien n'est gardé, on redemandera une autre fois.
        val video = vodVideo(playlist, streamId) ?: return null
        val quality = StreamQuality.fromSize(video.width, video.height)
        if (quality == null) {
            // Le serveur ne la donne pas : noté, pour ne pas lui redemander à chaque affichage.
            dao.insertMeasuredQuality(MeasuredQualityEntity(channel.sourceId, channel.streamUrl, "", fromPlayback = false, measuredAt = now()))
            return null
        }
        save(channel, quality, fromPlayback = false)
        return quality
    }

    private suspend fun save(channel: ChannelEntity, quality: StreamQuality, fromPlayback: Boolean) {
        dao.insertMeasuredQuality(MeasuredQualityEntity(channel.sourceId, channel.streamUrl, quality.name, fromPlayback, now()))
        dao.fillQuality(channel.id, quality.name, quality.rank)
    }
}
