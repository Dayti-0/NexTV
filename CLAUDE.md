# NixTV — notes pour Claude

Application IPTV Android en Kotlin / Jetpack Compose (Media3). Elle s'appelait NexTV et le code garde ce nom
(`tv.nextv.*`). Deux modules :
- `core` : Kotlin JVM, sans Android (playlists, guide, foot, qualité, réseau) ;
- `app` : l'application Android (écrans Compose, lecteur, base Room).

## Échanges avec l'utilisateur

- Il écrit en français, souvent avec des photos de l'écran : une Fire TV, une télé Sony, et une box Raspberry Pi
  sous LineageOS (KonstaKANG, Android 13+) branchée sur le moniteur ASUS (une photo de l'écran ASUS, c'est la box).
  Demander sur quel appareil est prise une autre photo avant d'en tirer une cause propre à un modèle. Répondre en français, simplement, sans jargon.
- Les tests sur l'appareil sont faits par lui : dire clairement ce qui a été vérifié ici (tests, captures) et ce
  qui reste à voir sur la télé.
- Point en attente : plantage du guide sur la Fire TV après la 25e chaîne, non reproduit. Il faut la photo de la
  carte « Dernier incident » (Réglages) pour en connaître la cause.

## Règles

- Ne jamais écrire dans le code, les commits, les journaux ou les réponses : les clés TMDB ou football-data.org,
  les adresses de playlist, les identifiants, la phrase secrète de signature. Le dépôt est public.
- Ne pas supprimer le secret GitHub `NEXTV_KEY_PASSPHRASE`.
- Pas de force push ni de réécriture de l'historique.
- Travailler sur `main` (commits directs). Messages de commit en anglais.
- Textes de l'interface et commentaires du code en français.

## Compiler et tester

Le SDK Android est dans `/opt/android-sdk`, installé par le script de démarrage de l'environnement « NexTV »,
qui écrit aussi `local.properties` (`sdk.dir=/opt/android-sdk`). S'il manque (autre environnement) :

```
SDK=/opt/android-sdk; mkdir -p $SDK/cmdline-tools
curl -fsSL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q /tmp/c.zip -d $SDK/cmdline-tools && mv $SDK/cmdline-tools/cmdline-tools $SDK/cmdline-tools/latest
$SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK "platforms;android-36" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$SDK" > local.properties
```

Commandes :

```
./gradlew -p core test                  # tests du module core
./gradlew :app:testDebugUnitTest        # tests de l'application (Robolectric)
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:lintDebug                # demande le réseau
./gradlew :app:recordRoborazziDebug --tests '*TvScreenshots*'   # captures dans app/build/screenshots/
```

- `--offline` accélère les commandes une fois les dépendances téléchargées.
- Résultats des tests : `app/build/test-results/testDebugUnitTest/*.xml`.
- Tests de focus à la télécommande : activer `PackageManager.FEATURE_LEANBACK` (Robolectric `shadowOf(...)`), sinon
  le défilement n'est pas celui d'une télé.
- Pour une capture ponctuelle dans un test temporaire, passer `-Proborazzi.test.record=true`.

## Pièges déjà rencontrés

- Dans une classe Kotlin, une propriété déclarée **après** le bloc `init` est encore vide quand `init` s'exécute.
  `PlayerViewModel.init` appelle `load()` : tout ce que `load()` et les surveillances utilisent doit être déclaré
  au-dessus (la 0.2.87 plantait à chaque ouverture du lecteur pour cette raison).
- Tout changement d'un écran ou d'un modèle doit passer par un test qui l'ouvre vraiment : `PlayerViewModelTest`
  (film, chaîne, veille, touches), `ApplicationStartTest` (lancement réel de l'application et de l'activité),
  `GuideNavigationTest` (navigation à la télécommande). Les tests unitaires seuls ne voient pas ces erreurs.
- libVLC : jamais `stop()` ni `release()` sur le fil principal (application figée, « ne répond plus »).
- Base Room : augmenter `NexTvDatabase.VERSION` demande d'ajouter la migration à `MIGRATIONS` et le schéma
  `app/schemas/…json` au dépôt (`MigrationsTest` le vérifie). Sans migration, l'application ne démarre plus.
- Ne jamais donner une autre surface au décodeur d'un lecteur en cours (`setVideoSurfaceView` d'une autre vue) : sur
  la box Raspberry Pi l'image reste noire alors que Media3 croit l'afficher, et sur plusieurs Fire TV Media3 rouvre
  le décodeur, qui attend la prochaine image complète (jusqu'à 20 s). D'où les couches vidéo (`VideoLayer`).
- Le lint ne tourne pas toujours ici (dépôt Maven qui limite les requêtes) : regarder le résultat de la CI après
  chaque envoi (`Android build & lint`), et corriger dans la version suivante.

## Publier une version

- Écrire d'abord la section `## X.Y.Z` de `CHANGELOG.md` (en français, une ligne par changement visible) : elle
  devient les notes de la version, seules affichées par l'application avant la mise à jour.
- Pousser une branche `release/vX.Y.Z` sur le commit à publier : le workflow « Release » construit l'APK signé et
  crée la version GitHub, que l'application propose en mise à jour.
- Numéro : la dernière version + 1 (`git ls-remote --heads origin 'release/*'`). Le numéro interne (versionCode)
  est calculé à partir de ce nom par le workflow (0.2.94 → 20094) : ne jamais publier un numéro plus petit que le
  précédent.
- Le workflow « Release » lance les tests du module `core` en même temps que la fabrication de l'APK (une seule commande).
- Fichiers publiés : l'APK universel (`NexTV-vX.Y.Z.apk`, et `NexTV.apk` pour le lien permanent) et un APK par
  processeur (`NexTV-vX.Y.Z.arm64-v8a.apk`, `NexTV-vX.Y.Z.armeabi-v7a.apk`), chacun avec son `.sha256`. Garder ces
  noms : GitHub trie les fichiers par nom, et les versions 0.2.102 et avant prennent le premier `.apk` de la liste
  (il doit rester l'universel). La mise à jour intégrée (0.2.103 et après) prend celui du processeur de l'appareil.
- L'utilisateur a demandé que chaque correction validée soit publiée sans attendre son accord, et le plus vite
  possible : dès que les tests locaux passent, pousser `main` et la branche `release/vX.Y.Z` en même temps, sans
  attendre la CI (elle ne fait que compiler, le lint et les captures ; la publication prend ~10 min). Si la CI
  échoue ensuite, corriger et publier la version suivante.
- Ne pas compter sur un rappel programmé pendant un long travail : il ne se déclenche qu'à la fin du tour.

## Repères dans le code

- Journal « Dernier incident » : `CrashLog.kt` (trace réduite sans perdre la cause, erreurs de fond) et
  `SystemExits.kt` (fermetures par le système : raison, écran affiché, mémoire). C'est lui qu'il faut demander en
  photo pour tout plantage.
- Passage aperçu ↔ plein écran (même lecteur, même connexion) : `PreviewHandoff.kt`, `ChannelPreviewPlayer.handOff` /
  `adopt`, `PlayerEngine.adopt` / `handBack`. Image : chaque lecteur de direct dessine dans sa propre couche
  (`player/VideoLayer.kt`, SurfaceControl, Android 10+), que `VideoViews` place sous la zone vidéo de l'écran qui le
  montre ; seuls ces lecteurs passent d'un écran à l'autre (réglage « Passage sans coupure », `previewHandoff`).
- Lecteur : `ui/player/PlayerViewModel.kt` avec `PlayerEngine` (un seul ExoPlayer par écran, une source réseau par
  flux via `PlayerFactory.mediaSource`), `DelayedPlaybackController` (pause, différé, replay, avance rapide),
  `AutoQualityController`, `ResumeController`, `ChannelMenuController`.
- Actualisations automatiques (playlist, guide) : `RefreshController` (dans `AppContainer.kt`) avec `background = true`
  passe par `BackgroundWork` : rien pendant qu'un lecteur est ouvert (`AppContainer.playbackOpen`), une actualisation
  en cours est abandonnée quand une lecture démarre et refaite après. Mémoire tampon : `PlayerFactory.loadControl()`
  pour un direct, `filmLoadControl()` pour un film ou un épisode.
- Accueil : `ui/home/` (interfaces Live Center, Streaming, Classique) ; composants communs dans
  `ui/components/Components.kt` (fondus `bottomFade`, `FadingRow` / `edgeFade`).
- Guide TV et fiche programme : `ui/guide/`.
- Chaînes reliées au guide : `core/epg/EpgImporter.match` puis `ChannelNames.kt` (noms « collés », table des autres
  appellations `ALIASES`). Toute nouvelle règle : augmenter `EpgImporter.VERSION` (le guide est relu au lancement).
- France 3 régionales : `core/catalog/FranceRegions.kt` ramène toutes les écritures d'une région au nom de son antenne
  (abréviations, lettre de travers, ville du siège) ; ce nom sert à la clé de version (`LiveVariants`, qui regroupe
  aussi les noms ne différant que par « TV »), au guide (`EpgImporter.match`, avec repli sur France 3 national) et au
  réglage « Région de France 3 ».
- « Rassembler les chaînes » (réglage, activé d'office) : `LiveVariants.spelling` ramène les écritures d'un nom à une
  clé (« + », « & », pluriels), les séparateurs de la playlist (« ▀▄ FR ▀▄ CINEMA ▄▀ ») ont leur propre clé
  (`SEPARATOR_PREFIX`), `core/catalog/ChannelLineup.kt` donne l'ordre rangé (TNT par numéro de `TntChannels`, Canal+,
  cinéma… table `KNOWN`, rubriques de la playlist). En base : `audio`, `lineup` et `listOrder` (ordre des listes en
  vigueur ; toutes les listes de chaînes et le zapping trient par lui) ; `ChannelDao.applyGrouping` bascule clés et
  ordre au changement du réglage, `ChannelFilters` masque séparateurs et autres éditions de France 3.
- Retour du lecteur à l'accueil : la chaîne regardée (`AppContainer.lastLiveChannel`) et la catégorie où elle a été
  choisie dans le menu des chaînes (`lastLiveCategory`) ; `HomeViewModel.followWatchedChannel` passe l'accueil à une
  catégorie qui la contient, `FollowLastChannel` / `restoreFocus` (`FocusMemory.kt`) y posent la sélection.
- Versions d'un film (« Titre (Truefrench) », « Titre (FR) », la même en 4K) : `ContentClassifier` retire les mentions du
  titre (`versionOf` donne la mention et la préférence), `MovieCopies` (dans `RoomPlaylistStore.kt`) choisit la copie
  affichée à l'import, les requêtes `MOVIE_IN_GROUP` / `MOVIE_VISIBLE` de `Daos.kt` n'en gardent qu'une par film, la
  fiche propose les autres (`movieVersions`, bouton « Version »). Toute nouvelle règle qui change un titre : augmenter
  `QualityParser.VERSION` (la playlist est relue au lancement) ; `ItemKeyRelinker` rattache les favoris, reprises,
  « Déjà vu » et chaînes récentes enregistrés sous l'ancienne clé (films, séries et chaînes).
- « Baisser le son des pubs » : `core/audio/AdVolumeGuard.kt` (silences entre deux pubs ; les pubs ne sont pas plus
  fortes que le programme) et `LogoWatcher.kt` (logo de la chaîne, absent pendant les pubs), branchés par
  `AdVolumeAudioProcessor` et `VideoFrameSampler` (une image par seconde). Réglé sur de vraies coupures pub de 2024.
