# Veripuce

**Lecture et vérification NFC de documents d'identité — CNIe française, passeports biométriques et titres de séjour (ICAO 9303).**

[![Build APK](https://github.com/sanjuant/veripuce/actions/workflows/build-apk.yml/badge.svg)](https://github.com/sanjuant/veripuce/actions/workflows/build-apk.yml)
[![Release](https://img.shields.io/github/v/release/sanjuant/veripuce)](https://github.com/sanjuant/veripuce/releases/latest)
![Platform](https://img.shields.io/badge/plateforme-Android%2024%2B-3DDC84?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-36-blue)

Veripuce lit la puce NFC d'une **carte nationale d'identité électronique** (format 2021) ou d'un
**passeport biométrique**, affiche l'état civil et la photo stockés dans la puce, et vérifie
**cryptographiquement** que les données n'ont pas été altérées (*passive authentication*).
Aucun matériel externe : le téléphone sert de lecteur.

| Écran principal | Scan OCR (viseur) | Document détecté | Résultat vérifié |
|:---:|:---:|:---:|:---:|
| ![Écran principal](docs/screen-main.png) | ![Scan OCR de la MRZ](docs/screen-scan.png) | ![Document détecté + invite NFC](docs/screen-detected.png) | ![Résultat vérifié](docs/screen-result.png) |

*Captures d'écran avec des données fictives (spécimen ICAO 9303 et identité factice).*

## Fonctionnalités

- **Un seul geste : scanner la MRZ.** La bande MRZ (bas du document) est scannée à la caméra ;
  Veripuce en **déduit le type de document** — passeport, carte d'identité ou **titre de séjour** —
  d'après le code MRZ, et pré-remplit tout après validation des **chiffres de contrôle ICAO**.
- **Ouverture de la puce** — tous documents : clé **PACE-MRZ** (repli **BAC**) directement
  issue du scan, **sans rien saisir** — y compris les cartes d'identité (CNIe française,
  CNIE marocaine… : ICAO 9303-11 impose l'acceptation du mot de passe MRZ). Si une carte
  refuse la clé MRZ, le champ **CAN** (6 chiffres au recto) apparaît en repli.
- **Repli robuste** — si la caméra est absente/cassée ou la lumière insuffisante : **lampe torche**
  intégrée, **saisie manuelle** (n° document + dates, ou CAN) toujours disponible, et bascule
  automatique vers la saisie si la caméra échoue.
- **Cohérence document ↔ puce** — la MRZ imprimée (scan optique) est comparée à la MRZ de la
  puce (DG1) : détecte un document dont la photo/impression ne correspond pas au contenu de la puce.
- **Détection de puce clonée** — **Chip Authentication** (la puce prouve qu'elle détient une clé
  privée non extractible ; repli **Active Authentication**). Une puce clonée — qui a copié les
  données mais pas la clé — échoue.
- **Vérification d'intégrité** — empreintes des data groups (DG1, DG2, DG13, DG14, DG15) recalculées
  sur les **octets bruts** et comparées aux empreintes **signées** du SOD.
- **100 % on-device, sans réseau** — lecture, OCR et vérifications locales ; l'app n'a pas la
  permission Internet (voir Confidentialité). Données France (DG13), photo ISO 19794 / 39794.

## Comment ça marche

```
      Scan MRZ (caméra, on-device)
            │  détection type + chiffres de contrôle ICAO
            ▼
   ┌── Passeport / titre de séjour ──► clé PACE-MRZ (repli BAC) ─┐
   │                                                             ├─► approcher la puce (NFC)
   └── Carte d'identité ──► clé PACE-MRZ (repli : CAN recto) ────┘
                                    │
   IsoDep ─► CardService (SCUBA) ─► PassportService (JMRTD)
                                    ├── DG1 (MRZ) · DG2 (photo) · DG13 (France)
                                    ├── EF.SOD ──► intégrité (empreintes signées)
                                    ├── DG1 puce == MRZ scannée ? (cohérence)
                                    └── DG14/DG15 ──► anti-clone (Chip/Active Auth.)
```

| Document | Ouverture de session | Clé d'accès |
|---|---|---|
| CNIe (France, 2021+) | PACE (PACE-only, pas de BAC) | **MRZ** (scannée) — l'applet accepte MRZ *et* CAN ; CAN en repli |
| Carte d'identité étrangère (ex. CNIE marocaine 2020+) | PACE | **MRZ** (scannée) — CAN en repli |
| Titre de séjour | PACE / BAC | Clé dérivée de la **MRZ** (scannée) |
| Passeport récent | PACE | Clé dérivée de la **MRZ** (scannée) |
| Passeport ancien | BAC | Clé dérivée de la **MRZ** (scannée) |

### Modèle de sécurité

Quatre vérifications indépendantes, reflétées par les chips de l'écran de résultat :

| Vérification | Ce que ça prouve | Statut |
|---|---|---|
| **Intégrité** (empreintes = SOD signé) | Données de la puce **non altérées** | ✅ |
| **Cohérence MRZ ↔ puce** | Le document imprimé correspond à la puce | ✅ |
| **Anti-clone** (Chip / Active Authentication) | La puce n'est **pas un clone** | ✅ |
| **Signature CSCA** (chaîne DSC → ANTS / ICAO PKD) | Document **émis par l'État** | ✅ |

> La détection de clone repose surtout sur la **Chip Authentication** ; l'Active Authentication
> (repli) est vérifiée pour les clés EC.
> Toutes ces vérifications demandent une **validation sur documents réels** (non testable sans puce).

### Magasin de confiance CSCA embarqué

L'app embarque les certificats CSCA du monde entier (~770 au dépôt initial — l'état courant est
dans `assets/csca/MANIFEST.tsv`), **un fichier PEM par certificat** pour l'auditabilité : union
dédupliquée de trois sources officielles indépendantes — la **masterlist ICAO**, la **masterlist
BSI** (Allemagne) et les certificats **ANTS** (CSCA-FRANCE 2010→2025 + **eID-FRANCE** pour la
CNIe, absente des masterlists « voyage »). Chaque fichier est nommé
`<pays>_<CN>_<sources>_<sha256>.pem` et porte en tête sa provenance exacte (édition de
masterlist / URL, validité, empreinte) ; `MANIFEST.tsv` récapitule le magasin et rend chaque
mise à jour diffable. Les CSCA françaises sont **recoupées octet-pour-octet** entre les canaux
à chaque génération. La signature du SOD est vérifiée (CMS), puis le certificat signataire
(DSC) est chaîné jusqu'à une de ces CSCA : verdict **vert uniquement si l'origine étatique est
prouvée** ; une signature valide sans chaîne connue reste neutre, une signature invalide est une
anomalie dure. Le magasin est rafraîchi par une **action planifiée hebdomadaire**
(`update-csca.yml`) qui ouvre une PR à relire quand les masterlists changent — jamais de fusion
automatique ; en local : `python tools/csca/update_csca.py`.

### Limites structurelles

- **DG3 (empreintes digitales) et DG4 (iris)** sont protégés par l'EAC, qui exige un certificat
  de terminal délivré par l'État. Ils sont **inaccessibles** à toute application tierce, par
  conception (la puce répond `6982`).
- La position de l'antenne NFC varie selon les téléphones : la lecture demande un placement
  stable du document quelques secondes.

## Installation

### Depuis les releases

Télécharger le dernier APK signé : **[Releases](https://github.com/sanjuant/veripuce/releases/latest)**
(`veripuce-x.y.z.apk`), puis l'installer (autoriser les sources inconnues si nécessaire).

Prérequis : Android 7.0+ (API 24) avec NFC. La caméra est optionnelle (scan OCR).

### Compilation locale

```bash
git clone https://github.com/sanjuant/veripuce.git
cd veripuce
./gradlew assembleDebug          # APK : app/build/outputs/apk/debug/veripuce-debug.apk
```

Prérequis : JDK 17, Android SDK (compileSdk 36). Le wrapper Gradle est fourni (8.14.3 épinglé).

### Intégration continue

| Déclencheur | Workflow | Sortie |
|---|---|---|
| push sur une branche | `build-apk.yml` | artefact **veripuce-debug-apk** |
| tag `v*` (ex. `v1.0.0`) | `release.yml` | **GitHub Release** avec APK **signé** `veripuce-x.y.z.apk` |

La clé de signature n'est jamais dans le dépôt : elle est injectée par secrets GitHub
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Le `versionCode` est
dérivé du tag (déterministe et monotone), et la signature de l'APK est vérifiée par `apksigner`
avant publication.

## Stack technique

| Composant | Version | Rôle |
|---|---|---|
| [JMRTD](https://jmrtd.org/) | 0.8.6 | PACE/BAC, Secure Messaging, LDS, SOD |
| [SCUBA](https://scuba.sourceforge.net/) (`scuba-sc-android`) | 0.0.26 | Transport carte à puce sur Android |
| BouncyCastle (`bcprov-jdk18on`) | 1.84 | Cryptographie (Brainpool, CMAC…) |
| JP2ForAndroid (miroir `io.github.CshtZrgk:jp2-android`) | 1.0.0 | Décodage JPEG 2000 |
| CameraX | 1.6.1 | Prévisualisation + analyse d'images (scan OCR) |
| ML Kit Text Recognition (bundled) | 16.0.1 | OCR on-device, hors-ligne |
| AndroidX / Material Components | core 1.18 · appcompat 1.7.1 · lifecycle 2.11 · material 1.14 | UI |

Chaîne de build : **AGP 8.13.2 · Kotlin 2.3.21 · Gradle 8.14.3 · JDK 17 · compileSdk/targetSdk 36 · minSdk 24**.

<details>
<summary>Notes techniques (pièges connus)</summary>

- **BouncyCastle vs BC embarqué d'Android** — Android embarque une version partielle de BC.
  Au démarrage, l'app remplace le provider : `Security.removeProvider("BC")` puis
  `Security.addProvider(BouncyCastleProvider())`.
- **Passive authentication sur octets bruts** — les empreintes du SOD sont calculées par
  l'émetteur sur les octets *tels que stockés* ; hacher une re-sérialisation JMRTD
  (`DGxFile.encoded`) peut diverger octet à octet et invalider un document authentique.
  Veripuce lit chaque DG une fois en brut et parse depuis ces mêmes octets.
- **Pas de `javax.imageio` sur Android** — la photo (souvent JPEG 2000) est décodée par
  JP2ForAndroid ; la coordonnée d'origine `com.gemalto.jp2:jp2-android` n'étant plus
  résolvable, le projet utilise son miroir Maven Central (même package, mêmes libs natives).
- **DG13 en Latin-1** — les champs texte de la CNIe sont encodés ISO-8859-1 avec `<` comme
  séparateur et NUL comme remplissage.
- **`androidx.core` plafonné à 1.18** — les versions 1.19+ exigent l'API 37 et AGP 9.1 ;
  la migration AGP 9 (Kotlin intégré, Gradle 9.1+) est prévue quand l'écosystème l'imposera.

</details>

## Structure du projet

```
app/src/main/
├── AndroidManifest.xml
├── java/fr/veripuce/app/
│   ├── MainActivity.kt      # flux unique (scan MRZ -> détection -> puce), résultat
│   ├── ScanActivity.kt      # scan OCR + viseur (CameraX + ML Kit, on-device)
│   ├── ScanOverlayView.kt   # viseur de cadrage (MRZ / carte)
│   ├── MrzOcr.kt            # parseur MRZ TD1/TD2/TD3 + CAN, type de doc, checks ICAO
│   ├── AccessKey.kt         # clé d'accès : Can (CNIe) / Mrz (passeport)
│   ├── CnieReader.kt        # PACE/BAC, lecture DGx, intégrité, cohérence, anti-clone
│   ├── ReadResult.kt        # résultat structuré (dont CloneCheck)
│   └── BerTlv.kt, Dg13*.kt  # parseur BER-TLV + DG13 (spécifique France)
├── test/java/fr/veripuce/app/MrzOcrTest.kt   # 15 tests (spécimens ICAO, fragmentation…)
└── res/                     # Material 3 : layouts, thèmes clair/sombre, icônes adaptatives
```

## Confidentialité

Veripuce est conçu **local-first**, et cette garantie est **vérifiable techniquement** :

- **Aucune permission Internet.** Les permissions `INTERNET` et `ACCESS_NETWORK_STATE`
  (injectées par les bibliothèques ML Kit/GMS) sont explicitement **retirées** du manifeste
  (`tools:node="remove"`) : l'application est *incapable* de transmettre une donnée.
  Vérifiable sur l'APK : `aapt2 dump permissions veripuce.apk` → NFC et CAMERA uniquement.
- **Aucun stockage.** Les données lues (état civil, photo, adresse) ne sont écrites nulle
  part — ni fichier, ni base, ni préférences ; `allowBackup=false`. Tout reste en mémoire
  et disparaît à la fermeture.
- **OCR hors-ligne.** Le modèle ML Kit est embarqué dans l'APK (mode *bundled*) : les images
  de la caméra sont analysées en mémoire sur l'appareil et jamais enregistrées.

Un bandeau « 100 % local » dans l'application ouvre le détail de ces garanties.

## Conformité et cadre d'usage

> **Important** — lire une pièce d'identité traite des **données personnelles** (dont la photo).
>
> - **RGPD** : consentement de la personne concernée, minimisation, aucune conservation —
>   voir les garanties techniques de la section Confidentialité.
> - La vérification d'**origine étatique** (étape 3, chaîne CSCA) est active avec le magasin
>   embarqué ; maintenir ce magasin à jour (`tools/csca/update_csca.py`) fait partie du
>   cadre d'exploitation.

## Roadmap

- [ ] Validation de l'anti-clone (Active Authentication RSA) sur documents réels
- [x] Rafraîchissement périodique du magasin CSCA : action planifiée hebdomadaire
      (`update-csca.yml`) qui régénère le magasin et ouvre une PR à relire
- [x] **Signature CSCA** : signature du SOD et chaîne DSC → CSCA de confiance
      (magasin ICAO + BSI + ANTS embarqué, 773 certs) — origine étatique
- [ ] Migration AGP 9.x / API 37 quand l'écosystème AndroidX l'exigera
- [x] Flux unique : scan MRZ → détection du type de document → lecture puce
- [x] Cohérence MRZ optique ↔ puce (DG1)
- [x] Détection de puce clonée (Chip / Active Authentication)
- [x] Scan OCR du CAN et de la MRZ (on-device) + viseur de cadrage
- [x] Support passeport (PACE-MRZ / BAC) et cartes d'identité (PACE-MRZ, CAN en repli)
- [x] Release signée automatisée (tag → GitHub Release)
