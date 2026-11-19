---
name: verify
description: Prouver qu'une modification de Veripuce marche avant de la rendre — tests JVM (dont propriétés MRZ/ICAO 9303), lint, APK, garde-fous de confidentialité, et lancement sur appareil/émulateur s'il y en a un. À utiliser avant tout « c'est fini », avant de fermer une tâche code, ou pour exercer l'app.
---

# verify — Veripuce

## 1. Preuve automatique (toujours)

```bash
bash .claude/skills/verify/verify.sh --rerun
```

≈ 10 s à chaud, 1 à 3 min dans un worktree neuf (build à froid). Ne démarre aucun processus
persistant. Exécute `testDebugUnitTest` (forcé par `--rerun`), `lintDebug`, `assembleDebug`, puis
lit les rapports JUnit et le manifeste **fusionné** ; construit enfin `assembleRelease` et prouve
que le journal des lectures (debug uniquement) n'y laisse ni classe, ni chaîne, ni ressource, ni
FileProvider. Sortie attendue (à coller telle quelle) :

```
VERIFY veripuce @ <sha>
  ✓ gradle testDebugUnitTest --rerun lintDebug assembleDebug (9 s)
  ✓ tests JVM : 111 exécutés, 0 échecs, 0 erreurs, 0 ignorés
  ✓ dont 12 propriétés MRZ / ICAO 9303 / BER-TLV (MrzPropertyTest)
  ✓ APK app/build/outputs/apk/debug/veripuce-debug.apk (30 Mo)
  ✓ aucune permission INTERNET (manifeste fusionné)
  ✓ pas de sauvegarde système (allowBackup)
  ✓ gradle assembleRelease (40 s)
  ✓ contrôle positif : journal présent dans l'APK debug
  ✓ release : aucune classe du journal (dex)
  ✓ release : aucun chemin de fichier du journal (dex)
  ✓ release : aucune ressource du journal (resources.arsc)
  ✓ release : pas de FileProvider (manifeste fusionné)
  ✓ release : aucune permission INTERNET
VERIFY: VERT
```

Le nombre de tests ne doit que **monter**. Un « ignorés » non nul est rouge : on ne `@Ignore` pas.

**Fiabilité** : lancer 3 fois d'affilée avec `--rerun` ; les 3 runs doivent être identiques. Les
tests de propriétés ont une graine fixe : un échec se reproduit à l'identique (graine affichée).

## 2. La « fausse puce » (pas de matériel NFC)

Il n'y a pas d'émulateur NFC pour ISO-DEP/PACE. Ce qui remplace la carte, côté JVM :

| Couche | Test | Données |
|---|---|---|
| OCR → MRZ (`MrzOcr`) | `MrzOcrTest`, `MrzPropertyTest` | spécimens ICAO « Utopia », PRADO, MRZ générées |
| Clés PACE (`MrzKeyCandidates`, `MrzVote`) | `MrzKeyCandidatesTest`, `MrzVoteTest`, propriétés | numéros générés |
| LDS / TLV (`BerTlv`, `Dg13Parser`) | `MrzPropertyTest` (aller-retour, octets aléatoires) | octets générés |
| Passive auth (`PassiveAuth`) | `BsiReferenceDataTest`, `LegacyReferenceDataTest`, `PassiveAuthTest` | jeux publics BSI TR-03105, `bsi2008`, `loes2006` |
| Magasin CSCA | `CscaBundleTest` | `assets/csca` |
| Rapport caviardé | `DebugReportTest` | données fictives |

Toute nouvelle logique de lecture se prouve en l'**extrayant en Kotlin pur** et en la testant ici
avec des octets de référence publics ou générés — jamais avec un dump de vraie carte.

## 3. Lancer et exercer l'app (si un appareil est là)

Ne démarre **pas** l'émulateur toi-même (processus sans fin) : demande à l'utilisateur
(`emulator -avd Pixel_9`, AVD API 36 x86_64 avec traduction ARM — l'APK n'embarque que
arm64/armv7) ou utilise un téléphone branché. Puis :

```bash
bash .claude/skills/verify/verify.sh --rerun --device
```

Ajoute : installation, `am start` de `MainActivity`, capture d'écran (chemin affiché), présence du
NFC, absence de crash (`logcat -b crash`). Regarder la capture (outil Read sur le PNG).

### Feature map — étape → résultat attendu

| # | Étape | Résultat attendu | Où |
|---|---|---|---|
| 1 | Lancer l'app | Écran principal « Veripuce », bouton scan MRZ, « Saisie manuelle » | émulateur / téléphone |
| 2 | Appareil sans NFC (émulateur) | Message « NFC indisponible sur cet appareil. », pas de crash | émulateur |
| 3 | NFC coupé | « NFC désactivé. Touche ici… » → ouvre les réglages NFC | téléphone |
| 4 | Scanner une MRZ de **spécimen** (image ICAO/PRADO à l'écran d'un autre appareil) | Carte « Document détecté », type correct (Carte / Passeport / Séjour), invite NFC | caméra (émulateur : *virtualscene*, peu fiable) |
| 5 | Saisie manuelle : onglets Carte / Passeport / Séjour | Carte → champ CAN ; Passeport/Séjour → n° + dates | émulateur |
| 6 | Approcher la puce | Progression des étapes, puis 4 contrôles (Intégrité, Émis par l'État, Cohérence, Anti-clone) | **téléphone + vrai document uniquement** |
| 7 | Clé MRZ refusée par une carte | Repli : champ CAN affiché | téléphone |
| 8 | Appui long sur le titre | « Mode diagnostic activé » ; après lecture, carte « Détails techniques » caviardée | émulateur (activation) / téléphone (rapport) |

Les étapes 6 et 7 exigent un document physique : si tu ne les as pas faites, **dis-le**
explicitement (« lecture NFC non testée sur appareil »). Une capture d'écran d'une vraie pièce ne
sort jamais de l'appareil : pas de PNG d'écran de résultat d'une vraie personne dans un commit,
un ticket ou un compte rendu.

## 4. Ce qu'il faut rendre

- Le bloc `VERIFY … VERT` (sortie brute de `verify.sh --rerun`), plus `--device` et la capture si
  un appareil était disponible.
- Dans chaque commit : trailer `Verified: verify.sh --rerun (111 tests, lint, APK)` ou
  `Verified: none — <raison>`.
- Rouge 3 fois sur le même problème : arrêter, rendre la tâche en échec avec le diagnostic. Jamais
  de `sleep`, de relance automatique ni de `@Ignore` pour faire passer un test.
