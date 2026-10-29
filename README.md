# NexTV

Lecteur IPTV Android (téléphone, tablette, Android TV) basé sur Media3.

## Sources prises en charge

| Source | Détails |
| --- | --- |
| Playlist M3U/M3U8 distante | URL HTTP ou HTTPS, redirections suivies (10 au maximum), compression gzip détectée sur le contenu. |
| Playlist M3U/M3U8 locale | Fichier choisi avec le sélecteur de fichiers Android (Storage Access Framework), autorisation persistante, relu à chaque actualisation. |
| Playlist protégée | Authentification HTTP Basic en réponse au défi `401` du serveur ; identifiants chiffrés (Android Keystore), jamais présentés à un autre hôte, masqués dans les journaux. Les identifiants saisis dans l'URL (`http://user:pass@…`) sont extraits et stockés de la même façon. |
| URL de flux directe | Si l'URL d'une « playlist » renvoie un manifeste HLS ou un flux vidéo (`video/*`, MPEG-TS, MP4), la source devient une chaîne unique. |
| Guide EPG XMLTV | URL HTTP/HTTPS ou fichier local `.xml` / `.xml.gz` ; guides annoncés par l'en-tête de la playlist (`url-tvg`, `x-tvg-url`) utilisables en plus. |

Plusieurs playlists peuvent être enregistrées, puis modifiées, supprimées et actualisées (individuellement, guide seul, ou toutes).
Un échec d'actualisation conserve les chaînes et le guide précédents ; l'erreur est affichée sur la playlist.

### Format M3U

En-tête `#EXTM3U` et ses attributs, `#EXTINF` (attributs entre guillemets doubles, simples ou sans guillemets, virgules dans les valeurs),
`#EXTGRP`, `#EXTVLCOPT` (User-Agent, Referer, Origin, Cookie), `#EXTHTTP`, `#KODIPROP` (en-têtes, détection DRM),
en-têtes au format Kodi (`url|User-Agent=…`), URL relatives (résolues par rapport à l'URL finale de la playlist), simples listes d'URL.

Le contenu reçu est vérifié avant analyse : page HTML (abonnement expiré, portail captif), réponse JSON, fichier binaire ou vide
produisent une erreur explicite plutôt qu'une liste de chaînes erronée.

### Compatibilité des flux

Chaque chaîne est classée à l'import :

- **pris en charge** : HTTP/HTTPS (HLS, DASH, Smooth Streaming, progressif TS/MP4/MKV…), avec proxy et en-têtes ;
- **limité** : RTSP et UDP/multicast, lus par Media3 sans proxy ;
- **non pris en charge** : RTMP, MMS, SRT, `file://`, URL relative d'un fichier local, flux protégés par DRM.

Une URL sans extension est d'abord lue comme flux progressif puis, en cas de format non reconnu, retentée en HLS.

## Réseau

- Délai de connexion 15 s, délai d'inactivité en lecture 30 s (20 s pour les flux) : un gros guide peut se télécharger longtemps tant que des données arrivent.
- Erreurs typées et messages dédiés : URL invalide, schéma non pris en charge, hôte introuvable, connexion refusée, délai dépassé, TLS, boucle de redirections, redirection vers un schéma non HTTP, HTTP 4xx/5xx, authentification requise/refusée/non prise en charge (Digest, NTLM…), proxy injoignable ou refusant l'authentification, fichier introuvable, accès au document perdu, contenu trop volumineux.
- Limites de taille : 200 Mo téléchargés pour une playlist (400 Mo décompressés), 500 Mo pour un guide (2 Go décompressés).

### Proxy

Configurable dans les réglages : HTTP (HTTPS via `CONNECT`, authentification Basic) ou SOCKS (sans authentification),
appliqué au choix aux playlists et logos, aux guides EPG et aux flux vidéo HTTP/HTTPS (manifestes et segments HLS/DASH compris).
Les adresses du réseau local peuvent l'éviter. Limites réelles de l'implémentation (OkHttp / Media3) :
pas d'authentification SOCKS, pas de proxy pour RTSP et UDP, aucun proxy pour les fichiers locaux.

## Architecture

```
core/   Bibliothèque Kotlin/JVM sans dépendance Android (build Gradle inclus)
  model/       Sources, emplacements, chaînes, programmes, statuts
  io/          ContentOpener (HTTP(S) ; documents Android côté app), détection du contenu, limites de taille
  net/         Clients OkHttp par usage, proxy, redirections, authentification par origine, validation d'URL
  m3u/         Analyseur M3U en flux
  epg/         Lecteur XMLTV en flux (XmlPull), association des chaînes, import par lots
  stream/      Analyse des URL de flux (format, compatibilité, en-têtes)
  source/      PlaylistLoader par type de source + registre
  repository/  SourceRepository (ajout, modification, suppression, actualisation) et interfaces de stockage
  log/         Journalisation avec masquage des secrets
app/    Application Android : Room, DataStore, Keystore, SAF, Media3, Jetpack Compose
```

Points d'extension :

- **Nouveau type de source** (Xtream Codes, Stalker…) : ajouter une valeur à `SourceType`, implémenter `PlaylistLoader`
  et l'enregistrer dans le `PlaylistLoaderRegistry` (`AppContainer`).
- **Nouvelle origine de contenu** (SMB, FTP, cloud…) : ajouter une variante à `SourceLocation` et un `ContentOpener`.

## Compilation et tests

```bash
./gradlew -p core test          # tests du cœur (JVM, sans SDK Android)
./gradlew :app:assembleDebug    # application (SDK Android requis)
```

La CI GitHub Actions exécute les tests du cœur, la compilation de l'application et le lint Android.
