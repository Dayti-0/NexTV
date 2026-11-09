# Nouveautés

Changements de chaque version, la plus récente en haut. À la publication, la section de la version
(« ## 0.2.36 ») devient les notes de la version GitHub, et l'application n'affiche qu'elle avant
d'installer la mise à jour (Réglages › Mise à jour), avec celles des versions sautées.

## 0.2.81
- Fiches films et séries : la distribution (TMDB). « Avec … » et les têtes d’affiche sous le résumé ; dans l’interface Streaming, une rangée « Distribution » avec photo, nom et rôle (rôle principal sur toutes les saisons pour une série)

## 0.2.80
- Plus réactif : passage d’un écran à l’autre en un quart de seconde (au lieu de 0,7 s), le lecteur s’ouvre et se ferme sans fondu
- Affiches et visuels apparaissent en fondu ; les affiches suivantes d’une rangée, et les premières des rangées du dessous, sont téléchargées d’avance (moins d’affiches vides en défilant vite)
- Images gardées en cache sur l’appareil (rechargées sans réseau) ; sur les box à petite mémoire, cache plus léger et bandes-annonces automatiques désactivées par défaut (activables dans les réglages)

## 0.2.79
- Streaming : nouvelle fiche des films et séries, plein écran : grand visuel avec sa bande-annonce (muette selon les réglages, bouton « Son »), affiche, titre, note, année, genres, durée, accroche et résumé, boutons « Reprendre », « Depuis le début », « Bande-annonce » (plein écran) et « Favoris », puis les titres similaires
- Séries (Streaming) : saisons en onglets (la saison change dès qu’on passe dessus), épisodes en vignettes avec l’épisode en cours et sa progression ; OK reprend l’épisode en cours ou lance celui choisi
- Badge « Déjà vu » sur les films et les épisodes regardés jusqu’au bout (affiches de l’accueil, fiches, épisodes)
- Épisodes : le suivant est proposé 40 s avant la fin, avec le temps restant ; il démarre à la fin (« Annuler » pour rester)
- Fin d’un film, ou du dernier épisode : le lecteur se ferme et revient à l’écran précédent

## 0.2.78
- Lecteur : la pause, le retour en arrière, le retour au direct et les sauts ne font plus baisser la qualité (ils étaient pris pour une connexion qui faiblit)
- Direct en différé : → ne ramène plus au direct par mégarde, seul OK maintenu le fait

## 0.2.77
- Streaming, films et séries : « Rechercher un film / une série » retiré du haut de l’écran (la « Recherche Globale » du menu suffit)
- Bandes-annonces bien plus rapides à démarrer : le lecteur se prépare caché et muet pendant le délai, le navigateur intégré est préparé à l’arrivée sur Films et Séries, et les fiches des contenus mis en avant sont lues d’avance

## 0.2.76
- Streaming, films et séries : nouveau grand bandeau à l’ouverture, avec plusieurs contenus mis en avant qui défilent d’eux-mêmes (points en bas), et les boutons « Regarder » ou « Reprendre », « Bande-annonce », « Plus d’infos ». La sélection s’ouvre sur « Regarder » ; ↓ passe aux rangées (le bandeau montre alors l’affiche choisie), ↑ depuis la première rangée revient aux boutons
- Bandes-annonces : muettes par défaut, bouton « Son » dans le bandeau, lues une seule fois puis retour à l’image ; si YouTube refuse la lecture, l’image reste. « Bande-annonce » la lit en plein écran avec le son (Retour pour fermer)
- Réglages › Affichage : son des bandes-annonces, délai avant leur lancement (2, 3 ou 5 s)
- Rangées Streaming : grandes affiches seules aux coins arrondis (titre et détails dans le bandeau), barre de progression pour les contenus commencés
- Menu de gauche : déploiement plus doux, sélection lumineuse aux couleurs de la palette, contenu assombri derrière le menu ouvert

## 0.2.75
- Streaming, films et séries : « Rechercher un film / une série » passe en haut de la page, sur la grande affiche (↑ depuis la première rangée)

## 0.2.74
- Lecteur, direct : ← recule de 10 s (plusieurs appuis s’additionnent, maintenu : en continu) ; → ou OK long revient au direct. Les dernières secondes du direct sont gardées en mémoire, sans couper ni recharger la chaîne
- Pause du direct : la reprise ne recharge plus la chaîne (la même connexion enregistre la suite)
- En pause ou en différé, « OK long pour revenir au direct » est affiché
- Streaming : en ouvrant le volet des catégories avec ←, le menu de gauche ne s’ouvre plus une fraction de seconde

## 0.2.73
- Nouveau : ajouter une playlist depuis le téléphone (bouton « Depuis le téléphone », à l’accueil sans playlist et dans la liste des playlists). La télé affiche un QR code ; sur le téléphone (même Wi-Fi), saisissez le compte Xtream ou le lien M3U, puis « Envoyer à la télé » : l’ajout s’ouvre prérempli, il ne reste qu’à confirmer. Rien ne passe par Internet, et la page ne fonctionne que tant que le QR code est affiché

## 0.2.72
- Rangées : plus de « Voir plus », la suite se charge d’elle-même en approchant du bout (seules les affiches à l’écran sont gardées en mémoire)
- Streaming : la rangée choisie est au milieu de l’écran ; en dessous, on voit juste le titre de la rangée suivante

## 0.2.71
- Streaming : la bande-annonce derrière la grande affiche est de retour (elle ne se lançait plus), toujours sans l’affichage YouTube du début
- Aperçu des chaînes : en revenant d’un autre menu, l’image arrive aussitôt avec le son (elle mettait parfois plusieurs secondes)
- Configuration rapide : les cartes choisies ne sont plus coupées aux bords ; après un choix, la sélection passe à « Suivant »
- Réglages, DNS : description retirée

## 0.2.70
- Streaming, chaînes : les catégories passent dans le volet, comme pour les films et séries (la liste et l’aperçu ont toute la place)
- Streaming : ← au bord des rangées ou de la liste ouvre le volet des catégories, un second ← ouvre le menu
- Volet des catégories : il s’ouvre sur la catégorie choisie ; après un choix, la sélection va sur la première chaîne de la nouvelle liste
- Films et séries : le saut à la rangée d’une catégorie attend qu’elle soit chargée (il revenait parfois sur l’ancienne rangée)
- Réglages, « Catégories affichées » : simplement Chaînes, Films, Séries

## 0.2.69
- Catégories des chaînes : en revenant sur « Toutes les chaînes », la liste repart du haut (elle restait défilée sur les chaînes de la catégorie précédente)
- Catégories : ← depuis la liste revient sur la catégorie choisie, et non sur la plus proche
- Streaming, menu de gauche : choisir une section le referme, la sélection passe au contenu
- Réglages, son de l’aperçu : « Moyen » devient « Normal »

## 0.2.68
- Cinéma Premium : moins d’espace sous la barre du haut, l’aperçu du direct et les catégories remontent

## 0.2.67
- Cinéma Premium : « Reprendre la lecture » du menu ouvre un écran dédié, avec les onglets Films et Séries (ouvert sur le dernier visionnage), de grandes affiches avec la progression et le temps restant ou l’épisode ; OK reprend, appui long retire de la liste, Retour revient au menu

## 0.2.66
- Catégories : un nom trop long défile quand la catégorie est sélectionnée

## 0.2.65
- Cinéma Premium : le Top 10 de la semaine change de forme (affiches ordinaires avec un petit rang doré « N°1 », « N°2 »… dans le coin, au lieu des grands chiffres de Streaming)
- Chaînes en Streaming et Cinéma Premium : le cadre de sélection du panneau de droite suit ses coins arrondis (ils étaient coupés)

## 0.2.64
- Bande-annonce derrière la grande affiche : l’affichage YouTube du début (titre, chaîne, boutons) n’apparaît plus ; la vidéo arrive en fondu une fois ces commandes parties, avec le son à ce moment-là

## 0.2.63
- Chaînes en mosaïque (Streaming et Cinéma Premium) : l’aperçu passe en bandeau en haut, avec le nom de la chaîne et le programme en grand ; la mosaïque prend toute la largeur, vignettes plus grandes

## 0.2.62
- Streaming et Cinéma Premium, films et séries : choisir une catégorie dans le volet saute à sa rangée, toutes les autres restent affichées
- Volet des catégories : ← le ferme et ouvre le menu de gauche (Streaming)
- Chaînes en Streaming et Cinéma Premium : espaces réduits entre les catégories, la liste et l’aperçu, plus de place pour les chaînes
- Chaînes en Streaming et Cinéma Premium : affichage en mosaïque possible (Réglages › Interface et couleurs)
- Réglages : la description de « Qualité automatique » indique qu’elle descend et remonte selon la connexion

## 0.2.61
- Streaming et Cinéma Premium, films et séries : une catégorie ouverte garde le même dessin que l’accueil (grande affiche en haut, une seule rangée qui défile de gauche à droite, « Voir plus » au bout) au lieu d’une grille
- Cinéma Premium : la rangée choisie est plus haute, la rangée du dessous se voit mieux (titre de la sélection sur une ligne)

## 0.2.60
- Streaming et Cinéma Premium, chaînes : nouvel écran avec les catégories à gauche (dont « Toutes les chaînes »), la liste des chaînes et leur programme au centre, l’aperçu et la suite des programmes à droite ; ↑ ↓ pour changer de chaîne, ← pour les catégories
- Films et séries : l’image de fond ne change plus deux fois (affiche puis belle image) ; elle attend la bonne image, et celles déjà vues s’affichent tout de suite
- Heure affichée dans la barre de navigation de toutes les interfaces, depuis n’importe quel menu
- Guide TV : le titre d’un programme commencé avant le bord de l’écran reste visible
- Guide TV : « L’essentiel » est retiré ; dans Favoris, choix entre « Guide TV » et « Ce soir »
- Guide TV, « Ce soir » : une fois la soirée finie (vers 23 h 30, et après minuit), c’est le lendemain soir qui s’affiche ; plus de programme de la veille
- Lecteur, menu des chaînes : la catégorie choisie est toujours calée à gauche, en allant à gauche comme à droite
- Réglages › Interface et couleurs : la sélection s’ouvre sur l’interface choisie (et non sur « Retour »)

## 0.2.59
- Streaming, films et séries : les boutons « Épisodes » / « Lecture » et « Plus d’infos » de la bannière sont retirés (OK sur une affiche suffit)
- Volet des catégories refait : fond translucide (on devine l’écran derrière), titre « Catégories », ouverture plus douce
- Volet des catégories : le focus ne peut plus en sortir vers les affiches derrière ; une catégorie choisie le referme, et il ne se rouvre plus tout seul au retour sur « Toutes les séries »
- Streaming et Cinéma Premium : une catégorie ouverte garde le même fonctionnement (volet par ← ou « Catégories » du menu) au lieu d’une colonne de catégories fixe
- « Rechercher partout » devient « Recherche Globale », sans le texte d’aide sous le champ

## 0.2.58
- Streaming, menu de gauche : « Réglages » tout en bas s'affiche en entier et lisible (il était écrasé)

## 0.2.57
- Streaming : la bande-annonce remplit tout son cadre sur la Fire TV (elle ne tenait qu'une bande en haut, le reste en noir)

## 0.2.56
- Streaming : l'image de la bande-annonce s'affiche de nouveau (carré noir avec seulement le son en 0.2.55)

## 0.2.55
- Réglages refaits : la liste des pages à gauche (Playlist, Lecture, Affichage, Guide TV, Films, séries et foot, Réseau, Sauvegarde, Mise à jour), avec un résumé sous chacune ; la page choisie s'affiche à droite, rangée en petites cartes. → ou OK entre dans la page, ← ou Retour revient à la liste
- Configuration rapide en 5 étapes (interface, couleurs, qualité et lecture) : proposée une seule fois, à l'ouverture qui suit cette mise à jour ou après l'installation, puis toujours disponible en haut des réglages
- Lecteur, infos de lecture : « Débit reçu » s'affiche aussi pour les chaînes en flux continu (il restait sur « — ») ; « Vitesse réseau » en plus pour les chaînes en HLS
- Foot : « chaîne probable » n'apparaît plus sur les cartes de la liste, seulement dans la fiche du match
- Lecteur, options (touche Menu) : la qualité visée (Meilleure qualité, HD maximum, Économie) se change pendant la lecture et s'applique tout de suite à la chaîne quand elle existe en plusieurs versions
- Streaming : la bande-annonce derrière la grande affiche s'affiche en entier (plus coupée en haut et en bas), avec le son ; la version française doublée (VF) est préférée, puis la VOSTFR ; sous-titres seulement si elle n'est pas en français ; elle se met en pause quand on quitte l'appli

## 0.2.54
- Interface Streaming façon Netflix : menu à gauche en icônes (Rechercher, sections, Catégories, Réglages) qui s'ouvre avec ←, à la place des onglets du haut
- Streaming, Films et Séries : boutons « Lecture » (ou « Épisodes ») et « Plus d'infos » sur la grande affiche
- Films et Séries : rangée « Top 10 de la semaine » avec les rangs en grand
- Streaming : la bande-annonce du film choisi démarre sans le son derrière la grande affiche après quelques secondes ; désactivable dans les réglages (« Bande-annonce en fond »)

## 0.2.53
- Streaming et Cinéma Premium : ← depuis le bord gauche des rangées ouvre un volet avec toutes les catégories (TV, Films, Séries) et leur nombre ; OK ouvre la catégorie, → ou Retour referme le volet

## 0.2.52
- Menu de Cinéma Premium : le nombre de chaînes, de films et de séries sous leur tuile, à la place des descriptions ; la fin de l'abonnement passe en haut, à côté de l'heure

## 0.2.51
- Live Center : plus de titre (« Toutes les chaînes », nombre de chaînes) au-dessus de la liste, déjà indiqué dans la colonne de gauche

## 0.2.50
- Recherche de partout (chaînes, films et séries d'un coup) : bouton dans le menu de Cinéma Premium et loupe en haut des interfaces Live Center et Streaming
- Cinéma Premium : l'aperçu en direct s'arrête au retour sur le menu d'accueil

## 0.2.49
- Scores en direct mis à jour plus vite : toutes les 15 à 20 s dans le bandeau du lecteur et dans Foot (au lieu d'une minute)

## 0.2.48
- Cinéma Premium : menu d'accueil à grandes tuiles (TV, Guide TV, Films, Séries, Foot), avec Favoris, Reprendre la lecture, Réglages et la fin de l'abonnement ; « Retour » ou « Menu » y ramène depuis une section

## 0.2.47
- Lecteur : le score des matchs des chaînes « LIVE MATCH » s'affiche bien dans le bandeau (il manquait faute de programme dans le guide)

## 0.2.46
- Lecteur : score et minute de jeu dans le bandeau aussi sur les chaînes « LIVE MATCH » (DAZN LALIGA, DAZN SERIE A), qui n'ont pas de programme dans le guide

## 0.2.45
- Réglages, mise à jour : les nouveautés se lisent version par version, chacune entière à l'écran (plus de texte coupé)

## 0.2.44
- Ajout d'une playlist : textes d'aide raccourcis sous l'adresse

## 0.2.43
- Adresse du serveur Xtream et lien M3U : le début se choisit sous le champ (http://www., https://www., http://, https:// ou aucun)
- Mots de passe (playlist, compte Xtream, proxy) toujours affichés en clair, sans bouton « Afficher / Masquer »

## 0.2.42
- Foot : le tour des chaînes « LIVE MATCH » continue d'un jour à l'autre (Elche – Betis reste sur LALIGA 5 dimanche)
- Foot : deux matchs à la même heure sont proposés dans l'ordre vu chez le fournisseur (Torino – Lecce sur DAZN 1 SERIE A, Cagliari – Frosinone sur la 2)

## 0.2.41
- Foot : les chaînes « LIVE MATCH » sont prises à tour de rôle ; un match annoncé par le guide du fournisseur donne la chaîne de tous les autres (Elche – Betis reste sur LALIGA 5, le match suivant revient sur LALIGA 1)

## 0.2.40
- Lecteur, liste des chaînes : le titre du programme en cours s'affiche en entier (plus petit quand il est long)

## 0.2.39
- Foot : les chaînes « LIVE MATCH » sont d'abord lues dans le guide de votre fournisseur (compte Xtream) ; quand il annonce le match, la chaîne n'est plus marquée « chaîne probable »
- Foot : sinon, les matchs sont numérotés depuis le début de la journée : un match garde sa chaîne quand le précédent est terminé (Espanyol – Deportivo sur LALIGA 2)
- Foot : Deportivo La Coruña est reconnu dans le guide

## 0.2.38
- Compte Xtream et lien M3U : « http://www. » reste affiché devant ce que vous tapez, même quand le clavier de la Fire TV remplace le texte du champ ; une adresse tapée en entier (http://…, https://…) le remplace

## 0.2.37
- Listes : fondu en haut quand on peut remonter, comme en bas (Guide TV, « Ce soir », accueil, Foot)
- Réglages : la liste défile jusqu'en haut (titre de la première carte) et jusqu'en bas (mentions)
- Fiches des matchs et des programmes : les descriptions en langue étrangère (néerlandais, anglais…) sont traduites en français
- Catégories (TV, Films, Séries, Foot) : la catégorie choisie n'est plus agrandie, son texte reste net, et son ombre est centrée autour d'elle

## 0.2.36
- Foot : les matchs absents du guide TV sont proposés sur les chaînes « LIVE MATCH » de la playlist (DAZN LALIGA, DAZN SERIE A), avec la mention « chaîne probable »
- Foot : tous les matchs sont affichés, même ceux dont la chaîne n'a pas été trouvée
- Mise à jour : seules les nouveautés de la nouvelle version sont affichées
- Compte Xtream : l'adresse du serveur commence déjà par « http://www. », il ne reste qu'à écrire la suite (effaçable)

## 0.2.35
- Accueil : nom de la playlist retiré de la barre du haut, cadres de sélection plus coupés
- Cinéma Premium et Streaming s'ouvrent toujours sur leur propre affichage

## 0.2.34
- Live Center : résumé complet du film et description complète du programme dans le volet

## 0.2.33
- Guide TV : plus de balises HTML dans les textes des programmes
