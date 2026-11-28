# Audit pour la 3.0 (28/11/2026)

Demande de l'utilisateur : audit complet et autonome, corrections publiées en petites versions (0.2.240…), puis la
3.0.0 (bugs visuels, fluidité des petites Fire TV, nouveau look, nouvelles fonctions, nettoyage du code). Les données de
NixTV sur les télés ne sont jamais effacées.

Sources :
- **Télés** (session lancée sur le Pi 4 relais, NixTV 0.2.239) : box Pi 5 en interface Streaming, Fire TV Stick Lite
  (`AFTSS`, Android 9) en interface Classique.
- **Captures** des 100 écrans (`TvScreenshots`, 960 × 540).

État : ☐ à faire, ☑ corrigé (version), ✗ écarté (raison).

## Défauts visuels

- ☑ (0.2.240) Affiche sans image (et légende des affiches étroites) : titre coupé au milieu d'un mot (« Opérati / on Mistral »,
  « Les Héritier / s ») dans les grilles Live Center. → `WholeWordsText` (police réduite tant qu'un mot ne tient pas).
- ☑ (0.2.240) Bandeau du lecteur en pause / en différé : « OK long pour revenir au direct » dans la ligne des pastilles repoussait
  le débit sur une deuxième ligne. → sous le programme.
- ☑ (0.2.240) « Recherche Globale » → « Recherche globale ».
- ☑ (0.2.240, marge entre les deux ; à revoir sur la télé) Menu des chaînes du lecteur : l'horloge chevauche la rangée des catégories (« Séries et divertisse… »), box et Fire TV.
- ☑ (0.2.240, dégradé plus sombre dès le haut du texte) Bandeau du direct sans fond sombre sur la Fire TV (Classique) : texte blanc illisible sur une image claire.
- ☐ Séries (Streaming) : haut de page tassé, le texte touche « Top 10 », titre de la série n° 1 coupé.
- ☐ Bande-annonce de fond : gros bouton « pause » de YouTube visible 1 à 2 s.
- ☑ (0.2.240, ligne réservée) Accueil Classique : les nombres (chaînes, films…) et la date d'abonnement arrivent ~3 s après, les titres sautent.
- ☐ Aperçu noir et vide tant qu'aucune chaîne n'est sélectionnée (Classique, TV).
- ☐ Radios de la playlist : noms en majuscules (« EUROPE 1 RADIO »), logo drapeau pour les HOTMIXRADIO.
- ☐ MMA : « .. » devant les horaires de diffusion ; heure du gala Hexagone fausse (11:21 au lieu de 20:30).
- ☐ Fiche de film : l'étoile des favoris seule sur une deuxième ligne quand les boutons ne tiennent pas.

## Défauts de fonctionnement

- ☑ (0.2.240, frise figée à la première sélection) Guide TV ouvert sur 05:00-07:00 à 11:02 (programme de TF1 commencé à 05:50) : se placer sur l'heure actuelle.
- ☑ (0.2.240, une copie par épisode, version française d'abord ; pas encore de choix de version) Fiche d'une série : épisodes en double (deux copies du fournisseur), sans titre ni image → une carte par épisode,
  choix de la version comme pour les films.
- ☐ Grille des chaînes (Streaming) : ← depuis la 4e colonne fait glisser toute la grille, la sélection reste en 4e colonne.
- ☐ Classique : sélection sur « ← Menu » à l'ouverture de TV et de Films (un appui de plus pour atteindre le contenu).
- ☐ Recherche de chaîne dans le volet des catégories : résultats seulement en quittant le volet, filtre invisible ensuite.
- ☐ MMA : 15-20 s de chargement avec « 0 » partout (garder le dernier calendrier) ; vainqueur affiché pour un gala
  annoncé « en différé ».
- ☐ Fausse page de pub sur M6 au début de « 66 minutes » (~30 s), cause inconnue (pas d'enregistrement).
- ☐ « Dernier incident » : une fermeture forcée (Android 9, sans `ApplicationExitInfo`) passe pour un plantage.

## Fluidité et mémoire (mesures du 28/11)

| | Box | Fire TV |
|---|---|---|
| Ouverture à froid | 0,45 s ; accueil ~2 s, aperçu ~6 s | 1,1-1,2 s |
| Images saccadées : accueil | 6,8 % | 11 % |
| Liste des chaînes / menu du lecteur | 2,8 % | 67 % / 62 % |
| Guide | 19,6 % | 40 % |
| Films | 3,8 % | 84 % |
| Radio (vinyle) | 0 % | 99,8 % |
| Mémoire : accueil / direct / radio | 140 / 250 / 225 Mo | 93 / 165 / 218 Mo |

- ☑ (0.2.240, 20 images/s, vinyle simplifié ; à remesurer) Fire TV : animation des radios trop lourde (viser 15-20 images/s).
- ☐ Fire TV : listes de chaînes, films, guide saccadés.
- ☐ Fire TV : la mémoire de l'appli monte à chaque écran (93 → 218 Mo en 7 min).
- ☑ (0.2.240, ouvert en arrière-plan au lancement ; à remesurer) Box : cache disque de Coil (`DiskLruCache`) bloqué ~2,2 s au démarrage (« Long monitor contention », 6 en attente).
- ☐ Aperçu changé à chaque appui pendant le défilement : attendre que la sélection s'arrête.

## Améliorations proposées

- ☐ Fiche d'un programme du guide : « Rappel » (prévenir au début) et « Regarder depuis le début ».
- ☐ MMA dans le menu Classique (absent sur la Fire TV ; peut-être masqué dans ses réglages).
