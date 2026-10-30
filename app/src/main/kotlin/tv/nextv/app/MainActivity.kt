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
     * Double appui rapide sur « Retour » : le premier agit normalement, le second ramène à l'accueil.
     * Intercepté ici plutôt que dans Compose : indépendant de l'élément qui a le focus.
     * (L'appui long est souvent réservé par le système, par exemple pour fermer l'application.)
     *
     * `Activity.dispatchKeyEvent` est public ; seule sa redéfinition dans androidx est marquée interne.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = onDoubleBack
        if (event.keyCode == KeyEvent.KEYCODE_BACK && action != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> when {
                    doubleBack -> return true
                    event.repeatCount == 0 && event.eventTime - lastBackUpTime <= DOUBLE_BACK_MILLIS -> {
                        doubleBack = true
                        lastBackUpTime = 0
                        action()
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    // Le relâchement du second appui ne doit pas déclencher un « Retour » de plus.
                    if (doubleBack) {
                        doubleBack = false
                        return true
                    }
                    lastBackUpTime = event.eventTime
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val DOUBLE_BACK_MILLIS = 400L
    }
}
