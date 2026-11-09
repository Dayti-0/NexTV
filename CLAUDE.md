# NixTV — notes pour Claude

Application IPTV Android en Kotlin / Jetpack Compose (Media3). Elle s'appelait NexTV et le code garde ce nom
(`tv.nextv.*`). Deux modules :
- `core` : Kotlin JVM, sans Android (playlists, guide, foot, qualité, réseau) ;
- `app` : l'application Android (écrans Compose, lecteur, base Room).

## Échanges avec l'utilisateur

- Il écrit en français, souvent avec des photos de l'écran : une Fire TV branchée sur un moniteur ASUS, et une
  télé Sony. Répondre en français, simplement, sans jargon.
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

## Publier une version

- Écrire d'abord la section `## X.Y.Z` de `CHANGELOG.md` (en français, une ligne par changement visible) : elle
  devient les notes de la version, seules affichées par l'application avant la mise à jour.
- Pousser une branche `release/vX.Y.Z` sur le commit à publier : le workflow « Release » construit l'APK signé et
  crée la version GitHub, que l'application propose en mise à jour.
- Numéro : la dernière version + 1 (`git ls-remote --heads origin 'release/*'`).
- L'utilisateur a demandé que chaque correction validée soit publiée sans attendre son accord, et le plus vite
  possible : dès que les tests locaux passent, pousser `main` et la branche `release/vX.Y.Z` en même temps, sans
  attendre la CI (elle ne fait que compiler, le lint et les captures ; la publication prend ~10 min). Si la CI
  échoue ensuite, corriger et publier la version suivante.
- Ne pas compter sur un rappel programmé pendant un long travail : il ne se déclenche qu'à la fin du tour.

## Repères dans le code

- Lecteur : `ui/player/PlayerViewModel.kt` avec `PlayerEngine` (un seul ExoPlayer par écran, une source réseau par
  flux via `PlayerFactory.mediaSource`), `DelayedPlaybackController` (pause, différé, replay, avance rapide),
  `AutoQualityController`, `ResumeController`, `ChannelMenuController`.
- Accueil : `ui/home/` (interfaces Live Center, Streaming, Cinéma Premium) ; composants communs dans
  `ui/components/Components.kt` (fondus `bottomFade`, `FadingRow` / `edgeFade`).
- Guide TV et fiche programme : `ui/guide/`.
