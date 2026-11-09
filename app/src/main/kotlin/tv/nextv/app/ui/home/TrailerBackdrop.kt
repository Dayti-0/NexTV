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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlin.math.roundToInt

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
        // Taille du cadre donnée au lecteur en pixels CSS (= dp) : sur la Fire TV, la page calcule mal la
        // hauteur disponible (100 %, vh), et la vidéo ne tenait qu'une bande en haut, ou rien (carré noir).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value.roundToInt()
            val height = maxHeight.value.roundToInt()
            if (width > 0 && height > 0) {
                androidx.compose.runtime.key(width, height) {
                    TrailerPage(key, captions(language), width, height)
                }
            }
        }
    }
}

/** Page du lecteur YouTube, mise en pause quand l'appli passe en arrière-plan. */
@Composable
private fun TrailerPage(key: String, captions: Captions, width: Int, height: Int) {
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
        factory = { context -> trailerView(context, key, captions, width, height).also { view = it } },
        onRelease = { web -> web.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerView(context: android.content.Context, key: String, captions: Captions, width: Int, height: Int): WebView = WebView(context).apply {
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    // Transparente tant que la vidéo est cachée (commandes YouTube du démarrage) : l'image reste visible dessous.
    setBackgroundColor(AndroidColor.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    // Page avec le lecteur intégré, à la taille exacte du cadre : son adresse de base sert d'origine
    // (YouTube refuse un lecteur sans). Le cadre est au format de la vidéo (16/9) :
    // rien n'est coupé. L'API YouTube se branche sur ce lecteur pour le son, la pause et les sous-titres.
    val html = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
        html,body{margin:0;width:${width}px;height:${height}px;background:transparent;overflow:hidden}
        iframe{position:absolute;top:0;left:0;width:${width}px;height:${height}px;border:0;pointer-events:none;opacity:0;transition:opacity .8s}
        </style></head><body>
        <iframe id="player" width="$width" height="$height" src="https://www.youtube-nocookie.com/embed/$key?autoplay=1&mute=1&controls=0&loop=1&playlist=$key&modestbranding=1&rel=0&playsinline=1&iv_load_policy=3&disablekb=1&fs=0&hl=fr&cc_load_policy=${captions.loadPolicy}&enablejsapi=1&origin=https%3A%2F%2Fnixtv.app"
        allow="autoplay; encrypted-media" referrerpolicy="strict-origin-when-cross-origin"></iframe>
        <script>
        var player=null;
        // Vidéo cachée au démarrage, à la reprise et à chaque boucle : YouTube y affiche quelques secondes
        // son titre, sa chaîne et ses boutons. Elle apparaît (avec le son) une fois ces commandes parties.
        var hidden=true;
        var revealTimer=null;
        function frame(){return document.getElementById('player');}
        function hide(){hidden=true;clearTimeout(revealTimer);frame().style.opacity=0;call(function(){player.mute();});}
        function revealSoon(p){
          clearTimeout(revealTimer);
          revealTimer=setTimeout(function(){
            hidden=false;
            frame().style.opacity=1;
            call(function(){p.unMute();p.setVolume(100);});
          },${REVEAL_DELAY_MILLIS});
        }
        function pause(){try{player.pauseVideo();}catch(e){}}
        function resume(){hide();try{player.playVideo();}catch(e){}}
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
            events:{
              onReady:function(e){applyCaptions(e.target);watchCaptions(e.target);},
              onApiChange:function(e){applyCaptions(e.target);},
              // Lecture (1) : la vidéo apparaît après les commandes du démarrage ; fin (0, avant la boucle) : cachée.
              onStateChange:function(e){
                if(e.data==1&&hidden)revealSoon(e.target);
                if(e.data==0)hide();
              }
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

/** Attente après le début de la lecture avant de montrer la vidéo : le temps que les commandes YouTube disparaissent. */
private const val REVEAL_DELAY_MILLIS = 3_500

/** Temps passé sur la même affiche avant que sa bande-annonce démarre. */
private const val TRAILER_DELAY_MILLIS = 4_000L
