# Lecteur eID Android — CNIe & passeport (NFC)

Portage de la logique de `cnie-python-tools` vers Android, en lecture **NFC** (sans
matériel externe) au lieu d'un lecteur PC/SC à contact. Lit la **CNIe française** (PACE-CAN)
et les **passeports** biométriques (PACE-MRZ / BAC).

## Idée

La CNI française (depuis 2021) stocke ses données au format ICAO 9303, comme un
passeport biométrique. On y accède via **PACE-CAN** (le CAN = les 6 chiffres imprimés
sur la face avant), puis en Secure Messaging. Tout ça est déjà implémenté par la
librairie **JMRTD**, qui tourne sur Android. On ne réimplémente donc que :

- le **transport** : `IsoDep` (NFC natif Android) à la place de la couche PC/SC ;
- éventuellement le **DG13** (spécifique France : adresse, taille, lieu de naissance),
  que JMRTD ne parse pas nativement — à porter depuis le code de Hubert.

## CNIe **et** passeport

Un passeport biométrique est un eMRTD ICAO 9303 exactement comme la CNIe — c'est le
document pour lequel JMRTD a été écrit. La seule différence est la **clé d'accès** :

| Document      | Ouverture de session                     | Saisie utilisateur                          |
|---------------|------------------------------------------|---------------------------------------------|
| CNIe (France) | **PACE-CAN**                             | CAN — 6 chiffres au recto                   |
| Passeport     | **PACE-MRZ** (repli **BAC** si legacy)   | n° document + naissance + expiration (AAMMJJ)|

Une fois la session ouverte, **DG1 (MRZ)**, **DG2 (photo)** et **EF.SOD** se lisent de
façon identique ; le **DG13** est propre à la France et sera simplement absent d'un
passeport. Le choix de mode se fait dans l'UI ; le code de clé est dans
[`AccessKey.kt`](app/src/main/java/fr/veridoc/app/AccessKey.kt) et l'ouverture de session
dans `CnieReader.openWithCan` / `openWithMrz`.

L'interface est en **Material 3** (thème clair/sombre, sélecteur de mode, carte résultat
avec photo, et chips d'état pour l'intégrité et la signature).

## Ce qui compte vraiment pour de l'anti-fraude

Lire DG1 (MRZ) et DG2 (photo) ne prouve rien. Ce qui prouve qu'une carte est
authentique et non trafiquée, c'est la **passive authentication** :

1. lire l'**EF.SOD** (Document Security Object) ;
2. recalculer le hash de chaque data group lu et le comparer au hash **signé** stocké
   dans le SOD → détecte toute altération des données ;
3. vérifier la **signature** du SOD et que son certificat signataire (DSC) remonte à une
   **CSCA de confiance** → prouve que la carte a bien été émise par l'État.

Pour l'étape 3, il faut un **magasin de certificats CSCA**. Sources : l'ICAO PKD, ou
pour la France les certificats CSCA publiés par l'ANTS. Sans ça, tu vérifies l'intégrité
interne mais pas l'origine étatique. `CnieReader.kt` fait déjà les étapes 1 et 2 ;
l'étape 3 est balisée par des `TODO`.

En complément, la **Chip Authentication** (CA/AA) permet de détecter une puce *clonée*
(la puce prouve qu'elle détient une clé privée non extractible).

## Ce qui reste impossible

Comme sur PC : les empreintes (DG3) et l'iris (DG4) sont derrière l'**EAC**, qui exige
un certificat de terminal délivré par l'État. La carte répondra `6982`. Aucune solution
logicielle ne contourne ça.

## Dépendances (app/build.gradle.kts)

```kotlin
implementation("org.jmrtd:jmrtd:0.8.6")                  // eMRTD : PACE/BAC, LDS, SOD (+ ISO 39794)
implementation("net.sf.scuba:scuba-sc-android:0.0.26")   // transport SCUBA pour Android
implementation("org.bouncycastle:bcprov-jdk18on:1.84")   // crypto (aligné sur le transitif JMRTD)
implementation("io.github.CshtZrgk:jp2-android:1.0.0")   // décodage JPEG 2000 (miroir JP2ForAndroid)
```

Chaîne de build : AGP 8.13.2 · Kotlin 2.3.21 · Gradle 8.14.3 (wrapper) · compileSdk/targetSdk 36
· minSdk 24. Le passage à AGP 9.x (Kotlin intégré, Gradle 9.1+, API 37) est l'étape suivante
naturelle quand l'écosystème AndroidX l'exigera.

Gotchas connus :

- **Bouncy Castle vs BC embarqué d'Android.** Android embarque une vieille version
  partielle de BC. Au démarrage de l'app, retire-la et enregistre la complète :
  ```kotlin
  Security.removeProvider("BC")
  Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
  ```
  (Historiquement on utilisait SpongyCastle pour éviter le conflit ; sur Android récent,
  BC standard passe si tu fais ce remplacement.)
- **Pas de `javax.imageio` sur Android** → la photo (souvent en JPEG 2000) se décode avec
  le décodeur Gemalto (`JP2Decoder(bytes).decode()` → `Bitmap`).

## Statut du code

C'est un **squelette d'architecture**, pas un projet compilé. Les signatures exactes de
certaines méthodes JMRTD/SCUBA (`PACEKeySpec.createCANKey`, `PACEInfo.toParameterSpec`,
`CardService.getInstance(IsoDep)`…) ont bougé selon les versions — je n'ai pas pu les
compiler ici (dépendances Maven non résolvables dans cet environnement), donc à
réconcilier avec les versions que tu épingles. La logique et l'ordre des appels, eux,
sont stables.

## Pour la présentation « alternative au lecteur dédié »

Points à assumer honnêtement en réunion :

- **Fragmentation matérielle.** L'antenne NFC et sa position varient énormément d'un
  téléphone à l'autre ; la lecture d'une puce eID est plus capricieuse qu'avec un lecteur
  dédié bien positionné. À tester sur le parc réel.
- **Pas de certification.** Un lecteur dédié peut être certifié/homologué ; une app
  maison sur téléphone quelconque ne l'est pas. Selon le cadre réglementaire de votre
  activité, ça peut être bloquant.
- **RGPD.** Lire une pièce d'identité = données personnelles (dont la photo, donnée
  biométrique). Consentement de la personne présente, minimisation, pas de stockage
  inutile, chiffrement si conservation. À cadrer avec votre DPO.

## Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/fr/veridoc/app/
│   ├── MainActivity.kt      # UI Material 3, sélecteur de mode, dispatch NFC, lancement lecture
│   ├── AccessKey.kt         # clé d'accès : Can(can) pour la CNIe, Mrz(...) pour le passeport
│   ├── CnieReader.kt        # PACE-CAN / PACE-MRZ+BAC + lecture DG1/DG2/DG13 + passive auth
│   ├── ReadResult.kt        # résultat structuré
│   ├── BerTlv.kt / Dg13*.kt # parsing DG13 (spécifique France)
│   └── ...
└── res/
    ├── layout/activity_main.xml       # écran Material 3 (modes, statut, carte résultat)
    ├── values/ (themes, colors, strings) + values-night/colors.xml
    ├── drawable/ic_*.xml              # icônes modes + états
    └── xml/nfc_tech_filter.xml        # filtre IsoDep
```

## Compilation (deux options)

Le projet est un projet Gradle complet. Il ne peut pas être compilé dans un
environnement sans SDK Android ni accès aux dépôts Google/Maven — voici deux voies.

### Option A — dans le cloud, sans rien installer (GitHub Actions)

1. Crée un dépôt GitHub et pousse tout ce dossier.
2. Le workflow `.github/workflows/build-apk.yml` se lance à chaque push (ou à la main
   via l'onglet **Actions**).
3. Une fois le job terminé, télécharge l'artefact **veridoc-debug-apk** : c'est l'APK.

Le runner GitHub fournit le SDK Android et Gradle ; aucun outil local requis.

### Option B — en local (Android Studio)

1. Ouvre le dossier dans Android Studio (Giraffe/Koala ou plus récent).
2. Laisse-le synchroniser Gradle et télécharger les dépendances.
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)**, ou en ligne de commande :
   ```
   ./gradlew assembleDebug        # le wrapper est fourni (Gradle 8.14.3 pinné)
   ```
   L'APK se trouve dans `app/build/outputs/apk/debug/`.

### Corrections appliquées (build vérifié vert)

Le squelette compile désormais tel quel : `assembleDebug` produit un APK signé (clé
debug) avec JMRTD 0.7.42 + scuba-sc-android 0.0.26 — les signatures JMRTD/SCUBA du code
n'ont **pas** eu besoin d'être touchées. Seuls des points de dépendances/packaging
bloquaient, tous corrigés dans `app/build.gradle.kts` :

- **Décodeur JP2.** La coordonnée `com.gemalto.jp2:jp2-android:1.0` est introuvable
  (Maven Central, Google, et JitPack — le JitPack officiel de Gemalto échoue au build).
  Remplacée par le miroir Maven Central `io.github.CshtZrgk:jp2-android:1.0.0`, qui
  fournit le **même package** `com.gemalto.jp2.*` et les libs natives — aucun changement
  de code (l'import `com.gemalto.jp2.JP2Decoder` reste valide).
- **Bouncy Castle en double.** JMRTD tire `bcprov-jdk18on` en transitif, en conflit avec
  le `bcprov-jdk15to18` déclaré (« Duplicate class »). Aligné sur une seule variante :
  `org.bouncycastle:bcprov-jdk18on:1.78.1`.
- **Collision de packaging.** Les jars BC embarquent des métadonnées identiques
  (`META-INF/versions/9/OSGI-INF/MANIFEST.MF`, licences) → bloc
  `packaging { resources { excludes } }`.

Si tu épingles d'autres versions de `org.jmrtd:jmrtd` / `net.sf.scuba:scuba-sc-android`,
revérifie les signatures `doPACE` / `PACEKeySpec.createCANKey` / `PACEInfo.toParameterSpec`
/ `CardService.getInstance(IsoDep)` : elles ont bougé selon les versions.
