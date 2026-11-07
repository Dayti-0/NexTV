package tv.nextv.app.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Début d'adresse écrit d'office (« http://www. ») : affiché dans le champ devant ce qui est tapé, sans
 * faire partie du texte que modifie le clavier. Celui de la Fire TV remplace tout le texte du champ par
 * ce qu'on y tape : un début écrit dans le texte même disparaissait dès la première lettre.
 * Une adresse tapée en entier (« http://… », « https://… ») le remplace ; devant « www. », seul
 * « http:// » est ajouté.
 */
object AddressPrefix {
    const val DEFAULT = "http://www."
    private const val SCHEME = "http://"

    /** Début affiché devant le texte tapé [typed]. */
    fun shown(typed: String): String {
        val text = typed.trimStart().lowercase()
        return when {
            text.startsWith("http") || "://" in text -> ""
            text.startsWith("www.") -> SCHEME
            else -> DEFAULT
        }
    }

    /** Adresse complète d'après le texte tapé ; vide s'il n'y a rien. */
    fun full(typed: String): String {
        val text = typed.trim()
        return if (text.isEmpty()) "" else shown(text) + text
    }

    /** Texte à taper pour une adresse complète : sans le début écrit d'office. */
    fun typed(full: String): String =
        if (full.startsWith(DEFAULT, ignoreCase = true)) full.substring(DEFAULT.length) else full
}

/** Affiche [prefix] devant le texte du champ ; le curseur reste dans le texte tapé. */
internal class PrefixTransformation(private val prefix: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (prefix.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val length = prefix.length
        return TransformedText(
            AnnotatedString(prefix) + text,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset + length
                override fun transformedToOriginal(offset: Int): Int = (offset - length).coerceIn(0, text.length)
            },
        )
    }

    override fun equals(other: Any?): Boolean = other is PrefixTransformation && other.prefix == prefix

    override fun hashCode(): Int = prefix.hashCode()
}
