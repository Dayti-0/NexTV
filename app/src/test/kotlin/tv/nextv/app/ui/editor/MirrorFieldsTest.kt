package tv.nextv.app.ui.editor

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Serveurs de secours : un champ par serveur. En ajouter un ne touche pas aux autres, même quand le
 * clavier de la Fire TV remplace tout le texte du champ où l'on écrit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MirrorFieldsTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(EditorState(name = "Test", mode = LocationMode.URL, url = "http://principal.example:80"))

    private fun show() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                SourceEditorContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onEdit = { edit -> state = edit(state) },
                    onPick = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun `a new server is added without retyping the saved one`() {
        state = state.copy(mirrors = listOf("http://secours.example:80"))
        show()

        compose.onNodeWithText("Ajouter un serveur").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // Le clavier remplace tout le texte du nouveau champ : l'ancien serveur reste.
        field("Serveur de secours 2").performScrollTo().performTextReplacement("http://troisieme.example")
        compose.waitForIdle()

        assertEquals(listOf("http://secours.example:80", "http://troisieme.example"), state.mirrors)
    }

    @Test
    fun `an empty field is offered and a server can be removed`() {
        show()
        field("Serveur de secours 1").performScrollTo().performTextReplacement("http://secours.example:80")
        compose.waitForIdle()
        assertEquals(listOf("http://secours.example:80"), state.mirrors)

        compose.onAllNodesWithText("Retirer")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), state.mirrors)
    }
}
