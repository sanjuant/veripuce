# Veripuce

Application Android qui lit la puce NFC des documents d'identité (CNIe française,
passeports biométriques, titres de séjour, ICAO 9303) et vérifie qu'ils sont authentiques.
Tout se passe sur l'appareil : l'application n'a pas d'accès réseau et n'écrit rien.
Détails fonctionnels dans `README.md` et `docs/`.

## Stack

- Kotlin 2.3 + Android (AGP 8.13, Gradle 8.14 via le wrapper, JDK 17, compileSdk/targetSdk 36, minSdk 24)
- NFC / puce : JMRTD + SCUBA ; crypto : BouncyCastle (`jdk18on`)
- OCR de la MRZ : **Tesseract** (`tesseract4android`) avec un modèle OCR-B dédié dans `app/src/main/assets/tessdata/`. ML Kit n'est plus utilisé, même si le README le cite encore.
- CameraX pour le scan
- CI : GitHub Actions (`.github/workflows/`) : `build-apk.yml` produit un APK debug à chaque push, `release.yml` produit un APK signé sur un tag `v*`, `update-csca.yml` met à jour les certificats CSCA

## Commandes

| Action | Commande |
|---|---|
| Build debug | `./gradlew assembleDebug` (sortie : `app/build/outputs/apk/debug/`) |
| Tests unitaires | `./gradlew testDebugUnitTest` |
| Lint Android | `./gradlew lintDebug` |

Avant de dire qu'une tâche est finie : lancer le build et les tests unitaires. Une vraie
lecture NFC ne se teste que sur un téléphone physique ; dire clairement si ce n'est pas fait.

## Vérification

- **Preuve avant de rendre** : `bash .claude/skills/verify/verify.sh --rerun` (tests JVM forcés,
  `lintDebug`, `assembleDebug`, pas de permission `INTERNET` ni `allowBackup` dans le manifeste
  fusionné, puis `assembleRelease` sans aucune trace du journal des lectures). Coller le bloc `VERIFY … VERT` dans le compte rendu. Mode d'emploi, « fausse puce »
  JVM et feature map de l'app : skill `.claude/skills/verify/SKILL.md`.
- Appareil ou émulateur déjà démarré : ajouter `--device` (installation, lancement, capture, crash
  logcat). Ne jamais démarrer l'émulateur depuis un agent (processus sans fin).
- **Hook `Stop`** (`.claude/settings.json` → `.claude/hooks/stop-verify.sh`) : `testDebugUnitTest`
  + `lintDebug` à chaque fin de tour si le code a changé depuis le dernier vert (≈ 10–20 s à chaud).
  Rouge = l'agent ne peut pas s'arrêter ; après 3 tours rouges il est relâché et doit rendre la
  tâche en échec avec le diagnostic.
- **Tests de propriétés** : `MrzPropertyTest` (Kotest property, graine fixe) — aller-retour
  formatage ICAO 9303 ↔ `MrzOcr`, chiffres de contrôle, entrées invalides (texte, octets TLV) sans
  crash. Données générées ou spécimens uniquement.
- Pas de test flaky : ni `sleep`, ni relance, ni `@Ignore`. Chaque commit porte un trailer
  `Verified: <ce qui a tourné>` (ou `Verified: none — <raison>`).
- Revue : règles dans `REVIEW.md`.

## Structure

- `app/src/main/java/fr/veripuce/` : le code de l'application
- `app/src/main/assets/csca/` : les certificats des autorités nationales (CSCA)
- `app/src/main/assets/tessdata/` : le modèle OCR de la MRZ
- `app/src/test/` : tests JVM ; les jeux de test publics (BSI TR-03105, `bsi2008`, `loes2006`) sont dans `resources/`
- `docs/` : glossaire, architecture, algorithmes, clés d'accès, sécurité, dépannage

## Données personnelles : règle absolue

- **Aucune vraie donnée d'identité** (MRZ, numéro de document, photo, dump de puce, rapport de diagnostic d'une vraie personne) ne va dans le code, les tests, la doc, les logs ou un commit. Seuls des spécimens fictifs ou les jeux de test publics sont autorisés.
- Le rapport de diagnostic doit rester caviardé (voir `docs/securite-confidentialite.md`).
- Le journal des lectures (`src/debug` uniquement, bouchon vide en `src/release`) ne passe que par
  `JournalRedaction` : liste blanche, jamais d'octet de données APDU (voir `docs/depannage.md`).
- Ne jamais ajouter la permission Internet.

## Secrets

- La clé de signature n'est jamais dans le dépôt : elle vient des variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`, fournies par les secrets de la CI. `.gitignore` exclut `*.jks`, `*.keystore`, `*.p12`, `*.b64` et `keystore.properties`.

## Pièges connus

- Le BouncyCastle partiel d'Android doit être remplacé au démarrage (`removeProvider("BC")` puis `addProvider`).
- Les DG sont hachés sur leurs **octets bruts tels que lus**, jamais re-sérialisés : sinon la vérification du SOD échoue sur un document authentique.
- La photo est en JPEG 2000, décodée par JP2ForAndroid.
- `androidx.core` est plafonné à 1.18 : la 1.19+ exige l'API 37 / AGP 9.1.
- Les builds locaux laissent des `*.log` à la racine. Ils sont ignorés par git, on peut les supprimer.

## Git

- Remote actuelle : `https://github.com/sanjuant/veridoc.git`. Le dépôt s'appelle encore `veridoc` côté GitHub et n'est pas sur la forge `git.sjn.wf`.
- Branche principale : `main`
