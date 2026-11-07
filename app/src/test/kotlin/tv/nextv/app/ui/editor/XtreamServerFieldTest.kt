package tv.nextv.app.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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

/**
 * Adresse d'un serveur Xtream ou d'une playlist M3U : « http://www. » est affiché d'office devant le
 * texte tapé, et reste quand le clavier (Fire TV) remplace tout le texte du champ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class XtreamServerFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the start of the address is added to what is typed`() {
        assertEquals("http://www.", AddressPrefix.shown(""))
        assertEquals("http://www.exemple.com:8080", AddressPrefix.full("exemple.com:8080"))
        // « www. » tapé : seul « http:// » est ajouté ; adresse entière : rien.
        assertEquals("http://www.site.org", AddressPrefix.full("www.site.org"))
        assertEquals("https://autre.net/liste.m3u", AddressPrefix.full("https://autre.net/liste.m3u"))
        assertEquals("http://ligne.exemple.com", AddressPrefix.full("http://ligne.exemple.com"))
        assertEquals("", AddressPrefix.full("  "))
        // Adresse enregistrée : le début d'office n'est pas à retaper.
        assertEquals("exemple.com", AddressPrefix.typed("http://www.exemple.com"))
        assertEquals("http://ligne.exemple.com", AddressPrefix.typed("http://ligne.exemple.com"))
    }

    @Test
    fun `choosing xtream needs the rest of the address`() {
        val xtream = EditorState().withMode(LocationMode.XTREAM)
        assertTrue(xtream.serverMissing)
        assertTrue(xtream.copy(server = "http://www.").serverMissing)
        assertFalse(xtream.copy(server = "http://www.exemple.com").serverMissing)
        // Lien Xtream déjà saisi : son serveur est repris tel quel.
        val fromLink = EditorState(url = "http://exemple.com:8080/get.php?username=a&password=b").withMode(LocationMode.XTREAM)
        assertEquals("http://exemple.com:8080", fromLink.server)
        // Retour au lien : rien n'est construit sans adresse.
        assertEquals("", xtream.withMode(LocationMode.URL).url)
    }

    @Test
    fun `the start stays when the keyboard replaces the whole text`() {
        var value by mutableStateOf("")
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                NexTvTextField(value = value, onValueChange = { value = it }, label = "Adresse du serveur", addressPrefix = true)
            }
        }
        val field = hasSetTextAction()
        // Rien de tapé : le début est déjà là.
        compose.onNodeWithText("http://www.").assertIsDisplayed()
        // Clavier de la Fire TV : tout le texte du champ est remplacé par ce qui est tapé.
        compose.onNode(field).performTextReplacement("exemple.com")
        compose.waitForIdle()
        assertEquals("http://www.exemple.com", value)
        compose.onNodeWithText("http://www.exemple.com").assertIsDisplayed()
        // Lettre par lettre : la suite s'ajoute à la fin.
        compose.onNode(field).performTextInput(":8080")
        compose.waitForIdle()
        assertEquals("http://www.exemple.com:8080", value)
        // Adresse tapée en entier : elle remplace le début d'office.
        compose.onNode(field).performTextReplacement("https://autre.net")
        compose.waitForIdle()
        assertEquals("https://autre.net", value)
        compose.onNodeWithText("https://autre.net").assertIsDisplayed()
        // Tout effacé : adresse vide, le début reste affiché.
        compose.onNode(field).performTextClearance()
        compose.waitForIdle()
        assertEquals("", value)
        compose.onNodeWithText("http://www.").assertIsDisplayed()
    }

    @Test
    fun `an address without its start is shown as it will be read`() {
        // Lien importé sans « http:// » : pas de début affiché qui n'y serait pas.
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                NexTvTextField(value = "exemple.com/liste.m3u", onValueChange = {}, label = "URL de la playlist", addressPrefix = true)
            }
        }
        compose.onNodeWithText("exemple.com/liste.m3u").assertIsDisplayed()
    }

    @Test
    fun `a saved address is shown whole and can be completed`() {
        var value by mutableStateOf("http://www.exemple.com")
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                NexTvTextField(value = value, onValueChange = { value = it }, label = "URL de la playlist", addressPrefix = true)
            }
        }
        compose.onNodeWithText("http://www.exemple.com").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("/liste.m3u")
        compose.waitForIdle()
        assertEquals("http://www.exemple.com/liste.m3u", value)
    }
}
