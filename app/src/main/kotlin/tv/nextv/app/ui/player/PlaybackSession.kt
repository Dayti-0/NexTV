package tv.nextv.app.ui.player

import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.player.PlaybackRequest
import tv.nextv.core.model.PlaylistSource
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.source.Mirrors

/**
 * Ce qui est en cours de lecture dans l'écran du lecteur, partagé par ses parties (moteur, qualité,
 * différé et replay, reprise des films) : chaîne, version lue, flux demandé et ses serveurs.
 */
internal class PlaybackSession {
    /** Chaîne affichée (meilleure version), base du zapping. */
    var channel: ChannelEntity? = null

    /** Ligne choisie dans les listes (une version précise : celle de sa catégorie). */
    var listed: ChannelEntity? = null

    /** Version effectivement lue (peut différer de [channel] après un changement de qualité). */
    var playing: ChannelEntity? = null

    /** Flux à lire : adresse, en-têtes, User-Agent et identifiants de la playlist. */
    var request: PlaybackRequest? = null

    /** Seconde tentative en HLS déjà faite (URL sans extension qui sert en réalité du HLS). */
    var triedHlsFallback = false

    /** Flux lu comme du HLS (format annoncé, ou seconde tentative). */
    var playingAsHls = false

    /** Serveurs de la playlist en cours (principal puis secours), vide sans serveur de secours. */
    var serverRoots: List<String> = emptyList()

    /** Même flux sur les autres serveurs, essayés si celui-ci ne répond pas. */
    var backupUrls: List<String> = emptyList()

    /** Position de départ d'une reprise de film ou d'épisode ; 0 : début (ou direct). */
    var startPositionMs = 0L

    /**
     * Flux rouvert juste après la fermeture de l'aperçu de l'accueil : beaucoup d'abonnements refusent encore la
     * nouvelle connexion un instant. Une erreur avant la première image est alors réessayée une fois, sur la même
     * version, avant de passer à une qualité inférieure.
     */
    var retryAfterPreview = false
}

/** Serveur principal de la playlist puis ses serveurs de secours ; vide sans serveur de secours. */
internal fun serverRootsOf(source: PlaylistSource): List<String> {
    if (source.mirrors.isEmpty()) return emptyList()
    val main = (source.location as? SourceLocation.Remote)?.url?.let(Mirrors::normalize)
    return (listOfNotNull(main) + source.mirrors).distinct()
}
