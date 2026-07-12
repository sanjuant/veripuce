# Architecture

Comment le code est organisé, et le **voyage complet d'une lecture** de la caméra jusqu'au
verdict. À lire après le [glossaire](glossaire.md) pour reprendre le projet.

- [1. Vue d'ensemble](#1-vue-densemble)
- [2. Carte des fichiers](#2-carte-des-fichiers)
- [3. Le voyage d'une lecture](#3-le-voyage-dune-lecture)
- [4. Les machines à états de l'écran](#4-les-machines-à-états-de-lécran)
- [5. Fils conducteurs (threads)](#5-fils-conducteurs-threads)
- [6. Principes de conception](#6-principes-de-conception)

---

## 1. Vue d'ensemble

L'application n'a que **deux écrans** :

```
┌─────────────────────┐   scan MRZ (résultat)   ┌─────────────────────┐
│    ScanActivity     │ ──────────────────────► │    MainActivity     │
│  (caméra + OCR)      │                         │ (NFC + résultat)    │
└─────────────────────┘ ◄────────────────────── └─────────────────────┘
                          lance le scan (Intent)
```

- **`ScanActivity`** : la caméra. Elle lit la MRZ (ou le CAN) et renvoie le résultat.
- **`MainActivity`** : tout le reste. Elle orchestre le flux, déclenche la lecture NFC, et
  affiche le résultat + les vérifications.

Le **cerveau de la lecture NFC** est `CnieReader`, appelé par `MainActivity` en tâche de fond.

```
   Caméra ──► ScanActivity ──► MrzOcr / MrzVote ──► MrzData (n°, dates, type)
                                                        │
                                                        ▼
   Puce NFC ──► MainActivity ──► CnieReader ──► PassiveAuth ──► ReadResult ──► écran
                                     │
                                     └──► DiagnosticsCollector ──► DebugReport (mode diag)
```

---

## 2. Carte des fichiers

Chaque fichier a **une responsabilité**. Regroupés par domaine :

### Écrans (UI)

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Flux principal : scan → détection → lecture NFC → résultat. Pilote toute l'UI. |
| `ScanActivity.kt` | Caméra (CameraX) + OCR (ML Kit) : lit la MRZ / le CAN, recadre sur la zone MRZ. |
| `ScanOverlayView.kt` | Le viseur (fenêtre claire + pièce d'identité fantôme). |

### Lecture optique (OCR)

| Fichier | Rôle |
|---|---|
| `MrzOcr.kt` | Parse le texte OCR en `MrzData` : regex TD1/TD3, chiffres de contrôle ICAO, type de document. |
| `MrzVote.kt` | Vote par position sur plusieurs lectures (converge malgré le bruit OCR). |
| `MrzKeyCandidates.kt` | Paires aveugles (G↔6…) + génération de candidats de numéro. |
| `ScanRoi.kt` | Calcul (pur) du rectangle de recadrage sur la zone MRZ. |

### Lecture NFC (le cœur)

| Fichier | Rôle |
|---|---|
| `CnieReader.kt` | Orchestre : ouverture PACE/BAC, lecture des DG, passive auth, anti-clone. |
| `AccessKey.kt` | La clé d'accès : `Mrz(...)` ou `Can(...)`. |
| `PaceError.kt` | Classe les échecs PACE : refus de clé (SW 6300) vs perte de contact (TagLost). |
| `PassiveAuth.kt` | Les 3 contrôles crypto : intégrité, signature CMS, chaîne CSCA. |
| `CscaStore.kt` | Charge les certificats CSCA embarqués (ancre de confiance). |
| `ReadResult.kt` | Le résultat structuré (identité, photo, verdicts). |

### Parsing bas niveau

| Fichier | Rôle |
|---|---|
| `BerTlv.kt` | Parseur BER-TLV (navigue les octets bruts sans les reconstruire). |
| `Dg13Parser.kt`, `Dg13.kt` | DG13, données propres à la France (adresse, etc.). |

### Diagnostic

| Fichier | Rôle |
|---|---|
| `DiagnosticsCollector.kt` | Collecte (masquée) les tentatives PACE, SW, durées pendant une lecture. |
| `DebugReport.kt` | Assemble le rapport **caviardé** + sérialisation texte stable. |

---

## 3. Le voyage d'une lecture

Suivons une lecture réussie, étape par étape, avec les fichiers concernés.

```
┌────────────────────────────────────────────────────────────────────────┐
│  ÉTAPE 1 — SCANNER LA MRZ                        ScanActivity + MrzOcr    │
├────────────────────────────────────────────────────────────────────────┤
│  chaque trame caméra :                                                   │
│    recadrer sur la zone MRZ (ScanRoi) ──► ML Kit lit le texte            │
│    MrzOcr.findMrz(texte) : regex + chiffres de contrôle + plausibilité   │
│    MrzVote.offer(mrz) : voter sur plusieurs trames                        │
│    dès qu'une lecture est "sûre" ──► renvoyer MrzData à MainActivity     │
└────────────────────────────────────────────────────────────────────────┘
                                      │  MrzData(n°, naissance, expiration, type)
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│  ÉTAPE 2 — DÉTECTER + INVITER À LA PUCE                     MainActivity  │
├────────────────────────────────────────────────────────────────────────┤
│  onMrzScanned() : afficher le type de document + "approche la puce"      │
│  (le CAN n'est demandé qu'en repli, si la clé MRZ échoue)                │
└────────────────────────────────────────────────────────────────────────┘
                                      │  l'utilisateur approche le document
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│  ÉTAPE 3 — LIRE LA PUCE                     MainActivity.onNewIntent      │
│                                            ──► CnieReader.read()          │
├────────────────────────────────────────────────────────────────────────┤
│  a) construire la clé d'accès (buildRequest) : Mrz ou Can                │
│  b) CnieReader.read(isoDep, key, ...) en tâche de fond :                 │
│                                                                          │
│     1. OUVRIR LA SESSION                          openWithMrz / openWithCan│
│        readPaceInfos ──► trier les protocoles (GM d'abord, ou IM)        │
│        pour chaque candidat de numéro :                                   │
│           tryPace : doPACE(clé, protocole)                                │
│              succès ──► session ouverte (Secure Messaging actif)         │
│              refus 6300 ──► candidat suivant                             │
│              TagLost ──► remonter (aléa transitoire)                     │
│                                                                          │
│     2. LIRE LES DATA GROUPS (octets BRUTS)                                │
│        DG1 (MRZ) · DG2 (photo) · DG13/14/15 · EF.SOD                     │
│                                                                          │
│     3. PASSIVE AUTHENTICATION                          PassiveAuth        │
│        (A) intégrité : hash(DG) == hash signé du SOD ?                   │
│        (B) signature CMS du SOD valide ?                                  │
│        (C) DSC ──► CSCA de confiance ? (CscaStore)                       │
│                                                                          │
│     4. COHÉRENCE : MRZ scannée == MRZ de la puce (DG1) ?                 │
│                                                                          │
│     5. ANTI-CLONE : Chip Authentication / Active Authentication          │
│                                                                          │
│     ──► ReadResult (identité, photo, 4 verdicts)                        │
└────────────────────────────────────────────────────────────────────────┘
                                      │  ReadResult
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│  ÉTAPE 4 — AFFICHER LE RÉSULTAT                   MainActivity.showResult │
├────────────────────────────────────────────────────────────────────────┤
│  verdict global + 4 vérifications + identité (floutée par défaut)        │
│  (mode diag) DebugReports.build() ──► rapport caviardé                   │
└────────────────────────────────────────────────────────────────────────┘
```

### En pseudocode (le cœur, `CnieReader.read`)

```
fonction lireLaPuce(isoDep, clé, préférerIM):
    isoDep.timeout = 6s              # handshake : échec rapide si ça gèle
    service = ouvrirSession(isoDep)

    # 1. Ouvrir la session sécurisée (PACE, repli BAC)
    ouvrirAvecClé(service, clé, préférerIM)     # peut lever "clé refusée" ou "TagLost"
    isoDep.timeout = 15s             # lecture des données : marge plus large

    # 2. Lire les fichiers, en gardant les OCTETS BRUTS
    dg1, dg2, dg13, dg14, dg15, sod = lireLesDataGroups(service)

    # 3. Passive authentication (3 contrôles indépendants)
    intègre   = PassiveAuth.vérifierEmpreintes(sod, {dg1, dg2, ...})
    signature = PassiveAuth.vérifierSignature(sod, certificatsCSCA)

    # 4. Cohérence scan optique <-> puce
    cohérent  = (numéroScanné == dg1.numéro et dates == ...)

    # 5. Anti-clone (en dernier : réétablit le canal sécurisé)
    clone     = détecterClone(service, dg14, dg15)

    retourner ReadResult(identité, photo, intègre, signature, cohérent, clone)
```

---

## 4. Les machines à états de l'écran

`MainActivity` est essentiellement une machine à états. Chaque état montre **seulement** ce qui
est utile à cet instant (le reste est masqué).

```
   [Accueil]           scanner la MRZ
      │  onMrzScanned
      ▼
   [Détecté]           document reconnu + "approche la puce"   (+ CAN si repli)
      │  onNewIntent (puce détectée)
      ▼
   [Lecture]           "Puce détectée — ne bouge plus" + barre de progression
      │
      ├─ succès ──► [Résultat]   verdict + 4 vérifications + identité floutée
      │
      └─ échec  ──► retour [Détecté]
                     • refus de clé (carte) ......► propose le CAN
                     • échec MRZ répété (2x) ......► propose le CAN
                     • aléa transitoire ..........► "re-présente la carte"
```

La progression de lecture ([Lecture]) est **réelle** : `CnieReader` émet une étape à chaque
phase (`CONNECT → IDENTITY → PHOTO → SECURITY → VERIFY`), reflétée par la barre.

---

## 5. Fils conducteurs (threads)

| Travail | Thread |
|---|---|
| UI, machine à états | Principal (UI) |
| Analyse caméra / OCR | Exécuteur mono-thread dédié (`ScanActivity`) |
| Lecture NFC (`CnieReader.read`) | Coroutine `Dispatchers.IO` |
| Chargement des CSCA (~770 certs) | `Dispatchers.IO`, en tâche de fond dès le lancement ; la lecture NFC l'attend via `Deferred.await()` |

Le callback de progression NFC est **remarshalé sur le thread UI** (`runOnUiThread`) avant de
toucher aux vues.

---

## 6. Principes de conception

Quelques règles qui reviennent partout dans le code :

1. **Octets bruts pour l'intégrité.** On ne re-sérialise jamais un DG avant de le hacher (voir
   [algorithmes.md](algorithmes.md#6-passive-authentication)).
2. **Fonctions pures testables.** La logique délicate est isolée en fonctions pures sans Android
   (`MrzOcr`, `MrzVote`, `MrzKeyCandidates`, `ScanRoi`, `PassiveAuth`, `DebugReports`,
   `CnieReader.paceRank`) → testables sur JVM, sans émulateur.
3. **Verdict honnête.** Le vert n'est accordé que si l'origine étatique est **prouvée** — jamais
   un « presque bon ».
4. **La puce est l'arbitre.** On n'a pas besoin d'un OCR parfait : une clé fausse est refusée par
   la puce. On génère quelques candidats et on laisse la puce trancher.
5. **Dégradation propre.** Caméra absente → saisie manuelle ; clé MRZ qui échoue → CAN ; aléa NFC
   → « re-présente la carte ». Jamais de crash, toujours un chemin.
6. **Caviardage à la construction.** Le rapport de diagnostic ne stocke que des dérivés masqués ;
   aucune donnée personnelle brute n'y entre (voir [securite-confidentialite.md](securite-confidentialite.md#3-le-rapport-de-diagnostic-caviardé)).
