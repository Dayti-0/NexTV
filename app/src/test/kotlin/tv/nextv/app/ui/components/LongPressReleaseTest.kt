package tv.nextv.app.ui.components

import android.app.Application
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle

/**
 * Appui long sur OK qui retire l'élément (favori) : la sélection passe à l'élément suivant, et la fin de l'appui
 * (répétitions, relâchement) ne doit pas l'ouvrir.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class LongPressReleaseTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun televisionDevice() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun releasingALongPressDoesNotOpenTheNextItem() {
        val items = mutableStateListOf("Film A", "Film B")
        val opened = mutableListOf<String>()
        val first = FocusRequester()
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                Column {
                    items.forEachIndexed { index, title ->
                        androidx.compose.runtime.key(title) {
                            FocusSurface(
                                onClick = { opened += title },
                                onLongClick = { items.remove(title) },
                                modifier = Modifier.size(120.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                            ) { Text(title) }
                        }
                    }
                }
                // Sélection sur la première case dès que la fenêtre l'accepte (quelques images) ; case retirée :
                // la sélection passe à la suivante, comme dans les listes de l'accueil.
                LaunchedEffect(items.size) {
                    for (attempt in 1..10) {
                        if (runCatching { first.requestFocus() }.getOrDefault(false)) break
                        withFrameNanos { }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Comme la télécommande : pression, répétitions tant que OK est tenu, puis relâchement.
        ok(KeyEvent.ACTION_DOWN, repeat = 0)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        for (repeat in 1..10) {
            ok(KeyEvent.ACTION_DOWN, repeat)
            compose.mainClock.advanceTimeBy(50)
        }
        ok(KeyEvent.ACTION_UP, repeat = 0)
        compose.waitForIdle()
        assertEquals(listOf("Film B"), items.toList())
        assertEquals(emptyList<String>(), opened)
        // Un nouvel appui court ouvre bien l'élément sélectionné.
        ok(KeyEvent.ACTION_DOWN, repeat = 0)
        ok(KeyEvent.ACTION_UP, repeat = 0)
        compose.waitForIdle()
        assertEquals(listOf("Film B"), opened)
    }

    private fun ok(action: Int, repeat: Int) {
        val event = KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat)
        compose.onRoot().performKeyPress(androidx.compose.ui.input.key.KeyEvent(event))
    }
}
