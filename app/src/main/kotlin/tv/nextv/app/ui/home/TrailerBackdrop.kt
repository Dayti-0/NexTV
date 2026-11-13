package tv.nextv.app.ui.home

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import android.webkit.JavascriptInterface
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog

import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Bande-annonce YouTube ([key]) dans le bandeau, sans les commandes, après [delayMillis] sur le même contenu
 * (pas de lecture en passant d'une affiche à l'autre ; un seul bandeau, donc une seule bande-annonce).
 * Elle passe par une page web (lecteur YouTube intégré) qui ne prend jamais le focus de la télécommande,
 * et se met en pause quand l'appli passe en arrière-plan. Muette selon [muted] (changeable en cours de
 * lecture). Lue une fois : à la fin, ou si YouTube refuse la lecture intégrée, [onFinished] (l'image de fond
 * revient). [onShown] : la vidéo est visible (bouton du son). Sous-titres selon sa langue ([language],
 * donnée par TMDB) : aucun en français (VF, ou VOSTFR qui a déjà les siens dans l'image), en français
 * pour une autre langue, au choix de YouTube si elle est inconnue.
 *
 * Le cadre du lecteur est fixe ([BoxWithConstraints] de [modifier]) : il ne doit pas changer de taille
 * pendant la lecture (la page serait recréée).
 */
@Composable
internal fun TrailerBackdrop(
    key: String,
    language: String?,
    modifier: Modifier = Modifier,
    delayMillis: Long = TRAILER_DELAY_MILLIS,
    muted: Boolean = true,
    onShown: (Boolean) -> Unit = {},
    onFinished: () -> Unit = {},
    /** YouTube refuse de lire cette vidéo dans l'appli (lecture intégrée interdite par son auteur, vidéo retirée). */
    onFailed: () -> Unit = {},
    /**
     * Agrandissement de la vidéo dans le cadre (bords rognés) : les bandes-annonces au format cinéma ont des
     * bandes noires en haut et en bas, qui la faisaient paraître petite. 1 : vidéo entière (plein écran).
     */
    zoom: Float = 1f,
) {
    // Le lecteur se prépare dès [PRELOAD_AFTER_MILLIS] (caché, muet), pendant le délai : la bande-annonce
    // apparaît au bout du délai si elle est prête (le chargement de YouTube prend plusieurs secondes sur
    // la Fire TV ; enchaîné après le délai, il la faisait attendre 10 à 20 s). Le court délai de préparation
    // évite d'ouvrir un lecteur à chaque affiche survolée.
    val preloadAfter = minOf(delayMillis, PRELOAD_AFTER_MILLIS)
    var load by remember(key) { mutableStateOf(preloadAfter <= 0) }
    var delayPassed by remember(key) { mutableStateOf(delayMillis <= 0) }
    LaunchedEffect(key, delayMillis) {
        if (delayPassed) return@LaunchedEffect
        delay(preloadAfter)
        load = true
        delay(delayMillis - preloadAfter)
        delayPassed = true
    }
    DisposableEffect(key) { onDispose { onShown(false) } }
    // Mémoire de l'appareil épuisée : pas de bande-annonce automatique pendant un moment (celle qui joue
    // s'arrête, l'image reste). Le bouton « Bande-annonce » (sans délai) reste permis.
    if (delayMillis > 0 && TrailerMemory.paused) return
    if (!load) return
    // Taille du cadre donnée au lecteur en pixels CSS (= dp) : sur la Fire TV, la page calcule mal la
    // hauteur disponible (100 %, vh), et la vidéo ne tenait qu'une bande en haut, ou rien (carré noir).
    BoxWithConstraints(modifier) {
        val width = maxWidth.value.roundToInt()
        val height = maxHeight.value.roundToInt()
        if (width > 0 && height > 0) {
            androidx.compose.runtime.key(key, width, height) {
                TrailerPage(key, captions(language), width, height, zoom, muted, delayPassed, onShown, onFinished, onFailed)
            }
        }
    }
}

/**
 * Bande-annonce en plein écran, avec le son (bouton « Bande-annonce ») : « Retour » la ferme, et elle se ferme
 * d'elle-même à la fin. Une vidéo qui ne se lit pas (lecture intégrée refusée, vidéo retirée, réservée à un pays ou
 * aux adultes, ou qui ne démarre pas) laisse la place à la suivante des [alternatives] (les autres bandes-annonces
 * du titre connues de TMDB) ; quand il n'en reste plus, la première s'ouvre dans l'application YouTube de
 * l'appareil, ou un message le dit s'il n'y en a pas.
 */
@Composable
internal fun TrailerFullScreen(
    key: String,
    language: String?,
    onClose: () -> Unit,
    alternatives: suspend () -> List<Pair<String, String?>> = { emptyList() },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var current by remember(key) { mutableStateOf(key to language) }
    val tried = remember(key) { mutableSetOf(key) }
    var others by remember(key) { mutableStateOf<List<Pair<String, String?>>?>(null) }
    // Vidéo en échec : sa fin (signalée juste après l'échec) ne ferme pas l'écran, la suivante est essayée.
    var failedKey by remember(key) { mutableStateOf<String?>(null) }
    val close by rememberUpdatedState(onClose)
    val onFailed: () -> Unit = {
        val failed = current.first
        failedKey = failed
        scope.launch {
            val list = others ?: runCatching { alternatives() }.getOrDefault(emptyList()).also { others = it }
            val next = list.firstOrNull { it.first !in tried }
            if (next != null) {
                tried += next.first
                current = next
            } else {
                openTrailerExternally(context, key)
                close()
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                // Les touches ne vont à rien d'autre (la page ne prend jamais le focus) ; « Retour » ferme.
                .focusRequester(focus)
                .focusable(),
        ) {
            TrailerBackdrop(
                current.first, current.second, Modifier.fillMaxSize(), delayMillis = 0, muted = false,
                onFinished = { if (failedKey != current.first) close() },
                onFailed = onFailed,
            )
        }
    }
}

/**
 * Ouvre la bande-annonce [key] dans une application YouTube : d'abord celles des TV (Fire TV, Android TV, SmartTube),
 * pour que le navigateur ne s'en empare pas, puis toute application qui comprend le lien, enfin le navigateur. Le lien
 * web d'abord : certaines (SmartTube) refusent `vnd.youtube:`. Message si aucune ne peut l'ouvrir.
 * @return vrai si une application l'a ouverte.
 */
internal fun openTrailerExternally(context: android.content.Context, key: String): Boolean {
    val links = listOf(android.net.Uri.parse("https://www.youtube.com/watch?v=$key"), android.net.Uri.parse("vnd.youtube:$key"))
    val intents = YOUTUBE_APPS.flatMap { app -> links.map { android.content.Intent(android.content.Intent.ACTION_VIEW, it).setPackage(app) } } +
        links.map { android.content.Intent(android.content.Intent.ACTION_VIEW, it) }
    val opened = intents.any { intent -> runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    if (!opened) android.widget.Toast.makeText(context, tv.nextv.app.R.string.trailer_unavailable, android.widget.Toast.LENGTH_LONG).show()
    return opened
}

/**
 * Ouvre la bande-annonce [key] dans SmartTube (YouTube sans publicité) s'il est installé sur l'appareil.
 * @return faux s'il ne l'est pas (la bande-annonce s'ouvre alors dans le lecteur de l'appli).
 */
internal fun openTrailerWithoutAds(context: android.content.Context, key: String): Boolean {
    val link = android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
    return SMARTTUBE_APPS.any { app ->
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, link).setPackage(app).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}

/** SmartTube (stable, bêta, ancien nom) : YouTube pour la télé, sans publicité. */
private val SMARTTUBE_APPS = listOf(
    "com.teamsmart.videomanager.tv",
    "com.liskovsoft.smarttubetv.beta",
    "com.liskovsoft.smarttubetv",
)

/** Applications YouTube des TV et des téléphones, par ordre de préférence. */
private val YOUTUBE_APPS = listOf(
    "com.amazon.firetv.youtube",
    "com.google.android.youtube.tv",
    "com.liskovsoft.smarttubetv.beta",
    "com.liskovsoft.smarttubetv",
    "com.google.android.youtube",
)

/** Page du lecteur YouTube, mise en pause quand l'appli passe en arrière-plan. */
@Composable
private fun TrailerPage(
    key: String,
    captions: Captions,
    width: Int,
    height: Int,
    zoom: Float,
    muted: Boolean,
    /** Délai d'attente écoulé : la vidéo peut apparaître (avant, elle se prépare cachée et muette). */
    delayPassed: Boolean,
    onShown: (Boolean) -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit,
) {
    var view by remember { mutableStateOf<WebView?>(null) }
    // Moteur de la page arrêté par le système (mémoire) : la vue n'est plus utilisable, elle est retirée.
    var gone by remember { mutableStateOf(false) }
    // Vue cachée (alpha) avant la fin du délai : la page y prépare la vidéo (une vidéo cachée dans la page
    // n'était jamais lancée), puis la signale prête quand elle joue depuis son début.
    var ready by remember { mutableStateOf(false) }
    val visible = ready && delayPassed
    // Fondu court : le début de la vidéo se voit (un fondu long en cachait la première seconde).
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "trailer")
    val shown by rememberUpdatedState(onShown)
    val finished by rememberUpdatedState(onFinished)
    val failed by rememberUpdatedState(onFailed)
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
    // Son coupé ou rendu en cours de lecture (bouton du bandeau) ; jamais de son tant qu'elle est cachée.
    LaunchedEffect(view, muted, delayPassed) { view?.evaluateJavascript("setMuted(${if (muted || !delayPassed) "true" else "false"})", null) }
    // Délai écoulé : la vidéo préparée repart de 0 et apparaît.
    LaunchedEffect(view, delayPassed) { if (delayPassed) view?.evaluateJavascript("allow()", null) }
    LaunchedEffect(visible) { shown(visible) }
    if (gone) return
    AndroidView(
        factory = { context ->
            trailerView(
                context, key, captions, width, height, zoom, muted || !delayPassed, delayPassed,
                onVisible = { ready = it },
                onFinished = { ready = false; shown(false); finished() },
                onFailed = { failed() },
                onGone = { crashed ->
                    gone = true
                    view = null
                    ready = false
                    TrailerMemory.pause()
                    noteRendererGone(context, crashed)
                    shown(false)
                    finished()
                },
            ).also { view = it }
        },
        update = { web -> web.alpha = alpha },
        onRelease = { web -> web.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerView(
    context: android.content.Context,
    key: String,
    captions: Captions,
    width: Int,
    height: Int,
    zoom: Float,
    startMuted: Boolean,
    /** Délai d'attente déjà écoulé : la vidéo apparaît dès qu'elle démarre. */
    startAllowed: Boolean,
    onVisible: (Boolean) -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit,
    /** Moteur de la page arrêté (vrai : planté ; faux : fermé par le système pour libérer de la mémoire). */
    onGone: (Boolean) -> Unit,
): WebView = WebView(context).apply {
    alpha = 0f
    // Le moteur de la page tourne dans un processus à part, que le système ferme en premier quand la mémoire
    // manque (YouTube en prend beaucoup). Sans cette réponse, Android ferme toute l'application avec lui
    // (« Fermeture brutale » sur la Fire TV, écran Films) : la vue est seulement retirée, l'image revient.
    webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            view.visibility = View.GONE
            // Appelé seulement à partir d'Android 8, où l'on sait si la page a planté ou a été fermée par le système.
            val crashed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) detail.didCrash() else true
            onGone(crashed)
            return true
        }
    }
    // La page signale quand montrer la vidéo (elle joue depuis son début), la cacher, et la fin.
    val web = this
    addJavascriptInterface(
        object {
            @JavascriptInterface
            fun show(value: Boolean) {
                web.post { onVisible(value) }
            }

            @JavascriptInterface
            fun finished() {
                web.post { onFinished() }
            }

            @JavascriptInterface
            fun failed() {
                web.post { onFailed() }
            }
        },
        "NixTv",
    )
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    // Transparente tant que la vidéo est cachée (pendant le délai) : l'image reste visible dessous.
    setBackgroundColor(AndroidColor.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    // Lecteur agrandi de [zoom] et centré : ce qui dépasse du cadre est rogné.
    val playerWidth = (width * zoom).roundToInt()
    val playerHeight = (height * zoom).roundToInt()
    val left = (width - playerWidth) / 2
    val top = (height - playerHeight) / 2
    // Page avec le lecteur intégré, à la taille exacte du cadre : son adresse de base sert d'origine
    // (YouTube refuse un lecteur sans). L'API YouTube se branche sur ce lecteur pour le son, la pause,
    // la fin et les sous-titres. Lue une fois (pas de boucle).
    val html = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
        html,body{margin:0;width:${width}px;height:${height}px;background:transparent;overflow:hidden}
        iframe{position:absolute;top:${top}px;left:${left}px;width:${playerWidth}px;height:${playerHeight}px;border:0;pointer-events:none}
        </style></head><body>
        <iframe id="player" width="$playerWidth" height="$playerHeight" src="https://www.youtube-nocookie.com/embed/$key?autoplay=1&mute=1&controls=0&modestbranding=1&rel=0&playsinline=1&iv_load_policy=3&disablekb=1&fs=0&hl=fr&cc_load_policy=${captions.loadPolicy}${if (captions == Captions.FRENCH) "&cc_lang_pref=fr" else ""}&enablejsapi=1&origin=https%3A%2F%2Fnixtv.app"
        allow="autoplay; encrypted-media" referrerpolicy="strict-origin-when-cross-origin"></iframe>
        <script>
        var player=null;
        // Son voulu (bouton du bandeau, réglage) ; la lecture démarre toujours muette (lecture automatique).
        var muted=${if (startMuted) "true" else "false"};
        // Vidéo cachée tant que le délai d'attente n'est pas écoulé : elle se prépare (chargée, en pause sur sa
        // première image), puis repart de 0 et apparaît aussitôt (le titre YouTube du début peut se voir).
        var hidden=true;
        var allowed=${if (startAllowed) "true" else "false"};
        var primed=false;
        var done=false;
        var startTimer=null;
        function call(f){try{f();}catch(e){}}
        function show(v){call(function(){NixTv.show(v);});}
        function finish(){if(done)return;done=true;clearTimeout(startTimer);call(function(){NixTv.finished();});}
        function applySound(){call(function(){if(muted||hidden){player.mute();}else{player.unMute();player.setVolume(100);}});}
        function setMuted(m){muted=m;applySound();}
        function reveal(){clearTimeout(startTimer);hidden=false;show(true);applySound();}
        // Rien ne démarre (réseau, page bloquée) : on abandonne, l'image reste.
        function watchStart(){clearTimeout(startTimer);startTimer=setTimeout(function(){if(hidden&&!done){call(function(){NixTv.failed();});finish();}},$START_TIMEOUT_MILLIS);}
        // Délai écoulé : lecture depuis le début.
        function allow(){
          if(allowed||done)return;
          allowed=true;
          watchStart();
          if(primed)call(function(){player.seekTo(0,true);player.playVideo();});
        }
        function pause(){call(function(){player.pauseVideo();});}
        function resume(){if(done||!allowed)return;call(function(){player.playVideo();});}
        var captions='${captions.name}';
        // Sous-titres en français posés (piste française, ou traduction) ; en attendant, l'anglais.
        var captionsSet=false;
        var fallbackSet=false;
        function track(list,lang){return list.filter(function(t){return (t.languageCode||'').indexOf(lang)==0;})[0];}
        function applyCaptions(p){
          if(captions=='OFF'){call(function(){p.setOption('captions','track',{});});return;}
          if(captions!='FRENCH'||captionsSet)return;
          call(function(){
            var list=p.getOption('captions','tracklist')||[];
            if(!list.length)return;
            // Piste française d'abord.
            var fr=track(list,'fr');
            if(fr){p.setOption('captions','track',{languageCode:fr.languageCode});captionsSet=true;return;}
            // Sinon la traduction automatique de YouTube en français, depuis l'anglais (ou la piste qui existe).
            var source=track(list,'en')||list[0];
            var target=track(p.getOption('captions','translationLanguages')||[],'fr');
            if(source&&target){
              p.setOption('captions','track',{languageCode:source.languageCode,translationLanguage:target});
              captionsSet=true;
              return;
            }
            // Pas (encore) de traduction : l'anglais en attendant ; aucune autre langue (une piste coréenne n'aide pas).
            if(!fallbackSet){var en=track(list,'en');p.setOption('captions','track',en?{languageCode:en.languageCode}:{});fallbackSet=true;}
          });
        }
        // Les pistes arrivent après le démarrage : réglées encore pendant les premières secondes.
        function watchCaptions(p){
          var i=setInterval(function(){applyCaptions(p);},1000);
          setTimeout(function(){clearInterval(i);},12000);
        }
        watchStart();
        function onYouTubeIframeAPIReady(){
          player=new YT.Player('player',{
            events:{
              onReady:function(e){applyCaptions(e.target);watchCaptions(e.target);},
              onApiChange:function(e){applyCaptions(e.target);},
              // Lecture (1) : avant la fin du délai, arrêt sur la première image (prête) ; après, la vidéo apparaît.
              // Fin (0) : terminée.
              onStateChange:function(e){
                if(e.data==1&&hidden){
                  if(allowed){reveal();}
                  else{primed=true;clearTimeout(startTimer);call(function(){e.target.pauseVideo();e.target.seekTo(0,true);});}
                }
                if(e.data==0){hidden=true;show(false);finish();}
              },
              // Lecture intégrée refusée par la vidéo (101, 150), vidéo introuvable… : l'image reste.
              onError:function(){if(!done){call(function(){NixTv.failed();});}hidden=true;show(false);finish();}
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

    /** Autre langue : en français (piste française, sinon traduction automatique de YouTube, sinon anglaise). */
    FRENCH(1),

    /** Langue inconnue : au choix de YouTube. */
    DEFAULT(0),
}

private fun captions(language: String?): Captions = when {
    language.isNullOrEmpty() -> Captions.DEFAULT
    language.startsWith("fr") -> Captions.OFF
    else -> Captions.FRENCH
}

/** Agrandissement des bandes-annonces du bandeau et des fiches (bandes noires du format cinéma rognées). */
internal const val TRAILER_FILL_ZOOM = 1.25f

/** Temps passé sur la même affiche avant que sa bande-annonce démarre (réglable). */
internal const val TRAILER_DELAY_MILLIS = 3_000L

/** Préparation du lecteur (caché, muet) après ce temps sur le même contenu, pendant le délai. */
private const val PRELOAD_AFTER_MILLIS = 600L

/**
 * Prépare le navigateur intégré (la première page web de l'appli met plusieurs secondes à s'ouvrir sur la
 * Fire TV) : à appeler une fois, à l'arrivée sur un écran qui lira des bandes-annonces.
 */
internal fun warmUpWebView(context: android.content.Context) {
    runCatching { WebView(context).destroy() }
}

/**
 * Bandes-annonces automatiques suspendues quand la mémoire de l'appareil est épuisée : signal « critique » du
 * système, ou page de la bande-annonce fermée par lui. Une page YouTube prend plusieurs centaines de Mo, de quoi
 * faire fermer l'application sur une Fire TV.
 */
internal object TrailerMemory {
    private var pausedUntil by mutableLongStateOf(0L)

    /** Horloge (remplacée dans les tests). */
    var clock: () -> Long = android.os.SystemClock::elapsedRealtime

    /** Lu dans une composition : elle se refait quand la suspension commence (pas à sa fin, relue au prochain contenu). */
    val paused: Boolean get() = pausedUntil > clock()

    /** À appeler sur le fil principal. */
    fun pause() {
        pausedUntil = clock() + TRAILER_MEMORY_PAUSE_MILLIS
    }

    internal fun reset() {
        pausedUntil = 0L
    }
}

/** Durée de la suspension des bandes-annonces automatiques après un manque de mémoire. */
internal const val TRAILER_MEMORY_PAUSE_MILLIS = 10 * 60_000L

/** Note dans « Dernier incident » (sans le remplacer s'il s'agit d'un plantage) la page arrêtée par le système. */
private fun noteRendererGone(context: android.content.Context, crashed: Boolean) {
    val app = context.applicationContext as? tv.nextv.app.NexTvApplication ?: return
    val reason = if (crashed) "plantage de la page YouTube" else "fermée par le système, mémoire insuffisante"
    app.crashLog.record(TrailerStoppedException("Bande-annonce arrêtée ($reason) : application gardée ouverte"), fatal = false)
}

private class TrailerStoppedException(message: String) : RuntimeException(message)

/** Lecture qui ne démarre pas (réseau, page bloquée) : abandonnée au bout de ce temps. */
private const val START_TIMEOUT_MILLIS = 20_000
