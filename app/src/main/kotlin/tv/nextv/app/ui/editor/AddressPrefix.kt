package tv.nextv.app.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Début d'adresse écrit d'office (« http://www. ») : affiché dans le champ devant ce qui est tapé, sans
 * faire partie du texte que modifie le clavier. Celui de la Fire TV remplace tout le texte du champ par
 * ce qu'on y tape : un début écrit dans le texte même disparaissait dès la première lettre.
 * Il se choisit sous le champ ([CHOICES] : « https:// », sans « www. », aucun). Une adresse tapée en
 * entier (« http://… », « https://… ») le remplace ; devant « www. », le « www. » du début n'est pas doublé.
 */
object AddressPrefix {
    const val DEFAULT = "http://www."

    /** Débuts proposés ; « » : aucun, l'adresse est tapée en entier. */
    val CHOICES = listOf("http://www.", "https://www.", "http://", "https://", "")

    /** Début d'une adresse complète parmi [CHOICES] (le plus long), `null` sans aucun. */
    fun of(full: String): String? =
        CHOICES.filter { it.isNotEmpty() && full.length > it.length && full.startsWith(it, ignoreCase = true) }.maxByOrNull { it.length }

    /** Début affiché devant le texte tapé [typed], avec le début choisi [prefix]. */
    fun shown(typed: String, prefix: String): String {
        val text = typed.trimStart().lowercase()
        return when {
            prefix.isEmpty() || text.startsWith("http") || "://" in text -> ""
            text.startsWith("www.") && prefix.endsWith("www.") -> prefix.removeSuffix("www.")
            else -> prefix
        }
    }

    /** Adresse complète d'après le texte tapé ; vide s'il n'y a rien. */
    fun full(typed: String, prefix: String = DEFAULT): String {
        val text = typed.trim()
        return if (text.isEmpty()) "" else shown(text, prefix) + text
    }

    /** Texte à taper pour une adresse complète : sans le début choisi. */
    fun typed(full: String, prefix: String = DEFAULT): String =
        if (prefix.isNotEmpty() && full.startsWith(prefix, ignoreCase = true)) full.substring(prefix.length) else full
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
