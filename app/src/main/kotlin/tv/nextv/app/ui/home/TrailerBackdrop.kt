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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Bande-annonce YouTube ([key]) derrière la grande affiche, avec le son et sans les commandes, après
 * [TRAILER_DELAY_MILLIS] sur le même film (pas de lecture en passant d'une affiche à l'autre). Elle
 * passe par une page web (lecteur YouTube intégré) qui ne prend jamais le focus de la télécommande,
 * et se met en pause quand l'appli passe en arrière-plan. Sous-titres selon sa langue ([language],
 * donnée par TMDB) : aucun en français (VF, ou VOSTFR qui a déjà les siens dans l'image), en français
 * pour une autre langue, au choix de YouTube si elle est inconnue.
 */
@Composable
internal fun TrailerBackdrop(key: String, language: String?, modifier: Modifier = Modifier) {
    var show by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(TRAILER_DELAY_MILLIS)
        show = true
    }
    AnimatedVisibility(show, enter = fadeIn(tween(800)), exit = fadeOut(), modifier = modifier) {
        var view by remember { mutableStateOf<WebView?>(null) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, view) {
            val web = view
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    // Appli quittée (touche Accueil…) : le son ne continue pas en arrière-plan.
                    Lifecycle.Event.ON_STOP -> web?.evaluateJavascript("pause()", null)
                    Lifecycle.Event.ON_START -> web?.evaluateJavascript("resume()", null)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        AndroidView(
            factory = { context -> trailerView(context, key, captions(language)).also { view = it } },
            onRelease = { web -> web.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerView(context: android.content.Context, key: String, captions: Captions): WebView = WebView(context).apply {
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    setBackgroundColor(AndroidColor.BLACK)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    // Page avec le lecteur intégré : son adresse de base sert d'origine (YouTube refuse un lecteur sans).
    // La vidéo occupe tout le cadre (16/9), en entier : rien n'est coupé en haut ni en bas.
    val html = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
        html,body{margin:0;height:100%;background:#000;overflow:hidden}
        iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;pointer-events:none}
        </style></head><body>
        <div id="player"></div>
        <script>
        var player=null;
        function pause(){try{player.pauseVideo();}catch(e){}}
        function resume(){try{player.playVideo();}catch(e){}}
        var captions='${captions.name}';
        var captionsSet=false;
        function call(f){try{f();}catch(e){}}
        function track(list,lang){return list.filter(function(t){return (t.languageCode||'').indexOf(lang)==0;})[0];}
        function applyCaptions(p){
          if(captions=='OFF'){call(function(){p.setOption('captions','track',{});});return;}
          if(captions!='FRENCH'||captionsSet)return;
          call(function(){
            // Piste française, sinon anglaise ; aucune autre (une piste coréenne n'aide pas).
            var list=p.getOption('captions','tracklist')||[];
            if(!list.length)return;
            var t=track(list,'fr')||track(list,'en');
            p.setOption('captions','track',t?{languageCode:t.languageCode}:{});
            captionsSet=true;
          });
        }
        // Les pistes arrivent après le démarrage : réglées encore pendant les premières secondes.
        function watchCaptions(p){
          var i=setInterval(function(){applyCaptions(p);},1000);
          setTimeout(function(){clearInterval(i);},12000);
        }
        function onYouTubeIframeAPIReady(){
          player=new YT.Player('player',{
            host:'https://www.youtube-nocookie.com',
            videoId:'$key',
            playerVars:{autoplay:1,controls:0,loop:1,playlist:'$key',modestbranding:1,rel:0,playsinline:1,
              iv_load_policy:3,disablekb:1,fs:0,hl:'fr',origin:'https://nixtv.app',
              cc_load_policy:${captions.loadPolicy}},
            events:{
              onReady:function(e){e.target.unMute();e.target.setVolume(100);applyCaptions(e.target);watchCaptions(e.target);e.target.playVideo();},
              onApiChange:function(e){applyCaptions(e.target);}
            }
          });
        }
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
        </body></html>
    """.trimIndent()
    loadDataWithBaseURL("https://nixtv.app/", html, "text/html", "utf-8", null)
}

/** Sous-titres de la bande-annonce. */
private enum class Captions(val loadPolicy: Int) {
    /** Bande-annonce en français : aucun. */
    OFF(0),

    /** Autre langue : en français (piste française, sinon anglaise, sinon aucun). */
    FRENCH(1),

    /** Langue inconnue : au choix de YouTube. */
    DEFAULT(0),
}

private fun captions(language: String?): Captions = when {
    language.isNullOrEmpty() -> Captions.DEFAULT
    language.startsWith("fr") -> Captions.OFF
    else -> Captions.FRENCH
}

/** Temps passé sur la même affiche avant que sa bande-annonce démarre. */
private const val TRAILER_DELAY_MILLIS = 4_000L
