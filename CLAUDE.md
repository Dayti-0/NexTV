# NixTV — notes pour Claude

Application IPTV Android en Kotlin / Jetpack Compose (Media3). Elle s'appelait NexTV et le code garde ce nom
(`tv.nextv.*`). Deux modules :
- `core` : Kotlin JVM, sans Android (playlists, guide, foot, qualité, réseau) ;
- `app` : l'application Android (écrans Compose, lecteur, base Room).

## Échanges avec l'utilisateur

- Il écrit en français, souvent avec des photos de l'écran : une Fire TV, une télé Sony, et une box Raspberry Pi
  sous LineageOS (KonstaKANG, Android 13+) branchée sur le moniteur ASUS (une photo de l'écran ASUS, c'est la box).
  Sur la télé Sony, c'est une Fire TV de première génération, peu puissante (environ 1 Go de mémoire) qui tourne ;
  il a aussi une Fire TV 4K, peu puissante elle aussi : les deux servent d'essai en conditions réelles.
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
- Clé USB : jamais d'accès aux fichiers d'une clé (création de dossier, fichier d'essai, place libre, liste) sur le fil
  principal : sur la box, `mkdirs` sur la clé a bloqué l'ouverture des Réglages plus de 5 s (ANR, 0.2.161). Les
  emplacements sont relus en arrière-plan (`AdRecordings.locations`, `AppContainer.refreshAdPlacesInBackground`) ; le fil
  principal lit `knownLocations` / `AdMusicLibrary.knownTracks`.
- Base Room : augmenter `NexTvDatabase.VERSION` demande d'ajouter la migration à `MIGRATIONS` et le schéma
  `app/schemas/…json` au dépôt (`MigrationsTest` le vérifie). Sans migration, l'application ne démarre plus.
- Ne jamais donner une autre surface au décodeur d'un lecteur en cours (`setVideoSurfaceView` d'une autre vue) : sur
  la box Raspberry Pi l'image reste noire alors que Media3 croit l'afficher, et sur plusieurs Fire TV Media3 rouvre
  le décodeur, qui attend la prochaine image complète (jusqu'à 20 s). D'où les couches vidéo (`VideoLayer`).
- Avant Android 12 (Fire TV), une vidéo d'aperçu (`PreviewVideo`, SurfaceView affichée sous la fenêtre à travers un
  « trou ») ne doit pas être dans un élément dessiné à part (`graphicsLayer` en `Offscreen`, comme `bottomFade` /
  `edgeFade`) : l'image reste cachée, le son passe. Le volet de droite n'a pas de fondu sur ces appareils
  (`paneFadeKeepsVideo`, 0.2.152 ; le fondu y était depuis le 6/11/2026).
- Sur la télé, une sélection perdue (l'élément sélectionné disparaît avant qu'un autre la prenne) est donnée par Android
  au premier élément en haut à gauche : en Streaming, le menu de gauche, qui s'ouvre. Robolectric la remet souvent
  ailleurs : suivre la sélection image par image (`HomeFocusTest.streamingCategoryPickKeepsTheFocusInTheList`).
- Le lint ne tourne pas toujours ici (dépôt Maven qui limite les requêtes) : regarder le résultat de la CI après
  chaque envoi (`Android build & lint`), et corriger dans la version suivante.
- Tests qui lancent la vraie application (`ActivityScenario` / `MainActivity`) : l'API de GitHub répond (ici comme sur la
  CI), et la version de test « 0.2.0 » paraît ancienne. La proposition de mise à jour au lancement s'ouvrait alors au
  milieu du test, dans une seconde fenêtre qui prenait la sélection (échecs au hasard, surtout sur la CI) : elle est
  coupée en debug (`LaunchUpdatePrompt`). Ces tests passent aussi en mode télécommande (`setInTouchMode(false)`) ; en
  cas d'échec de sélection, lister tous les éléments sélectionnés et les fenêtres (`ReturnFromPlayerFocusTest.focusReport`).

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

- Mémoire : `smallMemoryDevice` (`PlayerFactory.kt`, moins de 1,5 Go en tout) réduit tampons et cache d'images ;
  signal « basse » / « critique » pendant une lecture (`NexTvApplication.freeMemoryForPlayback`) : cache d'images
  vidé et 16 Mo de flux d'avance au plus pendant 3 min (`MemoryPressure`, `LiveLoadControl`). Fermeture par le
  système le 15/11/2026 sur la Fire TV de la Sony, en direct (appli 39 Mo, natif 49 Mo, 272 Mo libres, critique).
  Puis en 0.2.150, sur l'écran de démarrage : appli 22 Mo seulement (PSS), appareil 337 Mo libres sur 900, critique
  1 s avant, au premier lancement juste après l'installation de la mise à jour : la Fire TV manquait de
  mémoire hors de NixTV. Relevés réguliers depuis 0.2.151 (`CrashLog.tick`).
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
- Direct coupé ou figé (0.2.174) : `LiveRetries` (9 relances sur ~4 min ; les 2 premières coupures d'un direct qui a
  joué sont rouvertes dans la même qualité avant `quality.onError`), `LiveStallWatch` (rien de neuf depuis 20 s —
  images sorties du décodeur, `PlayerEngine.liveProgress` — alors que le lecteur se croit en lecture : flux rouvert ;
  écran noir au matin après une nuit en direct). Qualité automatique (`QualitySwitcher.Config`) : on descend après 10 s
  de blocage, 3 chargements d'au moins 1 s en 2 min, ou 120 images perdues en 10 s (l'utilisateur la trouvait trop
  réactive avec 3 s / 2 en 30 s / 30 en 5 s).
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
  (`SEPARATOR_PREFIX`), `core/catalog/ChannelLineup.kt` (`arrange`) donne l'ordre rangé (TNT par numéro de
  `TntChannels`, Canal+, cinéma… table `KNOWN_CHANNELS`, rubriques de la playlist), la catégorie par thème
  (`Section.title`) et le nom propre (`properName`) de chaque chaîne. En base : `audio`, `lineup`, `theme`, `niceTitle`,
  `baseTitle` (nom de la playlist), et les colonnes en vigueur `listOrder` (ordre), `listGroup` (catégorie) et `title`
  (nom) que lisent toutes les listes de chaînes, catégories, menu du lecteur, guide et zapping ;
  `ChannelDao.applyGrouping` les bascule au changement du réglage. Une catégorie masquée l'est sous son nom de playlist
  (`groupTitle`) comme sous son thème (`listGroup`). `ChannelFilters` masque séparateurs et autres éditions de France 3.
  Version du son (options du lecteur) : `channelStart` (`AutoQualityController.kt`) ne garde que les versions du son
  choisi pour la chaîne (`SettingsRepository.channelSound`). Qualité choisie à la main (options › « Qualité de la
  chaîne ») : `AppContainer.lockedQualities`, en mémoire seulement (jusqu'à la fermeture), lue par `channelStart`
  (qualité automatique coupée).
- Pluto TV / Rakuten TV (0.2.175) : rangés par thème grâce à leurs séparateurs (« ▀▄ PLUTO TV ▀▄ DOCUMENTAIRE ▄▀▄ » ; « pluto »,
  « rakuten » sont des mots passe-partout, les mots inconnus d'un séparateur sont ignorés) ; programmes mal rangés par la
  playlist corrigés par nom (`NAMED_SHOWS`) ; « cinéma » / « film » dans le nom l'emporte sur la rubrique ; rubrique
  `RADIO` (« Radios ») ; à défaut de tout, les mots du nom (« MYSPORTS »). Vérifié sur la vraie playlist le 17/11/2026 :
  plus aucune catégorie de la playlist parmi les chaînes françaises, 5 chaînes dans « Autres chaînes » (KTO, Daystar, NHK).
- Radios Internet (0.2.176) : `core/catalog/InternetRadios.kt` (liste, catégorie « Radios Internet »), ajoutées à la fin
  des chaînes en direct par `M3uPlaylistLoader(internetRadios = true)` (seulement l'appli, pas les tests), rubrique
  `RADIO` après les radios de la playlist. Ajouter une radio : vérifier le flux (curl), puis augmenter
  `QualityParser.VERSION` pour que la playlist soit relue au lancement.
- Rangement recalculé sans relire la playlist : `ChannelFilters.rearrange` (au lancement quand `ChannelLineup.VERSION`
  dépasse le `lineupVersion` des réglages). Toute nouvelle règle de `ChannelLineup` : augmenter `ChannelLineup.VERSION`.
- « Chaînes françaises seulement » (réglage) : colonne `french` (section autre que `OTHER` dans `ChannelLineup`), masquée
  par `VISIBLE_VERSION` quand `hidden_groups` contient `LIVE_FOREIGN`. La section Foot (`epgChannels`,
  `eventChannelCandidates`) garde toutes les chaînes, masquées en dernier.
- Retour du lecteur à l'accueil : la chaîne regardée (`AppContainer.lastLiveChannel`) et la catégorie où elle a été
  choisie dans le menu des chaînes (`lastLiveCategory`) ; `HomeViewModel.followWatchedChannel` passe l'accueil à une
  catégorie qui la contient (seulement lecteur ouvert, `playbackOpen` : jamais sous les yeux) et note sa place dans
  cette liste (`AppContainer.lastLivePlace`, lue dans la base) ; `TvBody` charge la liste jusqu'à elle,
  `FollowLastChannel` / `restoreFocus` (`FocusMemory.kt`) y posent la sélection (à défaut, sur la première chaîne : une
  sélection perdue ouvrait le menu de gauche de Streaming). Les tests attendent cette place avant le retour (CI lente).
- Zapping (haut/bas, chaîne +/-) : `PlayerViewModel.zap` reste, en boucle, dans la liste d'où la chaîne a été ouverte :
  favoris (`ARG_FAVORITES`), catégorie (`ARG_GROUP` : `Routes.groupPlayer`, depuis `HomeActions.onPlayGroupChannel` et le
  guide), sinon toutes les chaînes ; `playFromMenu` prend la catégorie du menu. Requêtes `ChannelDao.nextChannelId` /
  `previousChannelId` / `listedOrder` (une catégorie ou « Toutes », comme `pageChannels`).
- Versions d'un film (« Titre (Truefrench) », « Titre (FR) », la même en 4K) : `ContentClassifier` retire les mentions du
  titre (`versionOf` donne la mention et la préférence), `MovieCopies` (dans `RoomPlaylistStore.kt`) choisit la copie
  affichée à l'import, les requêtes `MOVIE_IN_GROUP` / `MOVIE_VISIBLE` de `Daos.kt` n'en gardent qu'une par film, la
  fiche propose les autres (`movieVersions`, bouton « Version »). Toute nouvelle règle qui change un titre : augmenter
  `QualityParser.VERSION` (la playlist est relue au lancement) ; `ItemKeyRelinker` rattache les favoris, reprises,
  « Déjà vu » et chaînes récentes enregistrés sous l'ancienne clé (films, séries et chaînes).
- « Baisser le son des pubs » (direct et replay seulement, jamais un film : `PlayerEngine.adBreaks`) :
  `core/audio/AdVolumeGuard.kt` reconnaît les **jonctions** entre deux pubs — un silence complet pendant lequel l'image
  change de plan ou montre le carton de la chaîne (`SmallFrame`, images 96 × 54) — et décide selon le logo
  (`LogoWatcher.kt` : gabarit de gradients du logo dans un coin ; affiché = programme, jamais de baisse). Ce qu'une chaîne
  a appris est gardé (`AdMarks` : logo, carton, « le logo suit les coupures » ; `AdMarksStore`, un fichier par flux dans
  `files/ad_marks`). Branché par `AdVolumeControl` / `AdVolumeAudioProcessor` (son décodé) et `VideoFrameSampler` (image
  480 × 270 : une par seconde, dix par seconde pendant un silence — boucle de `PlayerScreen`). La ligne « Pubs » des infos
  de lecture (`AdWatch`) dit ce que la détection voit sur l'appareil : **la demander en photo** quand elle ne marche pas.
  Réglé le 14/11/2026 sur 8 chaînes ; pour rejouer des enregistrements dans le code : `AdReplayTool` (test ignoré sans
  `AD_SAMPLES` ; dossiers contenant `levels.f32` (carré moyen par 20 ms), `frames.gray` (480 × 270, 1 image/s) et
  `small.gray` (96 × 54, 12,5 images/s)). Tout changement de règle : comparer avant / après sur des enregistrements, pas
  seulement sur les tests.
  Deux renforts passent avant ces règles (0.2.153) : les **repères du flux** (`core/audio/AdCues.kt` : balises HLS
  `EXT-X-DATERANGE` SCTE35-OUT, `EXT-X-CUE-OUT/IN`, et messages SCTE-35 « splice insert » d'un flux MPEG-TS ; lus par
  `PlayerEngine.readPlaylistCues` / `readSpliceCues`, donnés deux fois par seconde par `updateAdCues`) décident seuls
  dès que le flux en porte (`AdVolumeGuard.cue`) ; les **pubs déjà entendues** (`AudioFingerprinter` : empreintes
  32 bits toutes les 16 ms ; `AdPrintLibrary` commune à toutes les chaînes, fichier `files/ad_prints/library.bin`
  via `AdPrintStore` ; `AdPrintWatch` par flux) ouvrent la page en 2-3 s. Apprentissage à la fin d'une page sûre
  (`AdVolumeGuard.onBreak`) : annoncée par le flux (morceau « sûr ») ou ouverte par logo / carton et refermée par le
  retour du logo (« à l'essai » : ignoré sous le logo, oublié s'il y repasse). Lignes « Repères de pub » et « Pubs déjà
  entendues » des infos de lecture : les demander en photo (on ne sait pas encore si le fournisseur laisse les repères).
  Notation : `AdReplayTool` lit aussi `audio.s16` (11 025 Hz mono) et `tags.tsv` (balises HLS datées : vérité de
  référence), options `AD_PRINTS`, `AD_TEACH`, `AD_CUES`, `AD_WINDOW` ; enregistreur des directs publics :
  `tools/ad_record.py` (ffmpeg ; lancer 2 h sur plusieurs chaînes en parallèle). Le 15/11/2026 sur RMC Story / Découverte / Life :
  sans renfort 81 % des secondes de pub baissées (délai moyen 21 s), avec les repères 100 %. Les baisses « à tort »
  restantes, vues sur les images, étaient des bandes-annonces de la chaîne entre deux programmes (hors repères).
- Enregistrement d'analyse fait sur l'appareil (0.2.154) : options du lecteur › « Enregistrer pour l'analyse des pubs »
  (`AdRecordings` / `AdRecorder`, sans limite de durée jusqu'à l'arrêt, un dossier par enregistrement servi en `.zip`
  fabriqué à la volée ; format 3 (0.2.161, ~27 Mo/h, fichiers coupés à 27 Mo : l'utilisateur ne peut envoyer que
  30 Mo) : `levels.bin` (carré moyen, durée), `prints.bin` (empreintes du son au lieu du son), `frames.bin` en JPEG
  (« J » petites images, « C » les 4 coins du logo de 133 × 65 — un point de plus que `LogoWatcher`, sinon faux bord
  appris comme logo —, « T » vignette 160 × 90 toutes les 5 s pour relever les pubs à l'œil),
  `events.tsv` avec « ouverture » / « fermeture » et leur raison, « jonction », « empreinte », « flux » (changement de
  chaîne : détection repartie de zéro, horloge mise bout à bout), « chaine »), envoyé sur filebin (voir plus bas ; le
  téléchargement par le téléphone a été retiré en 0.2.169, le petit serveur local ne sert plus qu'à la musique). Emplacement au choix (0.2.157) :
  mémoire de l'appareil ou clé USB (`removableRecordingPlaces` : dossier de l'appli sur chaque volume amovible,
  `getExternalFilesDirs`, sans autorisation ; à défaut, Android 11+, dossier `NixTV/` à la racine avec « Accès à tous les
  fichiers » `MANAGE_EXTERNAL_STORAGE`, `allFilesAccessIntent` ; `describeStorage` liste les volumes vus pour la photo ;
  réglage `ad_recordings_location` = identifiant du volume). Essai « Enregistrement automatique » (0.2.158,
  `PlayerViewModel.toggleAutoRecording`) : zappe dans la liste d'origine toutes les 30 min (pas pendant une baisse, au
  plus +10 min ; 1 min si la chaîne est en erreur), écran gardé allumé ; ouvert depuis « Toutes les chaînes », il suit
  `AUTO_TNT_NUMBERS` (chaînes à pub, `TntChannels`). Choix (0.2.169, `pickAutoChannel`) : la chaîne la moins enregistrée
  (`AdTestedChannels`, fichier `files/ad_tested.tsv`, clé = numéro TNT ; `ANALYZED` = minutes déjà analysées par Claude,
  à augmenter après chaque analyse), celles en erreur en dernier ; dès le lancement si la chaîne en cours est connue
  de 10 min de plus (`autoStart`). Fichiers coupés à 27 Mo ou 2 h
  (`splitAdRecording`). L'enregistrement survit à la fermeture du lecteur (0.2.162 : `PlayerEngine.release` le
  détache, `startAdWatch` le rattache au lecteur suivant via `AdVolumeControl.attach`) ; arrêt aussi dans Réglages ;
  moins de 15 min (`AD_RECORDING_MIN_MILLIS`) : pas gardé. Autre qualité de la même chaîne (0.2.163, `channelKey` de
  `PlayerEngine.play` = `variantKey` de la chaîne choisie) : `AdVolumeControl.continueStream` garde la détection (page
  en cours, logo, horloge) et l'enregistrement ; seuls les repères du flux sont relus. Le rejouer : `AdDeviceReplayTool` (`AD_DEVICE`,
  `AD_PRINTS`, `AD_IMAGES=10` écrit une image par 10 s pour relever les pages de pub à l'œil dans `<nom>.truth.tsv`).
  C'est la seule façon de mesurer TF1, M6 et France Télévisions (pas de flux lisible d'ici, même avec un compte).
  Envoi automatique (0.2.167, réglage « Envoyer les enregistrements sur filebin », `AdUploads`) : chaque enregistrement
  terminé part en `.zip` sur `https://filebin.net/nixtv-…` (page tirée au hasard, `adUploadBin`), supprimé de l'appareil
  quand la réponse de filebin confirme la taille (0.2.168), sinon marqué `.envoye` ; l'utilisateur donne l'adresse de la page (Réglages › Son › Enregistrements, avec QR code ; page tirée au premier
  lancement, envoi toujours actif depuis 0.2.169), la lire avec `Accept: application/json` puis télécharger les fichiers
  (ne jamais écrire l'adresse d'une page dans le dépôt, public). Zip « stocké » mesuré exactement avant l'envoi
  (`StoredZip`).
- Musique pendant les pubs (0.2.159, réglage « Pendant les pubs » `AdSound` : baisser / radio / ma musique) :
  `player/AdMusic.kt` (`AdRadios` Radio France sans pub, `AdMusicLibrary` dossier `files/ad_music` rempli depuis le
  téléphone par `PUT …/musique/<nom>` sur le serveur local d'`AdRecordings.Share`, plus `NixTV/musique` des clés USB,
  `AdMusicPlayer` second ExoPlayer sans focus audio, fondus) ; branché par `PlayerViewModel.updateAdMusic` (boucle de
  0,5 s) ; `AdVolumeControl.muteAds` coupe la chaîne au lieu de −24 dB. « Ce n'est pas une pub » :
  `AdVolumeGuard.dismiss` (3 min sans nouvelle page, les repères du flux décident toujours).
- Nuit du 15 au 16/11/2026 enregistrée sur la box (13 fichiers, 10 h 40, 9 chaînes, appli 0.2.162) ; vérité relevée à l'œil
  sur les vignettes (`<nom>.truth.tsv`, planches faites par un script Python : une vignette toutes les 20 s, bordure
  rouge quand l'appareil baissait). `AdDeviceReplayTool` avec `AD_KNOWN=1` (premier passage qui apprend logo et carton de
  chaque chaîne, comme l'appareil) : 68 % → 81 % des secondes de pub baissées, programme à tort 183 → 249 s sur 8 h, avec
  0.2.166 : logo revu ≥ 3 s pour fermer (`LOGO_BACK_MILLIS`), page rouverte si le logo repart dans les 20 s
  (`REOPEN_MILLIS`, des pubs « montraient » le logo 3 à 11 s : pages morcelées sur W9, M6, TF1 Séries Films), et logo
  parti depuis 20 s sur une chaîne où il suit les coupures = page de pubs sans jonction (`LOGO_ONLY_MILLIS`, bandes-annonces
  enchaînées sans silence sur TF1). Restent difficiles : les pubs juste après un changement de chaîne, les bandes-annonces
  de fin de page, et le début d'un dessin animé de 6ter pris pour une pub (logo de la chaîne remplacé).
- Journée du 16/11/2026 (box en automatique 0.2.166 → 0.2.169, Fire TV sur M6 ; 6 h) : appareil 83 % des secondes de pub
  baissées (fichiers avec décisions), programme à tort surtout sur France 2 (météo, jeu) par « logo parti depuis 20 s » :
  0.2.171 referme une telle page sans jonction en 30 s (`LOGO_ONLY_HOLD_MILLIS`) et ne la rouvre plus ainsi 15 min
  (`LOGO_ONLY_PAUSE_MILLIS`) ; rejeu sur 20 h : programme à tort 1010 → 676 s, pubs baissées 79 → 78 %. Analyse :
  commande `/analyse` (`.claude/skills/analyse`, outils `tools/ad_fetch.py`, `ad_sheets.py`, `ad_errors.py`, vérités dans
  `tools/ad_truth/`).
- Soir du 16/11/2026 (box : CNews, LCI, T18, Novo19 ; Fire TV : M6, RMC Sport / Eurosport ; 3 h, appli 0.2.169) : appareil
  92 % des secondes de pub baissées, 105 s de programme à tort (80 s sur LCI, « logo parti » : corrigé par 0.2.171). Rejeu des
  11 fichiers encore sur filebin avec le code 0.2.171 : 85 % baissées, programme à tort 411 → 105 s. Le rejeu ne sait pas ce
  que l'appareil a appris les jours d'avant (logo de TF1 qui suit les coupures) : il ouvre plus tard que lui sur TF1.
  `AdDeviceReplayTool` prenait le nom de la chaîne précédente pour un flux changé moins d'une seconde après le début du
  fichier (Gulli appris comme TF1 : 283 s à tort qui n'existaient pas) : corrigé. Reste : une page de CNews refermée en
  pleine pub après 80 s sans jonction (« logo toujours absent »), puis rouverte 45 s plus tard.
- Nuit du 16 au 17/11/2026 (box en automatique 0.2.172 partie d'une liste hors TNT : beIN Sports, E!, Novelas, chaînes
  « 24/24 » de M6+ ; Fire TV : L'Équipe, Novo19 ; 6 h 25) : appareil 87 % des secondes de pub baissées, ~250 s de programme
  à tort (rugby après une page sur beIN, logo différent : 80 s ; ouverture « carton » juste après l'écran « M6+ revient dans
  un instant » : 50 s ; fermeture tardive sur Novo19, logo pas encore appris : 50 s). Les chaînes « 24/24 » n'ont pas de pubs,
  seulement cet écran fixe (pas compté comme pub). `AdRecorder.newStream` ne notait pas le retour du son à un changement de
  flux : la baisse semblait continuer sur la chaîne suivante (40 s « à tort » qui n'existaient pas). Corrigé en 0.2.173 ;
  `AdDeviceReplayTool` et `ad_sheets.py` lisent aussi « flux » comme son remonté pour les anciens fichiers.

## Essais sur de vrais flux

- La variable `NEXTV_M3U_URL` de l'environnement donne la playlist (se présenter comme l'appli : agent `NixTV/1.0`). La
  liste des chaînes et l'état du compte passent ; **la vidéo est refusée depuis le cloud** (erreur 511, machine hors de
  France) : ne pas chercher à contourner. L'abonnement n'a qu'une connexion : ne rien ouvrir si `active_cons` vaut 1.
- À la place : les directs publics officiels des chaînes (liste `iptv-org.github.io/iptv/countries/fr.m3u`, ne garder
  que les serveurs des chaînes elles-mêmes). Joignables le 14/11/2026 : RMC Story, RMC Découverte, RMC Life, L'Équipe,
  BFM 2, TV Monaco, Euronews, Fréquence Novelas. Les trois RMC annoncent leurs pages de pub dans la liste HLS
  (`EXT-X-DATERANGE` … `SCTE35-OUT`, début et durée) : une vérité de référence toute faite.
- TF1, M6, France Télévisions : aucun flux public lisible d'ici (DRM, France seulement), et le téléchargement
  d'archives vidéo a été refusé par la sécurité de la session. Leurs pages de pub n'ont donc pas été mesurées.
