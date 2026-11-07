package tv.nextv.app.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/** Compte Xtream : l'adresse du serveur commence déjà par « http://www. », la saisie la complète. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class XtreamServerFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `choosing xtream prefills the start of the address`() {
        val xtream = EditorState().withMode(LocationMode.XTREAM)
        assertEquals("http://www.", xtream.server)
        // Le seul début ne suffit pas : l'adresse manque encore.
        assertTrue(xtream.serverMissing)
        assertFalse(xtream.copy(server = "http://www.exemple.com").serverMissing)
        // Lien Xtream déjà saisi : son serveur est repris tel quel.
        val fromLink = EditorState(url = "http://exemple.com:8080/get.php?username=a&password=b").withMode(LocationMode.XTREAM)
        assertEquals("http://exemple.com:8080", fromLink.server)
        // Retour au lien : rien n'est construit avec le seul début de l'adresse.
        assertEquals("", xtream.withMode(LocationMode.URL).url)
    }

    @Test
    fun `typing completes the prefilled address`() {
        var value by mutableStateOf("http://www.")
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                NexTvTextField(value = value, onValueChange = { value = it }, label = "Adresse du serveur")
            }
        }
        compose.onNodeWithText("http://www.").performTextInput("exemple.com")
        compose.waitForIdle()
        assertEquals("http://www.exemple.com", value)
        // Effacé en entier : le champ reste vide.
        value = ""
        compose.waitForIdle()
        compose.onNodeWithText("Adresse du serveur").performTextInput("autre.net")
        compose.waitForIdle()
        assertEquals("autre.net", value)
    }
}
