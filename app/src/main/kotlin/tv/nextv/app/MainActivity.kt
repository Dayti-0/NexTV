package tv.nextv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import tv.nextv.app.ui.NexTvNavHost
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.update.LaunchUpdatePrompt
import tv.nextv.app.ui.theme.ThemeStyle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    /** Retour à l'accueil (appui long sur « Retour »), fourni par la navigation. */
    var onLongBack: (() -> Unit)? = null

    private var longBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readPlaylistImport(intent)
        val settings = (application as NexTvApplication).container.settings
        setContent {
            val theme by settings.themeStyle.collectAsState(initial = ThemeStyle.NIGHT)
            NexTvTheme(theme) {
                Surface {
                    NexTvNavHost()
                    // Nouvelle version publiée : proposée au lancement.
                    LaunchUpdatePrompt()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readPlaylistImport(intent)
    }

    private fun readPlaylistImport(intent: Intent?) {
        val import = PlaylistImport.from(intent) ?: return
        (application as NexTvApplication).container.playlistImport.value = import
    }

    /**
     * « Retour » va directement à la navigation (menus, écran précédent) : sans cela, Compose s'en
     * sert d'abord pour faire remonter le focus vers un élément parent, et un appui semble ignoré.
     *
     * Appui court : écran ou menu précédent, au relâchement. Appui long : retour direct à l'accueil,
     * sans « Retour » au relâchement.
     *
     * `Activity.dispatchKeyEvent` est public ; seule sa redéfinition dans androidx est marquée interne.
     * Le retour prédictif est désactivé (manifeste) et la touche aboutit aux mêmes callbacks
     * (OnBackPressedDispatcher) que le geste.
     */
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) longBack = false
                val toHome = onLongBack
                // Touche maintenue : répétitions de la touche (ou appui long signalé par le système).
                if (toHome != null && event.repeatCount > 0 && !longBack &&
                    (event.isLongPress || event.eventTime - event.downTime >= LONG_BACK_MILLIS)
                ) {
                    longBack = true
                    toHome()
                }
            }
            KeyEvent.ACTION_UP -> when {
                // Relâchement de l'appui long : pas de « Retour » de plus.
                longBack -> longBack = false
                !event.isCanceled -> onBackPressedDispatcher.onBackPressed()
            }
        }
        return true
    }

    private companion object {
        const val LONG_BACK_MILLIS = 700L
    }
}
