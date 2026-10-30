# NexTV

Lecteur IPTV Android (téléphone, tablette, Android TV) basé sur Media3, entièrement utilisable à la télécommande.

## Utilisation

- **Démarrage** : la dernière playlist utilisée est rechargée (si elle a plus de 12 h) avec son guide, avec une
  progression réelle (octets reçus, chaînes enregistrées, guide téléchargé puis importé), puis l'accueil s'ouvre.
  En cas d'échec : réessayer, continuer avec les données enregistrées ou choisir une autre playlist.
- **Accueil** : sections TV, Films, Séries et Guide TV, avec catégories (groupes de la playlist), recherche et favoris.
  Appui long sur OK : ajouter / retirer des favoris ; « Réorganiser » puis OK et flèches pour les déplacer.
- **Films et séries** : fiche enrichie par TMDB (synopsis, note, genres, affiche, bande-annonce), saisons et épisodes.
  Une clé d'API TMDB se saisit dans les réglages (ou s'intègre à la compilation, voir plus bas).
- **Lecture en direct** : bandeau de 5 s au changement de chaîne ou sur OK (programme en cours avec horaires et
  progression, programme suivant, qualité, débit mesuré en Mbit/s) ; haut / bas pour zapper ; Retour ouvre la liste
  des chaînes à gauche sans couper la lecture (catégories avec gauche / droite, chaînes avec haut / bas, OK pour
  lancer), un second Retour la ferme.
- **Qualité automatique** : quand une chaîne existe en plusieurs versions dans la playlist (« FHD », « HD », « SD »,
  « LOW » dans le nom), la lecture descend d'un cran en cas d'erreur ou de mise en mémoire tampon persistante, puis
  remonte quand le débit mesuré le permet. Jamais de bascule vers une autre chaîne ; désactivable dans les réglages.
- **Guide TV** : grille chaînes × horaires avec repère de l'heure actuelle et programme en cours mis en valeur ;
  OK ouvre la fiche du programme (horaires, durée, description) avec « Regarder ». Un créneau sans donnée reste vide.
- **Affichage** : nombre de chaînes (3 à 9) et d'affiches (4 à 10) par ligne réglable sur TV et tablette.
- **Retour à l'accueil** : double appui rapide sur Retour, depuis n'importe quel écran.
- **Ajout d'une playlist depuis un ordinateur** (adb), pour éviter la saisie à la télécommande : l'éditeur s'ouvre
  prérempli, il reste à confirmer avec OK.

  ```
  adb shell "am start -n tv.nextv.app/.MainActivity --es url 'http://exemple.com/playlist.m3u' --es name 'Ma playlist'"
  ```

  Paramètres facultatifs : `--es user '…' --es password '…'` pour les identifiants.

## Sources prises en charge

| Source | Détails |
| --- | --- |
| Playlist M3U/M3U8 distante | URL HTTP ou HTTPS, redirections suivies (10 au maximum), compression gzip détectée sur le contenu. |
| Playlist M3U/M3U8 locale | Fichier choisi avec le sélecteur de fichiers Android (Storage Access Framework), autorisation persistante, relu à chaque actualisation. |
| Playlist protégée | Authentification HTTP Basic en réponse au défi `401` du serveur ; identifiants chiffrés (Android Keystore), jamais présentés à un autre hôte, masqués dans les journaux. Les identifiants saisis dans l'URL (`http://user:pass@…`) sont extraits et stockés de la même façon. |
| URL de flux directe | Si l'URL d'une « playlist » renvoie un manifeste HLS ou un flux vidéo (`video/*`, MPEG-TS, MP4), la source devient une chaîne unique. |
| Guide EPG XMLTV | Guide commun téléchargé automatiquement depuis `https://xmltvfr.fr/xmltv/xmltv.xml.gz`, mis en cache sur l'appareil, revalidé (ETag / Last-Modified) au plus tard toutes les 12 h et réimporté seulement s'il a changé. Le cœur sait aussi lire une URL ou un fichier `.xml` / `.xml.gz` propre à une playlist. |

Plusieurs playlists peuvent être enregistrées, puis modifiées, supprimées et actualisées ; l'une d'elles est la playlist
active affichée à l'accueil. Un échec d'actualisation conserve les chaînes et le guide précédents ; l'erreur est affichée.

Chaque entrée est classée à l'import en chaîne, film ou épisode de série (groupe, attributs, URL `/movie/` ou `/series/`,
motifs `S01E02`), et les versions d'une même chaîne (`FHD`, `HD`, `SD`, `LOW`…) sont regroupées.

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
  stream/      Analyse des URL de flux (format, compatibilité, en-têtes), choix automatique de la qualité
  catalog/     Classement chaîne / film / série, détection des versions de qualité
  meta/        Client TMDB (fiches films et séries)
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

La CI GitHub Actions exécute les tests du cœur, la compilation de l'application (debug et release), le lint Android
et génère des captures d'écran (Roborazzi, téléphone et TV) publiées sur la branche `screenshots`.

## Publication

Le workflow `Release` se déclenche en poussant un tag `v*` (ex. `git tag v0.1.0 && git push origin v0.1.0`),
une branche `release/vX.Y.Z`, ou manuellement depuis l'onglet Actions avec la version voulue.
Il exécute les tests, construit l'APK release (R8) puis publie une release GitHub avec l'APK et son
empreinte SHA-256, en créant le tag s'il n'existe pas (versions `v0.*` ou `*-…` publiées en pré-version).

Clé TMDB : le secret facultatif `NEXTV_TMDB_API_KEY` l'intègre à l'APK (une clé saisie dans les réglages reste prioritaire).

Signature : définir dans les secrets du dépôt `NEXTV_KEYSTORE_BASE64` (keystore encodé en base64),
`NEXTV_KEYSTORE_PASSWORD`, `NEXTV_KEY_ALIAS` et `NEXTV_KEY_PASSWORD`. En leur absence, l'APK est signé avec
la clé de test du dépôt (`app/nextv-test.keystore`, aussi utilisée en debug) : chaque version s'installe par-dessus
la précédente (`adb install -r`) en gardant playlists et réglages. Clé à remplacer par les secrets si le dépôt
devient public.
