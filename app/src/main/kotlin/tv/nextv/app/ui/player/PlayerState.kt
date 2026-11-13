package tv.nextv.app.ui.player

import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.guide.GuideProgramme
import tv.nextv.app.ui.home.Category

/** Piste audio ou de sous-titres proposée dans les options du lecteur. */
data class TrackChoice(val groupIndex: Int, val trackIndex: Int, val label: String, val language: String?, val selected: Boolean)

/** Pistes du flux en cours : plusieurs langues audio, sous-titres. */
data class PlayerTracks(val audio: List<TrackChoice> = emptyList(), val subtitles: List<TrackChoice> = emptyList()) {
    val subtitlesOff: Boolean get() = subtitles.none { it.selected }
}

/** Programme affiché dans le bandeau du lecteur. */
data class PlayerProgramme(val title: String, val startMillis: Long, val stopMillis: Long)

/** Match diffusé par le programme en cours (football-data.org) : affiche et score dans le bandeau. */
data class PlayerMatch(
    val home: String,
    val away: String,
    val homeCrest: String?,
    val awayCrest: String?,
    /** Score du moment, `null` avant le coup d'envoi. */
    val score: String?,
    val live: Boolean,
    /** Minute de jeu (« 81' », « 45+2' ») pendant le match, si elle est connue. */
    val clock: String? = null,
    /** Mi-temps. */
    val halfTime: Boolean = false,
)

data class PlayerUiState(
    val channelName: String = "",
    val channelNumber: String? = null,
    val logoUrl: String? = null,
    val sourceName: String = "",
    /** Chaîne en direct (bandeau, zapping, menu) ou film / épisode (commandes de lecture). */
    val isLive: Boolean = true,
    val buffering: Boolean = true,
    val error: String? = null,
    /** Limites connues du flux (pas de proxy pour RTSP/UDP…). */
    val notices: List<String> = emptyList(),
    val now: PlayerProgramme? = null,
    val next: PlayerProgramme? = null,
    val match: PlayerMatch? = null,
    /** Qualité de la version en cours de lecture (« FHD »…), si la playlist l'indique. */
    val quality: String? = null,
    /** Message après un changement automatique de qualité. */
    val qualityNotice: String? = null,
    /** Débit réellement mesuré sur les téléchargements du flux, en Mbit/s (absent avant la première mesure). */
    val bandwidthMbps: Double? = null,
    /**
     * Débit reçu du réseau pour ce flux, en Mbit/s : seule mesure d'un flux continu (MPEG-TS), pour lequel
     * ExoPlayer n'en donne pas. Proche du débit de la chaîne une fois la mémoire tampon remplie.
     */
    val receivedMbps: Double? = null,
    /** Incrémenté à chaque changement de chaîne : le bandeau se réaffiche. */
    val channelVersion: Int = 0,
    /** Détails techniques de la lecture en cours (panneau « Infos de lecture »). */
    val info: PlaybackInfo = PlaybackInfo(),
    /** Direct en pause. */
    val paused: Boolean = false,
    /** Début de la pause en cours (le retard sur le direct augmente pendant la pause). */
    val pausedAtMillis: Long? = null,
    /** Retard sur le direct accumulé par les pauses précédentes. */
    val liveDelayMs: Long = 0,
    /** Film ou épisode déjà commencé : choix entre reprendre et recommencer. */
    val resumePrompt: ResumePrompt? = null,
    /** Pistes audio et sous-titres du flux en cours. */
    val tracks: PlayerTracks = PlayerTracks(),
    /** Replay d'un programme passé : jour (« ven. 30 oct. »). */
    val replay: String? = null,
    /** Lecture depuis l'enregistrement du serveur (replay, « Depuis le début ») : ← → reculent / avancent d'une minute. */
    val seekable: Boolean = false,
    /** Heure de ce qui est à l'écran pendant un replay (progression du programme). */
    val watchedAt: Long? = null,
    /** Saut demandé (secondes), appliqué quand les appuis s'arrêtent. */
    val pendingJumpSeconds: Int = 0,
    /** Durée d'un appui sur ← → : 10 s quand on peut se déplacer dans l'enregistrement, sinon une minute. */
    val jumpStepSeconds: Int = 60,
    /** Avance (1) ou retour (−1) rapide x2 en cours (← → maintenu), 0 sinon. */
    val fastSeek: Int = 0,
    /** Option « baisser le son des pubs » activée. */
    val lowerAdVolume: Boolean = false,
    /** Pub détectée : son baissé. */
    val adMuted: Boolean = false,
    /** Le flux n'a pas de son, ou dans un format que l'appareil ne lit pas. */
    val audioProblem: AudioProblem? = null,
    /** Lecture par le moteur VLC (son ou image que le lecteur de l'appli ne sait pas lire). */
    val engineVlc: Boolean = false,
    /** Format du son illisible (« MP2 », « DTS-HD »), pour le dire dans le message. */
    val unsupportedAudio: String? = null,
    /** Film ou épisode : titre, épisode, affiche et épisode suivant (commandes du lecteur). */
    val vod: VodInfo? = null,
    /** Épisode terminé : le suivant est proposé (lancé seul après un court décompte). */
    val upNext: NextEpisode? = null,
    /**
     * Épisode suivant proposé avant la fin (générique) : secondes restantes de l'épisode en cours, le suivant
     * démarre à la fin. `null` : proposé à la fin, avec son propre décompte.
     */
    val upNextSeconds: Int? = null,
    /** Film (ou dernier épisode) terminé : le lecteur se ferme. */
    val finished: Boolean = false,
)

/** Film ou épisode en cours : titre (de la série), épisode, année, affiche et épisode suivant. */
data class VodInfo(
    val title: String,
    val subtitle: String?,
    val year: Int?,
    val posterUrl: String?,
    val next: NextEpisode?,
)

/** Épisode suivant d'une série : « S01E04 », son titre et son affiche. */
data class NextEpisode(val id: Long, val label: String?, val title: String, val imageUrl: String?)

/** Raison de l'absence de son, affichée sur l'image. */
enum class AudioProblem {
    /** Le flux ne contient aucune piste audio. */
    NO_TRACK,

    /** Pistes audio dans un format que l'appareil ne sait pas lire (« AC3 »…). */
    UNSUPPORTED,
}

/**
 * @property resumeLabel épisode à reprendre (« S01E08 »), `null` pour un film.
 * @property startLabel épisode demandé, s'il diffère de celui à reprendre.
 */
data class ResumePrompt(
    val title: String,
    val resumeLabel: String?,
    val positionMs: Long,
    val startLabel: String?,
)

/** Ce que le lecteur décode réellement : formats, décodeurs, images perdues. */
data class PlaybackInfo(
    val videoMimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val videoDecoder: String? = null,
    /** Vrai : décodeur matériel ; faux : logiciel ; `null` : inconnu. */
    val hardwareDecoder: Boolean? = null,
    val audioMimeType: String? = null,
    val audioChannels: Int? = null,
    val audioDecoder: String? = null,
    /** Images perdues depuis le début de la lecture de cette version. */
    val droppedFrames: Int = 0,
    /** Chaîne ouverte depuis l'aperçu de l'accueil : son flux repris, ou rouvert (et pourquoi). `null` : autre ouverture. */
    val previewStart: PreviewStart? = null,
)

/** Ouverture d'une chaîne depuis l'aperçu de l'accueil (panneau « Infos de lecture »). */
enum class PreviewStart {
    /** Le plein écran continue sur le flux de l'aperçu. */
    TAKEN,

    /** Rouvert : le plein écran lit une autre version (qualité visée, serveur de secours…). */
    OTHER_VERSION,

    /** Rouvert : pas d'aperçu en cours (pas encore lancé, arrêté). */
    NOT_SHOWN,

    /** Rouvert : l'aperçu montrait une autre chaîne. */
    OTHER_CHANNEL,

    /** Rouvert : son envoyé tel quel à l'ampli. */
    PASSTHROUGH,

    /** Rouvert : le flux de l'aperçu était en erreur ou arrêté. */
    NOT_PLAYING,

    /** Rouvert : Android 9 ou avant, pas de couche vidéo à passer d'un écran à l'autre. */
    OLD_ANDROID,

    /** Rouvert : réglage « passage sans coupure » désactivé. */
    SETTING_OFF,

    /** Rouvert : l'appareil a refusé la couche vidéo du lecteur de l'aperçu. */
    NO_LAYER,
}

/** Chaîne du menu, avec la catégorie dont elle provient : la liste affichée correspond-elle au choix ? */
data class MenuChannel(val category: Category, val row: ChannelListRow)

/** Volet du menu des chaînes : programme en cours (avec sa description) et suivant de la chaîne sélectionnée. */
data class MenuProgramme(val channel: ChannelListRow, val now: GuideProgramme?, val next: GuideProgramme?)

/** Fiche du programme d'une chaîne du menu (appui long sur OK) ; [programme] `null` : rien dans le guide. */
data class ProgrammeSheetState(val channel: GuideChannel, val programme: GuideProgramme?)

/** Menu latéral des chaînes, ouvert par « Retour » pendant la lecture. */
data class ChannelMenuState(
    val open: Boolean = false,
    val category: Category = Category.All,
    val currentChannelId: Long = 0,
    /** Chaîne en cours, quelle que soit la version affichée par la liste. */
    val currentItemKey: String? = null,
    /** Rang de la chaîne en cours dans la liste affichée (focus initial). */
    val initialIndex: Int = 0,
)

/** Débit affiché : celui mesuré par ExoPlayer (HLS), sinon celui reçu du flux. */
val PlayerUiState.shownMbps: Double? get() = bandwidthMbps ?: receivedMbps
