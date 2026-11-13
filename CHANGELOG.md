# Nouveautés

Changements de chaque version, la plus récente en haut. À la publication, la section de la version
(« ## 0.2.36 ») devient les notes de la version GitHub, et l'application n'affiche qu'elle avant
d'installer la mise à jour (Réglages › Mise à jour), avec celles des versions sautées.

## 0.2.141
- Live TV : chaîne choisie dans une autre catégorie du menu des chaînes (pendant la lecture) : au retour à l’accueil, la liste est sur cette catégorie et la sélection sur la chaîne regardée (elle revenait sur la chaîne ouverte au départ, ou se perdait)
- Même chose après avoir zappé hors de la catégorie ouverte : l’accueil passe sur « Toutes les chaînes », sélection sur la chaîne regardée

## 0.2.140
- Nouveau réglage « Rassembler les chaînes » (Réglages › Lecture › Chaînes, activé d’office) : une seule ligne par chaîne, même quand la playlist la propose en plusieurs versions de son (double audio, Dolby Digital) ou l’écrit de plusieurs façons (« CHASSE & PÊCHE » et « CHASSE ET PECHE », « TELETOON +1 » et « TELETOON+ 1 »…), dans « Toutes les chaînes » comme dans chaque catégorie
- « Toutes les chaînes » rangée comme sur une télé : la TNT dans l’ordre des numéros (TF1, France 2, France 3, France 4…), puis Canal+, le cinéma, les séries, le sport, la découverte, la jeunesse, la musique, l’info, les chaînes régionales et d’outre-mer, les chaînes « événement » (matchs en direct seulement), et enfin les autres ; les catégories, le zapping et le guide suivent le même ordre
- Les fausses chaînes de séparation de la playlist (« ▀▄ FR ▀▄ REGIONALES ▄▀ ») disparaissent, et les autres éditions régionales de France 3 aussi : seule France 3 reste, avec juste après elle celle de ta région si tu l’as choisie (Réglages › Région France 3)
- Réglage coupé : les chaînes telles que la playlist les présente, versions de son à part (« CANAL+ (Double audio) », « CANAL+ (Dolby) ») et dans l’ordre de la playlist
- Noms plus propres : « BFM BUSINESS » au lieu de « BFM BUSINESS / », « BEIN SPORTS FRANCE LIGUE 1 » sans « (CHAINE ) »
- La playlist est relue au lancement qui suit la mise à jour ; favoris et chaînes récentes suivent les chaînes regroupées

## 0.2.139
- Chaînes en plusieurs versions de son (« CANAL+ CINEMA(S) », « … (DOUBLE AUDIO) », « … DOLBY DIGITAL », pareil pour Ciné+ Festival…) : une seule chaîne dans la liste, qui s’ouvre d’office sur la version double audio (VF et VO au choix), sinon Dolby Digital ; la playlist est relue au lancement qui suit la mise à jour
- Favoris et chaînes récentes enregistrés sous une version « Double audio » ou « Dolby » suivent la chaîne regroupée

## 0.2.138
- Fiche d’une série : la durée des épisodes vient d’abord de ton serveur (durée exacte de chaque fichier), même pour une série que TMDB ne connaît pas encore ; TMDB reste utilisé sinon
- Interface Classique : le bouton de retour au menu devient un rond discret avec une flèche ; sélectionné, il s’éclaire et affiche « Menu »

## 0.2.137
- Retour à l’accueil après un changement de chaîne en plein écran : la sélection va enfin sur la dernière chaîne regardée (la liste la redonnait aussitôt à la chaîne ouverte au départ)

## 0.2.136
- Direct mis en pause une deuxième fois (ou après un retour en arrière) : la lecture reprend normalement ; l’image restait figée jusqu’au changement de chaîne, même en remettant pause puis lecture

## 0.2.135
- Retour à l’accueil après un changement de chaîne en plein écran : la sélection va vraiment sur la dernière chaîne regardée (l’écran refusait la sélection un instant au retour, et la liste revenait sur la chaîne ouverte au départ)

## 0.2.134
- Fiche d’une série : la durée de chaque épisode s’affiche sur sa vignette (en bas à droite), et à droite de chaque épisode dans la liste de l’interface classique ; elle vient de TMDB (pour l’épisode en cours, la durée réelle du fichier)

## 0.2.133
- Retour à l’accueil après avoir changé de chaîne en plein écran : la sélection (et l’aperçu) est sur la chaîne regardée en dernier, et non plus sur celle ouverte au départ (si elle est dans la liste affichée)

## 0.2.132
- Films en double sous leur titre français et leur titre original (« Le Virtuose » et « The Virtuoso », « Star Trek : Sans limites » et « Star Trek Beyond ») : une seule affiche, sous le titre français ; l’autre copie se choisit sur la fiche (bouton « Version », avec son titre). Les doublons déjà affichés une fois disparaissent à la prochaine lecture de la playlist (au lancement qui suit la mise à jour)
- Recherche de films : le titre original trouve aussi le film rangé sous son titre français
- Favoris, reprises et « Déjà vu » enregistrés sous le titre original suivent le film

## 0.2.131
- Aperçu → chaîne en grand (OK), et retour : l’image ne reste plus noire ; elle passe d’un écran à l’autre sans que le décodeur change de destination (Android 10 et plus)
- Retour à l’accueil depuis une chaîne : l’image continue en grand jusqu’à ce que l’aperçu la reprenne, au lieu d’un écran noir
- Réglages › Aperçu : nouveau réglage « Passage sans coupure vers le plein écran » (activé) ; coupé, la chaîne est rouverte à chaque passage, comme avant
- Android 9 ou avant : la chaîne est rouverte à chaque passage entre l’aperçu et le plein écran (le passage sans coupure pouvait y laisser l’image noire)
- Infos de lecture, ligne « Aperçu » : dit aussi pourquoi la chaîne a été rouverte (Android 9 ou avant, réglage coupé)

## 0.2.130
- Aperçu → chaîne en grand (OK) : le flux de l’aperçu est vraiment repris, aussi avec « Baisser le son des pubs », un décodeur choisi, et pour les chaînes en plusieurs versions (l’aperçu joue la version que la chaîne ouvrira)
- Chaîne refusée un instant par l’abonnement juste après la fermeture de l’aperçu : nouvel essai dans la même qualité, au lieu de « Connexion lente » et d’une qualité plus basse
- Retour à l’accueil depuis une chaîne : l’aperçu continue sur le même flux, aussi avec « Baisser le son des pubs » ou un décodeur choisi
- Infos de lecture : nouvelle ligne « Aperçu » qui dit si le flux de l’aperçu a été repris, ou pourquoi il a été rouvert
- Fiche d’un film ou d’une série : le bouton « Bande-annonce » revient à côté de « Regarder » ; il ouvre SmartTube (YouTube sans pub) s’il est installé, sinon le lecteur de l’appli
- Bande-annonce en plein écran : « Retour » ramène en haut de la fiche, sur « Regarder »
- Fiche Streaming : le résumé reste lisible sur les images claires
- « Masquer les chaînes 4K » : un film qui existe aussi hors 4K n’affiche plus le badge 4K dans les listes (la 4K reste proposée en dernière version sur sa fiche)
- Accueil : « Retour » n’ouvre plus de fenêtre « Quitter » ; « Appuyer à nouveau pour quitter » s’affiche 3 secondes, un second appui ferme l’application
- Menu de gauche (Streaming) : le flou couvre tout le menu, jusqu’à son bord droit

## 0.2.129
- Fiche d’un film ou d’une série : la bande-annonce est une grande image sur toute la largeur, tout en bas ; descendre jusqu’à elle l’ouvre en plein écran (le bouton « Bande-annonce » du haut disparaît)
- Bandes-annonces : une version française passe avant une version anglaise, même si ce n’est qu’un teaser ; pour un film récent, l’appli revérifie chaque jour si une VF est arrivée
- Bande-annonce en anglais sans sous-titres français : YouTube les traduit automatiquement en français
- Appareils sous Android 7 (anciennes Fire TV) : correction d’une fermeture possible de l’application à l’ouverture du menu (flou)

## 0.2.128
- Chaîne ouverte depuis son aperçu (OK) : plus de « Connexion lente » ni de baisse de qualité juste après l’ouverture
- Passage de l’aperçu à la chaîne en grand sans temps d’arrêt : l’image et le son continuent pendant l’ouverture
- Retour à l’accueil depuis une chaîne : l’aperçu continue sur le même flux, sans le rouvrir (sauf en pause, en différé, avec « Baisser le son des pubs » ou le son envoyé tel quel à l’ampli)
- Aperçu des chaînes : le flux est lu sans à-coups et ne tourne plus en boucle sur les mêmes secondes ; un flux qui se coupe sans arrêt laisse la place au logo

## 0.2.127
- Chaîne ouverte depuis son aperçu (OK sur la chaîne dont l’aperçu joue) : le plein écran continue sur le flux de l’aperçu au lieu de le rouvrir, la chaîne démarre tout de suite
- Ne s’applique pas avec « Baisser le son des pubs », le son envoyé tel quel à l’ampli ou un décodeur imposé : la chaîne s’ouvre alors comme avant

## 0.2.126
- Menu de gauche et volet des catégories : le flou n’est plus posé que sous le menu, le reste de l’écran reste net
- Flou sous le menu : plus de traits de couleur autour des badges et des cartes

## 0.2.125
- Interface Classique : la touche ☰ de la télécommande ouvre le menu des catégories (films, séries), et le referme

## 0.2.124
- Fiche d’un film ou d’une série (toutes les interfaces, réglage « Bande-annonce en fond ») : la bande-annonce est maintenant en bas de la fiche ; elle s’y lit en petit, et passe en plein écran avec le son quand on descend jusqu’à elle (« Retour » ramène sur la fiche)
- Interface Streaming : la bande-annonce ne joue plus dans le visuel en haut de la fiche (elle est en bas)

## 0.2.123
- Interfaces Classique et Live Center : la bande-annonce démarre aussi dans le fond de la fiche d’un film ou d’une série (réglage « Bande-annonce en fond »), avec le bouton du son

## 0.2.122
- Fire TV : l’application ne se ferme plus d’un coup sur l’écran Films quand la mémoire manque (la bande-annonce du bandeau s’arrête et l’image revient)
- Mémoire presque pleine : les bandes-annonces automatiques font une pause de 10 minutes (le bouton « Bande-annonce » marche toujours)

## 0.2.121
- King : nouveaux serveurs intégrés ; une playlist King déjà enregistrée les reprend en secours au démarrage

## 0.2.120
- Films sans badge de qualité : l’application demande la résolution au serveur (fiche du film) et le badge apparaît sur l’affiche et la fiche
- Films et épisodes lus : leur vraie résolution donne le badge quand le nom n’en indique pas ; gardé après chaque actualisation de la playlist

## 0.2.119
- Interface Streaming : la touche ☰ ouvre aussi le menu de gauche depuis la liste des chaînes (elle ne faisait que le fermer)

## 0.2.118
- Configuration rapide : OK sur une interface lance une vraie démonstration (chaînes, films et séries de démo, à parcourir à la télécommande), puis « Garder celle-ci » ou « Revenir au choix »
- Configuration rapide : l’étape « C’est prêt » s’appelle maintenant « Prêt »
- Foot, « Mes équipes » : bouton « Équipes suivies » pour voir les équipes ajoutées (et ne plus en suivre une)
- Volet des catégories et menu de gauche de Streaming : l’écran derrière est légèrement flouté
- Guide TV : ↓ et ↑ depuis un programme en cours vont au programme en cours de la chaîne voisine (et non plus à celui d’avant)

## 0.2.117
- Chaînes 4K masquées : le lecteur, les favoris et les chaînes récentes montrent le logo et le nom de la version regardée (M6 et non M6 4K)
- Interface Streaming : la touche ☰ de la télécommande ouvre le menu de gauche (et le referme)

## 0.2.116
- Nouvel onglet « King » pour ajouter une playlist : seulement l'identifiant et le mot de passe, les serveurs (principal et secours) sont déjà dans l'application
- Configuration depuis le téléphone : onglet « King » proposé d'office, la télé s'ouvre sur le compte King prérempli
- Ajout d'une playlist sur téléphone : les choix passent sur deux lignes, les mots ne sont plus coupés

## 0.2.115
- Guide TV : plus de chaînes françaises reliées à leur programme (BFM Marseille, Nice, Toulon et DICI Alpes, Ciné+ Family, Festival, Classic et OCS, National Geo Wild, BBC Earth, CGTN, TVPI, Télé Melody, My Zen, RMC Sport Live 4, Guyane Première, chaînes en « FHD+ »)
- Guide TV : une chaîne d'un autre pays (|DE|, |ES|, |AR|…) n'affiche plus le programme de la chaîne française du même nom (DAZN, beIN Sports, Eurosport…)

## 0.2.114
- Recherche globale : les films déjà vus portent la pastille « Déjà vu », comme dans les rangées

## 0.2.113
- Playlist et serveurs de secours : un port écrit à la fin de l'adresse (« :80 ») est conservé et réapparaît dans les réglages

## 0.2.112
- Serveurs de secours (modification de la playlist) : un champ par serveur, avec « Ajouter un serveur » et « Retirer ». Ajouter un serveur ne fait plus retaper les autres (le clavier de la Fire TV effaçait tout le champ)
- « Masquer les chaînes 4K » activé : la fiche d'un film propose d'abord sa version HD ou Full HD (« Regarder » la lit), la 4K reste au choix avec « Version »
- Titres coupés sous les affiches (rangées, recherche) : le titre de l'affiche choisie défile en entier
- Films écrits « (True FR) » (« À la dérive (True FR) » et « À la dérive (FR) ») : une seule affiche, comme pour « Truefrench ». La playlist est relue une fois au premier lancement après la mise à jour, et les deux favoris d'un même film n'en font plus qu'un

## 0.2.111
- « Chargement impossible — connexion coupée » : quand le serveur coupe le téléchargement de la playlist en route, l'application réessaie toute seule deux fois (2 puis 5 secondes plus tard) avant d'afficher l'erreur

## 0.2.110
- Film qui s'arrête et reprend toutes les quelques secondes : la playlist et le guide ne sont plus relus en arrière-plan pendant qu'un film ou une chaîne se lit (ils lui prenaient du débit et de la puissance, surtout juste après une mise à jour). La relecture attend la fermeture du lecteur ; lancée avant, elle s'interrompt et reprend après
- Films et épisodes : davantage de film chargé d'avance (jusqu'à deux fois plus, selon la mémoire de l'appareil), pour passer les ralentissements du serveur sans s'arrêter
- Après une coupure, le film attend une dizaine de secondes d'avance avant de reprendre : sur une connexion trop lente, quelques pauses franches au lieu d'une image qui s'arrête toutes les cinq secondes. Le démarrage et les déplacements restent aussi rapides
- Lecteur VLC : même chose pour les films (6 secondes d'avance au lieu de 2)

## 0.2.109
- France 3 régionales : davantage d'éditions reçoivent leur programme. Les écritures abrégées ou fautives de la playlist sont reconnues : « BNORMANDIE », « NORD PDC », « CHAMP ARDENNE », « POITOUCHAR », « POTOU-CHARENTES », « PROV ALPES », « LANGUE-DOC », « RHONES-ALPES »…
- Une édition régionale absente du guide affiche le programme national de France 3 (le même en dehors des journaux régionaux), au lieu de « Pas d'information »
- La même région écrite de deux façons (« PARIS IDF » en HD et « PARIS ILE DE FRANCE » en FHD) ne fait plus qu'une vignette, avec ses qualités ; de même pour « LE FIGARO TV IDF » et « LE FIGARO IDF », qui reçoit aussi son programme
- « France 3 Haute-Normandie » retrouve son nom (elle s'affichait « France 3 -Normandie »)
- Réglages › Région de France 3 : chaque région n'apparaît qu'une fois ; la région déjà choisie est gardée
- Les favoris, « L'essentiel » du guide et les chaînes récentes suivent les chaînes regroupées
- La playlist et le guide sont relus une fois au premier lancement après la mise à jour

## 0.2.108
- Films : un film que la playlist propose en plusieurs versions (« À la dérive (Truefrench) » et « À la dérive (FR) », la même en VOSTFR, en 4K…) n'apparaît plus qu'une fois dans les listes et la recherche, sous son vrai titre
- Sur la fiche du film, un bouton « Version » fait passer de l'une à l'autre (OK pour changer) ; « Lecture » et « Reprendre » lisent celle qui est affichée. La version française est proposée d'abord
- Les favoris, les reprises de lecture et les « Déjà vu » enregistrés sous l'ancien titre suivent le film : les deux favoris d'un même film n'en font plus qu'un
- La playlist est relue une fois au premier lancement après la mise à jour, pour regrouper ces films
- Affiches : l'étiquette de qualité (4K, FHD, HD) est plus petite et cache moins l'image
- Distribution : les mentions « (voice) », « (uncredited) », « Self »… sont en français (« (voix) », « (non crédité) », « Lui-même »)

## 0.2.107
- « Baisser le son des pubs » refait. Les pubs ne sont pas plus fortes que le programme (l'ancienne méthode, qui guettait un son plus fort, ne pouvait pas les voir) : elles sont maintenant repérées au court silence qui sépare deux pubs
- Le son est remis dès que le logo de la chaîne revient à l'image, à la reprise du programme. Sur une chaîne sans logo reconnu, il revient une trentaine de secondes après la dernière pub
- Le son n'est plus baissé quand un film ou un match démarre plus fort que ce qui précédait
- Réglages › Lecture : l'explication de l'option est mise à jour

## 0.2.106
- Guide TV : davantage de chaînes reçoivent leur programme. Les chaînes que la playlist nomme autrement que le guide sont maintenant reconnues : « TV5 NEWS » (TV5 Monde Info), « L'EQUIPE », « FRANCE INFO TV », « EUROPE 1 », « NAT GEO », « PLANETE+ CI », « SCIENCE & VIE TV », « CANAL+ CINEMA(S) », « LCP ASSEMBLEE NATIONALE », les chaînes « 24/24 » (Turbo, Zone Interdite…) et d'autres
- Le guide est relu une fois au premier lancement après la mise à jour, pour relier ces chaînes à leur programme

## 0.2.105
- Films et séries en mosaïque : le titre sous l’affiche s’affiche sur trois lignes au lieu d’une, il n’est presque plus coupé

## 0.2.104
- Guide TV : plus de léger tremblement en descendant d'une chaîne à l'autre (l'en-tête du programme garde toujours la même hauteur)
- « Ce soir » : sans numéros de chaîne (favoris hors TNT, par exemple), la colonne vide disparaît ; les chaînes et leurs programmes prennent la place
- Mosaïque des chaînes en Live Center : l'aperçu passe en bandeau en haut, comme en Classique ; les vignettes prennent toute la largeur et les noms se lisent mieux
- Mosaïque des chaînes (toutes les interfaces) : l'aperçu en haut est moins haut, on voit deux rangées de chaînes entières
- Streaming : le titre « Catégories » du volet est retiré, la liste commence tout en haut
- Réglages › Région de France 3 : « Languedoc » bien écrit, même quand la playlist écrit « Langue-doc »

## 0.2.103
- Mises à jour deux fois plus légères (environ 27 Mo au lieu de 51 Mo) : l’appli télécharge la version faite pour le processeur de l’appareil. Cette mise à jour-ci se télécharge encore en entier, les suivantes seront plus légères
- Bande-annonce qui ne se lit pas (retirée, réservée à un pays ou aux adultes, ou qui ne démarre pas) : l’appli essaie toute seule les autres bandes-annonces du titre, et n’ouvre l’appli YouTube qu’en dernier recours
- Fiche Classique : la bande-annonce se lit dans l’appli, comme dans la fiche Streaming
- Fiche Classique : en descendant sur la distribution, les noms et les rôles sont en entier à l’écran
- Fiche Classique : en remontant tout en haut, le bouton Retour n’est plus coupé

## 0.2.102
- Réglages › Apparence refait : schémas des trois interfaces à jour (Streaming sans boutons, avec son Top 10 ; Classique avec son menu en grandes tuiles) et descriptions plus courtes
- Apparence : « Chaînes et affiches » regroupe liste ou mosaïque et vignettes par ligne, avec une phrase qui dit où ça s’applique et un petit aperçu qui change en direct
- Apparence : « Par défaut » et « Revenir à… » sont sur la ligne du titre « Couleurs » ; toutes les couleurs restent sur une seule ligne
- La couleur « Classique (bleu) » s’appelle maintenant « Bleu », et sa famille « Essentielles » (pour ne pas confondre avec l’interface Classique)

## 0.2.101
- L’interface « Cinéma Premium » s’appelle maintenant « Classique »
- Classique : « Favoris » du menu d’accueil ouvre une fenêtre avec trois onglets, Films, Séries et Chaînes TV, comme « Reprendre la lecture ». OK ouvre la fiche d’un film ou d’une série ; sur une chaîne, elle se lance et haut/bas passe aux autres chaînes favorites. Appui long : retirer des favoris
- Recherche globale : au retour d’une fiche, la sélection revient sur l’élément ouvert (et non sur « Retour »)
- « Reprendre la lecture » (Classique) : en remontant depuis une affiche, la sélection revient sur l’onglet en cours, sans changer de liste

## 0.2.100
- Fiche d’un film ou d’une série (Cinéma Premium) : en passant d’un bouton à l’autre, l’écran ne descend plus ; il ne défile que si l’élément choisi sort de l’écran
- Fiche Cinéma Premium : les boutons tiennent sur une ligne (le bouton Favoris devient une étoile ronde, comme dans la fiche Streaming)
- « Parce que vous avez regardé » : la rangée montre maintenant les titres que TMDB recommande pour le dernier film ou la dernière série regardés, quand la playlist les a (avant, c’étaient simplement d’autres titres de la même catégorie). Sans recommandation trouvée, la rangée n’apparaît pas
- Top 10 de Cinéma Premium : la qualité passe en bas à droite de l’affiche, un peu transparente

## 0.2.99
- Aperçu des chaînes : quand le son joue sans image (catégorie ouverte dans l’interface Streaming, l’image pouvait mettre 20 s à venir), l’aperçu repart au direct au bout de 2 s et l’image arrive tout de suite

## 0.2.98
- Favoris, « Réorganiser » : la dernière chaîne se déplace de nouveau, et une chaîne ne peut plus être montée au-delà de la première place. Les favoris qui ne s’affichent plus (chaîne retirée de la playlist ou regroupée avec une autre version) ne comptent plus dans le déplacement

## 0.2.97
- Appui long (par exemple pour retirer un favori) : en relâchant OK, l’élément suivant ne s’ouvre plus. Vaut pour toute l’appli
- Accueil Streaming : plus de boutons « Regarder », « Bande-annonce » et « Plus » sur le bandeau ; la sélection démarre sur la première affiche (OK ouvre sa fiche)
- Accueil Streaming : la première affiche du Top 10 n’est plus coupée en haut quand elle est sélectionnée
- Fiche d’un film ou d’une série : en passant d’un bouton à l’autre, l’écran ne monte et ne redescend plus, et le titre n’est plus coupé en haut
- Recherche : le texte tapé cherche dans tous les films, toutes les séries ou toutes les chaînes, quelle que soit la catégorie ouverte ; choisir une catégorie efface la recherche
- Bande-annonce que YouTube refuse de lire dans l’appli : elle s’ouvre dans l’application YouTube (ou un message le dit), au lieu de se refermer aussitôt

## 0.2.96
- Chaîne lancée depuis les favoris (accueil, guide TV ou menu des chaînes) : « Retour » ouvre le menu sur les favoris, et haut/bas passe à la favorite suivante ou précédente, dans l’ordre choisi
- Versions de secours : « RMC SPORT 1 BKP » ou « BEIN SPORTS 2 FHD++ (Très haute résolution) » sont maintenant des versions de la même chaîne. Si la chaîne ne marche pas, l’appli essaie d’abord son secours de même qualité, avant de passer en HD
- FIFA+ : les versions FHD et HD, qui ne diffusent pas la même chose, restent deux chaînes séparées
- Options du lecteur : avec le moteur VLC, le bouton devient « Lire avec le lecteur de l’appli » pour revenir au lecteur de base
- La playlist est relue une fois au premier lancement après la mise à jour, pour regrouper ces versions

## 0.2.95
- Films et séries : la qualité annoncée par la playlist (4K, FHD, HD, SD) s’affiche sur la vignette, en haut à gauche, et sur la fiche à côté de l’année. Elle est lue dans le nom du film (« Avatar 4K »), sinon dans celui de sa catégorie (« FILMS 4K ») ; rien n’est affiché si la playlist ne l’indique pas
- La playlist est relue une fois au premier lancement après la mise à jour, pour y lire ces qualités

## 0.2.94
- Place libérée sur l’appareil : le fichier d’une mise à jour déjà installée est supprimé au lancement, et les fiches de films et séries qui ne sont plus consultées depuis trois mois sont retirées du cache
- Journaux : la clé football-data.org y est masquée comme les autres identifiants
- Publication des versions : les tests des playlists, du guide et de la sauvegarde sont lancés avant chaque version, et le numéro interne des versions ne dépend plus d’un compteur qui pouvait repartir de zéro

## 0.2.93
- Guide TV plus léger et plus fluide : seules les cases proches de l’heure affichée sont dessinées (les autres le sont en s’en approchant), et les résumés ne sont lus que pour le programme sélectionné. Même affichage, même navigation ; beaucoup moins de mémoire utilisée sur Fire TV
- Guide TV : la grille se met à jour dès qu’un nouveau guide est importé (elle pouvait garder l’ancien jusqu’à la demi-heure suivante)
- Playlist reçue incomplète (connexion coupée en route) : si elle perd d’un coup plus de la moitié de ses éléments, elle n’est pas enregistrée et la précédente est gardée ; un message le dit. Si la liste est vraiment devenue plus courte, elle est acceptée à l’actualisation suivante
- Listes de chaînes : elles ne sont plus relues toutes les trois heures quand le guide est déjà à jour
- Après une mise à jour de l’appli qui change le regroupement des chaînes ou l’import du guide : la relecture est retentée au lancement suivant si elle a échoué (hors ligne)

## 0.2.92
- Moteur VLC : l’appli ne se fige plus (« ne répond plus ») en quittant le lecteur ou en changeant de titre pendant une lecture par VLC
- Moteur VLC : un flux qu’il n’arrive pas à ouvrir affiche « Lecture impossible, même avec le moteur VLC » au lieu de fermer le lecteur comme à la fin d’un film

## 0.2.91
- « Reprendre » un film ou une série après un changement d’adresse des flux (autre serveur, autre mot de passe) : le film ou l’épisode est retrouvé par son titre, au lieu d’ouvrir « chaîne introuvable ». Les reprises sont rattachées au lancement et après chaque mise à jour de la playlist
- « Reprendre la lecture » : un titre retiré de la playlist par le fournisseur n’y apparaît plus (il revient si le titre revient)
- Sauvegarde : elle contient aussi les reprises de lecture et les « Déjà vu », retrouvés sur un autre appareil une fois la playlist chargée
- Restauration d’une sauvegarde : un fichier abîmé ou modifié ne peut plus bloquer le lancement de l’appli (les réglages d’un mauvais type sont ignorés), et les favoris d’une playlist sont restaurés d’un seul tenant

## 0.2.90
- Zapping : la mémoire du retour en arrière (←) est réutilisée d’une chaîne à l’autre au lieu d’être réservée de nouveau à chaque chaîne (32 Mo à chaque fois) ; moitié moins sur les petits appareils. Moins de risque de fermeture par manque de mémoire sur Fire TV
- Aperçu de l’accueil : l’image d’une chaîne survolée arrive plus vite (le même lecteur sert d’une chaîne à l’autre)
- Son : le lecteur tient compte des autres applis (il s’interrompt pendant une annonce vocale ou un appel, et se met en pause si le casque est débranché)
- Film ou replay en pause : l’économiseur d’écran peut se lancer (pause oubliée). L’écran reste allumé pendant la lecture et pendant la pause d’un direct
- Différé resté sur l’appareil après une fermeture de l’appli pendant une pause du direct : supprimé au lancement suivant
- Bandeau du lecteur : après un zapping rapide, le programme affiché est bien celui de la nouvelle chaîne

## 0.2.89
- Correction urgente : l’appli se fermait à l’ouverture d’un film ou d’une chaîne (erreur introduite dans la 0.2.87). Le lecteur s’ouvre de nouveau normalement

## 0.2.88
- « Dernier incident » (Réglages) : la vraie cause d’un plantage n’est plus coupée (elle se trouvait en bas de la trace, au-delà des 40 lignes gardées)
- « Dernier incident » : les fermetures par le système sont notées aussi, avec leur raison (mémoire insuffisante, appli qui ne répond plus, bibliothèque native), l’écran affiché à ce moment-là (guide, lecteur…) et la mémoire utilisée. Sur Android 11 et plus, la raison vient du système ; sur les appareils plus anciens, l’appli constate seulement qu’elle a été fermée pendant qu’on s’en servait (appareil éteint et mise à jour exclus)
- Bandeau Streaming : le titre et le résumé gardent leur texte pendant le fondu vers le titre suivant

## 0.2.87
- Stockage de l’appareil plein : l’appli ne se ferme plus pendant une actualisation de la playlist ou du guide (même automatique, pendant un film). Un message le dit, et les chaînes et le guide précédents sont gardés
- Pause et retour en arrière (←) du direct : plus de blocage de l’image au moment d’appuyer, et plus de fermeture de l’appli quand le stockage est plein (la pause reste alors une simple pause)
- Direct interrompu (coupure du réseau, flux fermé par le serveur) : la chaîne est relancée toute seule, trois fois au plus, au lieu de rester figée sans message ou d’afficher tout de suite une erreur
- Retour de veille, ou d’une autre appli, pendant un direct : la chaîne repart au direct (elle rejouait d’anciennes images puis tombait en erreur). La connexion au fournisseur est libérée pendant la veille ; un direct laissé en pause reprend au direct
- Mise à jour de l’appli : playlists, favoris et identifiants ne peuvent plus être effacés en silence lors d’un changement de la base de données
- Fichier de réglages abîmé (coupure de courant) : l’appli se lance quand même, avec les réglages par défaut

## 0.2.86
- Son (ou image) que le lecteur ne sait pas lire : le moteur de VLC prend le relais tout seul, au même endroit du film, dans le même écran et avec les mêmes commandes (pause, avance, reprise, épisode suivant). « Son et image lus par le moteur VLC » s’affiche quelques secondes
- Options du lecteur : « Lire avec le moteur VLC » pour le choisir soi-même
- L’appli est plus lourde (environ 50 Mo) : elle contient le moteur de VLC

## 0.2.85
- Films avec l’image mais sans le son : le son MP2 (celui des enregistrements de la TNT), que l’appareil et le décodeur intégré ne savaient pas lire, est maintenant décodé par l’appli. Après une erreur de son, le lecteur réessaie avec le décodage de l’appli avant de couper le son
- Son illisible : le message et le menu des pistes disent le format (par exemple « MP2 », « Dolby Digital (AC3) »)
- Rangées Streaming et Cinéma Premium : les affiches sont celles des fiches (TMDB) au lieu des images floues ou recadrées de la playlist
- Fiches des séries : « Lire S4 E1 » pour la saison choisie, et la bande-annonce est celle de cette saison quand TMDB en a une
- Fiches Streaming : boutons sur une seule ligne ; favoris en étoile (pleine et dorée si le titre est en favori), le texte apparaît quand elle est sélectionnée

## 0.2.84
- Streaming : la rangée choisie est de nouveau bien placée (le titre sous les affiches la rendait plus haute) : le nom de la catégorie du dessous se voit, et plus de reste de texte de la rangée du dessus. Titre des affiches sur une ligne ; « Reprendre la lecture » : l’épisode et le temps restant passent sur l’image
- Fiches Streaming : tout le haut (titre, note) est visible à l’ouverture et en remontant sur les boutons
- Bandeau Streaming : en remontant depuis une affiche (par exemple le n°4 du Top 10), le bandeau reste sur elle au lieu de revenir au n°1
- Bandeau Streaming : les petits points sous les boutons sont supprimés
- Bandes-annonces automatiques plus grandes : la vidéo remplit le cadre (bandes noires du format cinéma rognées)

## 0.2.83
- Bande-annonce automatique : elle démarre au tout début (0 s). Elle se prépare en pause sur sa première image pendant l’attente, puis repart de 0 en apparaissant (le titre YouTube peut se voir quelques secondes)

## 0.2.82
- Films qui ne se lançaient pas (« Codec non pris en charge ») : le lecteur réessaie tout seul (autre piste son, sans sous-titres, autre décodeur, autre version du même film dans la playlist) ; sinon le message dit exactement ce que l’appareil ne sait pas lire (par exemple « Vidéo HEVC 10 bits HDR »)
- Recherche et Recherche Globale sans accents : « a la derive » trouve « À la dérive »
- Streaming : le titre est écrit sous chaque affiche des rangées
- Streaming : « Top 10 de la semaine » tout en haut, puis « Reprendre la lecture » et les favoris
- Top 10 et titres similaires : vraies affiches (TMDB) au lieu des images floues ou recadrées de la playlist
- Bandeau Streaming : moins de flou autour de l’aperçu
- Bande-annonce automatique : elle apparaît à 1 s de son début au lieu de 3,5 s (le début n’est presque plus sauté)
- Fiches : on remonte jusqu’en haut après être descendu
- Fiches Cinéma Premium et Live Center : la distribution (photos, noms, rôles)
- « Titres similaires » : vraies recommandations TMDB trouvées dans la playlist (la rangée disparaît s’il n’y en a pas), au lieu des titres de la même catégorie
- Séries : le nombre de saisons est celui de la playlist (Lupin : 4 saisons, et non 3)
- « Depuis le début » (différé) : ← → reculent ou avancent de 10 s au lieu d’une minute (replay : 10 s au lieu de 30 s)

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
