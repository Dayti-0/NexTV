package tv.nextv.core.epg

import java.nio.charset.Charset

/**
 * Textes du guide sans la mise en forme HTML que certaines sources y laissent : un genre
 * « <span style="color:#FF2F79">Inédit</span> » devient « Inédit », un résumé « <p>(EN DIRECT) »
 * devient « (EN DIRECT) » et « l&#039;équipe », « l'équipe ». Un texte sans « < » ni « & » est
 * rendu tel quel.
 */
internal object GuideText {

    /** Balises HTML courantes (« <BZZZZZZ...> », dans le résumé d'un dessin animé, n'en est pas une). */
    private val TAG = Regex(
        """</?(p|br|span|div|b|i|u|s|em|strong|font|a|small|big|sup|sub|ul|ol|li|h[1-6]|center|blockquote|hr)\b[^<>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /** Balises qui séparent deux lignes ou deux paragraphes : remplacées par un retour à la ligne. */
    private val LINE_BREAK = Regex("""^</?(p|br|div|li|ul|ol|h[1-6]|blockquote|hr)\b""", RegexOption.IGNORE_CASE)

    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,7});""")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "hellip" to "…", "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "deg" to "°", "euro" to "€", "oelig" to "œ", "OElig" to "Œ",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "agrave" to "à", "acirc" to "â", "ccedil" to "ç",
        "icirc" to "î", "iuml" to "ï", "ocirc" to "ô", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü",
        "Eacute" to "É", "Egrave" to "È", "Ecirc" to "Ê", "Agrave" to "À", "Ccedil" to "Ç",
    )

    /** Caractères 128 à 159 : ceux de Windows-1252 (« &#150; » : tiret), comme le font les navigateurs. */
    private val WINDOWS_1252: Charset? = runCatching { Charset.forName("windows-1252") }.getOrNull()

    private val SPACES = Regex("""[ \t ]+""")
    private val LINES = Regex(""" ?\n[ \n]*""")

    fun clean(text: String): String {
        if ('<' !in text && '&' !in text) return text
        // Entités d'abord : un texte échappé deux fois (« &lt;p&gt; ») donne des balises, retirées ensuite.
        val decoded = ENTITY.replace(text) { decode(it.groupValues[1]) ?: it.value }
        val plain = TAG.replace(decoded) { if (LINE_BREAK.containsMatchIn(it.value)) "\n" else "" }
        return plain.replace(SPACES, " ").replace(LINES, "\n").trim()
    }

    private fun decode(entity: String): String? {
        if (!entity.startsWith("#")) return NAMED[entity]
        val code = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
            entity.substring(2).toIntOrNull(16)
        } else {
            entity.substring(1).toIntOrNull()
        } ?: return null
        return when {
            code == 10 -> "\n"
            code == 9 || code == 13 -> " "
            code in 128..159 -> WINDOWS_1252?.let { String(byteArrayOf(code.toByte()), it).replace("\uFFFD", "") }
            code < 32 || !Character.isValidCodePoint(code) -> null
            else -> String(Character.toChars(code))
        }
    }
}
