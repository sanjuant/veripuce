# Documentation Veripuce

Documentation technique détaillée. Le [README principal](../README.md) donne la vue d'ensemble ;
ces fichiers approfondissent chaque sujet.

**Pour reprendre le projet, lis dans l'ordre :** le glossaire, puis l'architecture, puis le sujet qui t'intéresse.

| Document | Contenu |
|---|---|
| [**Glossaire**](glossaire.md) | Tous les sigles du domaine (MRZ, TD1/TD3, CAN, DG, SOD, PACE/BAC/GM/IM/CAM, CSCA, DSC, EAC…) expliqués en français simple, avec analogies. **À lire en premier.** |
| [**Architecture**](architecture.md) | Carte des fichiers, le « voyage d'une lecture » (diagrammes + pseudocode), les machines à états des écrans, les threads, les principes de conception. |
| [**Algorithmes**](algorithmes.md) | Pipeline OCR de la MRZ, parsing TD1/TD3, chiffres de contrôle ICAO, paires aveugles, vote par position, session PACE/BAC, *passive authentication* (intégrité + signature + chaîne CSCA), détection de puce clonée. |
| [**Calcul des clés d'accès**](cles-acces.md) | Comment la clé d'ouverture de la puce est dérivée (MRZ / CAN), la dérivation ICAO 9303-11, les candidats de clé sur paires aveugles, l'itération multi-protocoles PACE. |
| [**Sources & données**](sources.md) | Les certificats CSCA embarqués (ICAO + BSI + ANTS), le format auditable, le recoupement inter-canaux, le rafraîchissement automatique, les jeux de test cryptographiques. |
| [**Sécurité & confidentialité**](securite-confidentialite.md) | Modèle 100 % on-device, floutage des données lues, rapport de diagnostic caviardé, ancre de confiance CSCA, limites EAC. |
| [**Dépannage**](depannage.md) | Lire le rapport de diagnostic caviardé, arbre de décision des échecs de lecture (gel GM/IM, OCR défaillant, repli CAN, reflet), pannes classiques. |

> Les références de code sont au format `Fichier.kt:ligne` — elles pointent la source faisant foi.
