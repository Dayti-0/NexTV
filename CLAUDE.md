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
- Robolectric télécharge `android-all-instrumented` au premier test ; si Maven Central répond 429, le prendre sur
  `maven-central.storage-download.googleapis.com/maven2/…` (avec son `.sha512`) dans `~/.m2/repository/` au même chemin.
- Image Coil dans une capture : `LocalInspectionMode` + `LocalAsyncImagePreviewHandler` (`Screenshots.radioVinylCover`),
  sans fondu (`crossfade`), sinon l'image reste transparente.

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
- Réglages (0.2.195-0.2.196) : pages `SettingsPage` (Playlist et guide, Chaînes, Image = `PLAYBACK`, Son et radios = `SOUND`,
  Affichage, Télés reliées, Réseau, Mise à jour, puis la moins touchée en dernier : Configuration et sauvegarde =
  `TRANSFER` : configuration rapide + configuration à distance + fichier + clés des services, 0.2.197) ; contenu dans
  `SettingsContent.body` (`SettingsScreen.kt`), liste et titres dans `SettingsPages.kt`.
- Premier lancement sans playlist (0.2.195, `ui/startup/WelcomeScreen.kt`) : trois cartes (abonnement → éditeur,
  téléphone → QR code, « On me l'installe » avec le code de configuration à distance affiché) et « Réglages ». La
  configuration rapide n'est plus ouverte par-dessus : elle vient après le premier chargement (`onHome`, `quickSetupDone`).
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
- Web radios (0.2.176, catégorie à part en 0.2.179) : `core/catalog/InternetRadios.kt` (liste par genre, sans pub,
  catégorie « Web radios », ancien nom `FORMER_GROUP`), ajoutées à la fin des chaînes en direct par
  `M3uPlaylistLoader(internetRadios = true)` (seulement l'appli, pas les tests), rubrique `WEBRADIO` après `RADIO`. Elles
  sont aussi proposées pendant les pubs (`AdRadios.all` = Radio France + Web radios, choix par `ChoiceDialog` des
  Réglages). Ajouter une radio : vérifier le flux (curl, sans changer d'agent : SomaFM répond 403 d'ici), puis augmenter
  `QualityParser.VERSION` pour que la playlist soit relue au lancement.
- Animation des radios (0.2.179, réglage `RadioVisual`, Réglages › Son) : `ui/player/RadioVisualizer.kt` (Spectre,
  Ondes, Aurore, Particules, Vinyle, Mélange ; 30 images/s et moins de particules sur une petite Fire TV), affichée par
  `PlayerScreen` quand le flux n'a que du son (`PlayerUiState.audioOnly`, pistes vues par `PlayerEngine.onTracksChanged`),
  avec le morceau en cours (métadonnées ICY, `radioTitle`). Vinyle (0.2.187) : pochette du morceau (`nowPlayingCover`) en étiquette du disque, qui tourne avec lui (`graphicsLayer`, même taille que `drawVinyl` : `VINYL_RADIUS`, `VINYL_LABEL_SHARE`), logo dessous tant qu'elle charge. Texte (0.2.188, `NowPlayingText`) : radio · genre en petit, titre en grand, artiste dessous (titre ICY séparé par `NowPlaying.fromStreamTitle` dans `PlayerScreen` ; services : `nowPlayingSong` / `nowPlayingArtist` ; RTL : émission en titre, jamais séparée) ; Vinyle sur écran large : disque à gauche (`vinylCenter`), texte à droite, avec la pochette en carré fixe au-dessus (0.2.193, `SquareCover`, `VINYL_COVER_SHARE` ; à côté du texte sur un téléphone). Radio et genre dans le coin en haut à gauche (`RadioBadge`, 0.2.194) ; couleurs tirées de la pochette (`core/audio/CoverColors`, `coverPaletteOf`, pochette réduite à 48 × 48 ; sans couleur franche : celles de `paletteFor`) ; artistes en entier (5 lignes, plus petit au-delà de `LONG_ARTIST`). Son : `SpectrumTap` (processeur audio de chaque lecteur de
  `PlayerFactory`, simple copie tant qu'aucune animation n'est affichée) → `core/audio/SpectrumAnalyzer` (FFT 1024,
  32 bandes). Sans son analysé (VLC, son passé à l'ampli) : houle de remplacement. Captures : `Screenshots.radioVisuals`
  (`animate = false`, sinon la boucle d'images empêche le test de finir). Choix aussi dans les options du lecteur (rubrique en
  tête quand `audioOnly`). Couleurs selon le genre ou la radio (`paletteFor` : reggae vert-jaune-rouge, ska noir-blanc-rouge…).
- Morceau en cours des radios (0.2.181-0.2.182, `ui/player/RadioNowPlaying.kt`, lancé par le dernier bloc `init` de
  `PlayerViewModel`) : titre ICY du flux s'il y en a ; sinon le service de la radio (`InternetRadios.TitleSource`,
  `titlesFor` : radios de l'appli par leur nom, Skyrock de la playlist par `logoKey`) : Radio France
  (`api.radiofrance.fr/livemeta/pull/<n>` : FIP 7, Mouv' 6, Inter 1, franceinfo 2, Musique 4, Culture 5, FIP Jazz 65,
  Groove 66, Monde 69, Reggae 71, Electro 74 ; FIP Hip-Hop inconnu), Skyrock (`skyrock.fm/api/v3/player/onair/national`,
  titre seul), Nova et ses webradios (`nova.fr/radios-data/www.nova.fr/all.json`, par adresse du flux), Les Indés
  (`POST https://<site>/lite/update_onair` `{"radio_ids":["1"]}` : jazzradio.fr, mradio.fr, generations.fr ; Latina et
  Alouette ont un autre site, pas trouvé), Europe 2 et RFM (vérifiés sur l'appareil le 18/11/2026 ; `https://<site>/cetait-quoi-ce-titre/history?idplayer=1&limit=1`,
  0.2.183), Radioking (`api.radioking.io/widgets/api/v1/radio/<id>/track/current` : Reggae.fr 50473), émission en cours
  de RTL (`www.rtl.fr/ws/live/live` : titre et animateurs, pas de morceaux ; RTL2 et Fun donnent aussi leur émission mais
  pas le morceau : `/ws/live/{id}/last-tracks` répond 404). Europe 1 : Cloudflare refuse le cloud. NRJ / Chérie /
  Nostalgie / Rire (nrj.fr et players.nrjaudio.fm refusent le cloud), RTL2, Fun, Latina, Alouette, FIP Hip-Hop (pas
  trouvé entre les numéros 1 et 140 de Radio France) : reconnaissance. Service muet 3 fois de suite : reconnaissance à la place. L'utilisateur ne veut pas de bouton
  « Quel est ce titre ? » : tout doit s'afficher seul. Sinon
  reconnaissance AudD (`api.audd.io`, 8 s en WAV mono 11 025 Hz tirés de `AudioSnippet`, rempli par `SpectrumTap` ;
  quelques essais par jour sans clé, vérifié d'ici le 18/11/2026 ; clé : Réglages › Services ou secret CI
  `NEXTV_AUDD_API_KEY` → `BuildConfig.AUDD_API_KEY`), relancée à la fin du morceau reconnu (`durationInMillis` −
  `timecode`). Lecture des réponses : `core/audio/NowPlaying.kt`. Pas de Shazam : pas d'accès public autorisé.
  Flux sans titre ICY (vérifié) : Radio France, NRJ / Chérie / Rire, RTL2, Fun, Nova, Jazz Radio, TSF, Infomaniak (titre vide).
- Vidéo du direct de Skyrock (0.2.191) : `live_video` de `skyrock.fm/api/v3/player/onair/national` (`on_air`, `provider`
  « youtube », `external_stream_id` ; relevé pendant Planète Rap le 18/11/2026, lecture intégrée permise) lu par
  `NowPlaying.skyrockLiveVideo`, relu toutes les minutes (`RadioNowPlaying.followLiveVideo`) → `PlayerUiState.radioVideo` ;
  `PlayerScreen` l'affiche par-dessus l'animation avec le lecteur des bandes-annonces (`TrailerBackdrop`, son de la vidéo,
  son de la radio coupé : `PlayerViewModel.radioVideoShown` ; 0.2.192 : OK ne met plus la radio en pause dessous — le bandeau restait « En pause » —, lecteur YouTube dessiné 2 × plus grand puis réduit, `renderScale`, sinon image floue). `radiovision/stream` de Skyrock : clips, liste vide, pas le live.
- Logos des radios : `InternetRadios` (logos officiels de Wikipédia ou des sites des radios ; Wikipédia et ses images
  refusent l'agent d'OkHttp par défaut : client `IMAGE` avec l'agent de l'appli, `HttpClientProvider`) ; radios de la
  playlist : `InternetRadios.logoFor` à l'import (0.2.189 : remplace aussi l'image de la playlist pour une radio connue, ex. drapeau
  de SkyRock FM ; pas de logo trouvé pour West Coast G-Funk — AzuraCast sans logo — ni Tonic Ska Radio — site refusé d'ici). Wikimedia limite fortement les requêtes depuis le cloud (429).
  Radios françaises ajoutées (`InternetRadios.french`, catégorie `FRENCH_GROUP` rangée dans `RADIO`) seulement si la
  playlist ne les a pas déjà parmi ses radios (`M3uPlaylistLoader`).
- Guides (0.2.178-0.2.179) : `xmltvfr.fr` d'abord, puis `HttpEpgFeedProvider.EXTRA_GUIDES` (Pluto TV, Samsung TV Plus,
  Plex France d'i.mjh.nz ; epgshare01 FR1 et RAKUTEN1), puis le guide du fournisseur (`xmltv.php` du compte Xtream,
  `AppContainer.providerGuide`) ; chacun seulement pour les chaînes encore sans programme (`SourceRepository.importFromFeed`).
  Guides qui déclarent chaque chaîne juste avant ses programmes (Plex, Samsung) : second passage
  (`EpgImportResult.lateChannels`, `EpgLoader.importFile`). Mesurer une source sur la vraie playlist : `EpgProbeTool`
  (test ignoré sans `EPG_PROBE` = TSV des chaînes, `EPG_GUIDES` = guides dans l'ordre ; lancer avec `--rerun`, sinon
  Gradle reprend l'ancien résultat). Écartés le 18/11/2026 : guides étrangers de Pluto/Plex/Samsung (autres grilles sous
  les mêmes noms), epgshare01 BE2/CH1 (peu de chaînes, une fausse association), iptv-org (pas de guide prêt à
  télécharger, seulement un outil à faire tourner soi-même).
- Catégories de films et de séries (0.2.185) : `core/catalog/VodCategories.kt` donne le nom propre (`properName`, table
  `NAMES` des noms vus dans la playlist, sinon écriture normale des majuscules ; redonne le même nom à un nom déjà propre)
  et les catégories d'autres pays (`isFrench` : arabes, maghrébines, turques, polonaises, ramadan ; coréennes gardées).
  Appliqué à la lecture de la playlist (`M3uPlaylistLoader.toChannel` : `groupTitle` propre, colonne `french`) ; les
  catégories masquées sous l'ancien nom sont renommées (`RoomPlaylistStore.renameHiddenContentGroups`). « Contenus
  français seulement » les traite comme des catégories masquées (`HIDDEN_MOVIE_GROUPS`, `HIDDEN_SERIES_GROUPS`,
  `HIDDEN_KIND_GROUPS` de `Daos.kt`). Nouveau nom : augmenter `QualityParser.VERSION`.
- Année de sortie dans la fiche programme : celle du guide (`<date>`, la moitié des films de xmltvfr), sinon celle de la
  fiche TMDB du même titre (`rememberProgrammeYear`, `sameTitleYear` dans `ProgrammeArt.kt`, même cache que l'image).
- Fun Radio / RTL2 : `icecast.funradio.fr` redirige vers `streaming-ice.audiomeans.fr` (paramètres AdsWizz `aw_…` : pubs
  insérées, sans doute seulement en France). « Flux invalide » sur la box le 18/11/2026 : radio de l'appli illisible même en
  HLS → moteur VLC (`UNREADABLE_STREAM_ERRORS`, `handleError`, 0.2.189).
- Radios : pas de détection des pubs sur un flux sans image (`PlayerEngine.radioStream`, 0.2.184 : elle baissait la
  musique entre deux morceaux).
- Télés reliées (0.2.186, Réglages › Télés reliées) : `core/sync/` — `SyncDocument` (dernière écriture gagnante par clé,
  effacements gardés 90 jours ; une télé qui rejoint ajoute ses éléments datés de 1 : les réglages du groupe l'emportent,
  favoris réunis), `SyncKeys` (clés `s` réglage, `k` clé de service, `p` playlist, `f` favori, `h` catégorie masquée,
  `w` « Déjà vu », `r` reprise ; éléments rangés par fournisseur = serveur de l'adresse sans identifiants ni port :
  chaque télé garde son compte, une seule connexion par abonnement), `TvSyncEngine` (chaque télé dépose son document
  complet chiffré AES-GCM `tv-<id>.bin` sur la page filebin du groupe `nixtv-sync-…`, et fusionne ceux des autres ;
  page fermée par filebin — 7 jours sans dépôt ou supprimée, 405 — : page suivante `…-1`, redépôt tous les 3 jours),
  `SyncPairing` (code à 6 chiffres : invitation chiffrée sur `nixtv-link-<sha256 du code>`, supprimée à la réponse ou
  après 10 min). Appli : `data/sync/RoomSyncLocal` (lecture / écriture des bases et réglages ; playlist ajoutée
  seulement à une télé sans ce fournisseur, jamais retirée ailleurs ; réglages propres à l'appareil :
  `SettingsRepository.DEVICE_ONLY`), `TvSync` (quand : 5 s après un changement, fin de lecture, retour dans l'appli,
  toutes les 5 min ; jamais pendant une lecture), `EncryptedSyncStateStore` (`files/sync`), écran `ui/settings/TvLink.kt`.
  Nouveau réglage partagé : rien à faire ; propre à l'appareil : l'ajouter à `DEVICE_ONLY`.
- Configuration à distance (0.2.194, Réglages › Configuration à distance) : pour installer la télé d'un ami sans y être.
  `core/sync/RemoteSetup.kt` (`RemoteSetupLink` : code à 6 chiffres, page filebin `nixtv-setup-<sha256 du code>`, fichiers
  chiffrés avec une clé tirée du code : `attente.bin` (nom de la télé) → `configuration.bin` (`RemoteConfig` : une playlist,
  réglages, clés, catégories masquées LIVE/MOVIE/SERIES) → `recu.bin` ; page supprimée par celle qui configure dès la
  réponse, sinon 2 min après ; code valable 15 min, renouvelé seul). Télé configurée : `RemoteSetupReceiver` (actif tant
  qu'un écran montre le code : bienvenue, première étape de la configuration rapide sans playlist, page des réglages ;
  coupé sous Robolectric) puis `RemoteSetupApplier` (playlist ajoutée et choisie, ou mise à jour si même fournisseur ;
  configuration rapide marquée faite) ; `NexTvNavHost` recharge alors la playlist. Celle qui configure :
  `ui/settings/RemoteSetup.kt` (`RemoteSendViewModel` : serveur prérempli d'après sa playlist, identifiant + mot de passe
  de l'ami, réglages `SettingsRepository.remoteSetupPreferences` sans les équipes suivies ; « king365 » à la place du serveur =
  King 365 (`isKing`, serveurs de `King`) ; playlist toujours nommée « NixTV » (`REMOTE_PLAYLIST_NAME`) ; QR code d'un formulaire
  sur le téléphone, 0.2.198 : `PhoneSetupServer` + `PhoneForm` (`RemotePhoneForm` → `fromPhone`, plusieurs envois
  acceptés), page `PhoneSetupPage.remoteForm`). Pas de lien durable (≠ Télés reliées).
- Rangement recalculé sans relire la playlist : `ChannelFilters.rearrange` (au lancement quand `ChannelLineup.VERSION`
  dépasse le `lineupVersion` des réglages). Toute nouvelle règle de `ChannelLineup` : augmenter `ChannelLineup.VERSION`.
- « Chaînes françaises seulement » (réglage) : colonne `french` (section autre que `OTHER` et `GENERAL` « Autres chaînes »,
  0.2.190, dans `ChannelLineup`), masquée
  par `VISIBLE_VERSION` quand `hidden_groups` contient `LIVE_FOREIGN`. La section Foot (`epgChannels`,
  `eventChannelCandidates`) garde toutes les chaînes, masquées en dernier.
- Retour du lecteur à l'accueil : la chaîne regardée (`AppContainer.lastLiveChannel`) et la catégorie où elle a été
  choisie dans le menu des chaînes (`lastLiveCategory`) ; `HomeViewModel.followWatchedChannel` passe l'accueil à une
  catégorie qui la contient (seulement lecteur ouvert, `playbackOpen` : jamais sous les yeux) et note sa place dans
  cette liste (`AppContainer.lastLivePlace`, lue dans la base) ; `TvBody` charge la liste jusqu'à elle,
  `FollowLastChannel` / `restoreFocus` (`FocusMemory.kt`) y posent la sélection (à défaut, sur la première chaîne : une
  sélection perdue ouvrait le menu de gauche de Streaming). Les tests attendent cette place avant le retour (CI lente).
- Volet des catégories de Streaming (0.2.190) : la colonne (`CategoryRail`) s'ouvre déjà défilée sur la catégorie choisie
  (`railIndexOf`), sinon une catégorie loin dans la liste n'existait pas encore et la sélection allait à la recherche. Liste
  d'une catégorie choisie vide moins de 1,5 s (`EMPTY_LIST_CONFIRM_MILLIS`) : pas prise pour une catégorie vide (le volet
  restait ouvert sur la box, cause exacte non reproduite). Rangées de Streaming : `LazyListState` créé sur la rangée de
  `lastOpened` (en plus de `RestoreRow`) ; saut « haut de page → rangée » vu en vidéo sur la box, non reproduit ici.
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
  en cours, logo, horloge) et l'enregistrement ; seuls les repères du flux sont relus. Pendant un enregistrement (0.2.177,
  `AppContainer.analysisRecording`) : moins bonne version de la chaîne (`channelStart`, `ChannelStart.analysis`, sans
  remontée ni « qualité qui a fonctionné »), qualité visée rétablie à l'arrêt (`PlayerViewModel.followAnalysisQuality`). Le rejouer : `AdDeviceReplayTool` (`AD_DEVICE`,
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
- 16 au 18/11/2026 (box en automatique 0.2.169 → 0.2.176, 26 fichiers, 15 h 30, relevés dans `tools/ad_truth/`) : appareil
  88 % des secondes de pub baissées (93 % sans un bloc de bandes-annonces de France 5, indétectable), 329 s de programme à
  tort, délai moyen 28 s. Le rejeu (« ici », 81 %) ouvre plus tard : il ne sait pas ce que la box a appris avant. Corrigés en
  0.2.180 : découpage en fichiers d'une seconde après 2 h sur une chaîne (`AdRecorder.recordedMillis` lisait l'horloge de la
  détection, qui compte depuis l'ouverture de la chaîne) ; chaînes « 24/24 » de M6+ sans détection (`AdFreeChannels`).
  Restent : courts programmes parrainés entre deux pubs (« Petits plats » de Laurent Mariotte sur TF1), documentaire de T18
  repris après une page (logo inconnu), premières secondes d'une page après un changement de chaîne.

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
