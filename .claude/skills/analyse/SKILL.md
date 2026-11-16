---
name: analyse
description: Analyse des enregistrements « pubs » envoyés par l'appli sur filebin (box, Fire TV) — relevé des pages de pub à l'œil, notation de la détection, causes des erreurs, corrections. À lancer quand l'utilisateur écrit /analyse (avec ou sans adresses de pages filebin).
---

# /analyse — enregistrements d'analyse des pubs

L'utilisateur lance l'enregistrement d'analyse (ou l'enregistrement automatique) sur ses appareils ; chaque fichier
terminé part tout seul sur la page filebin de l'appareil (Réglages › Son › Enregistrements, avec QR code). Cette
commande va les chercher, relève la vérité à l'œil, note la détection et en tire des corrections.

Arguments : adresses de pages filebin (`filebin.net/nixtv-…`), une par appareil. Sans argument, demander les adresses
(elles ne sont **jamais** écrites dans le dépôt, public : ni code, ni commit, ni CLAUDE.md). Les pages expirent au bout
d'une semaine.

## Étapes

1. **Télécharger** (dossier de travail dans le scratchpad, jamais dans le dépôt) :
   `python3 -I tools/ad_fetch.py <page> <scratchpad>/pubs/<appareil>` pour chaque page. Lire `info.txt` (appli,
   appareil) : un fichier sans `events.tsv` rempli (appli fermée d'un coup avant 0.2.170) n'a pas les décisions de
   l'appareil, seulement la notation « ici ». Format 2 (avant 0.2.161, `audio.ulaw`) : relevé plus long, à faire en dernier.
2. **Planches** : `python3 -I tools/ad_sheets.py <enregistrement> <dossier planches> 20` (une vignette toutes les 20 s,
   bordure rouge = son baissé par l'appareil, lettre = logo vu V/H/U). Repérer les changements de chaîne :
   `awk -F'\t' '$2=="chaine"||$2=="flux"' <enregistrement>/events.tsv` (horloge en ms).
3. **Vérité** : regarder chaque planche (Read), écrire `<enregistrement>.truth.tsv` : une page de pub par ligne,
   « début fin » en secondes **de l'horloge de l'enregistrement** (celle des vignettes, pas forcément 0 au début).
   Compte comme pub : spots, bandes-annonces de la chaîne et parrainages entre deux programmes. Programme : météo,
   journal, génériques, résumés. Une page coupée par un changement de chaîne : deux lignes. Copier les vérités dans
   `tools/ad_truth/` (pas de données privées : nom de chaîne et heures).
4. **Noter** (cleanTest, sinon Gradle saute le test) :
   ```
   AD_DEVICE="<enr1>:<enr2>:…" AD_KNOWN=1 AD_TAG=<étiquette> ./gradlew -p core cleanTest test --offline -q --tests '*AdDeviceReplayTool*' -i | grep -E "pubs_.* : |TOTAL"
   ```
   « appareil » = ce que l'appareil a fait, « ici » = le code actuel rejoué. Long (≈ 1 min par heure) : lancer en
   arrière-plan au-delà de 5 fichiers. `AD_PRINTS=1` ajoute les pubs déjà entendues (rejouées dans l'ordre donné).
5. **Erreurs** : `python3 -I tools/ad_errors.py <dossier> <étiquette>` liste pubs ratées et programme baissé à tort, avec
   la raison donnée par l'appareil (règles dans `core/audio/AdVolumeGuard.kt`, `Opening`). Regarder les vignettes de
   chaque erreur pour comprendre (logo qui change, météo, bandes-annonces…).
6. **Corriger** une règle seulement si elle améliore l'ensemble : rejouer **avant / après** sur ces fichiers et sur tous
   ceux encore disponibles (vérités dans `tools/ad_truth/`), regarder pubs baissées, programme à tort et délai. Ajouter un
   test dans `AdVolumeGuardTest`. Publier selon CLAUDE.md (CHANGELOG, `release/vX.Y.Z`).
7. **Chaînes testées** : ajouter les minutes analysées par chaîne à `AdTestedChannels.ANALYZED` (clé `tnt<numéro>`),
   pour que l'enregistrement automatique aille vers les chaînes les moins connues.
8. **Répondre** en français simple : par appareil et par chaîne, part des pubs baissées, programme baissé à tort, délai,
   les erreurs typiques (avec l'heure), ce qui a été corrigé et publié, ce qui reste à voir sur la télé.
