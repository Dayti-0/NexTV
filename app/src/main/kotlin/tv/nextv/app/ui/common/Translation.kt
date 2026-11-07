package tv.nextv.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Traduction en français d'un texte du guide dans une autre langue, `null` quand il n'y a rien à
 * traduire. Fournie par l'activité (service de traduction de l'application) ; sans elle (aperçus,
 * tests), les textes restent tels quels.
 */
val LocalTranslator = staticCompositionLocalOf<suspend (String) -> String?> { { null } }

/**
 * [text] en français : la traduction remplace le texte d'origine dès qu'elle arrive (description en
 * néerlandais d'une chaîne flamande, en anglais…) ; un texte français reste tel quel.
 */
@Composable
fun frenchText(text: String): String {
    val translate = LocalTranslator.current
    val translated by produceState<String?>(null, text, translate) { value = translate(text) }
    return translated ?: text
}
