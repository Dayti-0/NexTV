package tv.nextv.core.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import tv.nextv.core.error.SourceError
import tv.nextv.core.error.SourceException
import java.io.InputStream

/** Élément de premier niveau d'un document XMLTV. */
sealed interface XmltvItem

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
) : XmltvItem

data class XmltvProgramme(
    val channelId: String,
    val startMillis: Long,
    val stopMillis: Long?,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    /** Année de production (`<date>`), si le guide l'indique. */
    val year: Int? = null,
) : XmltvItem

/**
 * Lecteur XMLTV en flux (API XmlPull, fournie par Android) : les guides de plusieurs
 * centaines de mégaoctets sont parcourus sans être chargés en mémoire.
 *
 * Les programmes aux horaires illisibles ou sans titre sont ignorés ([skippedProgrammes]).
 *
 * @param parserFactory fabrique de parseur : `android.util.Xml::newPullParser` sur Android.
 */
class XmltvReader(
    input: InputStream,
    parserFactory: () -> XmlPullParser,
) {
    private val parser: XmlPullParser = parserFactory().apply {
        trySetFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        trySetFeature(FEATURE_RELAXED, true)
        try {
            setInput(input, null) // encodage pris dans la déclaration XML / le BOM
        } catch (e: XmlPullParserException) {
            throw SourceException(SourceError.InvalidXmltv, e)
        }
    }

    private var rootChecked = false

    var skippedProgrammes: Int = 0
        private set

    /** @return l'élément suivant, ou `null` en fin de document. */
    fun next(): XmltvItem? {
        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> {
                        if (!rootChecked) throw SourceException(SourceError.InvalidXmltv)
                        return null
                    }
                    XmlPullParser.START_TAG -> {
                        if (!rootChecked) {
                            if (!parser.name.equals("tv", ignoreCase = true)) throw SourceException(SourceError.InvalidXmltv)
                            rootChecked = true
                            continue
                        }
                        if (parser.depth != 2) continue
                        when (parser.name) {
                            "channel" -> readChannel()?.let { return it }
                            "programme" -> readProgramme()?.let { return it } ?: skippedProgrammes++
                            else -> skipElement()
                        }
                    }
                }
            }
        } catch (e: XmlPullParserException) {
            throw SourceException(SourceError.InvalidXmltv, e)
        }
    }

    private fun readChannel(): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim()
        val names = mutableListOf<String>()
        var icon: String? = null
        forEachChild { name ->
            when (name) {
                "display-name" -> readText().takeIf { it.isNotEmpty() }?.let(names::add)
                "icon" -> {
                    icon = icon ?: parser.getAttributeValue(null, "src")
                    skipElement()
                }
                else -> skipElement()
            }
        }
        return if (id.isNullOrEmpty()) null else XmltvChannel(id, names, icon)
    }

    private fun readProgramme(): XmltvProgramme? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()
        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        // Tous les genres (« Sport, Football ») : le premier seul est souvent trop vague.
        val categories = mutableListOf<String>()
        var year: Int? = null
        forEachChild { name ->
            when (name) {
                "title" -> readText().let { if (title.isNullOrEmpty()) title = it }
                "sub-title" -> readText().let { if (subtitle.isNullOrEmpty()) subtitle = it }
                "desc" -> readText().let { if (description.isNullOrEmpty()) description = it }
                "category" -> readText().takeIf { it.isNotEmpty() }?.let { text ->
                    if (categories.none { it.equals(text, ignoreCase = true) }) categories += text
                }
                // « 2019 », « 20190314 »… : seule une année plausible est retenue.
                "date" -> readText().take(4).toIntOrNull()?.takeIf { it in 1880..2100 }?.let { year = it }
                else -> skipElement()
            }
        }
        val finalTitle = title?.takeIf { it.isNotEmpty() }
        if (channel.isNullOrEmpty() || start == null || finalTitle == null) return null
        if (stop != null && stop <= start) return null
        return XmltvProgramme(
            channelId = channel,
            startMillis = start,
            stopMillis = stop,
            title = finalTitle.take(MAX_TITLE_LENGTH),
            subtitle = subtitle?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_LENGTH),
            description = description?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION_LENGTH),
            category = categories.joinToString(", ").takeIf { it.isNotEmpty() }?.take(MAX_TITLE_LENGTH),
            year = year,
        )
    }

    /** Parcourt les enfants directs de l'élément courant ; [block] doit consommer l'enfant. */
    private inline fun forEachChild(block: (String) -> Unit) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.START_TAG) block(parser.name)
        }
    }

    /** Texte de l'élément courant (éléments imbriqués ignorés), puis positionne sur sa balise fermante. */
    private fun readText(): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> if (parser.depth == depth) return text.toString().trim()
                XmlPullParser.END_DOCUMENT -> return text.toString().trim()
            }
        }
    }

    private fun skipElement() {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (parser.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun XmlPullParser.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: XmlPullParserException) {
            // Fonctionnalité non prise en charge par cette implémentation : sans conséquence.
        }
    }

    private companion object {
        const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        const val MAX_TITLE_LENGTH = 300
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
