package tv.nextv.app.ui.editor

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.core.repository.SourceInput
import tv.nextv.app.AppContainer
import tv.nextv.app.King
import tv.nextv.app.PlaylistImport
import tv.nextv.app.ui.phonesetup.PhoneImportResult
import tv.nextv.app.ui.phonesetup.PhoneSetupPage
import tv.nextv.app.ui.phonesetup.phoneImport
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.model.SourceLocation

/** Onglet King : identifiant et mot de passe, serveurs (principal et secours) fournis par l'application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KingAccountTest {

    @get:Rule
    val compose = createComposeRule()

    private val previous = King.servers

    @Before
    fun servers() {
        King.servers = King.parse("http://king-a.test:80\nhttp://king-b.test:80, http://king-c.test")
    }

    @After
    fun restore() {
        King.servers = previous
    }

    private fun idleUntil(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        error("délai dépassé")
    }

    @Test
    fun `a King account is saved with the built-in servers and reopens in the King tab`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val editor = SourceEditorViewModel(container, SavedStateHandle())
        editor.edit { it.withMode(LocationMode.KING).copy(xtreamUsername = " moi ", xtreamPassword = "secret") }
        editor.save()
        idleUntil { editor.state.value.saved != null }

        val source = runBlocking { container.playlistStore.getSources() }.single()
        assertEquals(King.DEFAULT_NAME, source.name)
        val url = (source.location as SourceLocation.Remote).url
        assertTrue(url, url.startsWith("http://king-a.test:80/get.php?username=moi&password=secret"))
        assertEquals(listOf("http://king-b.test:80", "http://king-c.test"), source.mirrors)

        val reopened = SourceEditorViewModel(container, SavedStateHandle(mapOf(SourceEditorViewModel.ARG_SOURCE_ID to source.id)))
        idleUntil { !reopened.state.value.loading && reopened.state.value.sourceId != null }
        assertEquals(LocationMode.KING, reopened.state.value.mode)
        assertEquals("moi", reopened.state.value.xtreamUsername)
        assertEquals("secret", reopened.state.value.xtreamPassword)
    }

    @Test
    fun `a King account sent from the phone opens in the King tab`() {
        val result = phoneImport(mapOf("mode" to "king", "username" to "moi", "password" to "secret")) as PhoneImportResult.Ok
        assertTrue(result.import.url.startsWith("http://king-a.test:80/get.php?username=moi"))
        assertEquals(King.DEFAULT_NAME, result.import.name)
        assertTrue(phoneImport(mapOf("mode" to "king", "username" to "moi", "password" to "")) is PhoneImportResult.Error)
        assertTrue(PhoneSetupPage.form(error = null, king = true).contains("value=\"king\" checked"))

        val container = AppContainer(RuntimeEnvironment.getApplication())
        container.playlistImport.value = result.import
        val editor = SourceEditorViewModel(container, SavedStateHandle())
        assertEquals(LocationMode.KING, editor.state.value.mode)
        assertEquals("moi", editor.state.value.xtreamUsername)
        // Lien quelconque : pas King.
        container.playlistImport.value = PlaylistImport("http://autre.test/get.php?username=a&password=b", null, null, null)
        assertEquals(LocationMode.URL, SourceEditorViewModel(container, SavedStateHandle()).state.value.mode)
    }

    @Test
    fun `the King tab asks only for the username and password`() {
        var state by mutableStateOf(EditorState(name = ""))
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
        compose.onNodeWithText("King").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(LocationMode.KING, state.mode)
        compose.onNodeWithText("Identifiant").assertExists()
        compose.onNodeWithText("Adresse du serveur").assertDoesNotExist()
        compose.onNodeWithText("Ajouter un serveur").assertDoesNotExist()
    }

    /**
     * Nouveaux serveurs King dans une version plus récente : une playlist King déjà enregistrée les reprend au
     * démarrage, une autre playlist n'est pas touchée.
     */
    @Test
    fun `saved King playlists follow the servers of the app`() = runBlocking {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val store = container.playlistStore
        val king = container.sourceRepository.add(
            SourceInput("King", SourceLocation.Remote("http://king-a.test:80/get.php?username=moi&password=secret&type=m3u_plus"), mirrors = listOf("http://king-b.test:80")),
        )
        val other = container.sourceRepository.add(
            SourceInput("Autre", SourceLocation.Remote("http://autre.test/get.php?username=a&password=b"), mirrors = listOf("http://autre-2.test")),
        )
        // Version suivante : un serveur ajouté (king-d), un retiré (king-b).
        King.servers = King.parse("http://king-a.test:80 http://king-c.test http://king-d.test")

        assertEquals(1, King.syncMirrors(store))
        assertEquals(listOf("http://king-c.test", "http://king-d.test"), store.getSource(king)!!.mirrors)
        assertEquals(listOf("http://autre-2.test"), store.getSource(other)!!.mirrors)
        // Déjà à jour : rien à faire au démarrage suivant.
        assertEquals(0, King.syncMirrors(store))
    }
}
