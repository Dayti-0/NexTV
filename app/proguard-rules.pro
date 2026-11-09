# Les bibliothèques utilisées (OkHttp, Media3, Room, Coil, coroutines) fournissent leurs propres règles R8.
# Le module `core` n'utilise pas de réflexion.

# Décodeur FFmpeg du son : chargé par réflexion par Media3 (DefaultRenderersFactory).
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }

# Bande-annonce : la page du lecteur YouTube appelle NixTv.show(...) (méthodes @JavascriptInterface).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Son MP2 (JLayer) : ses tables (sfd.ser…) sont lues par Class.getResourceAsStream relatif à ses classes,
# qui gardent donc leur nom et leur paquet. Le lecteur audio de JLayer (javax.sound, applets) n'est pas utilisé.
-keep class javazoom.jl.decoder.** { *; }
-dontwarn javazoom.jl.player.**
-dontwarn javazoom.jl.converter.**
-dontwarn javax.sound.**
