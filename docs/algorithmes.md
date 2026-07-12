# Algorithmes

Ce document décrit les algorithmes de Veripuce, du scan optique de la MRZ jusqu'à la
vérification cryptographique de la puce. Le calcul des clés d'accès a son propre document :
[Calcul des clés d'accès](cles-acces.md).

- [1. Chaîne de traitement](#1-chaîne-de-traitement)
- [2. Scan OCR de la MRZ](#2-scan-ocr-de-la-mrz)
- [3. Parsing MRZ et chiffres de contrôle ICAO](#3-parsing-mrz-et-chiffres-de-contrôle-icao)
- [4. Paires aveugles et robustesse OCR](#4-paires-aveugles-et-robustesse-ocr)
- [5. Ouverture de la session (PACE / BAC)](#5-ouverture-de-la-session-pace--bac)
- [6. Passive authentication](#6-passive-authentication)
- [7. Détection de puce clonée](#7-détection-de-puce-clonée)

---

## 1. Chaîne de traitement

```
Caméra ─► OCR MRZ (ML Kit, on-device) ─► parsing + chiffres de contrôle ICAO
                                              │  type de document déduit (TD1/TD3)
                                              ▼
   clé d'accès (MRZ ou CAN) ─► session sécurisée (PACE, repli BAC)
                                              │
   IsoDep ─► CardService (SCUBA) ─► PassportService (JMRTD)
                                              ├── DG1 (MRZ) · DG2 (photo) · DG13 (France)
                                              ├── EF.SOD ──► intégrité (empreintes signées)
                                              │              + signature CMS + chaîne CSCA
                                              ├── DG1 puce == MRZ scannée ? (cohérence)
                                              └── DG14/DG15 ──► anti-clone (Chip/Active Auth.)
```

Chaque contrôle est **indépendant** : intégrité, origine étatique, cohérence document↔puce et
anti-clone se prouvent séparément. C'est ce qui distingue une simple lecture d'un contrôle
anti-fraude.

---

## 2. Scan OCR de la MRZ

Implémenté dans `ScanActivity.kt` (capture + qualité) et `MrzOcr.kt` (extraction).

**Capture caméra** (`ScanActivity.kt`)
- Moteur : **ML Kit Text Recognition Latin, embarqué (*bundled*)** — `TextRecognition.getClient(...)`
  (`ScanActivity.kt:59`). Aucune image ne quitte l'appareil.
- Résolution d'analyse : cible **2560×1440 (1440p)**, repli sur la plus proche —
  `ResolutionStrategy(Size(2560,1440), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`
  (`ScanActivity.kt:174-178`). Une MRZ dense a besoin de résolution pour l'OCR-B.
- Contre-pression : `STRATEGY_KEEP_ONLY_LATEST` — on n'analyse que la dernière trame
  (`ScanActivity.kt:182`).

**Qualité de capture** (plan de luminance Y, sans conversion bitmap, `ScanActivity.kt:281-322`)
- Mise au point périodique au centre de la fenêtre de visée (toutes les ~2,5 s).
- Basse lumière si luminance moyenne `avgLuma < 60` → **torche automatique** après ~12 trames.
- Reflet spéculaire si `glareFraction > 0.015` (pixels saturés > 250, fréquent sur le
  polycarbonate) → conseil « incliner légèrement la carte ».

**Reconstruction du texte** (`ScanActivity.kt:220-224`)
- Les lignes ML Kit sont **triées de haut en bas** (`sortedBy boundingBox.top`) avant
  concaténation — indispensable en TD1 où le numéro (ligne 1) précède les dates (ligne 2).

**Immunité à la fragmentation** (`MrzOcr.findMrz`, `MrzOcr.kt:61-119`)
- Le texte est **joint** : passage en majuscules, `«`→`<`, puis on ne garde que `A–Z 0–9 <`
  (`MrzOcr.kt:64-66`). Cela neutralise le découpage erratique des blocs de `<` par ML Kit.
- Balayage **position par position** (`matchAt` sur chaque index, pas `findAll`) : une regex
  non chevauchante consommerait un faux départ dans les `<` et raterait la vraie séquence
  (`MrzOcr.kt:69-71`).

---

## 3. Parsing MRZ et chiffres de contrôle ICAO

### Formats reconnus

| Format | Documents | Ligne(s) exploitée(s) | Code |
|---|---|---|---|
| **TD3** | Passeport | 1 ligne : doc(9)·cd·nationalité(3)·naissance(6)·cd·sexe·expiration(6)·cd | `TD3_SEQ` (`MrzOcr.kt:46`) |
| **TD1** | Carte d'identité, titre de séjour | ligne 1 : code(2)·état(3)·doc(9)·cd ; ligne 2 : naissance(6)·cd·sexe·expiration(6)·cd | `TD1_DOC` + `TD1_DATES` (`MrzOcr.kt:51-52`) |

En TD1, les deux segments viennent de morceaux distincts : les dates doivent **suivre** le
numéro dans une fenêtre de proximité (`MrzOcr.kt:96-98`).

### Chiffre de contrôle ICAO 9303 (`checkDigit`, `MrzOcr.kt:144-157`)

Algorithme officiel à poids cycliques **7‑3‑1** :

```
valeur('0'..'9') = 0..9
valeur('A'..'Z') = 10..35        (A=10, B=11, …, Z=35)
valeur('<')      = 0
somme    = Σ  valeur(caractère_i) × poids[i mod 3]   avec poids = [7, 3, 1]
contrôle = somme mod 10
```

Chaque champ (numéro, naissance, expiration) porte son chiffre de contrôle ; la lecture n'est
acceptée que si **tous** sont valides (`checkOk`, `MrzOcr.kt:159-160`).

### Barrières anti-faux-positif

- **`fixDigits`** (`MrzOcr.kt:163-173`) : dans un champ *numérique* (dates, chiffres de
  contrôle), re-normalise les confusions OCR classiques `O,Q→0 · I→1 · Z→2 · S→5 · B→8`.
- **`plausibleDate`** (`MrzOcr.kt:136-141`) : format AAMMJJ avec mois `01–12`, jour `01–31` —
  exigé **en plus** des chiffres de contrôle.

### Type de document

Le code document (2 premiers caractères de la ligne 1 en TD1) n'a **aucun** chiffre de
contrôle. Règle de classification robuste aux confusions OCR (`MrzOcr.kt:109-113`) :

```
code[0] ≠ 'I'   →  titre de séjour   (A?, C?)
code[1] = 'R'   →  titre de séjour   (« IR » net)
sinon           →  carte d'identité  (« ID » + confusions IO/IB/IQ…)
```

Fondé sur les spécimens officiels PRADO : **CNIe française = « ID »**, **titre de séjour
français = « IR »**. Une « ID » mal lue (le D confondu) ne bascule donc jamais en titre de
séjour — ce qui, sinon, priverait la carte du repli CAN. (Voir [Calcul des clés](cles-acces.md).)

Le **CAN** (`findCan`, `MrzOcr.kt:125-133`), sans chiffre de contrôle, n'est accepté que si
le texte contient **une seule** valeur distincte à 6 chiffres isolée.

---

## 4. Paires aveugles et robustesse OCR

### Le point aveugle du chiffre de contrôle

La pondération 7‑3‑1 est **aveugle à toute substitution lettre↔chiffre dont l'écart de valeur
est un multiple de 10** (car `7·10 ≡ 3·10 ≡ 1·10 ≡ 0 mod 10`). Une telle erreur passe le
chiffre de contrôle **sans être détectée**, mais produit une clé d'accès fausse → refus de la
puce (**SW 0x6300**). Aucun chiffre de contrôle ne peut l'attraper : le traitement se fait ailleurs.

Les paires plausibles pour ML Kit (modèle latin, non entraîné sur la police OCR-B des MRZ),
table exacte `BLIND_SUBSTITUTIONS` (`MrzKeyCandidates.kt:18-30`) :

| Glyphe | Confusions | Écart de valeur |
|---|---|---|
| `G` ↔ `6` | | Δ10 |
| `6` ↔ `G`, `Q` | | Δ10 / Δ20 |
| `L` ↔ `1` | | Δ20 |
| `S` ↔ `8` | | Δ20 |
| `C` ↔ `2` | | Δ10 |
| `D` ↔ `3` | | Δ10 |

### Deux parades

1. **Vote par position** (`MrzVote.kt`) — un historique des 8 dernières lectures validées
   vote caractère par caractère sur le numéro. Une position n'est retenue que si le glyphe de
   tête a `≥ 3` voix et devance son rival de `≥ 2` (`MrzVote.kt:37,53-60`). Une position serrée
   n'est tolérée que si les deux glyphes forment une paire aveugle ; sinon la lecture est
   rejetée et le scan continue. En cas de conflit stable, les **deux** variantes sont remontées
   comme candidats (`Decision.BlindPairConflict`, `MrzVote.kt:69-77`).
2. **Candidats de clé** (`MrzKeyCandidates.documentNumberCandidates`, `MrzKeyCandidates.kt:38-48`)
   — voir [Calcul des clés d'accès](cles-acces.md#candidats-de-clé). En pratique : la puce
   elle-même tranche, puisqu'une mauvaise clé est refusée.

---

## 5. Ouverture de la session (PACE / BAC)

`CnieReader.kt` bâtit un `PassportService` (JMRTD) sur le transport `IsoDep`/SCUBA, avec
`shouldCheckMAC = true` (indispensable à l'anti-clone, voir §7). Le timeout transceive est
**court pendant le handshake** PACE (6 s — un échange normal répond en <2 s, donc un gel
échoue vite et laisse la rotation IM tenter au tap suivant) puis **élargi à 15 s** pour la
lecture des data groups (`CnieReader.kt`).

**Itération multi-protocoles PACE** (`readPaceInfos` + `tryPace`, `CnieReader.kt:321-363`)
- `EF.CardAccess` peut annoncer plusieurs protocoles PACE ; JMRTD les stocke dans un `HashSet`
  (ordre non déterministe). Veripuce les **trie explicitement** : ECDH-GM d'abord, puis DH-GM,
  CAM, et IM en dernier (`CnieReader.kt:356-363`). Le *Generic Mapping* est le mieux éprouvé
  (ReadID, NFCPassportReader — GM-only — lisent la CNIe).
- On tente PACE sur **chaque** protocole trié. Un **refus de clé** (SW `0x6300`/`63Cx`) arrête
  immédiatement (changer de protocole n'y changerait rien) ; un **rejet de protocole**
  (`MSE:Set AT`) passe au suivant ; une **perte de contact** (`TagLostException`) est remontée
  telle quelle (aléa transitoire, pas un refus).

**Classification du refus** (`PaceError.kt`) — la distinction est cruciale pour l'UX : seul un
`CardServiceException` portant `SW 0x6300`/`63Cx` dans sa chaîne de causes prouve un vrai refus
de clé (`PaceError.kt:16-18`) ; toute autre exception (perte de tag, aléa) invite simplement à
re-présenter la carte, sans réclamer le CAN.

Le détail de la dérivation de clé, du repli BAC et des candidats est dans
[Calcul des clés d'accès](cles-acces.md).

---

## 6. Passive authentication

Trois contrôles indépendants (ICAO 9303-11), dans `PassiveAuth.kt`, orchestrés par
`CnieReader.read()`.

### A. Intégrité — empreintes sur octets bruts (`verifyDataGroupHashes`)

- L'algorithme de hash est celui **déclaré par le SOD** (`MessageDigest.getInstance(sod.digestAlgorithm)`,
  `PassiveAuth.kt:60`), pas un algorithme codé en dur.
- Chaque data group est re-haché **sur ses octets bruts lus on-card** et comparé octet à octet
  à l'empreinte stockée dans le SOD (`PassiveAuth.kt:62-64`). Vrai seulement si au moins un DG
  est présent **et** que tous correspondent (une map vide ne peut pas produire un faux « intègre »).

> **Pourquoi les octets bruts ?** L'émetteur a haché la séquence d'octets *exacte* gravée dans
> le DG. Re-sérialiser un objet JMRTD (`DGxFile.encoded`) peut produire un encodage BER/DER
> différent (canonicalisation, longueurs, padding) → une empreinte différente, et un document
> authentique échouerait à tort. Veripuce lit chaque DG une fois en brut, hache ces octets-là,
> et ne parse (MRZ, photo) qu'à part (`CnieReader.kt:107-150`).

### B. Signature CMS du SOD (`verifySodSignature`)

1. `EF.SOD` = `[APPLICATION 23]` (tag `0x77`) enveloppant une `ContentInfo` CMS ; on déballe le
   tag `0x77` avec le parseur BER-TLV maison (`PassiveAuth.kt:32-33`, `BerTlv.kt`).
2. On vérifie le premier `SignerInfo` contre le **DSC embarqué** dans le SOD, via BouncyCastle
   (`JcaSimpleSignerInfoVerifierBuilder().setProvider("BC")`, `PassiveAuth.kt:36-39`).
3. Ce `verify()` contrôle **deux choses** : la signature porte bien sur les `signedAttrs`, **et**
   le `message-digest` signé correspond au `LDSSecurityObject` (la structure qui contient les
   empreintes des DG). La signature scelle donc l'ensemble des empreintes vérifiées en (A).

### C. Chaîne DSC → CSCA de confiance (`chainToTrustedCsca`)

- On cherche une CSCA embarquée dont le sujet = l'émetteur du DSC **et** qui vérifie la
  signature du DSC (`PassiveAuth.kt:47-50`).
- **Repli BouncyCastle** : certaines CSCA (courbes *brainpool*, cas français) ne sont pas
  gérées par le provider Android par défaut ; on retente `dsc.verify(csca.publicKey, "BC")`
  (`PassiveAuth.kt:53`).

### Verdict (`SignatureCheck`, `ReadResult.kt:26`)

| Valeur | Signification | Verdict global |
|---|---|---|
| `TRUSTED` | Signature valide **et** DSC → CSCA de confiance | **VERT** (émis par l'État) |
| `VALID_UNTRUSTED` | Signature valide, mais aucune CSCA connue | neutre (intégrité OK, origine non prouvée) |
| `INVALID` | Signature du SOD invalide | **ROUGE** (falsification) |
| `NOT_CHECKED` | SOD illisible / pas de DSC | neutre |

**Règle : vert uniquement si `TRUSTED`.** Seule cette valeur combine intégrité *et* origine
étatique prouvée. Un fraudeur qui embarque son propre DSC auto-signé reste `VALID_UNTRUSTED` —
jamais vert. Les sources des CSCA sont dans [Sources & données](sources.md).

---

## 7. Détection de puce clonée

Principe : une puce authentique détient une **clé privée non extractible**. Un clone copie les
data groups (parties publiques) mais **pas** la clé. `detectClone` (`CnieReader.kt:381-427`) est
exécuté en dernier car il réétablit le canal sécurisé.

### Chip Authentication (DG14, EAC-CA) — mécanisme principal

Deux temps :
1. **Établissement** — `doEACCA(...)` fait un échange de clés (ECDH/DH) avec la clé publique CA
   de la puce (`CnieReader.kt:392`). Un échec ici est **inconclusif** (courbe non supportée,
   aléa) → `UNSUPPORTED`, jamais une accusation.
2. **Confirmation** — on force une lecture **sous le nouveau Secure Messaging** issu de la CA.
   Avec `shouldCheckMAC = true`, le MAC de l'échange n'est valide **que si la puce a dérivé la
   même clé de session** — donc qu'elle détient la clé privée (`CnieReader.kt:398-402`).

Un clone échoue à cette confirmation → `FAILED` (clone probable).

### Active Authentication (DG15, ECDSA) — repli

La puce **signe un défi aléatoire** de 8 octets (`doAA`, `CnieReader.kt:408-412`). La signature
est vérifiée en essayant plusieurs profils de hash (`SHA-256/1/384/512 with PLAIN-ECDSA`) via BC
(`CnieReader.kt:413-418`).

Verdict **conservateur** : on ne conclut `AUTHENTIC` que sur **preuve** (signature vérifiée) ;
un échec ne donne **jamais** `FAILED` — pour ne pas accuser à tort un vrai document sur un
profil AA non couvert (`CnieReader.kt:421-423`). Sans aucun mécanisme exploitable → `UNSUPPORTED`
(rien n'est affiché à l'utilisateur, pour ne pas l'inquiéter inutilement).
