package tv.nextv.core.catalog

import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.stream.StreamUrlInspector

/**
 * Web radios ajoutées par l'appli aux chaînes en direct de la playlist, dans leur propre catégorie « Web radios » (à part
 * des radios de la playlist) : radios sans publicité (service public, radios financées par leurs auditeurs, associatives
 * ou de passionnés), rangées par genre. Elles servent aussi de musique pendant les pubs (réglage « Pendant les pubs »).
 * Flux vérifiés le 17/11/2026 (reggae) et le 18/11/2026 (autres genres).
 */
object InternetRadios {

    /** Catégorie de ces radios ; [ChannelLineup] en fait la rubrique « Web radios », après les radios de la playlist. */
    const val GROUP = "Web radios"

    /** Ancien nom de la catégorie (0.2.176 à 0.2.178) : chaînes enregistrées avant la relecture de la playlist. */
    const val FORMER_GROUP = "Radios Internet"

    /**
     * @property key identifiant stable (réglage « radio pendant les pubs »).
     * @property genre genre affiché sous le nom (animations des radios, choix de la radio des pubs).
     */
    data class Radio(val key: String, val name: String, val genre: String, val url: String, val logo: String? = null)

    val all: List<Radio> = listOf(
        // Rap et hip-hop
        Radio("mouv", "Mouv'", "Rap", "https://icecast.radiofrance.fr/mouv-hifi.aac"),
        Radio("fip_hiphop", "FIP Hip-Hop", "Hip-hop", "https://icecast.radiofrance.fr/fiphiphop-hifi.aac"),
        Radio("west_coast", "West Coast G-Funk", "Hip-hop", "https://r.bgp.rodeo/listen/west_coast/radio.mp3"),
        Radio("sensi_hiphop", "Sensi Hip Hop", "Hip-hop", "https://sensihiphop.radioca.st/stream"),
        // Groove, funk, soul
        Radio("fip_groove", "FIP Groove", "Groove, funk, soul", "https://icecast.radiofrance.fr/fipgroove-hifi.aac"),
        Radio("dublab", "Dublab", "Groove, beats", "https://dublab.out.airtime.pro/dublab_a"),
        // Ska
        Radio("tonic_ska", "Tonic Ska Radio", "Ska", "https://eu10.fastcast4u.com/tonicska"),
        Radio("boot_boy", "Boot Boy Radio", "Ska, rocksteady, soul", "https://streaming04.liveboxstream.uk/proxy/dwain?mp=/stream"),
        // Reggae
        Radio("fip_reggae", "FIP Reggae", "Reggae", "https://icecast.radiofrance.fr/fipreggae-hifi.aac"),
        Radio("soma_reggae", "SomaFM Heavyweight Reggae", "Reggae", "https://ice1.somafm.com/reggae-128-mp3", "https://somafm.com/img3/reggae400.jpg"),
        Radio("grosse_reggae", "La Grosse Radio Reggae", "Reggae", "https://hd.lagrosseradio.info/lagrosseradio-reggae-192.mp3"),
        Radio("reggae_fr", "Reggae.fr Webradio", "Reggae", "https://listen.radioking.com/radio/50473/stream/87415"),
        Radio("alpha_boys", "Alpha Boys School Radio", "Reggae, ska", "https://alphaboys-live.streamguys1.com/alphaboys.mp3"),
        // Électro, jazz, rock, monde
        Radio("fip_electro", "FIP Electro", "Électro", "https://icecast.radiofrance.fr/fipelectro-hifi.aac"),
        Radio("fip_jazz", "FIP Jazz", "Jazz", "https://icecast.radiofrance.fr/fipjazz-hifi.aac"),
        Radio("fip_world", "FIP Monde", "Musiques du monde", "https://icecast.radiofrance.fr/fipworld-hifi.aac"),
        Radio("fip_rock", "FIP Rock", "Rock", "https://icecast.radiofrance.fr/fiprock-hifi.aac"),
        Radio("grosse_rock", "La Grosse Radio Rock", "Rock", "https://hd.lagrosseradio.info/lagrosseradio-rock-192.mp3"),
        Radio("grosse_metal", "La Grosse Radio Metal", "Metal", "https://hd.lagrosseradio.info/lagrosseradio-metal-192.mp3"),
    )

    /** Radio de cette liste lue à cette adresse (genre affiché par les animations), `null` sinon. */
    fun byUrl(url: String?): Radio? = url?.let { address -> all.firstOrNull { it.url == address } }

    /** Ces radios en chaînes en direct, aux places qui suivent [firstPosition]. */
    fun channels(firstPosition: Int): List<Channel> = all.mapIndexed { index, radio ->
        Channel(
            position = firstPosition + index,
            name = radio.name,
            stream = StreamUrlInspector.inspect(radio.url, baseUrl = null),
            groupTitle = GROUP,
            logoUrl = radio.logo,
            kind = ContentKind.LIVE,
            title = radio.name,
            variantKey = LiveVariants.key(QualityParser.parse(radio.name).variantKey),
        )
    }
}
