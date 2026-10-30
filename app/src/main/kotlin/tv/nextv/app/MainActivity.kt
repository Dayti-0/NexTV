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

class MainActivity : ComponentActivity() {

    /** Action du double appui rapide sur « Retour » (retour à l'accueil), fournie par la navigation. */
    var onDoubleBack: (() -> Unit)? = null

    /** Touches traitées avant l'écran affiché (bandeau de rappel) ; vrai : touche consommée. */
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    /** Relâchement du dernier « Retour » (horloge des événements). */
    private var lastBackUpTime = 0L
    private var doubleBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readPlaylistImport(intent)
        setContent {
            NexTvTheme {
                Surface {
                    NexTvNavHost()
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
     * Double appui rapide : le premier agit normalement, le second ramène à l'accueil.
     * (L'appui long est souvent réservé par le système, par exemple pour fermer l'application.)
     *
     * `Activity.dispatchKeyEvent` est public ; seule sa redéfinition dans androidx est marquée interne.
     * Le retour prédictif est désactivé (manifeste) et la touche aboutit aux mêmes callbacks
     * (OnBackPressedDispatcher) que le geste.
     */
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val toHome = onDoubleBack
                if (toHome != null && !doubleBack && event.repeatCount == 0 && event.eventTime - lastBackUpTime <= DOUBLE_BACK_MILLIS) {
                    doubleBack = true
                    lastBackUpTime = 0
                    toHome()
                }
            }
            KeyEvent.ACTION_UP -> when {
                // Relâchement du second appui : pas de « Retour » de plus.
                doubleBack -> doubleBack = false
                !event.isCanceled -> {
                    lastBackUpTime = event.eventTime
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        return true
    }

    private companion object {
        const val DOUBLE_BACK_MILLIS = 400L
    }
}
