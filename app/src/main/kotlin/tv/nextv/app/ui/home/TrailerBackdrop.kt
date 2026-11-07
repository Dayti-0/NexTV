package tv.nextv.app.ui.home

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Bande-annonce YouTube ([key]) en fond de la grande affiche, sans le son ni les commandes, après
 * [TRAILER_DELAY_MILLIS] sur le même film (pas de lecture en passant d'une affiche à l'autre). Elle
 * passe par une page web (lecteur YouTube intégré) qui ne prend jamais le focus de la télécommande.
 */
@Composable
internal fun TrailerBackdrop(key: String, modifier: Modifier = Modifier) {
    var show by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(TRAILER_DELAY_MILLIS)
        show = true
    }
    AnimatedVisibility(show, enter = fadeIn(tween(800)), exit = fadeOut(), modifier = modifier) {
        AndroidView(
            factory = { context -> trailerView(context, key) },
            onRelease = { view -> view.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerView(context: android.content.Context, key: String): WebView = WebView(context).apply {
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    setBackgroundColor(AndroidColor.BLACK)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    // Page avec le lecteur intégré : son adresse de base sert d'origine (YouTube refuse un lecteur sans).
    val html = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
        html,body{margin:0;height:100%;background:#000;overflow:hidden}
        iframe{position:absolute;top:50%;left:50%;width:177.78vh;min-width:100%;height:56.25vw;min-height:100%;transform:translate(-50%,-50%);border:0;pointer-events:none}
        </style></head><body>
        <iframe src="https://www.youtube-nocookie.com/embed/$key?autoplay=1&mute=1&controls=0&loop=1&playlist=$key&modestbranding=1&rel=0&playsinline=1&iv_load_policy=3&disablekb=1&fs=0"
        allow="autoplay; encrypted-media" referrerpolicy="strict-origin-when-cross-origin"></iframe>
        </body></html>
    """.trimIndent()
    loadDataWithBaseURL("https://nixtv.app/", html, "text/html", "utf-8", null)
}

/** Temps passé sur la même affiche avant que sa bande-annonce démarre. */
private const val TRAILER_DELAY_MILLIS = 4_000L
