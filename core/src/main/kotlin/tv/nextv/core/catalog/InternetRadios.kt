package tv.nextv.core.catalog

import tv.nextv.core.model.Channel
import tv.nextv.core.model.ContentKind
import tv.nextv.core.stream.StreamUrlInspector

/**
 * Radios Internet ajoutées aux chaînes en direct de la playlist (catégorie « Radios » quand les chaînes sont
 * rassemblées, « Radios Internet » sinon) : radios reggae sans publicité (service public, radios financées par leurs
 * auditeurs ou associatives). Flux vérifiés le 17/11/2026.
 */
object InternetRadios {

    /** Catégorie de ces radios ; [ChannelLineup] les range dans la rubrique Radios, après celles de la playlist. */
    const val GROUP = "Radios Internet"

    data class Radio(val name: String, val url: String, val logo: String? = null)

    val all: List<Radio> = listOf(
        Radio("FIP Reggae", "https://icecast.radiofrance.fr/fipreggae-hifi.aac"),
        Radio("SomaFM Heavyweight Reggae", "https://ice1.somafm.com/reggae-128-mp3", "https://somafm.com/img3/reggae400.jpg"),
        Radio("La Grosse Radio Reggae", "https://hd.lagrosseradio.info/lagrosseradio-reggae-192.mp3"),
        Radio("Reggae.fr Webradio", "https://listen.radioking.com/radio/50473/stream/87415"),
        Radio("Alpha Boys School Radio", "https://alphaboys-live.streamguys1.com/alphaboys.mp3"),
    )

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
