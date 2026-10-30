package tv.nextv.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import tv.nextv.app.ui.NexTvNavHost
import tv.nextv.app.ui.theme.NexTvTheme

class MainActivity : ComponentActivity() {

    /** Action de l'appui long sur « Retour » (retour à l'accueil), fournie par la navigation. */
    var onLongBack: (() -> Unit)? = null

    private var longBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexTvTheme {
                Surface {
                    NexTvNavHost()
                }
            }
        }
    }

    /**
     * Intercepté ici plutôt que dans Compose : indépendant de l'élément qui a le focus, et le
     * relâchement de la touche n'atteint pas le « Retour » habituel (qui fermerait l'accueil).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = onLongBack
        if (event.keyCode == KeyEvent.KEYCODE_BACK && action != null) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> longBack = false
                // Touche maintenue : elle se répète.
                event.action == KeyEvent.ACTION_DOWN -> {
                    if (!longBack) {
                        longBack = true
                        action()
                    }
                    return true
                }
                event.action == KeyEvent.ACTION_UP && longBack -> {
                    longBack = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
