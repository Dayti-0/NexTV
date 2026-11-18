package tv.nextv.core.catalog

import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.stream.StreamUrlInspector
import java.text.Normalizer

/**
 * Radios ajoutées par l'appli aux chaînes en direct de la playlist :
 * - les **Web radios** ([all]), dans leur propre catégorie « Web radios » : radios sans publicité (service public, radios
 *   financées par leurs auditeurs, associatives ou de passionnés), rangées par genre. Elles servent aussi de musique pendant
 *   les pubs (réglage « Pendant les pubs ») ;
 * - les **grandes radios françaises** ([french]) absentes de la playlist, rangées avec ses radios (catégorie « Radios »).
 *
 * Chaque radio a son logo (logos officiels de Wikipédia ou des sites des radios) ; les radios de la playlist sans image
 * reçoivent le leur par [logoFor]. Flux vérifiés le 17/11/2026 (reggae) et le 18/11/2026 (le reste).
 */
object InternetRadios {

    /** Catégorie des Web radios ; [ChannelLineup] en fait la rubrique « Web radios », après les radios de la playlist. */
    const val GROUP = "Web radios"

    /** Ancien nom de la catégorie (0.2.176 à 0.2.178) : chaînes enregistrées avant la relecture de la playlist. */
    const val FORMER_GROUP = "Radios Internet"

    /** Catégorie des radios françaises ajoutées ; [ChannelLineup] les range avec les radios de la playlist. */
    const val FRENCH_GROUP = "Radios françaises"

    /**
     * Service qui donne le morceau en cours d'une radio dont le flux ne l'annonce pas (sinon : reconnaissance à l'écoute).
     */
    sealed interface TitleSource {
        /** Radio France (`api.radiofrance.fr/livemeta/pull/<station>`). */
        data class RadioFrance(val station: Int) : TitleSource

        /** Skyrock (`skyrock.fm/api/v3/player/onair/national`). */
        data object Skyrock : TitleSource

        /** Radio Nova et ses webradios (`nova.fr/radios-data/www.nova.fr/all.json`), reconnues à l'adresse de leur flux. */
        data class Nova(val stream: String) : TitleSource

        /** Les Indés Radios (Jazz Radio, M Radio, Générations…) : `https://<site>/lite/update_onair`, radio [radioId]. */
        data class Indes(val site: String, val radioId: String = "1") : TitleSource

        /** Europe 2, RFM (groupe Lagardère) : `https://<site>/cetait-quoi-ce-titre/history?idplayer=1&limit=1`. */
        data class Lagardere(val site: String) : TitleSource

        /** Radios hébergées par Radioking (Reggae.fr…) : `api.radioking.io/widgets/api/v1/radio/<id>/track/current`. */
        data class Radioking(val radioId: Int) : TitleSource

        /** Émission en cours d'une radio du groupe RTL (radio parlée : RTL) : `https://<site>/ws/live/live`. */
        data class RtlProgram(val site: String) : TitleSource
    }

    /**
     * @property key identifiant stable (réglage « radio pendant les pubs »).
     * @property genre genre affiché sous le nom (animations des radios, choix de la radio des pubs) ; il donne aussi les
     * couleurs de l'animation.
     * @property titles service qui donne le morceau en cours (leurs flux ne l'annoncent pas), `null` : reconnaissance.
     */
    data class Radio(
        val key: String,
        val name: String,
        val genre: String,
        val url: String,
        val logo: String? = null,
        val titles: TitleSource? = null,
    )

    private const val FIP_LOGO = "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/16/FIP_logo_2021.svg/330px-FIP_logo_2021.svg.png"
    private const val MOUV_LOGO = "https://charte.radiofrance.fr/images/mouv/mouv-avatar.png"
    private const val NOVA_LOGO = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/ba/Radio_Nova_2024.svg/330px-Radio_Nova_2024.svg.png"
    private const val GROSSE_RADIO_LOGO = "https://upload.wikimedia.org/wikipedia/fr/3/31/Logo_GrosseRadio_300dpi.png"

    /** Web radios sans pub, par genre (rock et metal retirés le 18/11/2026 : pas du goût de l'utilisateur). */
    val all: List<Radio> = listOf(
        // Rap et hip-hop
        Radio("mouv", "Mouv'", "Rap", "https://icecast.radiofrance.fr/mouv-hifi.aac", MOUV_LOGO, titles = TitleSource.RadioFrance(6)),
        Radio("fip_hiphop", "FIP Hip-Hop", "Hip-hop", "https://icecast.radiofrance.fr/fiphiphop-hifi.aac", FIP_LOGO),
        Radio("west_coast", "West Coast G-Funk", "Hip-hop", "https://r.bgp.rodeo/listen/west_coast/radio.mp3"),
        Radio("sensi_hiphop", "Sensi Hip Hop", "Hip-hop", "https://sensihiphop.radioca.st/stream"),
        // Groove, funk, soul
        Radio("fip_groove", "FIP Groove", "Groove, funk, soul", "https://icecast.radiofrance.fr/fipgroove-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(66)),
        Radio("dublab", "Dublab", "Groove, beats", "https://dublab.out.airtime.pro/dublab_a"),
        // Ska
        Radio("tonic_ska", "Tonic Ska Radio", "Ska", "https://eu10.fastcast4u.com/tonicska"),
        Radio(
            "boot_boy", "Boot Boy Radio", "Ska, rocksteady, soul", "https://streaming04.liveboxstream.uk/proxy/dwain?mp=/stream",
            "https://bootboyradio.net/wp-content/uploads/2021/02/cropped-900logosquare-192x192.jpg",
        ),
        // Reggae
        Radio("fip_reggae", "FIP Reggae", "Reggae", "https://icecast.radiofrance.fr/fipreggae-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(71)),
        Radio("soma_reggae", "SomaFM Heavyweight Reggae", "Reggae", "https://ice1.somafm.com/reggae-128-mp3", "https://somafm.com/img3/reggae400.jpg"),
        Radio("grosse_reggae", "La Grosse Radio Reggae", "Reggae", "https://hd.lagrosseradio.info/lagrosseradio-reggae-192.mp3", GROSSE_RADIO_LOGO),
        Radio(
            "reggae_fr", "Reggae.fr Webradio", "Reggae", "https://listen.radioking.com/radio/50473/stream/87415", "https://www.reggae.fr/apple-touch-icon.png",
            titles = TitleSource.Radioking(50473),
        ),
        Radio("alpha_boys", "Alpha Boys School Radio", "Reggae, ska", "https://alphaboys-live.streamguys1.com/alphaboys.mp3"),
        // Électro, jazz, monde
        Radio("fip_electro", "FIP Electro", "Électro", "https://icecast.radiofrance.fr/fipelectro-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(74)),
        Radio("fip_jazz", "FIP Jazz", "Jazz", "https://icecast.radiofrance.fr/fipjazz-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(65)),
        Radio("fip_world", "FIP Monde", "Musiques du monde", "https://icecast.radiofrance.fr/fipworld-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(69)),
    )

    /**
     * Grandes radios françaises, ajoutées à la catégorie « Radios » quand la playlist ne les a pas déjà (NRJ, RTL, RFM,
     * RMC, Skyrock, Nostalgie, Europe 1 et 2… y sont). Flux officiels des radios.
     */
    val french: List<Radio> = listOf(
        Radio("rtl2", "RTL2", "Pop-rock", "https://icecast.rtl2.fr/rtl2-1-44-128", "https://www.rtl2.fr/apple-touch-icon.png"),
        Radio("fun", "Fun Radio", "Dance, hits", "https://icecast.funradio.fr/fun-1-44-128", "https://www.funradio.fr/apple-touch-icon.png"),
        Radio(
            "cherie", "Chérie FM", "Pop, love songs", "https://streaming.nrjaudio.fm/oubqggj25zuw",
            "https://upload.wikimedia.org/wikipedia/commons/e/ec/Ch%C3%A9rie_FM_logo_2012.png",
        ),
        Radio(
            "rire", "Rire & Chansons", "Humour", "https://streaming.nrjaudio.fm/ousguym4z85f",
            "https://players.nrjaudio.fm/live-metadata/player/img/player-files/rire/logos/640x640/RIRE-WR-Sketches.jpg",
        ),
        Radio(
            "generations", "Générations", "Rap, R&B", "https://generationfm.ice.infomaniak.ch/generationfm-high.mp3",
            "https://generations.fr/apple-touch-icon-120x120.png", titles = TitleSource.Indes("generations.fr"),
        ),
        Radio(
            "nova", "Radio Nova", "Groove, électro", "https://novazz.ice.infomaniak.ch/novazz-128.mp3", NOVA_LOGO,
            titles = TitleSource.Nova("https://novazz.ice.infomaniak.ch/novazz-128.mp3"),
        ),
        // Webradios de Nova qui collent aux goûts de l'utilisateur (hip-hop, reggae, soul).
        Radio(
            "nova_hiphop", "Nova Hip-Hop", "Hip-hop", "https://nova-odn.ice.infomaniak.ch/nova-odn-128.mp3", NOVA_LOGO,
            titles = TitleSource.Nova("https://nova-odn.ice.infomaniak.ch/nova-odn-128.mp3"),
        ),
        Radio(
            "nova_reggae", "Nova Reggae", "Reggae", "https://nova-reggae.ice.infomaniak.ch/nova-reggae-128.mp3", NOVA_LOGO,
            titles = TitleSource.Nova("https://nova-reggae.ice.infomaniak.ch/nova-reggae-128.mp3"),
        ),
        Radio(
            "nova_soul", "Nova Soul", "Soul, funk", "https://nova-soul.ice.infomaniak.ch/nova-soul-128.mp3", NOVA_LOGO,
            titles = TitleSource.Nova("https://nova-soul.ice.infomaniak.ch/nova-soul-128.mp3"),
        ),
        Radio(
            "fg", "Radio FG", "Électro, house", "http://radiofg.impek.com/fg",
            "https://thumb.wikimedia.org/wikipedia/fr/thumb/1/10/Logo_radio-fg-2019.svg/330px-Logo_radio-fg-2019.svg.png",
        ),
        Radio(
            "latina", "Latina", "Musiques latines", "https://start-latina.ice.infomaniak.ch/start-latina-high.mp3",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fd/Logo_Latina.png/330px-Logo_Latina.png",
        ),
        Radio(
            "mradio", "M Radio", "Chanson française", "https://mfm.ice.infomaniak.ch/mfm-128.mp3", "https://mradio.fr/apple-touch-icon-120x120.png",
            titles = TitleSource.Indes("mradio.fr"),
        ),
        Radio(
            "alouette", "Alouette", "Pop, hits", "https://alouette.ice.infomaniak.ch/alouette-high.mp3",
            "https://thumb.wikimedia.org/wikipedia/fr/thumb/a/a4/Logo_station_Alouette.jpeg/330px-Logo_station_Alouette.jpeg",
        ),
        Radio(
            "jazz", "Jazz Radio", "Jazz", "https://jazzradio.ice.infomaniak.ch/jazzradio-high.aac", "https://www.jazzradio.fr/apple-touch-icon-120x120.png",
            titles = TitleSource.Indes("www.jazzradio.fr"),
        ),
        Radio(
            "tsf", "TSF Jazz", "Jazz", "https://tsfjazz.ice.infomaniak.ch/tsfjazz-high.mp3",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cd/TSFJazz-2017.jpg/330px-TSFJazz-2017.jpg",
        ),
        Radio("fip", "FIP", "Éclectique", "https://icecast.radiofrance.fr/fip-hifi.aac", FIP_LOGO, titles = TitleSource.RadioFrance(7)),
        Radio(
            "inter", "France Inter", "Info, culture", "https://icecast.radiofrance.fr/franceinter-hifi.aac",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a0/France_Inter_logo_2021.svg/330px-France_Inter_logo_2021.svg.png",
            titles = TitleSource.RadioFrance(1),
        ),
        Radio(
            "franceinfo", "franceinfo", "Info", "https://icecast.radiofrance.fr/franceinfo-hifi.aac",
            "https://www.francetvinfo.fr/assets/common/images/pwa/ios/120-0cfbd6d4.png", titles = TitleSource.RadioFrance(2),
        ),
        Radio(
            "culture", "France Culture", "Culture", "https://icecast.radiofrance.fr/franceculture-hifi.aac",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/France_Culture_logo_2021.svg/330px-France_Culture_logo_2021.svg.png",
            titles = TitleSource.RadioFrance(5),
        ),
        Radio(
            "musique", "France Musique", "Classique", "https://icecast.radiofrance.fr/francemusique-hifi.aac",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6c/France_Musique_logo_2021.svg/330px-France_Musique_logo_2021.svg.png",
            titles = TitleSource.RadioFrance(4),
        ),
        Radio(
            "classique", "Radio Classique", "Classique", "https://radioclassique.ice.infomaniak.ch/radioclassique-high.mp3",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8f/Logo_Radio_Classique.svg/330px-Logo_Radio_Classique.svg.png",
        ),
        Radio(
            "sud", "Sud Radio", "Info, débats", "https://live.sudradio.fr/sudradio-mp3-128",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/03/Logo_Sud_Radio_2014.svg/330px-Logo_Sud_Radio_2014.svg.png",
        ),
        Radio("bfm_business", "BFM Business", "Économie", "https://audio.bfmtv.com/bfmbusiness_128.mp3", "https://www.bfmtv.com/android-icon-192x192.png"),
    )

    /** Logos des radios de la playlist qui n'en ont pas (nom ramené par [logoKey]). */
    private val PLAYLIST_LOGOS: Map<String, String> by lazy {
        mapOf(
        "nrj" to "https://cdn.uc.assets.prezly.com/b1be11c1-7593-48e0-b0d3-dfedd93ef5a2/-/preview/512x512/-/format/png/Newsroom%20Icon-NRJ.png",
        "rtl" to "https://www.rtl.fr/apple-touch-icon.png",
        "rfm" to "https://cdn-rfm.lanmedia.fr/apple-touch-icon-120x120.png",
        "rmc" to "https://rmc.bfmtv.com/apple-icon-228x228.png",
        "nostalgie" to "https://players.nrjaudio.fm/live-metadata/player/img/player-files/nosta/logos/640x640/P_Nostalgie_Premium_Logo_2023.png",
        "skyrock" to "https://thumb.wikimedia.org/wikipedia/fr/thumb/a/a4/Logo_Skyrock_FM_%281997%29.svg/330px-Logo_Skyrock_FM_%281997%29.svg.png",
        "europe1" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ae/Europe1_FLAT.png/330px-Europe1_FLAT.png",
        "europe2" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/47/Europe_2_noir_2024.png/330px-Europe_2_noir_2024.png",
        "vibration" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e0/Logo_Vibration2018.png/330px-Logo_Vibration2018.png",
        ) + (all + french).mapNotNull { radio -> radio.logo?.let { logoKey(radio.name) to it } }
    }

    /** Mots qui ne changent pas la radio : « |FR| RFM RADIO », « SkyRock FM », « RADIO NOSTALGIE ». */
    private val RADIO_WORDS = Regex("""\b(radio|fm|hd|fhd|sd)\b""")
    private val PREFIX = Regex("""^\s*[|\[(]?\s*[A-Z]{2,3}\s*[|\])]\s*""")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    /** Nom ramené à une clé de logo : sans pays, sans « radio » / « FM », sans accents ni séparateurs. */
    fun logoKey(name: String): String {
        val plain = Normalizer.normalize(PREFIX.replace(name, "").lowercase(), Normalizer.Form.NFD).let { DIACRITICS.replace(it, "") }
        return NON_ALPHANUMERIC.replace(RADIO_WORDS.replace(plain, " "), "")
    }

    /** Logo d'une radio de la playlist d'après son nom, `null` si elle n'est pas connue. */
    fun logoFor(name: String): String? = PLAYLIST_LOGOS[logoKey(name)]

    /** Radio de l'appli de ce nom (genre, logo, numéro Radio France), `null` sinon. */
    fun byName(name: String?): Radio? = name?.let { n -> (all + french).firstOrNull { it.name == n } }

    /** Services du morceau en cours pour les radios de la playlist (clé [logoKey]). */
    private val PLAYLIST_TITLES: Map<String, TitleSource> = mapOf(
        "skyrock" to TitleSource.Skyrock,
        "europe2" to TitleSource.Lagardere("www.europe2.fr"),
        "rfm" to TitleSource.Lagardere("www.rfm.fr"),
        "rtl" to TitleSource.RtlProgram("www.rtl.fr"),
    )

    /** Service du morceau en cours d'une radio (de l'appli, ou de la playlist par son nom), `null` : aucun connu. */
    fun titlesFor(name: String?): TitleSource? = name?.let { byName(it)?.titles ?: PLAYLIST_TITLES[logoKey(it)] }

    /** Radio de cette liste lue à cette adresse, `null` sinon. */
    fun byUrl(url: String?): Radio? = url?.let { address -> (all + french).firstOrNull { it.url == address } }

    /**
     * Ces radios en chaînes en direct, aux places qui suivent [firstPosition] : les Web radios, puis les radios françaises
     * dont le nom ([logoKey]) n'est pas déjà parmi les chaînes de la playlist ([existingNames]).
     */
    fun channels(firstPosition: Int, existingNames: Collection<String> = emptyList()): List<Channel> {
        val existing = existingNames.mapTo(HashSet(), ::logoKey)
        val radios = all.map { it to GROUP } + french.filter { logoKey(it.name) !in existing }.map { it to FRENCH_GROUP }
        return radios.mapIndexed { index, (radio, group) ->
            Channel(
                position = firstPosition + index,
                name = radio.name,
                stream = StreamUrlInspector.inspect(radio.url, baseUrl = null),
                groupTitle = group,
                logoUrl = radio.logo,
                kind = ContentKind.LIVE,
                title = radio.name,
                variantKey = LiveVariants.key(QualityParser.parse(radio.name).variantKey),
            )
        }
    }
}
