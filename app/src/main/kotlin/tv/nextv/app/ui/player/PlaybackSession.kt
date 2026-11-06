package tv.nextv.app.ui.player

import tv.nextv.app.data.db.ChannelEntity
import tv.nextv.app.player.PlaybackRequest

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
}
