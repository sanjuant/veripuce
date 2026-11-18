# Revue — règles de Veripuce

Format : **déclencheur dans le diff → contrôle → raison**.

1. Une MRZ, un n° de document, une date de naissance, un nom, une photo, un dump d'octets de puce ou un rapport de diagnostic dans le code, les tests, `resources/`, la doc, un log ou un message de commit → vérifier qu'il s'agit d'un spécimen officiel (ICAO « Utopia », PRADO, BSI/LOES) ou d'une donnée générée, et que la provenance est écrite à côté → une vraie pièce d'identité ne doit jamais entrer dans le dépôt (RGPD, historique git indélébile).
2. `Log.`, `println`, `printStackTrace`, message d'exception ou `DebugReport` qui touche une valeur lue (MRZ, CAN, DG1, DG2, DG13) → exiger le caviardage (voir `DebugReportTest`) → logcat et rapports partagés sortent de l'appareil.
3. `AndroidManifest.xml` : permission `INTERNET`, `allowBackup="true"`, nouveau `exported="true"` → refuser sauf décision explicite → l'app promet « aucun réseau, rien conservé ».
4. Écriture disque (`openFileOutput`, `File(`, `SharedPreferences`, `MediaStore`, cache) de données lues → refuser → rien ne doit survivre à la fermeture (seul le drapeau du mode diagnostic est persistant).
5. `MrzOcr`, `MrzKeyCandidates`, `MrzVote`, `BerTlv`, `Dg13Parser` modifiés → `MrzPropertyTest` et les tests d'exemples restent verts, et le nouveau cas a son test → c'est la frontière avec des entrées non fiables (OCR, octets de puce).
6. Hachage des DG / `PassiveAuth` → les empreintes portent sur les **octets bruts lus**, jamais sur une re-sérialisation JMRTD → sinon un document authentique échoue.
7. `Security.addProvider` / `removeProvider("BC")`, versions BouncyCastle ou JMRTD → BC complet chargé au démarrage, versions alignées sur celles que tire JMRTD → le BC partiel d'Android casse brainpool/CMAC.
8. `app/src/main/assets/csca/` → changement issu de `tools/csca/update_csca.py` (PR de rafraîchissement), sources et empreintes tracées → c'est l'ancre de confiance « Émis par l'État ».
9. `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`, mot de passe ou jeton en clair → refuser → la clé de signature vient uniquement des secrets CI.
10. `@Ignore`, `Thread.sleep`, relance de test, graine aléatoire non fixée dans un test → refuser → pas de test flaky ni masqué.
11. Dépendance ajoutée ou montée (`build.gradle.kts`) → vérifier `androidx.core` ≤ 1.18, minSdk 24, et qu'aucune bibliothèque n'ajoute `INTERNET` au manifeste fusionné (`verify.sh` le contrôle) → contraintes AGP 8.13 / API 36 et promesse hors-ligne.
12. Commit sans trailer `Verified:` ou avec `Co-Authored-By` / mention d'IA → demander la correction → preuve de vérification obligatoire, aucune attribution.
