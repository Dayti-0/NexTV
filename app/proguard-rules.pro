# Les bibliothèques utilisées (OkHttp, Media3, Room, Coil, coroutines) fournissent leurs propres règles R8.
# Le module `core` n'utilise pas de réflexion.

# Décodeur FFmpeg du son : chargé par réflexion par Media3 (DefaultRenderersFactory).
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }

# Bande-annonce : la page du lecteur YouTube appelle NixTv.show(...) (méthodes @JavascriptInterface).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
