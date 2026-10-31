package tv.nextv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.data.db.HeaderCodec
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.model.StreamFormat
import tv.nextv.core.model.StreamIssue
import tv.nextv.core.model.StreamSupport
import tv.nextv.core.repository.SourceRepository

/** Flux de lecture d'une chaîne enregistrée. */
fun ChannelEntity.toStream() = StreamDescriptor(
    url = streamUrl,
    headers = HeaderCodec.decode(streamHeaders),
    format = StreamFormat.entries.firstOrNull { it.name == streamFormat } ?: StreamFormat.UNKNOWN,
    support = StreamSupport.entries.firstOrNull { it.name == streamSupport } ?: StreamSupport.SUPPORTED,
    issues = streamIssues.split(',').mapNotNull { name -> StreamIssue.entries.firstOrNull { it.name == name } }.toSet(),
)

/**
 * Aperçu vidéo de l'accueil : un seul lecteur, au son réduit (réglages), pour la chaîne sélectionnée.
 * À libérer avant de lancer une chaîne en plein écran : beaucoup d'abonnements n'autorisent
 * qu'une connexion à la fois.
 */
@OptIn(UnstableApi::class)
class ChannelPreviewPlayer(
    private val factory: PlayerFactory,
    private val sources: SourceRepository,
) {
    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)

    /** Lecteur de l'aperçu, `null` sans aperçu (aucune chaîne, flux illisible ou en erreur). */
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private var channelId: Long? = null

    /** Volume de l'aperçu (0 : sans le son), appliqué aussi à l'aperçu en cours. Fil principal. */
    var volume: Float = 0f
        set(value) {
            field = value
            mutablePlayer.value?.volume = value
        }

    /** Montre [channel] (sans effet si c'est déjà la chaîne de l'aperçu). Fil principal. */
    suspend fun show(channel: ChannelEntity) {
        if (channel.id == channelId && mutablePlayer.value != null) return
        stop()
        val stream = channel.toStream()
        if (stream.support == StreamSupport.UNSUPPORTED || stream.format == StreamFormat.UDP) return
        val source = sources.getSource(channel.sourceId)
        val request = PlaybackRequest(
            stream = stream,
            userAgent = source?.userAgent,
            credentials = sources.credentialsFor(channel.sourceId),
            credentialsOrigin = (source?.location as? SourceLocation.Remote)?.url,
        )
        channelId = channel.id
        mutablePlayer.value = factory.create(request, preview = true).apply {
            volume = this@ChannelPreviewPlayer.volume
            addListener(object : Player.Listener {
                // Flux en erreur : l'aperçu s'efface (le logo reste affiché).
                override fun onPlayerError(error: PlaybackException) = stop()
            })
            setMediaItem(factory.mediaItem(stream))
            playWhenReady = true
            prepare()
        }
    }

    /** Arrête l'aperçu et ferme sa connexion. Fil principal. */
    fun stop() {
        channelId = null
        mutablePlayer.value?.release()
        mutablePlayer.value = null
    }
}
